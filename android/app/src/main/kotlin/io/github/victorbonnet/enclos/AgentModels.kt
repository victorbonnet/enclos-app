package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** A model provider and one of its models; the provider is absent when the runtime names only a model. */
data class ModelSelection(val provider: String?, val model: String) {
    val label: String get() = provider?.let { "$model ($it)" } ?: model
    companion object {
        fun from(o: JSONObject?): ModelSelection? = o?.let {
            ModelSelection(it.optString("provider").takeUnless { _ -> it.isNull("provider") || it.optString("provider").isBlank() }, it.getString("model"))
        }
    }
}

/** A provider as the runtime lists it: one the agent cannot use yet carries what to do on the host. */
data class ModelProvider(val id: String, val label: String, val usable: Boolean, val setup: String?)

data class ModelChoice(val id: String, val label: String)

/**
 * What the agent's runtime says of its model ([fresh] until a change or a
 * lost reply makes it stale). [applies] is `live` or `restart`.
 */
data class ModelOptions(
    val current: ModelSelection?,
    val revision: String,
    val applies: String,
    val providers: List<ModelProvider>,
    val customModels: Boolean,
    val fresh: Boolean = true,
) {
    /** Whether saving restarts the runtime's service, and with it the sandbox's other agents. */
    val restarts: Boolean get() = applies != "live"
    companion object {
        fun from(m: JSONObject): ModelOptions {
            val a = m.getJSONArray("providers")
            val providers = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                ModelProvider(p.getString("id"), p.optString("label").ifBlank { p.getString("id") }, p.getBoolean("usable"),
                    p.optString("setup").takeUnless { p.isNull("setup") || it.isBlank() })
            }
            return ModelOptions(ModelSelection.from(m.optJSONObject("current")), m.getString("revision"), m.getString("applies"), providers, m.optBoolean("custom_models"))
        }
    }
}

/** One provider's models, at most what the runtime lists; [limited] when it offers more. */
data class ProviderModels(val provider: String, val models: List<ModelChoice>, val limited: Boolean)

data class ModelRequest(val id: String, val address: String, val action: String, val provider: String? = null, val model: String? = null)

/**
 * The agent's model, read and changed live through its runtime (enclos
 * `docs/protocol.md`, An agent's model). Keyed by agent address
 * (`host/sandbox/agent`); a change is never queued or retried.
 */
