package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** A human name is independent of the canonical host/sandbox/agent address. */
data class SharedAgentName(val address: String, val displayName: String?, val revision: Long)

/** The same Unicode limits as the bridge; null is the only way to clear a name. */
fun normalizeAgentName(value: String): String {
    var index = 0
    while (index < value.length) {
        val point = value.codePointAt(index)
        // An override or a zero-width character would let a name read as another agent's (U+202E then "niam" shows as "main").
        require(!Visible.isHidden(point) && point != 0x2028 && point != 0x2029 && point !in 0xD800..0xDFFF) { "Names cannot contain control characters, invisible characters or line breaks." }
        index += Character.charCount(point)
    }
    val name = value.trim()
    require(name.isNotBlank()) { "Enter a name, or use the original name." }
    require(name.codePointCount(0, name.length) <= 100 && name.toByteArray(Charsets.UTF_8).size <= 400) { "Use at most 100 characters." }
    return name
}

/** A complete Unicode scalar, including an emoji, rather than half of a surrogate pair. */
fun firstAgentCharacter(name: String): String = if (name.isEmpty()) "?" else name.substring(0, name.offsetByCodePoints(0, 1))

fun resolveAgentLabel(agents: List<Agent>, address: String): String = agents.firstOrNull { it.address == address }?.label ?: address.substringAfterLast('/')

fun agentNameProblem(value: String): String? = try { normalizeAgentName(value); null } catch (e: IllegalArgumentException) { e.message }

/** Keep newer metadata while taking permissions and runtime facts only from the incoming list. */
fun mergeAgentName(current: Agent?, incoming: Agent): Agent =
    if (current != null && current.address == incoming.address && current.nameRevision > incoming.nameRevision) {
        incoming.copy(displayName = current.displayName, nameRevision = current.nameRevision)
    } else incoming

fun newestAgentName(current: SharedAgentName?, incoming: SharedAgentName): SharedAgentName =
    if (current != null && current.address == incoming.address && current.revision > incoming.revision) current else incoming

/** An exact request receipt; an empty broadcast id can never acknowledge an edit. */
data class AgentNameRequest(val id: String, val address: String, val setting: Boolean, val value: String? = null) {
    fun matches(host: String, of: String, agent: String): Boolean = id == of && address == "$host/$agent"
}

