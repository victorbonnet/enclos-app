package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject

/*
 * The rules of enclos-protocol's `question` module, rule for rule (docs/spec.md,
 * Questions, says why they are copied here rather than called through the core).
 * The tests hold the two together with the same cases.
 */

private const val MAX_CHOICES = 12
private const val MAX_QUESTION_BYTES = 24 * 1024
private const val MAX_ANSWER_BYTES = 8 * 1024
private const val MAX_QUESTION_INFO_BYTES = 48 * 1024
private const val MAX_ID_BYTES = 128
private const val MAX_AGENT_BYTES = 512

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
    /**
     * Whether this settlement is the one a paired device submitted, matched by its request.
     * False while pending and for a settlement the agent's channel reports on its own, which
     * the card shows as the agent's report rather than the person's choice.
     */
    val confirmed: Boolean = false,
) {
    val key: QuestionKey get() = QuestionKey(host, id)

    /** `QuestionInfo::validate`: identity, definition, state and answer, and the encoded sizes. */
    fun validate() {
        require(idValid(id) && idValid(conversation) && agentValid(agent)) { "Invalid question identity." }
        require(revision >= 0) { "Invalid question revision." }
        validateDefinition()
        require(!confirmed || state != QuestionState.Pending) { "A pending question has no confirmed answer." }
        // Answered may come without an answer: the runtime accepted one this contract cannot carry.
        if (answer != null) {
            require(state == QuestionState.Answered && answer != QuestionAnswer.Skip) { "Question state and answer disagree." }
            validateAnswer(answer)
        }
        val metadata = definitionJson().dropLast(1) +
            ",\"conversation\":${jsonString(conversation)},\"agent\":${jsonString(agent)}" +
            ",\"state\":${jsonString(state.wire)},\"revision\":$revision" +
            (answer?.let { ",\"answer\":${answerJsonText(it)}" } ?: "") +
            (if (confirmed) ",\"confirmed\":true" else "") + "}"
        require(metadata.utf8Size() <= MAX_QUESTION_INFO_BYTES) { "This question metadata is too large." }
    }

    /** `QuestionDefinition::validate`: text on any lines, labels on one line each and distinct. */
    private fun validateDefinition() {
        require(textValid(text, lines = true)) { "Invalid question text." }
        require(choices.size <= MAX_CHOICES) { "A question supports at most $MAX_CHOICES choices." }
        require(!multiple || choices.isNotEmpty()) { "Multiple selection requires choices." }
        require(choices.all { textValid(it, lines = false) }) { "Invalid choice label." }
        // Distinct once joiners are set aside ([Visible.bare]): two labels must not read the same.
        require(choices.map(Visible::bare).distinct().size == choices.size) { "Choice labels must be distinct." }
        require(definitionJson().utf8Size() <= MAX_QUESTION_BYTES) { "This question is too large." }
    }

    /** `QuestionAnswer::validate` against this question. */
    fun validateAnswer(value: QuestionAnswer) {
        when (value) {
            is QuestionAnswer.Choices -> require(
                value.indices.isNotEmpty() && (multiple || value.indices.size == 1) &&
                    value.indices.distinct().size == value.indices.size && value.indices.all { it in choices.indices },
            ) { "Choose an available answer." }
            is QuestionAnswer.Text -> require(textValid(value.text, lines = true)) {
                "Enter an answer without tabs or hidden characters."
            }
            QuestionAnswer.Skip -> Unit
        }
        require(answerJsonText(value).utf8Size() <= MAX_ANSWER_BYTES) { "This answer is too long." }
    }

    fun belongsTo(c: Conversation): Boolean =
        host == c.host && conversation == c.id && if (c.group) agent in c.members else agent == c.agent

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("choices", JSONArray(choices)).put("multiple", multiple)
        .put("conversation", conversation).put("agent", agent).put("state", state.wire).put("revision", revision)
        .apply { answer?.let { put("answer", it.json()) } }
        .apply { if (confirmed) put("confirmed", true) }

    /** The definition as serde writes it, for its size. */
    private fun definitionJson(): String =
        "{\"id\":${jsonString(id)},\"text\":${jsonString(text)}," +
            "\"choices\":[${choices.joinToString(",", transform = ::jsonString)}],\"multiple\":$multiple}"

    companion object {
        fun from(host: String, json: JSONObject): QuestionInfo {
            fun string(key: String): String = (json.get(key) as? String) ?: throw IllegalArgumentException("Invalid $key")
            require(!json.has("choices") || json.get("choices") is JSONArray) { "Invalid choices" }
            val choices = json.optJSONArray("choices")?.let { array ->
                (0 until array.length()).map { (array.get(it) as? String) ?: throw IllegalArgumentException("Invalid choice") }
            }.orEmpty()
            val multiple = if (json.has("multiple")) {
                json.get("multiple") as? Boolean ?: throw IllegalArgumentException("Invalid multiple")
            } else {
                false
            }
            val revision = json.get("revision")
            require(revision is Int || revision is Long) { "Invalid revision" }
            val state = QuestionState.entries.firstOrNull { it.wire == string("state") }
                ?: throw IllegalArgumentException("Invalid state")
            val answer = if (json.has("answer") && !json.isNull("answer")) answerFrom(json.getJSONObject("answer")) else null
            // serde's `default`: absent is false, and anything but a boolean is refused.
            val confirmed = if (json.has("confirmed")) {
                json.get("confirmed") as? Boolean ?: throw IllegalArgumentException("Invalid confirmed")
            } else {
                false
            }
            val info = QuestionInfo(
                host, string("id"), string("text"), choices, multiple, string("conversation"), string("agent"), state,
                (revision as Number).toLong(), answer, confirmed,
            )
            info.validate()
            return info.normalized()
        }
    }
}

