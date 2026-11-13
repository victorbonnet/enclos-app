package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import org.json.JSONException

/** Conversation ids are scoped to a host, including permanent deletion records. */
data class ConversationKey(val host: String, val id: String)

/** A delete stays visible until its host confirms it. A receipt alone never removes history. */
class ConversationDeletion(
    private val connected: (String) -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
    private val removed: (ConversationKey) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
    private val isDraft: (Conversation) -> Boolean = { false },
) {
    val deleted = mutableStateMapOf<ConversationKey, Boolean>()
    val pending = mutableStateMapOf<ConversationKey, String>()
    val errors = mutableStateMapOf<ConversationKey, String>()

    fun unavailable(c: Conversation): String? = when {
        isDraft(c) -> "Send the first message before deleting."
        c.home -> "Home stays available for the agent's notices."
        deleted[ConversationKey(c.host, c.id)] == true -> "This conversation was deleted."
        ConversationKey(c.host, c.id) in pending -> "Deleting conversation…"
        !connected(c.host) -> "Connect to ${c.host} to delete this conversation."
        else -> null
    }

    fun delete(c: Conversation): Boolean {
        if (unavailable(c) != null) return false
        val key = ConversationKey(c.host, c.id)
        val command = message("conversation.delete", "conversation" to c.id)
        val id = command.getString("id")
        errors.remove(key)
        pending[key] = id
        if (!send(c.host, command)) {
            pending.remove(key)
            errors[key] = "The deletion was not sent. Reconnect and try again."
            return false
        }
        // A group may need acknowledgements from six native runtime sessions.
        later(180_000) {
            if (pending[key] == id) {
                pending.remove(key)
                errors[key] = "The host has not confirmed deletion. Reconnect and check, or retry Delete."
            }
        }
        return true
    }

    fun received(host: String, message: JSONObject): Boolean {
        when (message.optString("type")) {
            "conversation.deleted" -> {
                val id = message.getString("conversation")
                if (id.isEmpty() || id.startsWith("home.") || id.toByteArray(Charsets.UTF_8).size > 128 || id.any { it.isISOControl() }) {
                    throw JSONException("Invalid deleted conversation")
                }
                val key = ConversationKey(host, id)
                pending.remove(key)
                errors.remove(key)
                if (deleted.put(key, true) != true) removed(key)
                return true
            }
            "error" -> {
                val key = pending.entries.firstOrNull { it.key.host == host && it.value == message.optString("of") }?.key ?: return false
                pending.remove(key)
                errors[key] = if (message.optString("code") == "unknown_type") "Update this host's enclos bridge to delete conversations."
                    else message.optString("message").ifBlank { "Deletion failed. Retry Delete." }
                return true
            }
        }
        return false
    }

    fun ended(host: String) {
        pending.keys.filter { it.host == host }.forEach {
            pending.remove(it)
            errors[it] = "The connection ended before deletion was confirmed. Reconnect and check, or retry Delete."
        }
    }

    fun forget(host: String) {
        pending.keys.filter { it.host == host }.forEach { pending.remove(it) }
        errors.keys.filter { it.host == host }.forEach { errors.remove(it) }
        deleted.keys.filter { it.host == host }.forEach { deleted.remove(it) }
    }
}