/** Live controls for bridge-owned names. Nothing is changed optimistically or queued offline. */
class AgentNames(
    private val send: (String, JSONObject) -> Boolean,
    private val connected: (String) -> Boolean,
    private val agent: (String) -> Agent?,
    private val hostExists: (String) -> Boolean,
    private val changed: (SharedAgentName) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
) {
    private val known = mutableMapOf<String, SharedAgentName>()
    private val requests = mutableMapOf<String, AgentNameRequest>()
    val pending = mutableStateMapOf<String, AgentNameRequest>()
    val errors = mutableStateMapOf<String, String>()
    val fresh = mutableStateMapOf<String, Boolean>()
    val receipts = mutableStateMapOf<String, String>()

    fun available(address: String): Boolean = connected(address.substringBefore('/')) && agent(address)?.supportsNames == true
    fun editable(address: String): Boolean = available(address) && agent(address)?.canEditName == true

    fun listed(incoming: Agent): Agent {
        val cached = known[incoming.address]?.let { incoming.copy(displayName = it.displayName, nameRevision = it.revision) }
        val selected = mergeAgentName(cached, incoming)
        known[incoming.address] = SharedAgentName(selected.address, selected.displayName, selected.nameRevision)
        return selected
    }

    fun current(address: String): SharedAgentName? = known[address] ?: agent(address)?.let { SharedAgentName(address, it.displayName, it.nameRevision) }

    fun load(address: String) {
        if (!editable(address) || address in pending) return
        fresh[address] = false
        request(address, false, null, null)
    }

    fun save(address: String, opening: SharedAgentName, value: String?): String? {
        if (!editable(address) || address in pending || fresh[address] != true) return null
        if (opening.address != address || current(address)?.revision != opening.revision) {
            errors[address] = "The name changed. Close and reopen Rename to load its current name."
            return null
        }
        val normalized = value?.let { try { normalizeAgentName(it) } catch (e: IllegalArgumentException) { errors[address] = e.message.orEmpty(); return null } }
        if (normalized == opening.displayName) return null
        return request(address, true, opening.revision, normalized)
    }

    private fun request(address: String, setting: Boolean, revision: Long?, value: String?): String? {
        val command = if (setting) message("agent.name.set", "agent" to address.substringAfter('/'), "revision" to revision, "display_name" to (value ?: JSONObject.NULL))
        else message("agent.name.get", "agent" to address.substringAfter('/'))
        val request = AgentNameRequest(command.getString("id"), address, setting, value)
        requests[request.id] = request
        pending[address] = request
        errors.remove(address)
        later(30_000) {
            if (requests[request.id] == request) {
                finish(request)
                fresh[address] = false
                errors[address] = if (setting) "The host has not confirmed the outcome. Reopen Rename to check the name before trying again. The request was not resent."
                else "The host did not reply. Try loading the name again."
            }
        }
        if (!send(address.substringBefore('/'), command)) {
            finish(request)
            fresh[address] = false
            errors[address] = "The request was not sent. Reconnect and load the name again."
            return null
        }
        return request.id
    }

    private fun finish(request: AgentNameRequest) {
        requests.remove(request.id)
        if (pending[request.address] == request) pending.remove(request.address)
    }

    fun received(host: String, message: JSONObject): Boolean {
        val of = message.optString("of")
        val request = requests[of]
        if (message.optString("type") == "error") {
            if (request == null || request.address.substringBefore('/') != host) return false
            finish(request)
            fresh[request.address] = false
            errors[request.address] = message.optString("message").ifBlank { "The host could not change the name. Reopen Rename to check its current name." }
            return true
        }
        if (message.optString("type") != "agent.name" || !hostExists(host)) return false
        val canonical = message.optString("agent")
        val address = "$host/$canonical"
        if (request != null && !request.matches(host, of, canonical)) return false
        try {
            require(message.has("display_name") && message.has("revision"))
            val raw = message.opt("display_name")
            require(raw == JSONObject.NULL || raw is String)
            val name = if (raw == JSONObject.NULL) null else normalizeAgentName(raw as String)
            val revision = message.getLong("revision")
            require(revision >= 0)
            if (request != null && request.setting) require(request.value == name)
            val incoming = SharedAgentName(address, name, revision)
            val selected = newestAgentName(known[address], incoming)
            known[address] = selected
            changed(selected)
            // A broadcast/current-state correction updates the label, never a request receipt.
            if (request != null && request.matches(host, of, canonical)) {
                finish(request)
                fresh[address] = true
                errors.remove(address)
                receipts[address] = request.id
            }
        } catch (_: Exception) {
            if (request != null && request.matches(host, of, canonical)) {
                finish(request)
                fresh[address] = false
                errors[address] = "The host's name reply could not be read. Reopen Rename to check the outcome."
            }
        }
        return true
    }

    fun ended(host: String) {
        fresh.keys.filter { it.startsWith("$host/") }.forEach { fresh[it] = false }
        requests.values.filter { it.address.startsWith("$host/") }.toList().forEach { request ->
            finish(request)
            errors[request.address] = "The connection ended before the host confirmed the outcome. Reconnect and reopen Rename to check the name."
        }
    }

    fun forget(host: String) {
        ended(host)
        known.keys.removeAll { it.startsWith("$host/") }
        fresh.keys.filter { it.startsWith("$host/") }.forEach { fresh.remove(it) }
        errors.keys.filter { it.startsWith("$host/") }.forEach { errors.remove(it) }
        receipts.keys.filter { it.startsWith("$host/") }.forEach { receipts.remove(it) }
    }
}