/**
 * The cached questions to keep, from `(host, id, info)` rows: valid, and of a conversation
 * still kept. The keys of the others, which the cache drops.
 */
fun keptQuestions(
    rows: List<Triple<String, String, String>>,
    conversations: List<Conversation>,
    parse: (host: String, info: String) -> QuestionInfo? = { host, info ->
        runCatching { QuestionInfo.from(host, JSONObject(info)) }.getOrNull()
    },
): Pair<List<QuestionInfo>, List<QuestionKey>> {
    val known = conversations.mapTo(HashSet()) { it.host to it.id }
    val kept = mutableListOf<QuestionInfo>()
    val stale = mutableListOf<QuestionKey>()
    for ((host, id, info) in rows) {
        val question = parse(host, info)
        if (question != null && question.id == id && (host to question.conversation) in known) kept += question
        else stale += QuestionKey(host, id)
    }
    return kept to stale
}

/** Immutable definitions and terminal results cannot be replaced by delayed sync/cache work. */
fun newestQuestion(current: QuestionInfo?, incoming: QuestionInfo): QuestionInfo {
    if (current == null || current.key != incoming.key) return incoming
    if (incoming.revision <= current.revision) return current
    if (current.copy(state = incoming.state, revision = incoming.revision, answer = incoming.answer, confirmed = incoming.confirmed) != incoming) return current
    if (current.state != QuestionState.Pending &&
        (current.state != incoming.state || current.answer != incoming.answer || current.confirmed != incoming.confirmed)) return current
    return incoming
}

/** A local draft never sends itself; listed choices and Other are mutually exclusive. */
data class QuestionSelection(val indices: Set<Int> = emptySet(), val other: Boolean = false, val text: String = "") {
    fun choose(index: Int, multiple: Boolean): QuestionSelection = copy(
        indices = if (!multiple) setOf(index) else if (index in indices && !other) indices - index else indices + index,
        other = false,
    )

    fun chooseOther(): QuestionSelection = copy(indices = emptySet(), other = true)

    /** The answer to submit, or none while the draft is not one. Line breaks are sent as plain newlines. */
    fun answer(question: QuestionInfo): QuestionAnswer? {
        val value = if (other || question.choices.isEmpty()) {
            QuestionAnswer.Text(text.replace("\r\n", "\n").replace('\r', '\n'))
        } else {
            QuestionAnswer.Choices(indices.sorted())
        }
        return value.takeIf { runCatching { question.validateAnswer(it) }.isSuccess }
    }
}

internal fun QuestionAnswer.json(): JSONObject = JSONObject(answerJsonText(this))
internal fun QuestionAnswer.normalized(): QuestionAnswer = if (this is QuestionAnswer.Choices) copy(indices = indices.sorted()) else this
internal fun QuestionInfo.normalized(): QuestionInfo = copy(answer = answer?.normalized())

private fun answerFrom(json: JSONObject): QuestionAnswer = when (json.getString("kind")) {
    "choices" -> {
        val array = json.getJSONArray("indices")
        QuestionAnswer.Choices(
            (0 until array.length()).map { i ->
                val value = array.get(i)
                require(value is Int || value is Long) { "Invalid index" }
                val index = (value as Number).toLong()
                require(index in 0..Int.MAX_VALUE.toLong()) { "Invalid index" }
                index.toInt()
            },
        )
    }
    "text" -> QuestionAnswer.Text((json.get("text") as? String) ?: throw IllegalArgumentException("Invalid text"))
    "skip" -> QuestionAnswer.Skip
    else -> throw IllegalArgumentException("Invalid answer")
}

private fun answerJsonText(answer: QuestionAnswer): String = when (answer) {
    is QuestionAnswer.Choices -> "{\"kind\":\"choices\",\"indices\":[${answer.indices.joinToString(",")}]}"
    is QuestionAnswer.Text -> "{\"kind\":\"text\",\"text\":${jsonString(answer.text)}}"
    QuestionAnswer.Skip -> "{\"kind\":\"skip\"}"
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/** A lone surrogate is no character: Rust's strings cannot hold one. */
private fun hasSurrogate(value: String): Boolean = value.codePoints().anyMatch { it in 0xD800..0xDFFF }

/** `ids::check`: nonempty, at most 128 bytes, no control character. */
private fun idValid(value: String): Boolean = value.isNotEmpty() && value.utf8Size() <= MAX_ID_BYTES &&
    value.codePoints().noneMatch(Character::isISOControl) && !hasSurrogate(value)

private fun agentValid(value: String): Boolean = value.isNotEmpty() && value.utf8Size() <= MAX_AGENT_BYTES &&
    value.codePoints().noneMatch(Character::isISOControl) && !hasSurrogate(value)

/** `question::text`: not blank, and no hidden character ([Visible]), newlines aside when [lines]. */
private fun textValid(value: String, lines: Boolean): Boolean =
    value.isNotBlank() && !Visible.hasHidden(value, lines) && !hasSurrogate(value)

/** serde_json's escaping, for sizes: hidden characters are refused before, newlines aside. */
private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}
