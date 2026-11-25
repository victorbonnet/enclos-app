package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject

/*
 * Held tasks (enclos ADR 0066, `docs/protocol.md`, Held tasks; docs/spec.md, Held tasks):
 * background work an agent's runtime started that has stopped and waits for a person
 * before it runs again. The runtime keeps the task; the bridge keeps a card for it in the
 * Home of the agent that started it and sends it to the host's person alone. The app
 * shows the card and offers Resume when the runtime permits it and, where the bridge
 * takes one (`held_task.answer`, enclos ADR 0073), the person's answer to the agent.
 */

/** enclos-protocol's `held_task` bounds, in characters, as the bridge cuts a card. */
internal const val MAX_HELD_TITLE_CHARS = 200
internal const val MAX_HELD_REASON_CHARS = 1_000
internal const val MAX_HELD_ERROR_CHARS = 2_000
internal const val MAX_HELD_RESUME_CHARS = 200
internal const val MAX_HELD_ASSIGNEE_CHARS = 64
private const val MAX_HELD_AGENT_BYTES = 512

/** enclos-protocol's bounds of an agent's ask and of the person's answer (enclos ADR 0073). */
internal const val MAX_HELD_DECISION_CHARS = 120
internal const val MAX_HELD_BLOCKED_CHARS = 400
internal const val MAX_HELD_CHOICES = 4
internal const val MAX_HELD_CHOICE_CHARS = 80
internal const val MAX_HELD_ANSWER_CHARS = 200
internal const val MAX_HELD_ANSWER_TEXT_CHARS = 2_000

/** The bridge's capability for a person's answer to a held card (enclos ADR 0073). */
internal const val HELD_TASK_ANSWER = "held_task.answer"

/** What a card says when the runtime gave no reason (enclos-protocol's `NO_REASON`). */
internal const val HELD_NO_REASON = "Waiting for a person."

/**
 * A card's identity: the bridge keeps one per task of each agent, and a task id is the
 * runtime's, so it is scoped by the host and the agent that started the work.
 */
data class HeldTaskKey(val host: String, val agent: String, val task: String)

/**
 * Where a card is. `held` is the only state with a Resume or an answer, and the only one under
 * Needs you. `answered` is still held by the runtime, but waits on the agent, not the person.
 * A state this app does not know has no action.
 */
enum class HeldTaskState(val wire: String) {
    Held("held"), Answered("answered"), Resumed("resumed"), Released("released"), Expired("expired"), Unknown("");

    companion object {
        fun of(wire: String): HeldTaskState = entries.firstOrNull { it != Unknown && it.wire == wire } ?: Unknown
    }
}

/**
 * What the agent that started a task wrote for the person when it handed the hold to them
 * (enclos ADR 0073): [decision], one question; [blocked], what is stopped and why; [choices],
 * up to four answers, each saying what happens, or none for a free answer; [recommended], the
 * index of the choice the agent recommends. A choice is answered by its index, the bridge's,
 * so the app never drops or reorders one: it only changes the order they are shown in.
 */
data class HeldTaskAsk(
    val decision: String,
    val blocked: String,
    val choices: List<String> = emptyList(),
    val recommended: Int? = null,
) {
    /** The ask as the bridge writes it, for the cache. */
    fun json(): JSONObject = JSONObject().put("decision", decision).put("blocked", blocked)
        .apply { if (choices.isNotEmpty()) put("choices", JSONArray(choices)) }
        .apply { recommended?.let { put("recommended", it) } }

    /** The choices in the order the card shows them, each with its index: the recommended one first. */
    fun shown(): List<Pair<Int, String>> {
        val all = choices.mapIndexed { i, label -> i to label }
        return all.filter { it.first == recommended } + all.filter { it.first != recommended }
    }

    companion object {
        /**
         * Reads an `ask` object, cut again here as the bridge cuts it: a field of the wrong type
         * refuses the card (IllegalArgumentException). An ask the bridge would not have sent (a
         * blank decision, more than four choices, a blank choice) reads as none, so the card is
         * one no agent wrote, rather than offering a choice whose index the bridge reads
         * otherwise. A `recommended` that names no choice is dropped.
         */
        internal fun from(json: JSONObject): HeldTaskAsk? {
            val decision = (json.opt("decision") as? String) ?: throw IllegalArgumentException("Invalid ask decision")
            val blocked = if (!json.has("blocked") || json.isNull("blocked")) "" else
                (json.get("blocked") as? String) ?: throw IllegalArgumentException("Invalid ask blocked")
            val choices = if (!json.has("choices") || json.isNull("choices")) emptyList() else {
                val array = json.get("choices") as? JSONArray ?: throw IllegalArgumentException("Invalid ask choices")
                (0 until array.length()).map { (array.get(it) as? String) ?: throw IllegalArgumentException("Invalid ask choice") }
            }
            val recommended = if (!json.has("recommended") || json.isNull("recommended")) null else
                when (val v = json.get("recommended")) {
                    is Int -> v
                    // Beyond any choice's index: dropped below.
                    is Long -> -1
                    else -> throw IllegalArgumentException("Invalid ask recommended")
                }
            val shownDecision = heldCut(heldOneLine(decision), MAX_HELD_DECISION_CHARS)
            val shownChoices = choices.map { heldCut(heldOneLine(it), MAX_HELD_CHOICE_CHARS) }
            if (shownDecision.isEmpty() || shownChoices.size > MAX_HELD_CHOICES || shownChoices.any { it.isEmpty() }) return null
            return HeldTaskAsk(
                decision = shownDecision,
                blocked = heldCut(Visible.stripped(blocked, lines = true), MAX_HELD_BLOCKED_CHARS),
                choices = shownChoices,
                recommended = recommended?.takeIf { it in shownChoices.indices },
            )
        }
    }
}

