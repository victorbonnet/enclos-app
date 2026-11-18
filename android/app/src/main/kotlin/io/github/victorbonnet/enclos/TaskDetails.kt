package io.github.victorbonnet.enclos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** enclos-protocol's `task_detail` bounds, as the bridge cuts a task's detail. */
internal const val MAX_TASK_BODY_CHARS = 4_000
internal const val MAX_TASK_COMMENTS = 20
internal const val MAX_TASK_COMMENT_CHARS = 1_000
internal const val MAX_TASK_AUTHOR_CHARS = 64
internal const val MAX_TASK_RELATED = 20
internal const val MAX_TASK_HISTORY = 30
internal const val MAX_TASK_HISTORY_TEXT_CHARS = 300

/** How a related task stands to the one shown: it waits for a [Parent], a [Child] waits for it. */
enum class Relation(val wire: String) {
    Parent("parent"),
    Child("child");

    companion object {
        fun of(wire: String?): Relation? = entries.firstOrNull { it.wire == wire }
    }
}

/** A task the one shown waits for, or that waits for it, as its runtime names it. */
data class RelatedTask(
    val id: String,
    val title: String,
    val relation: Relation,
    /** Where it is; none when the runtime says nothing the app knows. */
    val state: TaskState?,
    /** The agent doing it, `sandbox/agent`. */
    val assignee: String?,
)

/** A comment on a task, by an agent or a person, as the runtime keeps it. */
data class TaskComment(
    /** Who wrote it, as the runtime names them. */
    val author: String,
    /** `sandbox/agent` when [author] is an agent the host serves: shown by its name and photo. */
    val agent: String?,
    /** In seconds since the Unix epoch. */
    val time: Long,
    /** Its lines kept. */
    val text: String,
)

/** What a history entry records; a kind the app does not know is left out. */
enum class HistoryKind(val wire: String) {
    Created("created"),
    Attempt("attempt"),
    Blocked("blocked"),
    Unblocked("unblocked"),
    Review("review"),
    Done("done");

