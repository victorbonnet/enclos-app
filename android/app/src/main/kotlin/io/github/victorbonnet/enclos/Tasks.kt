package io.github.victorbonnet.enclos

import org.json.JSONException
import org.json.JSONObject

/** Where a task is (enclos `docs/protocol.md`, Tasks): the last three have ended. */
enum class TaskState(val wire: String) {
    Queued("queued"),
    Running("running"),
    Blocked("blocked"),
    Done("done"),
    Failed("failed"),
    Cancelled("cancelled");

    val ended: Boolean get() = this == Done || this == Failed || this == Cancelled

    companion object {
        fun of(wire: String): TaskState? = entries.firstOrNull { it.wire == wire }
    }
}

/** A task's key on this phone: its host, its assignee's sandbox and the runtime's id, unique in that sandbox. */
data class TaskKey(val host: String, val sandbox: String, val id: String)

/**
 * A task, as a host's bridge reports it to the host's person: work an
 * agent does outside a conversation's answer, which its runtime tracks
 * until it ends. Every text comes from the sandbox: shown as plain text,
 * on one line, never as markdown or links.
 */
data class AgentTask(
    val host: String,
    val id: String,
    val title: String,
    /** The agent doing it, `sandbox/agent`. */
    val assignee: String,
    /** The agent that asked for it, as the runtime claims it, `sandbox/agent`. */
    val askedBy: String?,
    /** Where it was asked for, when the runtime knows. */
    val conversation: String?,
    val state: TaskState,
    /** When it entered its current state, in seconds since the Unix epoch. */
    val since: Long,
    /** A short result, once it has ended. */
    val summary: String?,
    /**
     * Where it sits in its conversation, in seconds since the Unix epoch:
     * the `since` it first came with, kept as its state changes, so the
     * card stays where it appeared.
     */
    val placed: Long = since,
    /**
     * How many comments it has, from a runtime that keeps comments; none
     * when it says nothing. A change has an open task sheet ask for the
     * task's detail again ([TaskDetails.updated]).
     */
    val comments: Int? = null,
) {
    val sandbox: String get() = assignee.substringBefore('/')
    val key: TaskKey get() = TaskKey(host, sandbox, id)
    /** Its assignee's address, `host/sandbox/agent`. */
    val assigneeAddress: String get() = "$host/$assignee"

    /** As the phone keeps it. */
    fun json(): JSONObject = JSONObject()
        .put("host", host).put("id", id).put("title", title).put("assignee", assignee)
        .put("asked_by", askedBy).put("conversation", conversation).put("state", state.wire)
        .put("since", since).put("summary", summary).put("placed", placed).put("comments", comments)

    companion object {
        /** The most characters of a title shown, as the bridge cuts it. */
        const val MAX_TITLE = 120

        /** The most characters of a summary shown, as the bridge cuts it. */
        const val MAX_SUMMARY = 500

        /**
         * The task a `task` message from [host] holds; throws [JSONException]
         * when a field is missing or wrong (an id, an assignee that is not
         * `sandbox/agent`, a state the app does not know, a blank title).
         * Its text is cleaned again here: one line, no hidden character, cut
         * to the bridge's bounds.
         */
        fun from(host: String, m: JSONObject): AgentTask = read(host, m.getJSONObject("task"), null)

        /** A task the phone kept, or none when it can no longer be read. */
        fun kept(json: String): AgentTask? = try {
            val t = JSONObject(json)
            read(t.getString("host"), t, t.optLong("placed").takeIf { t.has("placed") })
        } catch (e: JSONException) {
            null
        }

        private fun read(host: String, t: JSONObject, placed: Long?): AgentTask {
            val id = protocolId(t.opt("id")) ?: throw JSONException("a task has no valid id")
            val assignee = agentName(t.opt("assignee")) ?: throw JSONException("a task's assignee is not sandbox/agent")
            val state = TaskState.of(t.getString("state")) ?: throw JSONException("a task's state is not one the app knows")
            val title = oneLine(t.getString("title"), MAX_TITLE).ifEmpty { throw JSONException("a task's title is blank") }
            val since = t.getLong("since").coerceAtLeast(0)
            return AgentTask(
                host = host,
                id = id,
                title = title,
                assignee = assignee,
                askedBy = agentName(t.opt("asked_by")),
                conversation = protocolId(t.opt("conversation")),
                state = state,
                since = since,
                summary = (t.opt("summary") as? String)?.let { oneLine(it, MAX_SUMMARY) }?.ifEmpty { null },
                placed = placed ?: since,
                comments = count(t.opt("comments")),
            )
        }

        /** `sandbox/agent`, each part an id; none otherwise. */
        internal fun agentName(value: Any?): String? = protocolId(value)?.takeIf {
            val parts = it.split('/')
            parts.size == 2 && parts.all(String::isNotBlank)
        }

        /** A count as JSON gives one: a whole number, at least 0; none otherwise. */
        internal fun count(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else -> null
        }?.takeIf { it >= 0 }
    }
}

/**
 * [text] on one line, without hidden characters, its spaces collapsed,
 * trimmed and cut to [max] characters: what a person reads of a field from
 * the sandbox (enclos-protocol's `task::one_line`).
 */