class ModelClient(
    private val send: (String, JSONObject) -> Boolean,
    private val connected: (String) -> Boolean,
    private val supported: (String) -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
) {
    val options = mutableStateMapOf<String, ModelOptions>()
    /** By [modelsKey]. */
    val models = mutableStateMapOf<String, ProviderModels>()
    val pending = mutableStateMapOf<String, ModelRequest>()
    val errors = mutableStateMapOf<String, String>()
    val notices = mutableStateMapOf<String, String>()
    private val requests = mutableMapOf<String, ModelRequest>()

    fun modelsKey(address: String, provider: String) = "$address\n$provider"
    fun available(address: String): Boolean = connected(address.substringBefore('/')) && supported(address)
    fun canSet(address: String): Boolean = available(address) && options[address]?.fresh == true && address !in pending

    private fun request(address: String, action: String, vararg fields: Pair<String, Any?>): String? {
        if (!available(address) || address in pending) return null
        val command = message("model.request", "agent" to address.substringAfter('/'), "action" to action, *fields)
        val provider = fields.firstOrNull { it.first == "provider" }?.second as String?
        val model = fields.firstOrNull { it.first == "model" }?.second as String?
        val request = ModelRequest(command.getString("id"), address, action, provider, model)
        requests[request.id] = request
        pending[address] = request
        errors.remove(address)
        if (action == "set") notices.remove(address)
        later(75_000) {
            if (requests[request.id] == request) {
                finish(request)
                if (action != "models") stale(address)
                errors[address] = if (action == "set") "The host has not confirmed the change. Refresh and check the agent's model before trying again. The request was not resent."
                else "The host did not reply. Refresh to try again."
            }
        }
        if (!send(address.substringBefore('/'), command)) {
            finish(request)
            stale(address)
            errors[address] = "The request was not sent. Reconnect and refresh."
            return null
        }
        return request.id
    }

    /** Reads the options again, and drops the model lists read before them. */
    fun refresh(address: String): String? {
        if (address in pending) return null
        stale(address)
        models.keys.filter { it.startsWith("$address\n") }.forEach { models.remove(it) }
        return request(address, "options")
    }

    fun load(address: String, provider: String): String? = request(address, "models", "provider" to provider)

    /** Sends the change, only against fresh options and to a provider the agent can use. */
    fun set(address: String, provider: String, model: String): String? {
        val current = options[address] ?: return null
        if (!canSet(address) || model.isBlank()) return null
        if (current.providers.none { it.id == provider && it.usable }) return null
        if (current.current == ModelSelection(provider, model)) return null
        return request(address, "set", "provider" to provider, "model" to model.trim(), "revision" to current.revision)
    }

    /** Marks the options out of date: Save waits for a refresh. */
    private fun stale(address: String) { options[address]?.let { options[address] = it.copy(fresh = false) } }
    private fun finish(request: ModelRequest) { requests.remove(request.id); if (pending[request.address] == request) pending.remove(request.address) }

    /** Returns true only for an answer to this client's exact pending request. */
    fun received(host: String, m: JSONObject): Boolean {
        val request = requests[m.optString("of")] ?: return false
        if (request.address.substringBefore('/') != host) return false
        if (m.optString("type") == "error") {
            finish(request)
            // A model list that failed changed nothing: the options stay.
            if (request.action != "models") stale(request.address)
            errors[request.address] = m.optString("message").ifBlank { "The host could not complete the request. Refresh to check the agent's model." }
            return true
        }
        if (m.optString("type") != "model.response" || m.optString("agent") != request.address.substringAfter('/')) return false
        val expected = when (request.action) { "options" -> "options"; "models" -> "models"; else -> "set" }
        if (m.optString("result") != expected) return false
        val address = request.address
        try {
            when (expected) {
                "options" -> options[address] = ModelOptions.from(m)
                "models" -> {
                    require(m.getString("provider") == request.provider) { "The host replied for another model provider." }
                    val a = m.getJSONArray("models")
                    val listed = (0 until a.length()).map { i -> a.getJSONObject(i).let { ModelChoice(it.getString("id"), it.optString("label").ifBlank { it.getString("id") }) } }
                    models[modelsKey(address, request.provider!!)] = ProviderModels(request.provider, listed, m.optBoolean("limited"))
                }
                "set" -> {
                    val now = ModelSelection.from(m.getJSONObject("current"))!!
                    options[address]?.let { options[address] = it.copy(current = now, revision = m.getString("revision"), applies = m.getString("applies")) }
                    notices[address] = if (m.getString("applies") == "live") "Saved. ${now.label} answers from the agent's next message."
                    else "Saved. ${now.label} applies once the runtime restarts, after the turns running now."
                }
            }
            finish(request)
            errors.remove(address)
        } catch (_: Exception) {
            finish(request)
            if (request.action != "models") stale(address)
            errors[address] = "The host's reply could not be read. Refresh to check the outcome before trying again."
        }
        return true
    }

    fun ended(host: String) {
        options.keys.filter { it.startsWith("$host/") }.forEach(::stale)
        requests.values.filter { it.address.startsWith("$host/") }.toList().forEach {
            finish(it)
            errors[it.address] = if (it.action == "set") "The connection ended before the host confirmed the change. Reconnect and refresh before trying again."
            else "The connection ended. Reconnect and refresh."
        }
    }

    fun forget(host: String) {
        ended(host)
        options.keys.filter { it.startsWith("$host/") }.forEach { options.remove(it) }
        models.keys.filter { it.startsWith("$host/") }.forEach { models.remove(it) }
        errors.keys.filter { it.startsWith("$host/") }.forEach { errors.remove(it) }
        notices.keys.filter { it.startsWith("$host/") }.forEach { notices.remove(it) }
    }
}
