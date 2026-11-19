package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * What the person writes and sends, by conversation, while the app runs.
 *
 * A message leaves the field when its frame is handed to the connection,
 * but that only means it is queued on the phone: it is sent once the
 * bridge shows it back as stored (its `message`, from the person). Until
 * then it is kept here; should the connection end, or the bridge refuse
 * it, first, the text goes back into the field with why it was not sent.
 * File sends instead keep an immutable original separately until an ACK; canonical
 * history has no origin ID. Failure means unknown delivery, never a merged draft.
 * Nothing is queued to go later: the person sends it again.
 */
class Outgoing {
    internal val files = SendingFiles()
    /** By conversation: what the person wrote and has not sent yet. */
    val unsent: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** By conversation: why what is back in the field was not sent. */
    val notSent: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** A message handed to the connection: [id] is its frame's, [back] whether its text was put back in the field since. */
    private data class Sent(val host: String, val id: String, val text: String, val back: Boolean = false, val withFiles: Boolean = false, val literal: Boolean = false)

    /** By conversation: what was sent and the bridge has not shown back yet, oldest first. */
    private val sent = mutableStateMapOf<String, List<Sent>>()

    fun waiting(conversation: String): Boolean = sent[conversation].orEmpty().any { !it.back }

    internal fun waitingFiles(conversation: String): Boolean = sent[conversation].orEmpty().any { it.withFiles && !it.back }

    /** Literal reply path, rechecked at tap time; preserve drafts on both success and failure. */
    fun sendSuggested(conversation: String, line: Line, reply: String, history: List<Line>, send: (String) -> String?): String? {
        if (waiting(conversation) || reply !in offeredReplies(line, history)) return "This reply is no longer available."
        val draft = unsent[conversation]
        val previousError = notSent[conversation]
        val before = sent[conversation].orEmpty().map { it.id }.toSet()
        val error = send(reply)
        put(conversation, sent[conversation].orEmpty().map { if (it.id !in before) it.copy(literal = true) else it })
        if (draft == null) unsent.remove(conversation) else unsent[conversation] = draft
        if (previousError == null) notSent.remove(conversation) else notSent[conversation] = previousError
        return error
    }

    /** The person wrote [text] in [conversation]'s field. */
    fun write(conversation: String, text: String) {
        if (text.isEmpty()) unsent.remove(conversation) else unsent[conversation] = text
    }

    /**
     * Sends [text] in [conversation], on [host], with [transmit], which
     * returns the id of the frame it handed to the connection, or none when
     * it could not (not connected, the frame refused). The text leaves the
     * field only once handed over; returns whether it was.
     */
    fun send(conversation: String, host: String, text: String, withFiles: Boolean = files.drafts[conversation].orEmpty().isNotEmpty(), preserveDraft: Boolean = false, transmit: () -> String?): Boolean {
        val id = transmit() ?: return false
        notSent.remove(conversation)
        if (!preserveDraft) unsent.remove(conversation)
        if (withFiles) files.retain(conversation, id, text)
        sent[conversation] = sent[conversation].orEmpty().filterNot { it.id == id } + Sent(host, id, text, withFiles = withFiles)
        return true
    }

    /**
     * The bridge shows a message of the person's in [conversation] (as it
     * stores it, or at a sync): the one sent with that [text] is sent. If
     * its text had been put back in the field and is still there as it was,
     * it leaves the field again, so it is not sent twice.
     */
    fun shown(conversation: String, text: String) {
        val now = sent[conversation] ?: return
        // One still waited for first: one put back may have been sent again since.
        val at = now.indexOfFirst { it.text == text && !it.back && !it.withFiles }.takeIf { it >= 0 } ?: now.indexOfFirst { it.text == text && !it.withFiles }
        if (at < 0) return
        if (now[at].back && unsent[conversation] == text) {
            unsent.remove(conversation)
            notSent.remove(conversation)
        }
        put(conversation, now.filterIndexed { i, _ -> i != at })
    }

    internal fun discardRetry(c: String) {
        val retry = files.retries[c] ?: return
        if (waitingFiles(c)) return
        files.discardRetry(c)
        put(c, sent[c].orEmpty().filterNot { it.id == retry.id })
        notSent.remove(c)
    }
    internal fun waitingHost(host: String) = sent.values.flatten().any { it.host == host && it.withFiles && !it.back }
    internal fun pending(c: String, id: String) = sent[c].orEmpty().any { it.id == id && !it.back }
    internal fun timedOut(c: String, id: String) {
        val one = sent[c].orEmpty().firstOrNull { it.id == id && !it.back } ?: return
        ended(one.host, "Receipt timed out; check history and retry explicitly.")
    }
    internal fun acknowledged(host: String, id: String) {
        for ((c, now) in sent.toList()) {
            val one = now.firstOrNull { it.host == host && it.id == id && it.withFiles } ?: continue
            if (files.current(c, one.id)) {
                files.discardRetry(c)
                files.active.remove(c)
                files.progress.remove(c)
                notSent.remove(c)
            }
            put(c, now - one)
        }
    }

    /** The connection to [host] ended: what went over it and was not shown back goes back in its field, [why] said. */
    fun ended(host: String, why: String) {
        for ((conversation, now) in sent.toList()) {
            val lost = now.filter { it.host == host && !it.back }
            if (lost.isEmpty()) continue
            files.active.remove(conversation)
            files.progress.remove(conversation)
            val textOnly = lost.filterNot { it.withFiles || it.literal }
            if (textOnly.isNotEmpty()) restore(conversation, textOnly.map { it.text }, why)
            if (lost.any { it.literal }) notSent[conversation] = "Reply unconfirmed. Check history before sending it again."
            if (lost.any { it.withFiles }) notSent[conversation] = "Delivery unknown. Check history before retrying the original message."
            put(conversation, now.map { if (it in lost) it.copy(back = true) else it })
        }
    }

    /** The bridge refused the frame [of], saying [why]: its text goes back in its field. Returns its conversation, if it was a message. */
    fun refused(of: String, why: String): String? {
        for ((conversation, now) in sent.toList()) {
            val one = now.firstOrNull { it.id == of } ?: continue
            if (one.withFiles) {
                if (!files.current(conversation, one.id)) {
                    put(conversation, now - one)
                    return conversation
                }
                files.active.remove(conversation)
                files.progress.remove(conversation)
                notSent[conversation] = "Delivery unknown. Check history before retrying the original message."
                put(conversation, now.map { if (it.id == of) it.copy(back = true) else it })
                return conversation
            }
            if (!one.back && !one.literal) restore(conversation, listOf(one.text), why) else if (conversation !in notSent) notSent[conversation] = why
            // Refused, it will not be shown back.
            put(conversation, now - one)
            return conversation
        }
        return null
    }

    /** Forgets everything of [conversations] (a host forgotten, the cache cleared). */
    fun forget(conversations: Collection<String>) {
        conversations.forEach {
            files.forget(it)
            unsent.remove(it)
            notSent.remove(it)
            sent.remove(it)
        }
    }

    /** Forgets everything (the cache cleared). */
    fun clear() {
        files.clear()
        unsent.clear()
        notSent.clear()
        sent.clear()
    }

    /** What was sent goes before what the person wrote since, a blank line between. */
    private fun restore(conversation: String, texts: List<String>, why: String) {
        unsent[conversation] = (texts + unsent[conversation].orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
        notSent[conversation] = why
    }

    private fun put(conversation: String, now: List<Sent>) {
        if (now.isEmpty()) sent.remove(conversation) else sent[conversation] = now
    }
}
