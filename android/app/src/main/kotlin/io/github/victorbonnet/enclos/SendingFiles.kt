package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

internal fun fileSendAvailable(group: Boolean, bridge: Boolean, agent: Boolean) = !group && bridge && agent

internal val sendFileTypes = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
    "pdf" to "application/pdf", "md" to "text/markdown", "txt" to "text/plain", "log" to "text/plain", "py" to "text/plain", "sh" to "text/plain",
    "csv" to "text/csv", "json" to "application/json", "xml" to "application/xml", "yaml" to "application/yaml", "yml" to "application/yaml",
    "toml" to "application/toml", "zip" to "application/zip", "doc" to "application/msword", "xls" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation")

internal fun validSendFile(name: String, mime: String, size: Int): Boolean =
    size in 1..10_000_000 && name == name.trim() && name.codePointCount(0, name.length) <= 120 &&
        name.toByteArray().size <= 255 && !Visible.hasHidden(name) && name.substringBeforeLast('.', "").isNotEmpty() &&
        name.none { it == '/' || it == '\\' || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } &&
        sendFileTypes[name.substringAfterLast('.').lowercase()] == mime

internal data class SendFile(val name: String, val mime: String, val bytes: ByteArray) {
    fun declaration() = JSONObject().put("name", name).put("mime", mime).put("size", bytes.size)
}

/** Memory belongs only to a draft or an explicitly retryable send; no persistent hidden copies. */
internal class SendingFiles {
    var generation: Long = 0
        private set
    val active = java.util.concurrent.ConcurrentHashMap<String, Any>()
    val cameras = mutableMapOf<String, java.io.File>()
    val drafts = mutableStateMapOf<String, List<SendFile>>()
    val progress = mutableStateMapOf<String, Int>()
    private val ids = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun current(c: String, id: String) = ids[c] == id
    private val payloads = mutableMapOf<String, List<SendFile>>()
    private val texts = mutableMapOf<String, String>()
    fun id(c: String, text: String): String {
        if (texts[c] != text || payloads[c] != drafts[c].orEmpty()) ids.remove(c)
        payloads[c] = drafts[c].orEmpty().toList()
        texts[c] = text
        return ids.getOrPut(c) { UUID.randomUUID().toString() }
    }
    fun remove(c: String, index: Int) {
        drafts[c] = drafts[c].orEmpty().filterIndexed { i, _ -> i != index }
        ids.remove(c)
        payloads.remove(c)
    }
    fun forget(c: String) { cameras.remove(c)?.delete(); generation++; active.remove(c); drafts.remove(c); progress.remove(c); ids.remove(c); texts.remove(c); payloads.remove(c) }
    fun clear() { cameras.values.forEach { it.delete() }; cameras.clear(); generation++; active.clear(); drafts.clear(); progress.clear(); ids.clear(); texts.clear(); payloads.clear() }
}

/** Lazy ordered frames, bounded to one decoded chunk at a time; retries start from zero. */
internal fun fileParts(id: String, files: List<SendFile>): Sequence<JSONObject> = sequence {
    files.forEachIndexed { index, file ->
        var offset = 0
        while (offset < file.bytes.size) {
            val end = minOf(offset + 45_000, file.bytes.size)
            yield(message("message.part", "message" to id, "file" to index, "offset" to offset,
                "data" to Base64.getUrlEncoder().withoutPadding().encodeToString(file.bytes.copyOfRange(offset, end))))
            offset = end
        }
    }
}
