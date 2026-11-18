package io.github.victorbonnet.enclos

import org.json.JSONException
import org.json.JSONObject

/**
 * One thing an agent is doing, as its runtime reports it (enclos
 * `docs/protocol.md`, Tasks and agent activity): answering in a
 * conversation, running one of its routines, or working on a task.
 */
sealed interface Doing {
    data class Answering(val conversation: String) : Doing
    data class Routine(val routine: String) : Doing
    data class Task(val task: String) : Doing
}

/**
 * What an agent is doing, whole: [doing] empty when it is idle; [since]
 * when that list began, in seconds since the Unix epoch.
 */
data class AgentActivity(val doing: List<Doing>, val since: Long) {
    val idle: Boolean get() = doing.isEmpty()

    /**
     * What the agent is doing, in the person's words: `working on a task`
     * when an entry is a task, else `answering`, else `running a routine`;
     * none when it is idle.
     */
    val label: String?
        get() = when {
            doing.any { it is Doing.Task } -> "working on a task"
            doing.any { it is Doing.Answering } -> "answering"
            doing.any { it is Doing.Routine } -> "running a routine"
            else -> null
        }
}

/**
 * The agent (`sandbox/agent`) and activity an `agent.activity` holds;
 * throws [JSONException] when `agent` is missing. An entry of a kind the
 * app does not know, or without its id, is left out, so a later bridge can
 * add one.
 */
fun activityIn(m: JSONObject): Pair<String, AgentActivity> {
    val agent = protocolId(m.getString("agent")) ?: throw JSONException("agent.activity names no agent")
    val list = m.optJSONArray("doing")
    val doing = (0 until (list?.length() ?: 0)).mapNotNull { i ->
        val d = list!!.optJSONObject(i) ?: return@mapNotNull null
        when (d.optString("kind")) {
            "answering" -> protocolId(d.opt("conversation"))?.let(Doing::Answering)
            "routine" -> protocolId(d.opt("routine"))?.let(Doing::Routine)
            "task" -> protocolId(d.opt("task"))?.let(Doing::Task)
            else -> null
        }
    }.distinct()
    return agent to AgentActivity(doing, if (doing.isEmpty()) 0 else m.optLong("since").coerceAtLeast(0))
}

/**
 * What each agent of each host is doing now, by address
 * (`host/sandbox/agent`): live state from the bridge, never kept on the
 * phone. An agent missing is idle. [into] is where it is held (the app's
 * observable map, a plain one in tests).
 */
class Activities(private val into: MutableMap<String, AgentActivity> = mutableMapOf()) {
    /** What [host]'s bridge said of one of its agents: an idle one leaves the map. */
    fun received(host: String, m: JSONObject) {
        val (agent, activity) = activityIn(m)
        val address = "$host/$agent"
        if (activity.idle) into.remove(address) else into[address] = activity
    }

    /**
     * [host]'s bridge listed its agents again: what was held is stale, and
     * the bridge sends the activity of each agent not idle right after.
     */
    fun listed(host: String) = ended(host)

    /** [host]'s connection ended, or the host was forgotten: nothing it said holds any more. */
    fun ended(host: String) {
        into.keys.filter { it.startsWith("$host/") }.forEach { into.remove(it) }
    }

    fun clear() = into.clear()

    /** What the agent at [address] (`host/sandbox/agent`) is doing; none while idle. */
    fun of(address: String): AgentActivity? = into[address]?.takeUnless { it.idle }

    /** Whether an agent of [host] is answering in [conversation]. */
    fun answering(host: String, conversation: String): Boolean =
        into.any { (address, a) -> address.startsWith("$host/") && a.doing.any { it is Doing.Answering && it.conversation == conversation } }
}
