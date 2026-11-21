package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

internal fun fileSendAvailable(group: Boolean, bridge: Boolean, agent: Boolean) = !group && bridge && agent

/** enclos-protocol's `FILE_TYPES`: the one media type each extension is sent as. */
internal val sendFileTypes = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
    "pdf" to "application/pdf", "md" to "text/markdown", "txt" to "text/plain", "log" to "text/plain", "py" to "text/plain", "sh" to "text/plain",
    "csv" to "text/csv", "json" to "application/json", "xml" to "application/xml", "yaml" to "application/yaml", "yml" to "application/yaml",
    "toml" to "application/toml", "zip" to "application/zip", "doc" to "application/msword", "xls" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation")

internal const val MAX_SEND_FILES = 4
internal const val MAX_SEND_FILE_BYTES = 10_000_000
internal const val SEND_PART_BYTES = 45_000

/** enclos-protocol's `incoming::check_name`, with [Visible] as its `visible` module: refused, never changed. */
internal fun validSendFileName(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." &&
        name.toByteArray(Charsets.UTF_8).size <= 255 && name.codePointCount(0, name.length) <= 120 &&
        '/' !in name && '\\' !in name && name.codePoints().noneMatch(Character::isISOControl) &&
        !Visible.hasHidden(name) && name.trim() == name

/** The media type the protocol sends [name] as, from its extension alone; `null` when no agent takes it. */
internal fun sendFileType(name: String): String? {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: return null
    return sendFileTypes[name.substring(dot + 1).lowercase()]
}

internal fun validSendFile(name: String, mime: String, size: Int): Boolean =
    size in 1..MAX_SEND_FILE_BYTES && validSendFileName(name) && sendFileType(name) == mime

/**
 * A prepared file. Its bytes are reserved in [PreparedBudget] until released
 * once both their operation and any upload worker retire. A second release (a late callback, a discard
 * after a removal) does nothing, so it never frees another owner's bytes.
 */
internal class SendFile(val name: String, val mime: String, val bytes: ByteArray) {
    private var released = false
    private var workers = 0
    private var pendingBudget: PreparedBudget? = null
    @Synchronized fun retainWorker() { check(!released); workers++ }
    @Synchronized fun retireWorker() {
        check(workers > 0)
        workers--
        if (workers == 0) pendingBudget?.let { it.release(bytes.size); pendingBudget = null }
    }
    @Synchronized fun releaseFrom(budget: PreparedBudget) {
        if (released) return
        released = true
        if (workers == 0) budget.release(bytes.size) else pendingBudget = budget
    }
    fun declaration() = JSONObject().put("name", name).put("mime", mime).put("size", bytes.size)
}

/** Why a selection was not prepared, in words safe to show; a provider's own exception never is. */
internal class PrepareRefused(val safe: String) : Exception(safe)

/** Process-wide cap on prepared bytes held for drafts and operations, across every conversation. */
internal class PreparedBudget(val limit: Long) {
    private var used = 0L
    @Synchronized fun reserve(bytes: Int): Boolean {
        if (bytes < 0 || used + bytes > limit) return false
        used += bytes
        return true
    }
    @Synchronized fun release(bytes: Int) { used = maxOf(0L, used - bytes) }
    @Synchronized fun used(): Long = used
    companion object { val process = PreparedBudget(80_000_000) }
}

/** One message with files, immutable once begun: the same id goes again on an explicit retry. */
internal class FileSend(val id: String, val host: String, val conversation: String, val text: String, val files: List<SendFile>)

internal sealed interface FilePhase {
    /** No operation: the draft may change. */
    data object Draft : FilePhase
    data class Preparing(val job: Job) : FilePhase
    data class Sending(val send: FileSend, val progress: Int, val job: Job? = null) : FilePhase
    /** Every part went; [job] waits for the receipt, cancelled once it settles. */
    data class AwaitingAck(val send: FileSend, val job: Job? = null) : FilePhase
    /**
     * The bridge refused send [id], saying [reason]: final, nothing to retry.
     * Its bytes are released after its upload worker retires; its words and names stay to say what was not sent.
     */
    data class Failed(val id: String, val text: String, val names: List<String>, val reason: String) : FilePhase
    /** Delivery unknown: retry the same original, or discard it. */
    data class Unknown(val send: FileSend) : FilePhase
}

internal data class FileKey(val host: String, val conversation: String)

/**
 * A conversation's files: its newer draft, the phase of its one operation,
 * and the camera file this process asked for it ([capture]) until its result
 * is taken.
 */
internal data class FileConversation(
    val draft: List<SendFile> = emptyList(),
    val phase: FilePhase = FilePhase.Draft,
    val error: String? = null,
    val capture: File? = null,
)

/**
 * Files to send, by host and conversation, while the app runs. Bytes have
 * exactly one owner (the draft, then its operation) and are released once,
 * when it is confirmed, refused, removed, discarded or forgotten. Nothing is
 * stored or sent later.
 */
internal class SendingFiles(val budget: PreparedBudget = PreparedBudget.process) {
    private val all = mutableStateMapOf<FileKey, FileConversation>()

    /**
     * The camera is open for [key], writing [file]: the conversation owns it
     * until its result is taken, and forgetting the conversation deletes it.
     * A capture restored from saved state after the process's recreation is
     * owned by none, so it is never deleted on another's behalf.
     */
    fun capturing(key: FileKey, file: File) = update(key) { now ->
        // Saved camera paths are validated and returned in canonical form.
        val capture = file.canonicalFile
        if (now.capture != capture) {
            now.capture?.let { CameraOriginals.release(it); it.delete() }
            CameraOriginals.retain(capture)
        }
        now.copy(capture = capture)
    }
    fun captureOwnedElsewhere(key: FileKey, file: File) = all.any { (owner, value) -> owner != key && value.capture == file.canonicalFile }
    /** [file]'s result came: whoever takes it now deletes it. Whether a conversation owned it. */
    fun captured(file: File): Boolean {
        val capture = file.canonicalFile
        val key = all.entries.firstOrNull { it.value.capture == capture }?.key ?: return false
        CameraOriginals.release(capture)
        update(key) { it.copy(capture = null) }
        return true
    }

    /** What is kept of a confirmed operation: where it went and its files' names, never their bytes. */
    private class Confirmed(val key: FileKey, val names: List<String>) {
        /** A late `error` was told: another for the same id is consumed, said nowhere. */
        var told = false
    }

    /** Operations confirmed lately, by host and id, so a late `error` still names its conversation and files. */
    private val confirmed = object : LinkedHashMap<Pair<String, String>, Confirmed>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Confirmed>?) = size > 32
    }

    operator fun get(key: FileKey): FileConversation = all[key] ?: FileConversation()
    fun draft(key: FileKey) = get(key).draft
    fun phase(key: FileKey) = get(key).phase
    /** Whether an operation owns [conversation]: it outlives leaving the screen. */
    fun owns(conversation: String) = all.any { (k, v) -> k.conversation == conversation && v.phase.operation() != null }
    /** Whether an operation is going over [host]'s connection now. */
    fun busy(host: String) = all.any { (k, v) -> k.host == host && (v.phase is FilePhase.Sending || v.phase is FilePhase.AwaitingAck) }

    private fun put(key: FileKey, value: FileConversation) {
        if (value == FileConversation()) all.remove(key) else all[key] = value
    }
    private fun update(key: FileKey, f: (FileConversation) -> FileConversation) = put(key, f(get(key)))

    fun error(key: FileKey, why: String?) = update(key) { it.copy(error = why) }

    fun preparing(key: FileKey, job: Job): Boolean {
        val now = get(key)
        if (now.phase != FilePhase.Draft) return false
        put(key, now.copy(phase = FilePhase.Preparing(job), error = null))
        return true
    }

    /** [job]'s files join the draft, each one [refused] named; all are released if it no longer prepares for it. */
    fun prepared(key: FileKey, job: Job, files: List<SendFile>, refused: List<String> = emptyList()): Boolean {
        val now = get(key)
        if ((now.phase as? FilePhase.Preparing)?.job !== job) {
            release(files)
            return false
        }
        if (now.draft.size + files.size > MAX_SEND_FILES) {
            release(files)
            put(key, now.copy(phase = FilePhase.Draft, error = "Choose at most four files."))
            return false
        }
        val why = refused.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "Not attached: ")
        put(key, now.copy(draft = now.draft + files, phase = FilePhase.Draft, error = why))
        return true
    }

    fun preparationFailed(key: FileKey, job: Job, why: String) {
        val now = get(key)
        if ((now.phase as? FilePhase.Preparing)?.job === job) put(key, now.copy(phase = FilePhase.Draft, error = why))
    }

    /** [job] ended without handing over files (cancelled): the draft may change again. */
    fun preparationEnded(key: FileKey, job: Job) {
        val now = all[key] ?: return
        if ((now.phase as? FilePhase.Preparing)?.job === job) put(key, now.copy(phase = FilePhase.Draft))
    }

    /** Prepared files nobody took (a cancelled preparation): their bytes are released. */
    fun discard(files: List<SendFile>) = release(files)

    /** Whether [id] is still being uploaded: once settled, no further part or progress goes. */
    fun uploading(key: FileKey, id: String): Boolean = (all[key]?.phase as? FilePhase.Sending)?.send?.id == id

    fun remove(key: FileKey, index: Int) = update(key) { now ->
        if (now.phase !is FilePhase.Draft || index !in now.draft.indices) return@update now
        release(listOf(now.draft[index]))
        now.copy(draft = now.draft.filterIndexed { i, _ -> i != index })
    }

    /** The draft's bytes move to a new operation; the draft is empty after. */
    fun begin(key: FileKey, text: String): FileSend? {
        val now = get(key)
        if (now.phase != FilePhase.Draft || now.draft.isEmpty()) return null
        val send = FileSend(UUID.randomUUID().toString(), key.host, key.conversation, text, now.draft)
        put(key, now.copy(draft = emptyList(), phase = FilePhase.Sending(send, 0), error = null))
        return send
    }

    /** The same original, same id, from its first byte. */
    fun retry(key: FileKey): FileSend? {
        val now = get(key)
        val send = (now.phase as? FilePhase.Unknown)?.send ?: return null
        put(key, now.copy(phase = FilePhase.Sending(send, 0), error = null))
        return send
    }

    /** [send] could not even begin: it goes back to the draft, unchanged. */
    fun notBegun(key: FileKey, send: FileSend, why: String, retried: Boolean) = onPhase(key, send.id) { now ->
        if (retried) now.copy(phase = FilePhase.Unknown(send), error = why)
        else now.copy(draft = send.files + now.draft, phase = FilePhase.Draft, error = why)
    }

    fun started(key: FileKey, id: String, job: Job) = onPhase(key, id) { now ->
        val p = now.phase as? FilePhase.Sending ?: return@onPhase now
        now.copy(phase = p.copy(job = job))
    }

    fun progress(key: FileKey, id: String, percent: Int) = onPhase(key, id) { now ->
        val p = now.phase as? FilePhase.Sending ?: return@onPhase now
        now.copy(phase = p.copy(progress = percent.coerceIn(0, 100)))
    }

    /** Every part was handed to the connection: the receipt is awaited. */
    fun transmitted(key: FileKey, id: String) = onPhase(key, id) { now ->
        val p = now.phase as? FilePhase.Sending ?: return@onPhase now
        now.copy(phase = FilePhase.AwaitingAck(p.send, p.job))
    }

    /** The upload stopped part way: delivery unknown for this operation only. */
    fun interrupted(key: FileKey, id: String) = onPhase(key, id) { now ->
        val send = now.phase.inFlight() ?: return@onPhase now
        now.copy(phase = FilePhase.Unknown(send))
    }

    /** No receipt in time: this operation only, while it still awaits that receipt. */
    fun timedOut(key: FileKey, id: String) = onPhase(key, id) { now ->
        val p = now.phase as? FilePhase.AwaitingAck ?: return@onPhase now
        now.copy(phase = FilePhase.Unknown(p.send))
    }

    /**
     * The bridge's receipt for [id]: its bytes are released. A refusal
     * already final stays so (a late receipt does not undo it).
     */
    fun acknowledged(host: String, id: String): Boolean {
        val key = keyOf(host, id) ?: return false
        val now = get(key)
        if (now.phase is FilePhase.Failed) return true
        val send = now.phase.send() ?: return false
        cancel(now.phase)
        release(send.files)
        confirmed[host to id] = Confirmed(key, send.files.map { it.name })
        put(key, now.copy(phase = FilePhase.Draft, error = null))
        return true
    }

    /**
     * The bridge's `error` correlated to [id] by its `of`, before or after
     * the receipt. A [definitive] refusal is final ([FilePhase.Failed], no
     * retry) and releases its bytes once its worker retires; any other leaves delivery
     * unknown ([FilePhase.Unknown]), the original kept for an explicit retry.
     * After the receipt only names were kept: it is told once in the
     * original conversation, whichever is open; a repeat is consumed there,
     * so it never falls through to another conversation or the host.
     */
    fun refused(host: String, id: String, code: String, reason: String): Boolean {
        val final = code in definitive
        val key = keyOf(host, id)
        if (key == null) {
            val late = confirmed[host to id] ?: return false
            if (late.told) return true
            late.told = true
            val names = late.names.joinToString(", ") { safeFileName(it) }
            update(late.key) {
                it.copy(error = if (final) "Not accepted after its receipt ($names): $reason"
                    else "Delivery of $names is unconfirmed after its receipt: check history.")
            }
            return true
        }
        val now = get(key)
        if (now.phase is FilePhase.Failed) return true
        val send = now.phase.send() ?: return false
        cancel(now.phase)
        if (final) {
            release(send.files)
            put(key, now.copy(phase = FilePhase.Failed(send.id, send.text, send.files.map { it.name }, reason)))
        } else put(key, now.copy(phase = FilePhase.Unknown(send)))
        return true
    }

    /** The person discards an unknown original (its bytes are released) or dismisses a refusal. */
    fun dismiss(key: FileKey) {
        val now = get(key)
        if (now.phase !is FilePhase.Unknown && now.phase !is FilePhase.Failed) return
        now.phase.send()?.let { release(it.files) }
        put(key, now.copy(phase = FilePhase.Draft, error = null))
    }

    /** [host]'s connection ended: what went over it is of unknown delivery; a refusal or draft stays. */
    fun ended(host: String) {
        for ((key, now) in all.toList()) {
            if (key.host != host) continue
            val send = now.phase.inFlight() ?: continue
            cancel(now.phase)
            put(key, now.copy(phase = FilePhase.Unknown(send)))
        }
    }

    fun forgetHost(host: String) {
        all.keys.filter { it.host == host }.forEach(::forget)
        confirmed.keys.removeAll { it.first == host }
    }
    fun forget(conversation: String) {
        all.keys.filter { it.conversation == conversation }.forEach(::forget)
        confirmed.values.removeAll { it.key.conversation == conversation }
    }
    /** [key] is deleted, discarded or its host forgotten: its bytes are released and its own capture deleted. */
    fun forget(key: FileKey) = drop(key, deleteCapture = true)
    /** Everything forgotten on purpose (the cache cleared): as [forget] for each. */
    fun clear() {
        all.keys.toList().forEach(::forget)
        confirmed.clear()
    }
    /**
     * The ViewModel ends, which may be the process's recreation: bytes are
     * released, but a capture is not deleted, as its result may still come
     * back to the restored screen ([ownedCapture]).
     */
    fun close() {
        all.keys.toList().forEach { drop(it, deleteCapture = false) }
        confirmed.clear()
    }
    private fun drop(key: FileKey, deleteCapture: Boolean) {
        confirmed.values.removeAll { it.key == key }
        val now = all.remove(key) ?: return
        now.capture?.let { CameraOriginals.release(it); if (deleteCapture) it.delete() }
        cancel(now.phase)
        release(now.draft)
        now.phase.send()?.let { release(it.files) }
    }

    private fun keyOf(host: String, id: String) = all.entries.firstOrNull { (k, v) -> k.host == host && v.phase.operation() == id }?.key
    private fun onPhase(key: FileKey, id: String, f: (FileConversation) -> FileConversation) {
        val now = all[key] ?: return
        if (now.phase.operation() == id) put(key, f(now))
    }
    private fun cancel(phase: FilePhase) {
        (phase as? FilePhase.Preparing)?.job?.cancel()
        (phase as? FilePhase.Sending)?.job?.cancel()
        (phase as? FilePhase.AwaitingAck)?.job?.cancel()
    }
    private fun release(files: List<SendFile>) = files.forEach { it.releaseFrom(budget) }

    private companion object {
        /** Error codes that say the bridge did not take the message: anything else may have been stored. */
        val definitive = setOf("unsupported", "not_allowed", "bad_message", "operation_failed")
    }
}

