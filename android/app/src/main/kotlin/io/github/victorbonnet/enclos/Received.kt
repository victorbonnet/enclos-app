package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject

// What a bridge sends, read into the app's own types: apart from AppModel,
// so it can be tested on the JVM.

/** A `conversation` from [host]'s bridge: one of the person's, with its name, and a group's members (`sandbox/agent` each). */
fun conversationOf(host: String, m: JSONObject): Conversation = Conversation(
    host,
    m.getString("agent"),
    m.getString("conversation"),
    shownTitle(m.optString("title")),
    m.optString("titled_by").ifBlank { null },
    home = m.optBoolean("home"),
    members = strings(m.optJSONArray("members")),
    archived = m.optBoolean("archived", false),
    revision = m.optLong("revision", 0),
)

/**
 * A `message` from [host]'s bridge, as a line of its conversation. In a
 * [group], an agent's message says which member spoke (`host/sandbox/agent`,
 * from the message's `agent`); the person's own, and any outside a group,
 * have none.
 */
fun lineOf(host: String, m: JSONObject, group: Boolean): Line {
    val line = lineIn(m)
    return if (group && line.from == "agent") line.copy(agent = "$host/${m.getString("agent")}") else line
}

/** A `group.state` from [host]'s bridge: where the group's discussion is, and each member's turn (`host/sandbox/agent`). */
fun groupStatusOf(host: String, m: JSONObject): GroupStatus {
    val list = m.optJSONArray("members")
    return GroupStatus(
        state = m.optString("state"),
        round = m.optInt("round"),
        maxRounds = m.optInt("max_rounds"),
        turns = (0 until (list?.length() ?: 0)).map { i ->
            val t = list!!.getJSONObject(i)
            "$host/${t.getString("agent")}" to t.optString("turn").ifBlank { null }
        },
        reason = m.optString("reason").ifBlank { null },
    )
}

private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