    companion object {
        fun of(wire: String?): HistoryKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** How an attempt ended, in the person's words; [Ended] for an end a later version names. */
enum class Outcome(val wire: String?, val label: String) {
    Completed("completed", "completed"),
    Blocked("blocked", "blocked"),
    Crashed("crashed", "crashed"),
    TimedOut("timed_out", "timed out"),
    GaveUp("gave_up", "gave up"),
    Failed("failed", "failed"),
    Stopped("stopped", "stopped"),
    Ended(null, "ended");

    companion object {
        fun of(wire: String): Outcome = entries.firstOrNull { it.wire == wire } ?: Ended
    }
}

/** One thing that happened to a task. */
data class HistoryEntry(
    val kind: HistoryKind,
    /** In seconds since the Unix epoch. */
    val time: Long,
    /** For an attempt that ended: when. An attempt without it is still going. */
    val until: Long?,
    /** For an attempt that ended: how. */
    val outcome: Outcome?,
    /** The agent that did it, `sandbox/agent`. */
    val by: String?,
    /** One line about it: an attempt's summary or error, why it blocked. */
    val text: String?,
)

/**
 * What a task's runtime holds of it beyond what [AgentTask] says: its
 * body, the tasks it relates to, its newest comments (oldest first) of
 * [commentsTotal], and its history (oldest first), as a `task.detail`
 * carries it (enclos `docs/protocol.md`, Task details). Fetched when a task
 * sheet opens and kept in memory for that sheet only.
 */
data class TaskDetail(
    val body: String? = null,
    val related: List<RelatedTask> = emptyList(),
    val comments: List<TaskComment> = emptyList(),
    val commentsTotal: Int = 0,
    val history: List<HistoryEntry> = emptyList(),
) {
    /** How many comments the task has before those carried. */
    val earlier: Int get() = (commentsTotal - comments.size).coerceAtLeast(0)

    companion object {
        /**
         * The detail a `task.detail`'s `detail` holds, read leniently: a part
         * missing is empty, an entry the app cannot read (a field missing or
         * of the wrong type, a kind or relation it does not know, a blank
         * text) is left out. Every text comes from the sandbox and is cleaned
         * again here, to the bridge's bounds: hidden characters dropped, the
         * body and comments with their lines kept ([paragraphs]), the rest on
         * one line ([oneLine]).
         */
        fun from(detail: JSONObject?): TaskDetail {
            if (detail == null) return TaskDetail()
            val related = objects(detail.optJSONArray("related")).mapNotNull { r ->
                val id = protocolId(r.opt("id")) ?: return@mapNotNull null
                val title = (r.opt("title") as? String)?.let { oneLine(it, AgentTask.MAX_TITLE) }?.ifEmpty { null } ?: return@mapNotNull null
                val relation = Relation.of(r.opt("relation") as? String) ?: return@mapNotNull null
                RelatedTask(id, title, relation, (r.opt("state") as? String)?.let(TaskState::of), AgentTask.agentName(r.opt("assignee")))
            }.take(MAX_TASK_RELATED)
            val comments = objects(detail.optJSONArray("comments")).mapNotNull { c ->
                val author = (c.opt("author") as? String)?.let { oneLine(it, MAX_TASK_AUTHOR_CHARS) }?.ifEmpty { null } ?: return@mapNotNull null
                val text = (c.opt("text") as? String)?.let { paragraphs(it, MAX_TASK_COMMENT_CHARS) }?.ifEmpty { null } ?: return@mapNotNull null
                val time = seconds(c.opt("time")) ?: return@mapNotNull null
                TaskComment(author, AgentTask.agentName(c.opt("agent")), time, text)
            }.takeLast(MAX_TASK_COMMENTS)
            val history = objects(detail.optJSONArray("history")).mapNotNull { e ->
                val kind = HistoryKind.of(e.opt("kind") as? String) ?: return@mapNotNull null
                val time = seconds(e.opt("time")) ?: return@mapNotNull null
                HistoryEntry(
                    kind = kind,
                    time = time,
                    until = seconds(e.opt("until"))?.coerceAtLeast(time),
                    outcome = (e.opt("outcome") as? String)?.let(Outcome::of),
                    by = AgentTask.agentName(e.opt("by")),
                    text = (e.opt("text") as? String)?.let { oneLine(it, MAX_TASK_HISTORY_TEXT_CHARS) }?.ifEmpty { null },
                )
            }.takeLast(MAX_TASK_HISTORY)
            return TaskDetail(
                body = (detail.opt("body") as? String)?.let { paragraphs(it, MAX_TASK_BODY_CHARS) }?.ifEmpty { null },
                related = related,
                comments = comments,
                // At least the comments it carries, whatever it claims.
                commentsTotal = maxOf(AgentTask.count(detail.opt("comments_total")) ?: 0, comments.size),
                history = history,
            )
        }

        private fun objects(list: JSONArray?): List<JSONObject> =
            (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }

        private fun seconds(value: Any?): Long? = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }?.coerceAtLeast(0)
    }
}

/**
 * [text] without hidden characters, its lines kept: line and paragraph
 * separators made newlines, tabs spaces, the ends of lines trimmed, blank
 * lines in a row made one, and cut to [max] characters. What a person reads
 * of a longer text from the sandbox: a task's body, a comment
 * (enclos-protocol's `task_detail::paragraphs`).
 */
fun paragraphs(text: String, max: Int): String {
    val unified = text.replace("\r\n", "\n").replace(Regex("[\\r\\u2028\\u2029\\u0085\\u000B\\u000C]"), "\n").replace('\t', ' ')
    val lines = Visible.stripped(unified, lines = true).split('\n').map { it.trimEnd() }
    val out = StringBuilder()
    var blank = false
    for (line in lines) {
        if (line.isBlank()) {
            blank = true
            continue
        }
        if (out.isNotEmpty()) out.append(if (blank) "\n\n" else "\n")
        blank = false
        out.append(line)
    }
    val points = out.codePoints().toArray()
    return if (points.size <= max) out.toString() else String(points, 0, max).trimEnd()
}

/**
 * Whether a task sheet showing [after] asks for its detail again: the task
 * is new to the phone, or its state or its number of comments changed.
 */
fun asksAgain(before: AgentTask?, after: AgentTask): Boolean =
    before == null || before.state != after.state || before.comments != after.comments

/**
 * A history entry in the person's words, [name] giving an agent's shown
 * name from its `sandbox/agent`: `Created by Lead`, `Attempt by Engineer ·
 * crashed after 1 min`, `Attempt by Engineer · going for 3 min` (at [now]),
 * `Blocked`, `Unblocked`, `Review asked for`, `Done`.
 */
fun historyLine(entry: HistoryEntry, name: (String) -> String, now: Long): String {
    val by = entry.by?.let { " by ${name(it)}" }.orEmpty()
    return when (entry.kind) {
        HistoryKind.Created -> "Created$by"
        HistoryKind.Attempt -> when (val until = entry.until) {
            null -> "Attempt$by · going for ${taskDuration(now - entry.time)}"
            else -> "Attempt$by · ${(entry.outcome ?: Outcome.Ended).label} after ${taskDuration(until - entry.time)}"
        }
        HistoryKind.Blocked -> "Blocked$by"
        HistoryKind.Unblocked -> "Unblocked$by"
        HistoryKind.Review -> "Review asked for$by"
        HistoryKind.Done -> "Done$by"
    }
}

/**
 * The task sheet's detail: which task's sheet is [open], and its detail
 * as the assignee's runtime holds it, asked for with `task.get` and
 * answered by `task.detail` (enclos `docs/protocol.md`, Task details). Only
 * the open sheet's is kept, in memory: nothing is stored. It is asked for
 * when the sheet opens with the host connected and the agent offering
 * `task_details` ([available]), again when the task's state or comment count
 * changes, and when the person retries; never queued or resent on its own.
 */
class TaskDetails(
    private val send: (String, JSONObject) -> Boolean,
    /** The task kept under a key, if any. */
    private val task: (TaskKey) -> AgentTask?,
    /** Whether [task]'s detail can be asked for now: its host connected, its agent offering `task_details`. */
    private val available: (AgentTask) -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
    private val heldAvailable: (HeldTask) -> Boolean = { false },
) {
    private data class Request(val id: String, val host: String, val agent: String, val task: String)

    /** The task whose sheet shows, if any. */
    var open by mutableStateOf<TaskKey?>(null)
        private set

    var held by mutableStateOf<HeldTask?>(null)
        private set

    /** The open task's detail, once one came. */
    var detail by mutableStateOf<TaskDetail?>(null)
        private set

    /** Whether a request for it waits for its answer. */
    var loading by mutableStateOf(false)
        private set

    /** Why the last request got no detail, as the host or the app says it. */
    var error by mutableStateOf<String?>(null)
        private set

    private var pending: Request? = null

    /** The requests sent for the open sheet: a late answer to one still shows. */
    private val sent = mutableMapOf<String, Request>()

    /** The task changed while a request waited: asked again once it is answered. */
    private var again = false

    /** Opens the sheet of [key], in place of any other. */
    fun show(key: TaskKey) {
        if (open == key && held == null) return
        reset()
        held = null
        open = key
    }

    /** Uses the creator and exact opaque board reference; never a guessed task-list id. */
    fun showHeld(card: HeldTask) {
        reset()
        held = card
        open = TaskKey(card.host, card.agent.substringBefore('/'), card.task)
    }

    /** Closes the sheet: its detail goes. */
    fun close() {
        reset()
        open = null
        held = null
    }

    private fun reset() {
        detail = null
        error = null
        loading = false
        pending = null
        sent.clear()
        again = false
    }

    /** Asks the host for the open task's detail; returns whether it was sent. */
    fun fetch(): Boolean {
        val key = open ?: return false
        if (pending != null) return false
        val card = held
        val shown = if (card == null) task(key) ?: return false else null
        if (card != null && !heldAvailable(card) || shown != null && !available(shown)) return false
        val agent = card?.agent ?: shown!!.assignee
        val command = message(if (card != null) "held_task.get" else "task.get", "agent" to agent, "task" to key.id)
        val request = Request(command.getString("id"), key.host, agent, key.id)
        pending = request
        sent[request.id] = request
        loading = true
        error = null
        later(TIMEOUT_MS) {
            if (pending == request) {
                finish()
                error = "The host did not reply. Try again."
                askAgainIfChanged()
            }
        }
        if (!send(key.host, command)) {
            finish()
            sent.remove(request.id)
            error = "The request was not sent. Reconnect and try again."
            return false
        }
        return true
    }

    private fun finish() {
        pending = null
        loading = false
    }

    /**
     * Once a request is answered, however it is: when the task changed
     * while it waited, the newer task's detail is asked for. A request
     * that failed with no such change waits for the person's Retry.
     */
    private fun askAgainIfChanged() {
        if (again && pending == null) {
            again = false
            fetch()
        }
    }

    /** A task came from a bridge, [before] being what the phone kept of it: an open sheet on it asks again when it changed ([asksAgain]). */
    fun updated(before: AgentTask?, after: AgentTask) {
        if (held != null || after.key != open || !asksAgain(before, after)) return
        if (pending != null) again = true else fetch()
    }

    /**
     * A `task.detail` or an `error` from [host]: true when it answers a
     * request of this sheet (or a `task.detail` no sheet waits for any
     * more, which is dropped).
     */
    fun received(host: String, m: JSONObject): Boolean {
        val type = m.optString("type")
        if (type != "task.detail" && type != "error") return false
        val request = sent[m.optString("of")]?.takeIf { it.host == host } ?: return type == "task.detail"
        if (type == "error") {
            if (pending != request) return true
            finish()
            error = oneLine(m.optString("message"), MAX_ERROR_CHARS).ifEmpty { "The host could not get this task's details." }
            askAgainIfChanged()
            return true
        }
        if (m.optString("agent") != request.agent || m.optString("task") != request.task) return true
        if (pending == request) finish()
        detail = TaskDetail.from(m.optJSONObject("detail"))
        error = null
        askAgainIfChanged()
        return true
    }

    /** [host]'s connection ended or was replaced: what waits for it gets no answer, and the detail it gave goes until it is asked again. */
    fun ended(host: String) {
        if (pending?.host == host) {
            finish()
            again = false
        }
        sent.values.removeAll { it.host == host }
        if (open?.host == host) {
            detail = null
            error = null
        }
    }

    /** [host] was forgotten: its sheet closes. */
    fun forget(host: String) {
        ended(host)
        if (open?.host == host) close()
    }

    companion object {
        /** How long a request waits for its answer. */
        const val TIMEOUT_MS = 30_000L

        private const val MAX_ERROR_CHARS = 300
    }
}
