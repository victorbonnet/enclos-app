package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** Ephemeral, host-scoped views of runtime-owned routines. Mutations are never queued or retried. */
class RoutineClient(
    private val send: (String, JSONObject) -> Boolean,
    private val connected: (String) -> Boolean,
    private val supported: (String) -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
) {
    val lists = mutableStateMapOf<String, RoutineList>()
    val details = mutableStateMapOf<String, Routine>()
    val pending = mutableStateMapOf<String, RoutineRequest>()
    val errors = mutableStateMapOf<String, String>()
    val notices = mutableStateMapOf<String, String>()
    /** A correlated success, used to dismiss only the editor that submitted it. */
    val receipts = mutableStateMapOf<String, String>()
    private val requests = mutableMapOf<String, RoutineRequest>()

    fun available(address: String): Boolean = connected(address.substringBefore('/')) && supported(address)
    fun canChange(address: String): Boolean = available(address) && lists[address]?.fresh == true && address !in pending
    fun key(address: String, id: String) = "$address/$id"

    private fun request(address: String, action: String, fields: List<Pair<String, Any?>> = emptyList(), routine: String? = null, cursor: String? = null): String? {
        if (!available(address) || address in pending) return null
        val command = message("routine.request", "agent" to address.substringAfter('/'), "action" to action, *fields.toTypedArray())
        val request = RoutineRequest(command.getString("id"), address, action, routine, cursor)
        requests[request.id] = request
        pending[address] = request
        errors.remove(address)
        if (action == "create" || action == "update" || action == "delete" || action == "set_enabled" || action == "run") notices.remove(address)
        later(60_000) {
            if (requests[request.id] == request) {
                finish(request)
                stale(address)
                errors[address] = if (action == "list" || action == "get") "The host did not reply. Refresh to try reading its routines again."
                else "The host has not confirmed the outcome. Refresh and check its routines before trying again. The request was not resent."
            }
        }
        if (!send(address.substringBefore('/'), command)) {
            finish(request)
            stale(address)
            errors[address] = "The request was not sent. Reconnect and refresh its routines."
            return null
        }
        return request.id
    }

    fun refresh(address: String, more: Boolean = false) {
        val cursor = if (more) lists[address]?.nextCursor ?: return else null
        if (address in pending) return
        if (!more) stale(address)
        request(address, "list", if (cursor != null) listOf("cursor" to cursor) else emptyList(), cursor = cursor)
    }

    fun get(address: String, id: String) {
        if (!canChange(address)) return
        details.remove(key(address, id))
        request(address, "get", listOf("routine" to id), routine = id)
    }

    fun save(address: String, original: RoutineSummary?, definition: RoutineDefinition): String? {
        if (!canChange(address) || definition.problem() != null) return null
        if (original == null) return request(address, "create", listOf("definition" to definition.json()))
        if (!current(address, original)) return null
        return request(address, "update", listOf("routine" to original.id, "revision" to original.revision, "definition" to definition.json()), routine = original.id)
    }

    fun act(address: String, routine: RoutineSummary, action: String, enabled: Boolean? = null): String? {
        if (!canChange(address) || !current(address, routine)) return null
        if (action == "run" && "run" !in lists[address]?.options?.features.orEmpty()) return null
        // The runtime runs only an enabled routine: a paused one is resumed first.
        if (action == "run" && !routine.enabled) return null
        if (action !in setOf("run", "delete", "set_enabled")) return null
        val fields = mutableListOf<Pair<String, Any?>>("routine" to routine.id, "revision" to routine.revision)
        if (action == "set_enabled") fields += "enabled" to (enabled ?: return null)
        return request(address, action, fields, routine = routine.id)
    }

    private fun current(address: String, routine: RoutineSummary): Boolean = routine.editable && sameRoutineRevision(lists[address]?.items?.firstOrNull { it.id == routine.id }, routine)
    private fun stale(address: String) { lists[address]?.let { lists[address] = it.copy(fresh = false) } }
    private fun finish(request: RoutineRequest) { requests.remove(request.id); if (pending[request.address] == request) pending.remove(request.address) }

    /** Returns true only for a response belonging to this client's exact pending request. */
    fun received(host: String, m: JSONObject): Boolean {
        val request = requests[m.optString("of")] ?: return false
        if (request.address.substringBefore('/') != host) return false
        if (m.optString("type") == "error") {
            finish(request)
            stale(request.address)
            errors[request.address] = m.optString("message").ifBlank { "The host could not complete the request. Refresh to check its routines." }
            return true
        }
        if (m.optString("type") != "routine.response" || !request.matches(host, m.optString("of"), m.optString("agent"))) return false
        val expected = when (request.operation) { "list" -> "list"; "run" -> "run"; "delete" -> "deleted"; else -> "routine" }
        val returnedId = if (m.optString("result") == "routine") m.optJSONObject("routine")?.optString("id") else m.optString("routine")
        if (!request.accepts(m.optString("result"), returnedId)) return false
        try {
            val address = request.address
            when (expected) {
                "list" -> {
                    val a = m.getJSONArray("routines")
                    val items = (0 until a.length()).map { RoutineSummary.from(a.getJSONObject(it)) }
                    val old = lists[address]
                    val combined = if (request.cursor == null) items else old?.items.orEmpty().filter { previous -> items.none { it.id == previous.id } } + items
                    lists[address] = RoutineList(combined, RoutineOptions.from(m.getJSONObject("options")), RoutineOptions.choices(m.optJSONArray("deliveries")), m.optString("next_cursor").takeUnless { m.isNull("next_cursor") || it.isBlank() }, true, m.optBoolean("deliveries_limited"))
                }
                "routine" -> {
                    val routine = Routine.from(m.getJSONObject("routine"))
                    require(request.routine == null || request.routine == routine.summary.id) { "The host replied for a different routine." }
                    details[key(address, routine.summary.id)] = routine
                    lists[address]?.let { lists[address] = it.copy(items = mergeRoutineSummary(it.items, routine.summary)) }
                }
                "deleted", "run" -> require(m.getString("routine") == request.routine) { "The host replied for a different routine." }
            }
            finish(request)
            errors.remove(address)
            if (request.operation !in setOf("list", "get")) {
                receipts[address] = request.id
                notices[address] = when (request.operation) { "run" -> "Run requested. Refresh to check its status. Output uses this routine's saved delivery."; "delete" -> "Routine deleted."; else -> "Routine saved." }
                // A successful mutation is canonical, but its list must be read again.
                refresh(address)
            }
        } catch (_: Exception) {
            finish(request)
            stale(request.address)
            errors[request.address] = "The host's reply could not be read. Refresh to check the outcome before trying again."
        }
        return true
    }

    fun ended(host: String) {
        lists.keys.filter { it.startsWith("$host/") }.forEach(::stale)
        requests.values.filter { it.address.startsWith("$host/") }.toList().forEach {
            finish(it)
            errors[it.address] = "The connection ended before the host confirmed the outcome. Reconnect and refresh before trying again."
        }
    }

    fun forget(host: String) {
        ended(host)
        lists.keys.filter { it.startsWith("$host/") }.forEach { lists.remove(it); errors.remove(it); notices.remove(it); receipts.remove(it) }
        details.keys.filter { it.startsWith("$host/") }.forEach { details.remove(it) }
        errors.keys.filter { it.startsWith("$host/") }.forEach { errors.remove(it) }
    }
}
