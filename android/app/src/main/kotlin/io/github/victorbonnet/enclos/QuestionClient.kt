package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.util.UUID

/** Captures the canonical revision and answer so only its exact receipt finishes a submission. */
data class QuestionRequest(val id: String, val question: QuestionInfo, val answer: QuestionAnswer) {
    fun matches(host: String, of: String, incoming: QuestionInfo): Boolean = host == question.host && id == of &&
        incoming.key == question.key && incoming.conversation == question.conversation && incoming.agent == question.agent

    fun confirms(incoming: QuestionInfo): Boolean = incoming.revision > question.revision &&
        question.copy(state = incoming.state, revision = incoming.revision, answer = incoming.answer, confirmed = incoming.confirmed) == incoming &&
        if (answer == QuestionAnswer.Skip) incoming.state == QuestionState.Skipped && incoming.answer == null
        else incoming.state == QuestionState.Answered && incoming.answer?.normalized() == answer.normalized()

    fun command(): JSONObject = message("question.answer", "question" to question.id, "revision" to question.revision, "answer" to answer.json()).put("id", id)
}

/** Canonical metadata, independent drafts and exact receipts; nothing queues or resends offline. */
class QuestionClient(
    private val send: (QuestionRequest) -> Boolean,
    private val connected: (String) -> Boolean,
    private val accepts: (QuestionInfo) -> Boolean,
    private val changed: (QuestionInfo) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
    /** Asks the host for a fresh `sync` over its open connection; false when it could not be sent. */
    private val resync: (String) -> Boolean = { false },
    /** Why a question was not shown (never its text), for the log. */
    private val dropped: (String) -> Unit = {},
) {
    val questions = mutableStateMapOf<QuestionKey, QuestionInfo>()
    val pending = mutableStateMapOf<QuestionKey, QuestionRequest>()
    val errors = mutableStateMapOf<QuestionKey, String>()
    val uncertain = mutableStateMapOf<QuestionKey, Boolean>()
    val receipts = mutableStateMapOf<QuestionKey, String>()
    private val requests = mutableMapOf<String, QuestionRequest>()
    private val fresh = mutableStateMapOf<String, Boolean>()
    private val syncing = mutableMapOf<String, MutableSet<QuestionKey>>()

    fun current(conversation: Conversation, id: String?): QuestionInfo? = id?.let { questions[QuestionKey(conversation.host, it)] }?.takeIf { it.belongsTo(conversation) }

    fun unavailable(question: QuestionInfo): String? = when {
        question.state != QuestionState.Pending -> "This question is ${question.state.wire}."
        uncertain[question.key] == true && checking(question.host) -> "Checking the outcome with ${question.host}…"
        uncertain[question.key] == true -> "The outcome is unconfirmed. Check again before answering again."
        question.key in pending -> "Waiting for the agent to confirm…"
        !connected(question.host) -> "Connect to ${question.host} to answer."
        fresh[question.host] != true -> "Waiting for the host to sync…"
        else -> null
    }

    fun merge(incoming: QuestionInfo, cached: Boolean = false): Boolean {
        if (!accepts(incoming)) {
            dropped("question ${incoming.id} from ${incoming.host}: no kept conversation of a paired host")
            return false
        }
        runCatching { incoming.validate() }.exceptionOrNull()?.let {
            dropped("question ${incoming.id} from ${incoming.host}: ${it.message}")
            return false
        }
        if (!cached) syncing[incoming.host]?.add(incoming.key)
        val current = questions[incoming.key]
        val selected = newestQuestion(current, incoming.normalized())
        if (selected != current) {
            questions[incoming.key] = selected
            if (!cached) changed(selected)
        }
        return true
    }

    fun submit(opening: QuestionInfo, answer: QuestionAnswer): String? {
        val current = questions[opening.key] ?: return null
        if (current != opening || unavailable(current) != null || runCatching { current.validateAnswer(answer) }.isFailure) return null
        val request = QuestionRequest(UUID.randomUUID().toString(), current, answer.normalized())
        requests[request.id] = request
        pending[current.key] = request
        errors.remove(current.key)
        if (!send(request)) {
            finish(request)
            errors[current.key] = "The answer was not sent. Reconnect and check the question."
            return null
        }
        later(30_000) {
            if (requests[request.id] == request) {
                uncertain[current.key] = true
                errors[current.key] = "The agent has not confirmed the outcome. The answer was not resent. Check again to see it."
            }
        }
        return request.id
    }

    fun result(host: String, of: String, incoming: QuestionInfo): Boolean {
        val request = requests[of] ?: return false
        if (!request.matches(host, of, incoming) || !merge(incoming)) return false
        if (questions[incoming.key] != incoming.normalized()) return false
        if (!request.confirms(incoming)) return false
        finish(request)
        uncertain.remove(incoming.key)
        errors.remove(incoming.key)
        receipts[incoming.key] = request.id
        return true
    }

    fun failure(host: String, of: String, reason: String, code: String = ""): Boolean {
        val request = requests[of]?.takeIf { it.question.host == host } ?: return false
        finish(request)
        if (code !in REFUSED_BEFORE_SENDING) {
            // `unconfirmed`: the bridge can time out before our timer, after native
            // resolution has already happened. Its error is not proof that retrying is
            // safe; nor is a code this app does not know.
            uncertain[request.question.key] = true
            errors[request.question.key] = reason.ifBlank { "The agent has not confirmed the outcome." } +
                " The answer was not resent. Check again to see the outcome."
        } else {
            uncertain.remove(request.question.key)
            errors[request.question.key] = reason.ifBlank { "The agent could not accept this answer. Check the current question." }
        }
        return true
    }

    fun received(host: String, message: JSONObject): Boolean = when (message.optString("type")) {
        "question" -> { parsed(host, message)?.let { merge(it) }; true }
        "question.result" -> { parsed(host, message)?.let { result(host, message.optString("of"), it) }; true }
        "error" -> failure(host, message.optString("of"), message.optString("message"), message.optString("code"))
        else -> false
    }

    private fun parsed(host: String, message: JSONObject): QuestionInfo? =
        runCatching { QuestionInfo.from(host, message.getJSONObject("question")) }
            .onFailure { dropped("${message.optString("type")} from $host could not be read: ${it.javaClass.simpleName}") }
            .getOrNull()

    fun authenticated(host: String) { fresh[host] = false; syncing[host] = mutableSetOf() }

    /** Whether a sync of [host]'s is still running over a live connection. */
    private fun checking(host: String): Boolean = connected(host) && fresh[host] != true && host in syncing

    /** Whether [question]'s unconfirmed outcome can be checked now, with [checkAgain]. */
    fun canCheckAgain(question: QuestionInfo): Boolean = question.state == QuestionState.Pending &&
        uncertain[question.key] == true && connected(question.host) && fresh[question.host] == true

    /**
     * Asks the host for a fresh `sync` over the open connection, so an unconfirmed outcome is
     * settled without reconnecting: a sync sends every pending question again, and [synced]
     * then clears the doubt of each one it sent. As at a reconnect, the sync now judges the
     * outcome, not a late receipt. Nothing is resent to the agent.
     */
    fun checkAgain(question: QuestionInfo): Boolean {
        if (!canCheckAgain(question)) return false
        fresh[question.host] = false
        syncing[question.host] = mutableSetOf()
        if (resync(question.host)) {
            pending[question.key]?.let(::finish)
            return true
        }
        fresh[question.host] = true
        syncing.remove(question.host)
        return false
    }

    fun synced(host: String) {
        fresh[host] = true
        syncing.remove(host)?.forEach { key -> uncertain.remove(key); if (key !in pending) errors.remove(key) }
    }

    fun ended(host: String) {
        fresh[host] = false
        syncing.remove(host)
        pending.values.filter { it.question.host == host }.toList().forEach {
            finish(it)
            uncertain[it.question.key] = true
            errors[it.question.key] = "The connection ended before confirmation. Reconnect to check the outcome."
        }
    }

    fun forget(host: String) {
        ended(host)
        questions.keys.filter { it.host == host }.forEach { key -> questions.remove(key); errors.remove(key); uncertain.remove(key); receipts.remove(key) }
        fresh.remove(host)
    }

    private companion object {
        /**
         * Codes with which the bridge refuses an answer before writing it to the agent
         * (enclos `docs/protocol.md`, Errors): nothing was sent, and answering again is safe.
         */
        val REFUSED_BEFORE_SENDING = setOf("agent_unavailable", "bad_message", "conflict", "not_found", "unsupported", "not_allowed")
    }

    private fun finish(request: QuestionRequest) {
        requests.remove(request.id)
        if (pending[request.question.key] == request) pending.remove(request.question.key)
    }
}
