package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject

/** Question ids are scoped by the host which authenticated their origin. */
data class QuestionKey(val host: String, val id: String)

enum class QuestionState(val wire: String) {
    Pending("pending"), Answered("answered"), Skipped("skipped"), Expired("expired"), Cancelled("cancelled"),
}

sealed interface QuestionAnswer {
    data class Choices(val indices: List<Int>) : QuestionAnswer
    data class Text(val text: String) : QuestionAnswer
    data object Skip : QuestionAnswer
}

data class QuestionInfo(
    val host: String,
    val id: String,
    val text: String,
    val choices: List<String>,
    val multiple: Boolean,
    val conversation: String,
    val agent: String,
    val state: QuestionState,
    val revision: Long,
    val answer: QuestionAnswer? = null,
) {
    val key: QuestionKey get() = QuestionKey(host, id)

    fun validate() {
        require(questionIdValid(id) && questionIdValid(conversation) && agent.isNotEmpty() && agent.toByteArray(Charsets.UTF_8).size <= 512 &&
            agent.codePoints().noneMatch { Character.isISOControl(it) || it in 0xD800..0xDFFF }) { "Invalid question identity." }
        require(revision >= 0) { "Invalid question revision." }
        require(questionTextValid(text) && choices.size <= 12 && choices.all(::questionTextValid)) { "Invalid question or choices." }
        require(!multiple || choices.isNotEmpty()) { "Multiple selection requires choices." }
        val definition = "{\"id\":${jsonString(id)},\"text\":${jsonString(text)},\"choices\":[${choices.joinToString(",", transform = ::jsonString)}],\"multiple\":$multiple}"
        require(definition.toByteArray(Charsets.UTF_8).size <= 24 * 1024) { "This question is too large." }
        when (state) {
            QuestionState.Pending, QuestionState.Skipped, QuestionState.Expired, QuestionState.Cancelled -> require(answer == null)
            QuestionState.Answered -> { require(answer != null && answer != QuestionAnswer.Skip); validateAnswer(answer) }
        }
        val metadata = definition.dropLast(1) + ",\"conversation\":${jsonString(conversation)},\"agent\":${jsonString(agent)},\"state\":${jsonString(state.wire)},\"revision\":$revision" +
            (answer?.let { ",\"answer\":${answerJsonText(it)}" } ?: "") + "}"
        require(metadata.toByteArray(Charsets.UTF_8).size <= 48 * 1024) { "This question metadata is too large." }
    }

    fun validateAnswer(value: QuestionAnswer) {
        when (value) {
            is QuestionAnswer.Choices -> require(value.indices.isNotEmpty() && (multiple || value.indices.size == 1) &&
                value.indices.distinct().size == value.indices.size && value.indices.all { it in choices.indices }) { "Choose an available answer." }
            is QuestionAnswer.Text -> require(questionTextValid(value.text)) { "Enter an answer without control characters." }
            QuestionAnswer.Skip -> Unit
        }
        require(answerJsonText(value).toByteArray(Charsets.UTF_8).size <= 8 * 1024) { "This answer is too long." }
    }

    fun belongsTo(c: Conversation): Boolean = host == c.host && conversation == c.id &&
        if (c.group) agent in c.members else agent == c.agent

    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("choices", JSONArray(choices))
        .put("multiple", multiple).put("conversation", conversation).put("agent", agent).put("state", state.wire)
        .put("revision", revision).apply { answer?.let { put("answer", it.json()) } }

    companion object {
        fun from(host: String, json: JSONObject): QuestionInfo {
            fun string(key: String): String = (json.get(key) as? String) ?: throw IllegalArgumentException("Invalid $key")
            val choices = json.optJSONArray("choices")?.let { a -> (0 until a.length()).map { (a.get(it) as? String) ?: throw IllegalArgumentException("Invalid choice") } }.orEmpty()
            require(!json.has("choices") || json.get("choices") is JSONArray)
            val multiple = if (json.has("multiple")) json.get("multiple") as? Boolean ?: throw IllegalArgumentException("Invalid multiple") else false
            val rawRevision = json.get("revision")
            require(rawRevision is Int || rawRevision is Long)
            val state = QuestionState.entries.firstOrNull { it.wire == string("state") } ?: throw IllegalArgumentException("Invalid state")
            return QuestionInfo(host, string("id"), string("text"), choices, multiple, string("conversation"), string("agent"), state,
                (rawRevision as Number).toLong(), if (json.has("answer") && !json.isNull("answer")) answerFrom(json.getJSONObject("answer")) else null).also { it.validate() }.normalized()
        }
    }
}