/** The send whose bytes this phase holds: none once refused. */
internal fun FilePhase.send(): FileSend? = when (this) {
    is FilePhase.Sending -> send
    is FilePhase.AwaitingAck -> send
    is FilePhase.Unknown -> send
    else -> null
}

/** The id of this phase's send, refused included: it owns its conversation until settled. */
internal fun FilePhase.operation(): String? = (this as? FilePhase.Failed)?.id ?: send()?.id

private fun FilePhase.inFlight(): FileSend? = when (this) {
    is FilePhase.Sending -> send
    is FilePhase.AwaitingAck -> send
    else -> null
}

/** Lazy ordered frames, bounded to one decoded chunk at a time; a retry starts from byte zero. */
internal fun fileParts(id: String, files: List<SendFile>): Sequence<JSONObject> = sequence {
    files.forEachIndexed { index, file ->
        var offset = 0
        while (offset < file.bytes.size) {
            val end = minOf(offset + SEND_PART_BYTES, file.bytes.size)
            yield(message("message.part", "message" to id, "file" to index, "offset" to offset,
                "data" to Base64.getUrlEncoder().withoutPadding().encodeToString(file.bytes.copyOfRange(offset, end))))
            offset = end
        }
    }
}

/** A bridge's refusal as shown: its own words, hidden characters dropped, bounded. */
internal fun bridgeReason(message: String): String =
    Visible.stripped(message).trim().take(200).ifBlank { "the bridge refused it" }

/** A file's name as shown in an error: its own when valid, else with hidden characters written out. */
internal fun safeFileName(name: String?): String = when {
    name.isNullOrEmpty() -> "a file without a name"
    validSendFileName(name) -> name
    else -> Visible.revealed(name).replace('\n', ' ').take(120)
}

internal fun partCount(files: List<SendFile>) = files.sumOf { (it.bytes.size + SEND_PART_BYTES - 1) / SEND_PART_BYTES }
