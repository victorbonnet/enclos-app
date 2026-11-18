package io.github.victorbonnet.enclos

import org.json.JSONObject

/*
 * Held tasks (enclos ADR 0066, `docs/protocol.md`, Held tasks; docs/spec.md, Held tasks):
 * background work an agent's runtime started that has stopped and waits for a person
 * before it runs again. The runtime keeps the task; the bridge keeps a card for it in the
 * Home of the agent that started it and sends it to the host's person alone. The app
 * shows the card and offers Resume when the runtime permits it.
 */

/** enclos-protocol's `held_task` bounds, in characters, as the bridge cuts a card. */
internal const val MAX_HELD_TITLE_CHARS = 200
internal const val MAX_HELD_REASON_CHARS = 1_000
internal const val MAX_HELD_ERROR_CHARS = 2_000
internal const val MAX_HELD_RESUME_CHARS = 200
internal const val MAX_HELD_ASSIGNEE_CHARS = 64
private const val MAX_HELD_AGENT_BYTES = 512

/** What a card says when the runtime gave no reason (enclos-protocol's `NO_REASON`). */
internal const val HELD_NO_REASON = "Waiting for a person."

/**
 * A card's identity: the bridge keeps one per task of each agent, and a task id is the
 * runtime's, so it is scoped by the host and the agent that started the work.
 */
data class HeldTaskKey(val host: String, val agent: String, val task: String)

/** Where a card is. `held` is the only state with a Resume; one this app does not know has none. */
enum class HeldTaskState(val wire: String) {
    Held("held"), Resumed("resumed"), Released("released"), Expired("expired"), Unknown("");

    companion object {
        fun of(wire: String): HeldTaskState = entries.firstOrNull { it != Unknown && it.wire == wire } ?: Unknown
    }
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
            val title = cut(Visible.stripped(string("title")), MAX_HELD_TITLE_CHARS)
            val reason = cut(Visible.stripped(string("reason"), lines = true), MAX_HELD_REASON_CHARS)
            return HeldTask(
                host = host,
                agent = agent,
                conversation = conversation,
                task = task,
                title = title.ifEmpty { task },
                assignee = optional("assignee")?.let { cut(Visible.stripped(it), MAX_HELD_ASSIGNEE_CHARS) }?.ifEmpty { null },
                reason = reason.ifEmpty { HELD_NO_REASON },
                lastError = optional("last_error")?.let { cut(Visible.stripped(it, lines = true), MAX_HELD_ERROR_CHARS) }?.ifEmpty { null },
                since = integer("since"),
                resume = cut(Visible.stripped(string("resume")), MAX_HELD_RESUME_CHARS),
                stateWire = state,
                revision = revision,
                resumable = if (!json.has("resumable")) true else
                    (json.opt("resumable") as? Boolean) ?: throw IllegalArgumentException("Invalid resumable"),
            )
        }

        /** [text] trimmed and cut to [max] characters (code points), as `held_task::cut` does. */
        private fun cut(text: String, max: Int): String {
            val trimmed = text.trim()
            val points = trimmed.codePointCount(0, trimmed.length)
            if (points <= max) return trimmed
            return trimmed.substring(0, trimmed.offsetByCodePoints(0, max)).trimEnd()
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
    HeldTaskState.Resumed -> if (here) "Resumed from this phone" else "Resumed from the app"
    HeldTaskState.Released -> "No longer held · it runs again, finished or went away"
    HeldTaskState.Expired -> "No longer tracked · the agent's channel stopped reporting held tasks"
    HeldTaskState.Unknown -> "A state this app does not know (${Visible.stripped(card.stateWire)}) · update the app to act on it"
}

/** A last error longer than this many characters, or lines, is folded until the person opens it. */
internal const val FOLDED_ERROR_CHARS = 280
internal const val FOLDED_ERROR_LINES = 4

internal fun foldsError(text: String): Boolean = text.length > FOLDED_ERROR_CHARS || text.lines().size > FOLDED_ERROR_LINES
