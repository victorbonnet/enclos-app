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
    /**
     * Agents whose forget the host confirmed: their open page closes and
     * takes the flag ([closed]), and a new session drops it, so an agent
     * made again under the name has a page.
     */
    val forgotten = mutableStateMapOf<AgentKey, Boolean>()
    /** Requests the phone stopped waiting for: an answer that still comes settles them. */
    private val late = mutableMapOf<String, AgentKey>()
    /** By host: the id of its `agents.removed` until its list or a refusal comes. */
    val listing = mutableStateMapOf<String, String>()
    /** By host: why its list could not be had. */
    val listErrors = mutableStateMapOf<String, String>()

    fun isRemoved(key: AgentKey) = key.name in removed[key.host].orEmpty()

    /** An inventory can bring a forgotten name back without a new connection. */
    fun listed(host: String, agents: Set<String>) {
        agents.forEach { forgotten.remove(AgentKey(host, it)) }
        removed[host]?.let { removed[host] = it - agents }
        // Inventory absence alone is not proof of removal. Ask the bridge to check.
        refresh(host)
    }

    /** Asks [host] which agents it confirmed removed, when its bridge can say. */
    fun refresh(host: String) {
        if (!connected(host) || !supported(host) || host in listing) return
        val command = message("agents.removed")
        val id = command.getString("id")
        listErrors.remove(host)
        listing[host] = id
        if (!send(host, command)) {
            listing.remove(host)
            listErrors[host] = "The host could not be asked which agents were removed. Reconnect and try again."
            return
        }
        later(30_000) {
            if (listing[host] == id) {
                listing.remove(host)
                listErrors[host] = "The host did not say which agents were removed. Reconnect and try again."
            }
        }
    }

    /** A new session: what an earlier one listed may be stale. */
    fun authenticated(host: String) {
        removed.remove(host)
        listing.remove(host)
        forgotten.keys.filter { it.host == host }.forEach { forgotten.remove(it) }
        refresh(host)
    }

    /** The page of [key] closed after its forget. */
    fun closed(key: AgentKey) {
        forgotten.remove(key)
    }

    /** Why Forget cannot be used now, or `null`. */
    fun unavailable(key: AgentKey): String? = when {
        key in pending -> "Forgetting agent…"
        !connected(key.host) -> "Connect to ${key.host} to forget this agent."
        !supported(key.host) -> "Update this host's enclos bridge to forget removed agents."
        !isRemoved(key) -> "Its host does not confirm this agent was removed."
        else -> null
    }

    /**
     * What the page of [key], an agent its host no longer lists, says of
     * whether it can be forgotten, before the host confirms it removed;
     * `null` once it does, or while a forget of it is pending or failed.
     */
    fun status(key: AgentKey): String? = when {
        isRemoved(key) || key in pending || key in errors -> null
        !connected(key.host) -> "Connect to ${key.host} to check whether it was removed."
        !supported(key.host) -> "This host's enclos bridge cannot forget removed agents. Update it to forget this one."
        key.host in listing -> "Asking the host whether it was removed…"
        else -> listErrors[key.host]
    }

    /** Whether the page of [key] shows Forget: removed, or a forget of it pending or failed. */
    fun offers(key: AgentKey) = isRemoved(key) || key in pending || key in errors

    /** Host-page actions keep removed agents reachable after their inbox rows are hidden. */
    fun offered(host: String): List<AgentKey> =
        (removed[host].orEmpty().map { AgentKey(host, it) } + pending.keys + errors.keys)
            .filter { it.host == host }.distinct().sortedBy { it.name }

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
        // Every conversation's deletion record comes before the ack, as for a group's deletion.
        later(180_000) {
            if (pending[key] == id) {
                pending.remove(key)
                late[id] = key
                errors[key] = "The host has not confirmed. Reconnect and check, or retry Forget."
            }
        }
        return true
    }

    fun received(host: String, message: JSONObject): Boolean {
        when (message.optString("type")) {
            "agents.removed.list" -> {
                val list = message.getJSONArray("agents")
                val names = (0 until list.length()).map { i ->
                    list.getString(i).also { name ->
                        val parts = name.split('/')
                        if (parts.size != 2 || parts.any { it.isEmpty() } || name.toByteArray(Charsets.UTF_8).size > 128 || name.any { it.isISOControl() }) {
                            throw JSONException("Invalid removed agent")
                        }
                    }
                }.toSet()
                removed[host] = names
                listing.remove(host)
                listErrors.remove(host)
                // A forget whose answer was lost: no longer listed, it was most likely done,
                // and its conversations go at sync. Not a retry to offer.
                errors.keys.filter { it.host == host && it.name !in names }.forEach { errors.remove(it) }
                return true
            }
            "ack" -> {
                val key = answered(host, message) ?: return false
                errors.remove(key)
                removed[host] = removed[host].orEmpty() - key.name
                forgotten[key] = true
                return true
            }
            "error" -> {
                val of = message.optString("of")
                if (of.isNotEmpty() && listing[host] == of) {
                    listing.remove(host)
                    listErrors[host] = message.optString("message").ifBlank { "The host could not say which agents were removed." }
                    return true
                }
                val key = answered(host, message) ?: return false
                errors[key] = if (message.optString("code") == "unknown_type") "Update this host's enclos bridge to forget removed agents."
                    else message.optString("message").ifBlank { "Forget failed. Retry Forget." }
                return true
            }
        }
        return false
    }

    /** The agent whose `agent.forget` on [host] this answers, pending or given up on. */
    private fun answered(host: String, message: JSONObject): AgentKey? {
        val of = message.optString("of").ifEmpty { return null }
        pending.entries.firstOrNull { it.key.host == host && it.value == of }?.key?.let {
            pending.remove(it)
            return it
        }
        // A later request of the same agent decides, not this answer.
        return late.remove(of)?.takeIf { it.host == host && it !in pending }
    }

    fun ended(host: String) {
        listing.remove(host)
        pending.entries.filter { it.key.host == host }.forEach { (key, id) ->
            pending.remove(key)
            late[id] = key
            errors[key] = "The connection ended before the host confirmed. Reconnect and check, or retry Forget."
        }
    }

    /** Forgets what [host] said, or every host's with `null`. */
    fun clear(host: String? = null) {
        val mine = { h: String -> host == null || h == host }
        removed.keys.filter(mine).forEach { removed.remove(it) }
        listing.keys.filter(mine).forEach { listing.remove(it) }
        listErrors.keys.filter(mine).forEach { listErrors.remove(it) }
        pending.keys.filter { mine(it.host) }.forEach { pending.remove(it) }
        errors.keys.filter { mine(it.host) }.forEach { errors.remove(it) }
        forgotten.keys.filter { mine(it.host) }.forEach { forgotten.remove(it) }
        late.entries.removeAll { mine(it.value.host) }
    }
}
