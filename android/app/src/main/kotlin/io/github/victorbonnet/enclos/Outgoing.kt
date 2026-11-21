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
 * Nothing is queued to go later: the person sends it again.
 */
class Outgoing {
    /** Files settle by the original operation id, independently of text history. */
    internal val files = SendingFiles()

    /** By conversation: what the person wrote and has not sent yet. */
    val unsent: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** By conversation: why what is back in the field was not sent. */
    val notSent: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** By conversation: a note on the field, such as a draft kept in place of a question. It goes once the person writes. */
    val notes: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** By conversation: the question the app last put in the field ([prefill]), to tell it from what the person wrote. */
    private val prefilled = mutableMapOf<String, String>()

    /**
     * Puts [text] in [conversation]'s field for the person to read, change
     * and send, when the field is empty or still holds, untouched, what was
     * put there before. Text the person wrote is kept, with a note saying
     * [text] was not added. Returns whether [text] is in the field.
     */
    fun prefill(conversation: String, text: String): Boolean {
        val current = unsent[conversation]
        if (!current.isNullOrEmpty() && current != prefilled[conversation]) {
            notes[conversation] = "Your draft is kept: the question was not added."
            return false
        }
        unsent[conversation] = text
        prefilled[conversation] = text
        notSent.remove(conversation)
        notes.remove(conversation)
        return true
    }

    /** [conversation]'s screen was left: a question put in its field and left untouched goes, with any note. */
    fun leave(conversation: String) {
        notes.remove(conversation)
        val put = prefilled.remove(conversation) ?: return
        if (unsent[conversation] == put) unsent.remove(conversation)
    }

    /** A message handed to the connection: [id] is its frame's, [back] whether its text was put back in the field since. */
    private data class Sent(val host: String, val id: String, val text: String, val back: Boolean = false, val ownsRestored: Boolean = false)

    /** By conversation: what was sent and the bridge has not shown back yet, oldest first. */
    private val sent = mutableStateMapOf<String, List<Sent>>()

    fun waiting(conversation: String): Boolean = sent[conversation].orEmpty().any { !it.back }

    /** Literal reply path, rechecked at tap time; preserve drafts on both success and failure. */
    fun sendSuggested(conversation: String, line: Line, reply: String, history: List<Line>, send: (String) -> String?): String? {
        if (waiting(conversation) || reply !in offeredReplies(line, history)) return "This reply is no longer available."
        val draft = unsent[conversation]
        val restored = sent[conversation].orEmpty().filter { it.ownsRestored }.map { it.id }.toSet()
        val error = send(reply)
        if (error == null) {
            if (draft != null) unsent[conversation] = draft
            // Sending the literal temporarily removes the field. Putting that same
            // draft back is not a user edit of an older restored ordinary send.
            put(conversation, sent[conversation].orEmpty().map {
                if (it.id in restored) it.copy(ownsRestored = true) else it
            })
        } else if (unsent[conversation] != reply) {
            restore(conversation, listOf(reply), error)
        } else notSent[conversation] = error
        return error
    }

    /** The person wrote [text] in [conversation]'s field. */
    fun write(conversation: String, text: String) {
        notes.remove(conversation)
        relinquish(conversation)
        if (text.isEmpty()) unsent.remove(conversation) else unsent[conversation] = text
    }

    /**
     * Sends [text] in [conversation], on [host], with [transmit], which
     * returns the id of the frame it handed to the connection, or none when
     * it could not (not connected, the frame refused). The text leaves the
     * field only once handed over; returns whether it was.
     */
    fun send(conversation: String, host: String, text: String, transmit: () -> String?): Boolean {
        val id = transmit() ?: return false
        notSent.remove(conversation)
        unsent.remove(conversation)
        relinquish(conversation)
        sent[conversation] = sent[conversation].orEmpty() + Sent(host, id, text)
        return true
    }

    /**
     * The bridge shows a message of the person's in [conversation] (as it
     * stores it, or at a sync): the one sent with that [text] is sent. If
     * its text had been put back in the field and is still there as it was,
     * it leaves the field again, so it is not sent twice.
     */
    fun shown(conversation: String, text: String, host: String? = null, hasAttachments: Boolean = false) {
        if (hasAttachments) return
        val now = sent[conversation] ?: return
        // One still waited for first: one put back may have been sent again since.
        val at = now.indexOfFirst { it.text == text && (host == null || it.host == host) && !it.back }.takeIf { it >= 0 } ?: now.indexOfFirst { it.text == text && (host == null || it.host == host) }
        if (at < 0) return
        if (now[at].ownsRestored && unsent[conversation] == text) {
            unsent.remove(conversation)
            notSent.remove(conversation)
        }
        put(conversation, now.filterIndexed { i, _ -> i != at })
    }

    /** The connection to [host] ended: what went over it and was not shown back goes back in its field, [why] said. */
    fun ended(host: String, why: String) {
        files.ended(host)
        for ((conversation, now) in sent.toList()) {
            val lost = now.filter { it.host == host && !it.back }
            if (lost.isEmpty()) continue
            restore(conversation, lost.map { it.text }, why)
            put(conversation, sent[conversation].orEmpty().map {
                if (it in lost) it.copy(back = true, ownsRestored = unsent[conversation] == it.text) else it
            })
        }
    }

    /** The bridge refused the frame [of], saying [why]: its text goes back in its field. Returns its conversation, if it was a message. */
    fun refused(of: String, why: String): String? {
        for ((conversation, now) in sent.toList()) {
            val one = now.firstOrNull { it.id == of } ?: continue
            if (!one.back) restore(conversation, listOf(one.text), why)
            else if (conversation !in notSent) notSent[conversation] = why
            // Refused, it will not be shown back.
            put(conversation, sent[conversation].orEmpty().filter { it.id != one.id })
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
            notes.remove(it)
            prefilled.remove(it)
        }
    }

    /** Forgets everything (the cache cleared). */
    fun clear(closing: Boolean = false) {
        if (closing) files.close() else files.clear()
        notes.clear()
        prefilled.clear()
        unsent.clear()
        notSent.clear()
        sent.clear()
    }

    /** What was sent goes before what the person wrote since, a blank line between. */
    private fun restore(conversation: String, texts: List<String>, why: String) {
        relinquish(conversation)
        unsent[conversation] = (texts + unsent[conversation].orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
        notSent[conversation] = why
    }

    // Ownership belongs to a restoration, not a text value. Even an equal user
    // write replaces it. Keep this bit on the pending send rather than a second map.
    private fun relinquish(conversation: String) {
        val now = sent[conversation] ?: return
        put(conversation, now.map { if (it.ownsRestored) it.copy(ownsRestored = false) else it })
    }

    private fun put(conversation: String, now: List<Sent>) {
        if (now.isEmpty()) sent.remove(conversation) else sent[conversation] = now
    }
}