/** A person's answer to a held card: `held_task.answer`'s `answer` (enclos ADR 0073). */
sealed interface HeldTaskAnswer {
    /** One of the card's ask choices, by its zero-based index. */
    data class Choice(val index: Int) : HeldTaskAnswer

    /** A typed answer, as [heldAnswerText] reads it. */
    data class Text(val text: String) : HeldTaskAnswer

    /** Ask the agent to look into the task and say what it needs. */
    data object Agent : HeldTaskAnswer

    fun json(): JSONObject = when (this) {
        is Choice -> JSONObject().put("kind", "choice").put("index", index)
        is Text -> JSONObject().put("kind", "text").put("text", text)
        Agent -> JSONObject().put("kind", "agent")
    }
}

/**
 * [typed] as a typed answer the bridge takes (enclos `held_task.answer`): trimmed, nonblank, at
 * most 2,000 characters, with no hidden character other than a newline; null otherwise.
 */
internal fun heldAnswerText(typed: String): String? {
    val text = typed.trim()
    if (text.isEmpty() || text.codePointCount(0, text.length) > MAX_HELD_ANSWER_TEXT_CHARS) return null
    return text.takeUnless { Visible.hasHidden(it, lines = true) }
}

/**
 * [text] on one line, as enclos-protocol's `held_task::one_line`: each run of white space one
 * space, hidden characters dropped. No regex: Android's engine refuses the inline Unicode-class
 * flag the JVM accepts, which threw on every card with an ask or an answer, so phones never
 * showed those cards.
 */
internal fun heldOneLine(text: String): String {
    val line = StringBuilder()
    var gap = false
    text.codePoints().forEach { c ->
        if (isUnicodeWhiteSpace(c)) gap = true
        else {
            if (gap && line.isNotEmpty()) line.append(' ')
            gap = false
            line.appendCodePoint(c)
        }
    }
    return Visible.stripped(line.toString())
}

/** Unicode's White_Space property, as Rust's `char::is_whitespace`. */
internal fun isUnicodeWhiteSpace(c: Int): Boolean =
    c in 0x09..0x0D || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 || c in 0x2000..0x200A ||
        c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000

/** [text] trimmed and cut to [max] characters (code points), as `held_task::cut` does. */
private fun heldCut(text: String, max: Int): String {
    val trimmed = text.trim()
    val points = trimmed.codePointCount(0, trimmed.length)
    if (points <= max) return trimmed
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, max)).trimEnd()
}

