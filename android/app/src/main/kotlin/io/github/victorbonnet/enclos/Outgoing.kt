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
    /** By conversation: what the person wrote and has not sent yet. */
    val unsent: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** By conversation: why what is back in the field was not sent. */
    val notSent: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** A message handed to the connection: [id] is its frame's, [back] whether its text was put back in the field since. */
    private data class Sent(val host: String, val id: String, val text: String, val back: Boolean = false)

    /** By conversation: what was sent and the bridge has not shown back yet, oldest first. */
    private val sent = mutableMapOf<String, List<Sent>>()

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
    fun send(conversation: String, host: String, text: String, transmit: () -> String?): Boolean {
        val id = transmit() ?: return false
        notSent.remove(conversation)
        unsent.remove(conversation)
        sent[conversation] = sent[conversation].orEmpty() + Sent(host, id, text)
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
        val at = now.indexOfFirst { it.text == text && !it.back }.takeIf { it >= 0 } ?: now.indexOfFirst { it.text == text }
        if (at < 0) return
        if (now[at].back && unsent[conversation] == text) {
            unsent.remove(conversation)
            notSent.remove(conversation)
        }
        put(conversation, now.filterIndexed { i, _ -> i != at })
    }

    /** The connection to [host] ended: what went over it and was not shown back goes back in its field, [why] said. */
    fun ended(host: String, why: String) {
        for ((conversation, now) in sent.toList()) {
            val lost = now.filter { it.host == host && !it.back }
            if (lost.isEmpty()) continue
            restore(conversation, lost.map { it.text }, why)
            put(conversation, now.map { if (it in lost) it.copy(back = true) else it })
        }
    }

    /** The bridge refused the frame [of], saying [why]: its text goes back in its field. Returns its conversation, if it was a message. */
    fun refused(of: String, why: String): String? {
        for ((conversation, now) in sent.toList()) {
            val one = now.firstOrNull { it.id == of } ?: continue
            restore(conversation, listOf(one.text), why)
            // Refused, it will not be shown back.
            put(conversation, now - one)
            return conversation
        }
        return null
    }

    /** Forgets everything of [conversations] (a host forgotten, the cache cleared). */
    fun forget(conversations: Collection<String>) {
        conversations.forEach {
            unsent.remove(it)
            notSent.remove(it)
            sent.remove(it)
        }
    }

    /** Forgets everything (the cache cleared). */
    fun clear() {
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
