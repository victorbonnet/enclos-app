package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONException
import org.json.JSONObject

/** The host feature of forgetting a removed agent, in `authenticated`'s capabilities. */
const val AGENT_FORGET = "agent.forget"

/** An agent on a host: [name] is `sandbox/agent`, as its bridge names it. */
data class AgentKey(val host: String, val name: String) {
    val address: String get() = "$host/$name"

    companion object {
        /** From `host/sandbox/agent`, the app's address of an agent. */
        fun of(address: String): AgentKey {
            val name = address.substringAfterLast('/')
            val rest = address.substringBeforeLast('/')
            return AgentKey(rest.substringBeforeLast('/'), "${rest.substringAfterLast('/')}/$name")
        }
    }
}

/**
 * Forgetting a removed agent (enclos ADR 0067, proposed): the host's person
 * deletes its Home and their direct conversations with it at once. Offered
 * only for an agent the host's bridge lists in `agents.removed.list`, and
 * only by a bridge that announces [AGENT_FORGET]. The conversations go as
 * their `conversation.deleted` records come ([ConversationDeletion]); the
 * `ack` that follows them settles the request.
 */
class AgentForgetting(
    private val connected: (String) -> Boolean,
    private val supported: (String) -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
) {
    /** By host: the agents its bridge confirmed removed, as it last listed them. */
    val removed = mutableStateMapOf<String, Set<String>>()
    /** By agent: the id of its `agent.forget` until the host answers. */
    val pending = mutableStateMapOf<AgentKey, String>()
    val errors = mutableStateMapOf<AgentKey, String>()
    /** Agents forgotten from this phone since it connected, so their page can close. */
    val forgotten = mutableStateMapOf<AgentKey, Boolean>()

    fun isRemoved(key: AgentKey) = key.name in removed[key.host].orEmpty()

    /** Asks [host] which agents it confirmed removed, when its bridge can say. */
    fun refresh(host: String) {
        if (connected(host) && supported(host)) send(host, message("agents.removed"))
    }

    /** A new session: what an earlier one listed may be stale. */
    fun authenticated(host: String) {
        removed.remove(host)
        refresh(host)
    }

    /** Why Forget cannot be used now, or `null`. */
    fun unavailable(key: AgentKey): String? = when {
        key in pending -> "Forgetting agent…"
        !connected(key.host) -> "Connect to ${key.host} to forget this agent."
        !supported(key.host) -> "Update this host's enclos bridge to forget removed agents."
        !isRemoved(key) -> "Its host does not confirm this agent was removed."
        else -> null
    }

    fun forget(key: AgentKey): Boolean {
        if (unavailable(key) != null) return false
        val command = message("agent.forget", "agent" to key.name)
        val id = command.getString("id")
        errors.remove(key)
        pending[key] = id
        if (!send(key.host, command)) {
            pending.remove(key)
            errors[key] = "Forget was not sent. Reconnect and try again."
            return false
        }
        later(60_000) {
            if (pending[key] == id) {
                pending.remove(key)
                errors[key] = "The host has not confirmed. Reconnect and check, or retry Forget."
            }
        }
        return true
    }

    fun received(host: String, message: JSONObject): Boolean {
        when (message.optString("type")) {
            "agents.removed.list" -> {
                val list = message.getJSONArray("agents")
                removed[host] = (0 until list.length()).map { i ->
                    list.getString(i).also { name ->
                        val parts = name.split('/')
                        if (parts.size != 2 || parts.any { it.isEmpty() } || name.toByteArray(Charsets.UTF_8).size > 128 || name.any { it.isISOControl() }) {
                            throw JSONException("Invalid removed agent")
                        }
                    }
                }.toSet()
                return true
            }
            "ack" -> {
                val key = pendingFor(host, message) ?: return false
                pending.remove(key)
                errors.remove(key)
                removed[host] = removed[host].orEmpty() - key.name
                forgotten[key] = true
                return true
            }
            "error" -> {
                val key = pendingFor(host, message) ?: return false
                pending.remove(key)
                errors[key] = if (message.optString("code") == "unknown_type") "Update this host's enclos bridge to forget removed agents."
                    else message.optString("message").ifBlank { "Forget failed. Retry Forget." }
                return true
            }
        }
        return false
    }

    private fun pendingFor(host: String, message: JSONObject): AgentKey? {
        val of = message.optString("of").ifEmpty { return null }
        return pending.entries.firstOrNull { it.key.host == host && it.value == of }?.key
    }

    fun ended(host: String) {
        pending.keys.filter { it.host == host }.forEach {
            pending.remove(it)
            errors[it] = "The connection ended before the host confirmed. Reconnect and check, or retry Forget."
        }
    }

    /** Forgets what [host] said, or every host's with `null`. */
    fun clear(host: String? = null) {
        val mine = { h: String -> host == null || h == host }
        removed.keys.filter(mine).forEach { removed.remove(it) }
        pending.keys.filter { mine(it.host) }.forEach { pending.remove(it) }
        errors.keys.filter { mine(it.host) }.forEach { errors.remove(it) }
        forgotten.keys.filter { mine(it.host) }.forEach { forgotten.remove(it) }
    }
}
