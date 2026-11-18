package io.github.victorbonnet.enclos

/**
 * The person's messages sent and not answered yet, by message id: whether
 * the agent is still to answer in a conversation. A reply or the end of a
 * turn answers all of a conversation's; the bridge refusing one (an `error`
 * naming it in `of`, such as `agent_unavailable`) answers that one; and none
 * waits longer than [limit] milliseconds, for a runtime that takes a message
 * and never says more.
 */
class Awaiting(val limit: Long = 10 * 60_000L) {
    /** By message id: its conversation, and when it was sent. */
    private val sent = mutableMapOf<String, Pair<String, Long>>()

    /** Message [id] went to [conversation] at [at]. */
    fun sent(id: String, conversation: String, at: Long) {
        sent[id] = conversation to at
    }

    /** The bridge refused message [id]: returns its conversation, if it was one sent. */
    fun refused(id: String): String? = sent.remove(id)?.first

    /** The agent answered in [conversation], or its turn ended, or the connection did. */
    fun answered(conversation: String) {
        sent.values.removeAll { it.first == conversation }
    }

    /** Whether a message of [conversation] still waits at [now]; forgets those past [limit]. */
    fun waiting(conversation: String, now: Long): Boolean {
        sent.values.removeAll { now - it.second >= limit }
        return sent.values.any { it.first == conversation }
    }
}