fun oneLine(text: String, max: Int): String {
    val flat = text.replace(Regex("[\\r\\n\\t\\u000B\\u000C\\u0085\\u2028\\u2029]"), " ")
    val words = Visible.stripped(flat).split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    val points = words.codePoints().toArray()
    return if (points.size <= max) words else String(points, 0, max).trimEnd()
}

/**
 * The tasks of every host this phone keeps, by [TaskKey]; [into] is where
 * they are held (the app's observable map, a plain one in tests). Each
 * change returns what to write to, or delete from, the phone's copy.
 */
class TaskCache(private val into: MutableMap<TaskKey, AgentTask> = mutableMapOf()) {
    /** By host: the tasks a sync under way has sent so far. */
    private val syncing = mutableMapOf<String, MutableSet<TaskKey>>()

    /** Every task kept. */
    val all: Collection<AgentTask> get() = into.values

    /** The task kept under [key], if any. */
    operator fun get(key: TaskKey): AgentTask? = into[key]

    /** Tasks the phone kept, at start, before any connection. */
    fun load(kept: Collection<AgentTask>) = kept.forEach { into[it.key] = it }

    /** A task the bridge sent, new or changed: it keeps the place it first had. Returns what is kept. */
    fun received(task: AgentTask): AgentTask {
        val before = into[task.key]
        val kept = if (before == null) task else task.copy(placed = minOf(before.placed, task.placed))
        into[kept.key] = kept
        syncing[task.host]?.add(kept.key)
        return kept
    }

    /** The bridge no longer keeps task [id] of [agent] (`sandbox/agent`) on [host]. Returns its key when one was kept. */
    fun removed(host: String, agent: String, id: String): TaskKey? {
        val key = TaskKey(host, agent.substringBefore('/'), id)
        return into.remove(key)?.key
    }

    /** A sync of [host] starts: what it does not send is dropped once it ends. */
    fun syncing(host: String) {
        syncing[host] = mutableSetOf()
    }

    /**
     * [host]'s sync ended: its tasks the sync did not send are dropped, and
     * returned. Nothing is dropped when no sync was under way (a sync asked
     * again over the same connection, which this phone did not follow).
     */
    fun synced(host: String): List<TaskKey> {
        val seen = syncing.remove(host) ?: return emptyList()
        val dropped = into.keys.filter { it.host == host && it !in seen }
        dropped.forEach { into.remove(it) }
        return dropped
    }

    /** [host]'s connection ended: a sync it started will not end, and drops nothing. */
    fun ended(host: String) {
        syncing.remove(host)
    }

    /** [host] was forgotten: its tasks go, and are returned. */
    fun forget(host: String): List<TaskKey> {
        syncing.remove(host)
        val gone = into.keys.filter { it.host == host }
        gone.forEach { into.remove(it) }
        return gone
    }

    fun clear() {
        syncing.clear()
        into.clear()
    }

    /** The tasks of the agent at [address] (`host/sandbox/agent`): [currentFirst]. */
    fun of(address: String): List<AgentTask> = currentFirst(into.values.filter { it.assigneeAddress == address })

    /** The tasks asked for in [conversation] of [host], in the order they were placed. */
    fun askedIn(host: String, conversation: String): List<AgentTask> =
        into.values.filter { it.host == host && it.conversation == conversation }.sortedWith(compareBy({ it.placed }, { it.id }))
}

/**
 * [tasks] as an agent's page lists them: those still going (queued,
 * running, blocked) first, then those that ended, each the most recently
 * changed first.
 */
fun currentFirst(tasks: Collection<AgentTask>): List<AgentTask> =
    tasks.sortedWith(compareBy<AgentTask> { it.state.ended }.thenByDescending { it.since }.thenBy { it.id })

/**
 * How long a task has been in its state, [seconds] long: `<1 min`,
 * `12 min`, `3 h`, `3 h 5 min`, `2 d`.
 */
fun taskDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val minutes = s / 60
    val hours = minutes / 60
    return when {
        minutes < 1 -> "<1 min"
        hours < 1 -> "$minutes min"
        hours < 24 -> if (minutes % 60 == 0L) "$hours h" else "$hours h ${minutes % 60} min"
        else -> "${hours / 24} d"
    }
}

/** Where [task] is at [now]: `running 12 min` while it goes, `done`, `failed` or `cancelled` once it ended. */
fun taskStatus(task: AgentTask, now: Long): String =
    if (task.state.ended) task.state.wire else "${task.state.wire} ${taskDuration(now - task.since)}"

/** A task card's line: its assignee as shown ([assignee]), its title and where it is (`Engineer · Fix the login page · running 12 min`). */
fun taskLine(assignee: String, task: AgentTask, now: Long): String = "$assignee · ${task.title} · ${taskStatus(task, now)}"

/**
 * Where the task cards of a conversation go among its messages: by the
 * index of the message each follows (the last one at or before the card's
 * [AgentTask.placed]), -1 for those before the first. [times] are the
 * messages' times, in their order; [cards] in the order they were placed.
 */
fun placedAfter(times: List<Long>, cards: List<AgentTask>): Map<Int, List<AgentTask>> =
    cards.groupBy { card -> times.indexOfLast { it <= card.placed } }