/** A held task's card as the bridge sent it (`held_task`), on [host]. */
data class HeldTask(
    val host: String,
    /** The agent that started the work, `sandbox/agent`. */
    val agent: String,
    /** That agent's Home. */
    val conversation: String,
    /** The runtime's id for the task. */
    val task: String,
    val title: String,
    /** The agent doing the work, when it is not the one that started it. */
    val assignee: String? = null,
    val reason: String,
    val lastError: String? = null,
    /** Since when it is held, in seconds since the Unix epoch. */
    val since: Long,
    /** What resuming does for this runtime, one line; may be empty. */
    val resume: String,
    /** The state as the bridge wrote it, kept so a newer bridge's state survives the cache. */
    val stateWire: String,
    /** The bridge's revision of the card, advanced on every change; a resume names it. */
    val revision: Long,
    /** Whether the runtime permits Resume from the app; legacy cards default to true. */
    val resumable: Boolean = true,
    /** What the agent wrote for the person (enclos ADR 0073); null on a card no agent wrote. */
    val ask: HeldTaskAsk? = null,
    /** On an `answered` card, what the person answered, on one line. */
    val answer: String? = null,
) {
    val key: HeldTaskKey get() = HeldTaskKey(host, agent, task)
    val state: HeldTaskState get() = HeldTaskState.of(stateWire)

    /** A card shows in its agent's Home alone, never in a group or another conversation. */
    fun belongsTo(c: Conversation): Boolean =
        host == c.host && conversation == c.id && agent == c.agent && c.home && !c.group

    /** The card as the bridge writes it, for the cache. */
    fun json(): JSONObject = JSONObject()
        .put("agent", agent).put("conversation", conversation).put("task", task).put("title", title)
        .apply { assignee?.let { put("assignee", it) } }
        .put("reason", reason)
        .apply { lastError?.let { put("last_error", it) } }
        .put("resumable", resumable).put("since", since).put("resume", resume).put("state", stateWire).put("revision", revision)
        .apply { ask?.let { put("ask", it.json()) } }
        .apply { answer?.let { put("answer", it) } }

    companion object {
        /**
         * Reads a `held_task` object. A field missing or of the wrong type, an id that is not
         * one or a negative revision is refused (IllegalArgumentException); the texts are read
         * as the bridge cuts them (`HeldTask::normalized`), again here rather than trusted: hidden
         * characters dropped, each cut to its bound. A state this app does not know is kept and
         * read as one with no Resume.
         */
        fun from(host: String, json: JSONObject): HeldTask {
            fun string(key: String): String = (json.opt(key) as? String) ?: throw IllegalArgumentException("Invalid $key")
            fun optional(key: String): String? =
                if (!json.has(key) || json.isNull(key)) null else (json.get(key) as? String) ?: throw IllegalArgumentException("Invalid $key")
            fun integer(key: String): Long = when (val v = json.opt(key)) {
                is Int -> v.toLong()
                is Long -> v
                else -> throw IllegalArgumentException("Invalid $key")
            }
            val agent = string("agent")
            require(agentAddressValid(agent)) { "Invalid agent" }
            val conversation = string("conversation")
            require(heldIdValid(conversation)) { "Invalid conversation" }
            val task = string("task")
            require(heldIdValid(task) && !Visible.hasHidden(task)) { "Invalid task" }
            val state = string("state")
            require(heldIdValid(state)) { "Invalid state" }
            val revision = integer("revision")
            require(revision >= 0) { "Invalid revision" }
            val title = heldCut(Visible.stripped(string("title")), MAX_HELD_TITLE_CHARS)
            val reason = heldCut(Visible.stripped(string("reason"), lines = true), MAX_HELD_REASON_CHARS)
            return HeldTask(
                host = host,
                agent = agent,
                conversation = conversation,
                task = task,
                title = title.ifEmpty { task },
                assignee = optional("assignee")?.let { heldCut(Visible.stripped(it), MAX_HELD_ASSIGNEE_CHARS) }?.ifEmpty { null },
                reason = reason.ifEmpty { HELD_NO_REASON },
                lastError = optional("last_error")?.let { heldCut(Visible.stripped(it, lines = true), MAX_HELD_ERROR_CHARS) }?.ifEmpty { null },
                since = integer("since"),
                resume = heldCut(Visible.stripped(string("resume")), MAX_HELD_RESUME_CHARS),
                stateWire = state,
                revision = revision,
                resumable = if (!json.has("resumable")) true else
                    (json.opt("resumable") as? Boolean) ?: throw IllegalArgumentException("Invalid resumable"),
                ask = if (!json.has("ask") || json.isNull("ask")) null else
                    HeldTaskAsk.from(json.get("ask") as? JSONObject ?: throw IllegalArgumentException("Invalid ask")),
                answer = optional("answer")?.let { heldCut(heldOneLine(it), MAX_HELD_ANSWER_CHARS) }?.ifEmpty { null },
            )
        }

        /** `ids::check`: nonempty, at most 128 bytes, no control character, no lone surrogate. */
        private fun heldIdValid(value: String): Boolean = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 128 &&
            value.codePoints().noneMatch { Character.isISOControl(it) || it in 0xD800..0xDFFF }

        /** `sandbox/agent`: two parts, each an id. */
        private fun agentAddressValid(value: String): Boolean {
            val parts = value.split('/')
            return value.toByteArray(Charsets.UTF_8).size <= MAX_HELD_AGENT_BYTES && parts.size == 2 && parts.all(::heldIdValid)
        }
    }
}

/** A card kept on the phone, as the cache holds it. */
data class HeldTaskRow(val host: String, val agent: String, val task: String, val info: String)

/**
 * The cached cards to keep: valid, under the key they were stored with, and of a Home still
 * kept. The keys of the others, which the cache drops.
 */
