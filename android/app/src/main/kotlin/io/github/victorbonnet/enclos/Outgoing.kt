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
 * A message with files is its own operation ([files]), settled by the
 * bridge's receipt for its id, never by text shown back.
 * Nothing is queued to go later: the person sends it again.
 */
class Outgoing {
    /** Files to send, by host and conversation. */
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
        // A suggested reply's outcome is its own: a question put in the field does not retire it.
        if (conversation !in replyOutcome) notSent.remove(conversation)
        notes.remove(conversation)
        return true
    }

    /** [conversation]'s screen was left: a question put in its field and left untouched goes, with any note. */
    fun leave(conversation: String) {
        notes.remove(conversation)
        val put = prefilled.remove(conversation) ?: return
        if (unsent[conversation] == put) unsent.remove(conversation)
    }

    /**
     * A message handed to the connection: [id] is its frame's, [back] whether
     * its wait ended since (its connection ended). A [literal] one is a
     * suggested reply: never put back in the field, its outcome is a notice.
     */
    private data class Sent(val host: String, val id: String, val text: String, val back: Boolean = false, val literal: Boolean = false)

    /** By conversation: what was sent and the bridge has not shown back yet, oldest first. */
    private val sent = mutableStateMapOf<String, List<Sent>>()

    /** By conversation: the suggested reply whose outcome [notSent] says, until it is shown back, another send or forgotten. */
    private val replyOutcome = mutableMapOf<String, String>()

    fun waiting(conversation: String): Boolean = sent[conversation].orEmpty().any { !it.back }

    /**
     * Sends a suggested [reply] exactly as it reads, rechecked at tap time.
     * What the person wrote (text, files) and any notice stay as they were,
     * whether it goes or not.
     */
    fun sendSuggested(conversation: String, line: Line, reply: String, history: List<Line>, send: (String) -> String?): String? {
        if (waiting(conversation) || reply !in offeredReplies(line, history)) return "This reply is no longer available."
        val draft = unsent[conversation]
        val notice = notSent[conversation]
        val before = sent[conversation].orEmpty().map { it.id }.toSet()
        val error = send(reply)
        put(conversation, sent[conversation].orEmpty().map { if (it.id !in before) it.copy(literal = true) else it })
        if (draft == null) unsent.remove(conversation) else unsent[conversation] = draft
        if (notice == null) notSent.remove(conversation) else notSent[conversation] = notice
        return error
    }

    /** The person wrote [text] in [conversation]'s field. */
    fun write(conversation: String, text: String) {
        notes.remove(conversation)
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
        replyOutcome.remove(conversation)
        unsent.remove(conversation)
        sent[conversation] = sent[conversation].orEmpty() + Sent(host, id, text)
        return true
    }

    /**
     * The bridge shows a message of the person's in [conversation] (as it
     * stores it, or at a sync): the one sent with that [text] is sent. If
     * its text had been put back in the field and is still there as it was,
     * it leaves the field again, so it is not sent twice. A suggested reply
     * shown back retires its own notice and leaves the field alone, even
     * when it holds the same words. One with files settles no text message:
     * its receipt settles its own operation.
     */
    fun shown(conversation: String, text: String, host: String? = null, hasAttachments: Boolean = false) {
        if (hasAttachments) return
        val now = sent[conversation] ?: return
        fun mine(it: Sent) = it.text == text && (host == null || it.host == host)
        // One still waited for first: one put back may have been sent again since.
        val at = now.indexOfFirst { mine(it) && !it.back }.takeIf { it >= 0 } ?: now.indexOfFirst(::mine)
        if (at < 0) return
        val one = now[at]
        if (one.literal) {
            if (replyOutcome[conversation] == one.id) {
                replyOutcome.remove(conversation)
                notSent.remove(conversation)
            }
        } else if (one.back && unsent[conversation] == text) {
            unsent.remove(conversation)
            if (conversation !in replyOutcome) notSent.remove(conversation)
        }
        put(conversation, now.filterIndexed { i, _ -> i != at })
    }

    /**
     * The connection to [host] ended: what went over it and was not shown
     * back goes back in its field, [why] said. A suggested reply does not:
     * its conversation says it is unconfirmed. Files that went over it are
     * of unknown delivery ([SendingFiles.ended]).
     */
    fun ended(host: String, why: String) {
        files.ended(host)
        for ((conversation, now) in sent.toList()) {
            val lost = now.filter { it.host == host && !it.back }
            if (lost.isEmpty()) continue
            val typed = lost.filterNot { it.literal }
            if (typed.isNotEmpty()) restore(conversation, typed.map { it.text }, why)
            lost.lastOrNull { it.literal }?.let {
                notSent[conversation] = REPLY_UNCONFIRMED
                replyOutcome[conversation] = it.id
            }
            put(conversation, now.map { if (it in lost) it.copy(back = true) else it })
        }
    }

    /** The bridge refused the frame [of], saying [why]: its text goes back in its field (a suggested reply's does not). Returns its conversation, if it was a message. */
    fun refused(of: String, why: String): String? {
        for ((conversation, now) in sent.toList()) {
            val one = now.firstOrNull { it.id == of } ?: continue
            when {
                !one.back && !one.literal -> restore(conversation, listOf(one.text), why)
                conversation !in notSent || replyOutcome[conversation] == one.id -> {
                    notSent[conversation] = why
                    if (one.literal) replyOutcome[conversation] = one.id
                }
            }
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
            replyOutcome.remove(it)
            notes.remove(it)
            prefilled.remove(it)
        }
    }

    /**
     * Forgets everything: the cache cleared, or the ViewModel's end
     * ([closing]), which keeps a camera capture whose result may still come.
     */
    fun clear(closing: Boolean = false) {
        if (closing) files.close() else files.clear()
        unsent.clear()
        notSent.clear()
        sent.clear()
        replyOutcome.clear()
        notes.clear()
        prefilled.clear()
    }

    /** What was sent goes before what the person wrote since, a blank line between. */
    private fun restore(conversation: String, texts: List<String>, why: String) {
        unsent[conversation] = (texts + unsent[conversation].orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
        notSent[conversation] = why
        replyOutcome.remove(conversation)
    }

    private fun put(conversation: String, now: List<Sent>) {
        if (now.isEmpty()) sent.remove(conversation) else sent[conversation] = now
    }
}

internal const val REPLY_UNCONFIRMED = "Reply unconfirmed. Check history before sending it again."
