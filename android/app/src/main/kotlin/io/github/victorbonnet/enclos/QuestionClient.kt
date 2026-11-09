package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.util.UUID

/** Captures the canonical revision and answer so only its exact receipt finishes a submission. */
data class QuestionRequest(val id: String, val question: QuestionInfo, val answer: QuestionAnswer) {
    fun matches(host: String, of: String, incoming: QuestionInfo): Boolean = host == question.host && id == of &&
        incoming.key == question.key && incoming.conversation == question.conversation && incoming.agent == question.agent

    fun confirms(incoming: QuestionInfo): Boolean = incoming.revision > question.revision &&
        question.copy(state = incoming.state, revision = incoming.revision, answer = incoming.answer) == incoming &&
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
        uncertain[question.key] == true -> "The outcome is unconfirmed. Reconnect to check it before answering again."
        question.key in pending -> "Waiting for the agent to confirm…"
        !connected(question.host) -> "Connect to ${question.host} to answer."
        fresh[question.host] != true -> "Waiting for the host to sync…"
        else -> null
    }

    fun merge(incoming: QuestionInfo, cached: Boolean = false): Boolean {
        if (!accepts(incoming) || runCatching { incoming.validate() }.isFailure) return false
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
                errors[current.key] = "The agent has not confirmed the outcome. The answer was not resent. Reconnect to check it."
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
        if (code == "agent_unavailable") {
            // The bridge can time out before our timer, after native resolution
            // has already happened. Its error is not proof that retrying is safe.
            uncertain[request.question.key] = true
            errors[request.question.key] = reason.ifBlank { "The agent has not confirmed the outcome." } +
                " The answer was not resent. Reconnect to check the outcome."
        } else {
            uncertain.remove(request.question.key)
            errors[request.question.key] = reason.ifBlank { "The agent could not accept this answer. Check the current question." }
        }
        return true
    }

    fun received(host: String, message: JSONObject): Boolean = when (message.optString("type")) {
        "question" -> { runCatching { QuestionInfo.from(host, message.getJSONObject("question")) }.getOrNull()?.let { merge(it) }; true }
        "question.result" -> { runCatching { QuestionInfo.from(host, message.getJSONObject("question")) }.getOrNull()?.let { result(host, message.optString("of"), it) }; true }
        "error" -> failure(host, message.optString("of"), message.optString("message"), message.optString("code"))
        else -> false
    }

    fun authenticated(host: String) { fresh[host] = false; syncing[host] = mutableSetOf() }

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

    private fun finish(request: QuestionRequest) {
        requests.remove(request.id)
        if (pending[request.question.key] == request) pending.remove(request.question.key)
    }
}