fun keptHeldTasks(
    rows: List<HeldTaskRow>,
    conversations: List<Conversation>,
): Pair<List<HeldTask>, List<HeldTaskKey>> {
    val kept = mutableListOf<HeldTask>()
    val stale = mutableListOf<HeldTaskKey>()
    for (row in rows) {
        val card = runCatching { HeldTask.from(row.host, JSONObject(row.info)) }.getOrNull()
        if (card != null && card.key == HeldTaskKey(row.host, row.agent, row.task) && conversations.any(card::belongsTo)) kept += card
        else stale += HeldTaskKey(row.host, row.agent, row.task)
    }
    return kept to stale
}

/**
 * The card to keep of [current] and [incoming]: the higher bridge revision, the current one
 * on a tie, so a delayed broadcast, a sync or a cache load never brings back an older state.
 */
fun newestHeldTask(current: HeldTask?, incoming: HeldTask): HeldTask =
    if (current == null || current.key != incoming.key || incoming.revision > current.revision) incoming else current

/**
 * For each task, the newest message of [lines] that introduced its card (`notify`, from the
 * agent, linked by `held_task`): the card is drawn there, once, and an earlier introduction of
 * the same task (one held again) keeps its readable text.
 */
fun heldTaskIntroductions(lines: List<Line>): Map<String, Long> = buildMap {
    for (line in lines) {
        val task = line.heldTask ?: continue
        if (line.from == "agent" && line.kind == "notify") put(task, line.seq)
    }
}

/**
 * What a card says of its state. [here] when this phone holds the runtime's confirmation of its
 * own resume (kept in memory only: after a restart, or a lost answer, the card cannot tell which
 * device resumed it, so it says "from the app"). [gone] when the host answered a resume
 * `not_found` and no newer card came since.
 */
internal fun heldTaskStatus(card: HeldTask, here: Boolean = false, gone: Boolean = false): String = when {
    gone && card.state == HeldTaskState.Held -> "Not known on the host · the host (or its runtime) has no such held task"
    else -> heldStateLine(card, here)
}

private fun heldStateLine(card: HeldTask, here: Boolean): String = when (card.state) {
    HeldTaskState.Held -> "Held · waiting for you"
    HeldTaskState.Answered -> answeredLine(card)
    HeldTaskState.Resumed -> if (here) "Resumed from this phone" else "Resumed from the app"
    HeldTaskState.Released -> "No longer held · it runs again, finished or went away"
    HeldTaskState.Expired -> "No longer tracked · the agent's channel stopped reporting held tasks"
    HeldTaskState.Unknown -> "A state this app does not know (${Visible.stripped(card.stateWire)}) · update the app to act on it"
}

/**
 * The bridge's own `answer` texts (enclos-protocol's `ANSWERED_IN_CONVERSATION`, for an ask the
 * agent settled, and `ASKED_THE_AGENT`, for an `agent` answer): each reads as a whole line.
 */
internal val HELD_ANSWER_SENTENCES = setOf("Answered in the conversation.", "Asked the agent to look into it.")

/** What an `answered` card says: the person's answer, as the bridge kept it. */
internal fun answeredLine(card: HeldTask): String = when (val answer = card.answer) {
    null -> "Answered · waiting on the agent"
    in HELD_ANSWER_SENTENCES -> answer
    else -> "Answered: $answer"
}

/** A last error longer than this many characters, or lines, is folded until the person opens it. */
internal const val FOLDED_ERROR_CHARS = 280
internal const val FOLDED_ERROR_LINES = 4

internal fun foldsError(text: String): Boolean = text.length > FOLDED_ERROR_CHARS || text.lines().size > FOLDED_ERROR_LINES

/**
 * [items] without the rows of held tasks this phone knows are answered at the row's revision or
 * a newer one: an answered card waits on the agent, never under Needs you, even before the host
 * sends its next index.
 */
fun withoutAnswered(items: List<OpenItem>, cards: Map<HeldTaskKey, HeldTask>): List<OpenItem> = items.filterNot { item ->
    if (item.kind != "held_task" || item.agent == null) return@filterNot false
    val card = cards[HeldTaskKey(item.host, item.agent, item.id)] ?: return@filterNot false
    val indexed = item.version.removePrefix("held:").toLongOrNull() ?: return@filterNot false
    card.state == HeldTaskState.Answered && card.revision >= indexed
}

/**
 * A held task its runtime says waits on the team is not the person's: the team's
 * own schedule resolves it, so it never enters Needs you, while its card stays in
 * its conversation as today.
 */
fun withoutTeamWaits(items: List<OpenItem>): List<OpenItem> =
    items.filterNot { it.kind == "held_task" && it.waitsOnTeam }