/** Immutable definitions and terminal results cannot be replaced by delayed sync/cache work. */
fun newestQuestion(current: QuestionInfo?, incoming: QuestionInfo): QuestionInfo {
    if (current == null || current.key != incoming.key) return incoming
    if (incoming.revision <= current.revision) return current
    if (current.copy(state = incoming.state, revision = incoming.revision, answer = incoming.answer) != incoming) return current
    if (current.state != QuestionState.Pending && (current.state != incoming.state || current.answer != incoming.answer)) return current
    return incoming
}

/** A local draft never sends itself; listed choices and Other are mutually exclusive. */
data class QuestionSelection(val indices: Set<Int> = emptySet(), val other: Boolean = false, val text: String = "") {
    fun choose(index: Int, multiple: Boolean): QuestionSelection = copy(
        indices = if (!multiple) setOf(index) else if (index in indices && !other) indices - index else indices + index,
        other = false,
    )
    fun chooseOther(): QuestionSelection = copy(indices = emptySet(), other = true)
    fun answer(question: QuestionInfo): QuestionAnswer? {
        val value = if (other || question.choices.isEmpty()) QuestionAnswer.Text(text) else QuestionAnswer.Choices(indices.sorted())
        return value.takeIf { runCatching { question.validateAnswer(it) }.isSuccess }
    }
}

internal fun QuestionAnswer.json(): JSONObject = JSONObject(answerJsonText(this))
internal fun QuestionAnswer.normalized(): QuestionAnswer = if (this is QuestionAnswer.Choices) copy(indices = indices.sorted()) else this
internal fun QuestionInfo.normalized(): QuestionInfo = copy(answer = answer?.normalized())

private fun answerFrom(json: JSONObject): QuestionAnswer = when (json.getString("kind")) {
    "choices" -> QuestionAnswer.Choices(json.getJSONArray("indices").let { a -> (0 until a.length()).map { i ->
        val value = a.get(i); require(value is Int || value is Long)
        val index = (value as Number).toLong(); require(index in 0..Int.MAX_VALUE.toLong()); index.toInt()
    } })
    "text" -> QuestionAnswer.Text((json.get("text") as? String) ?: throw IllegalArgumentException("Invalid text"))
    "skip" -> QuestionAnswer.Skip
    else -> throw IllegalArgumentException("Invalid answer")
}

private fun answerJsonText(answer: QuestionAnswer): String = when (answer) {
    is QuestionAnswer.Choices -> "{\"kind\":\"choices\",\"indices\":[${answer.indices.joinToString(",")}]}"
    is QuestionAnswer.Text -> "{\"kind\":\"text\",\"text\":${jsonString(answer.text)}}"
    QuestionAnswer.Skip -> "{\"kind\":\"skip\"}"
}

private fun questionIdValid(value: String): Boolean = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 128 &&
    value.codePoints().noneMatch { Character.isISOControl(it) || it in 0xD800..0xDFFF }

private fun questionTextValid(value: String): Boolean = value.isNotBlank() && value.codePoints().noneMatch {
    (Character.isISOControl(it) && it != 10 && it != 13 && it != 9) || it in 0xD800..0xDFFF
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c -> when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(c) } }
    append('"')
}
