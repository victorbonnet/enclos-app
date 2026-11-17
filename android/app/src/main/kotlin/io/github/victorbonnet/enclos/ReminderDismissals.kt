package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.util.UUID

/** Dismissing changes discovery only. Exact receipts finish requests; nothing queues offline. */
class ReminderDismissals(
    private val index: NeedsYou,
    private val connected: (String) -> Boolean,
    private val supported: (String) -> Boolean,
    private val send: (OpenItem, String) -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
) {
    private data class Request(val item: OpenItem, val id: String)
    private val requests = mutableMapOf<String, Request>()
    val pending = mutableStateMapOf<String, String>()
    val errors = mutableStateMapOf<String, String>()

    fun unavailable(item: OpenItem): String? = when {
        !connected(item.host) -> "Connect to ${item.host} to dismiss this reminder."
        !supported(item.host) -> "This host needs an update to dismiss reminders."
        index.freshHosts[item.host] != true || item.version.isEmpty() -> "Waiting for requests to refresh…"
        index.items[item.key]?.version != item.version -> "This request changed. Refresh first."
        item.key in pending -> "Dismissing…"
        else -> null
    }
    fun dismiss(item: OpenItem): Boolean {
        if (unavailable(item) != null) return false
        val id = UUID.randomUUID().toString()
        requests[id] = Request(item, id); pending[item.key] = id; errors.remove(item.key)
        if (!send(item, id)) {
            finish(id); errors[item.key] = "The reminder was not dismissed. Reconnect and try again."
            return false
        }
        later(30_000) {
            if (finish(id) != null) errors[item.key] = "Dismissal was not confirmed. Refresh to check."
        }
        return true
    }
    fun received(host: String, message: JSONObject): Boolean {
        val id = message.optString("of")
        val request = requests[id]?.takeIf { it.item.host == host } ?: return false
        if (message.optString("type") == "error") {
            finish(id); errors[request.item.key] = message.optString("message", "Could not dismiss this reminder.")
            return true
        }
        if (message.optString("type") != "open_item.dismissed") return false
        val item = request.item
        val agent = message.opt("agent") as? String
        if (message.optString("kind") != item.kind || message.optString("item") != item.id || agent != item.agent || message.optString("version") != item.version) return true
        finish(id); errors.remove(item.key); index.dismissed(item)
        return true
    }
    fun ended(host: String) {
        requests.values.filter { it.item.host == host }.toList().forEach {
            finish(it.id); errors[it.item.key] = "Connection ended. Refresh to check whether it was dismissed."
        }
    }
    fun forget(host: String) { ended(host); errors.keys.filter { it.startsWith("$host/") }.forEach(errors::remove) }
    private fun finish(id: String): Request? = requests.remove(id)?.also { pending.remove(it.item.key) }
}
