package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONException
import org.json.JSONObject

/** A model provider and one of its models; the model provider is absent when the runtime's settings name only a model. */
data class ModelSelection(val provider: String?, val model: String) {
    /** The model, then its model provider: a model id may hold a `/` itself. */
    val label: String get() = provider?.let { "$model ($it)" } ?: model
    companion object {
        fun from(o: JSONObject?): ModelSelection? = o?.let {
            val provider = if (it.isNull("provider")) null else it.optString("provider").takeUnless(String::isBlank)
            ModelSelection(provider, it.getString("model"))
        }
    }
}

/** A model provider as the runtime lists it: one the agent cannot use yet carries what to do on the host. */
data class ModelProvider(val id: String, val label: String, val usable: Boolean, val setup: String?)

data class ModelChoice(val id: String, val label: String)

/**
 * What the agent's runtime says of its model. [fresh] until a refusal, a
 * lost or unreadable reply, a dropped connection or a refresh in flight
 * makes it stale; a confirmed save keeps it fresh. [applies] is `live` or
 * `restart`; anything but `live` is treated as a restart.
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
                    if (p.isNull("setup")) null else p.optString("setup").takeUnless(String::isBlank))
            }
            return ModelOptions(ModelSelection.from(m.optJSONObject("current")), m.getString("revision"), m.getString("applies"), providers, m.optBoolean("custom_models"))
        }
    }
}

/** One model provider's models as the runtime listed them (at most 300); [limited] when it offers more. */
data class ProviderModels(val provider: String, val models: List<ModelChoice>, val limited: Boolean)

/** Why a model provider's models did not load, and whether asking again may help. */
data class ModelListError(val text: String, val retry: Boolean)

data class ModelRequest(val id: String, val address: String, val action: String, val provider: String? = null, val model: String? = null)

/**
 * The agent's model, read and changed live through its runtime (enclos
 * `docs/protocol.md`, An agent's model). Keyed by agent address
 * (`host/sandbox/agent`); a change is never queued or retried.
 */
class ModelClient(
    private val send: (String, JSONObject) -> Boolean,
    /** Whether the host, by name, is connected. */
    private val connected: (String) -> Boolean,
    /** Whether the agent, by address, offers its model to this person. */
    private val supported: (String) -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
    private val log: (String) -> Unit = { android.util.Log.w("enclos", it) },
) {
    val options = mutableStateMapOf<String, ModelOptions>()
    /** By [modelsKey]. */
    val models = mutableStateMapOf<String, ProviderModels>()
    /** A model list that failed, by [modelsKey]. */
    val modelErrors = mutableStateMapOf<String, ModelListError>()
    val pending = mutableStateMapOf<String, ModelRequest>()
    val errors = mutableStateMapOf<String, String>()
    val notices = mutableStateMapOf<String, String>()
    /** How many saves the host confirmed, by address: what was picked is then the model. */
    val confirmed = mutableStateMapOf<String, Int>()
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
        if (action == "models") modelErrors.remove(modelsKey(address, provider!!)) else errors.remove(address)
        // What a notice said may no longer hold once the page asks again.
        notices.remove(address)
        // Past the bridge's 60 s: its own `unknown` normally comes first; this
        // is for an answer lost on the way.
        later(75_000) {
            if (requests[request.id] == request) {
                finish(request)
                when (action) {
                    "models" -> modelErrors[modelsKey(address, provider!!)] = ModelListError("The host did not reply.", retry = true)
                    "set" -> { stale(address); errors[address] = "The host has not confirmed the change. Refresh and check the agent's model before trying again. The request was not resent." }
                    else -> { stale(address); errors[address] = "The host did not reply. Refresh to try again." }
                }
            }
        }
        if (!send(address.substringBefore('/'), command)) {
            finish(request)
            if (action == "models") modelErrors[modelsKey(address, provider!!)] = ModelListError("The request was not sent. Reconnect and try again.", retry = true)
            else { stale(address); errors[address] = "The request was not sent. Reconnect and refresh." }
            return null
        }
        return request.id
    }

    /** Reads the options again, and drops the model lists read before them. */
    fun refresh(address: String): String? {
        if (address in pending) return null
        stale(address)
        models.keys.filter { it.startsWith("$address\n") }.forEach { models.remove(it) }
        modelErrors.keys.filter { it.startsWith("$address\n") }.forEach { modelErrors.remove(it) }
        return request(address, "options")
    }

    fun load(address: String, provider: String): String? = request(address, "models", "provider" to provider)

    /** Sends the change, only against fresh options, to a model provider the agent can use, and for another model than the current one. */
    fun set(address: String, provider: String, model: String): String? {
        val current = options[address] ?: return null
        val id = model.trim()
        if (!canSet(address) || id.isEmpty()) return null
        if (current.providers.none { it.id == provider && it.usable }) return null
        if (current.current == ModelSelection(provider, id)) return null
        return request(address, "set", "provider" to provider, "model" to id, "revision" to current.revision)
    }

    /** Marks the options out of date: Save waits for a refresh. */
    private fun stale(address: String) { options[address]?.let { options[address] = it.copy(fresh = false) } }
    private fun finish(request: ModelRequest) { requests.remove(request.id); if (pending[request.address] == request) pending.remove(request.address) }

    /** Returns true only for an answer to this client's exact pending request. */
    fun received(host: String, m: JSONObject): Boolean {
        val request = requests[m.optString("of")] ?: return false
        if (request.address.substringBefore('/') != host) return false
        if (m.optString("type") == "error") {
            refused(request, m.optString("code"), m.optString("message").takeUnless(String::isBlank))
            return true
        }
        if (m.optString("type") != "model.response" || m.optString("agent") != request.address.substringAfter('/')) {
            log("model reply to ${request.action} for ${request.address} not taken: type ${m.optString("type")}, agent ${m.optString("agent")}")
            return false
        }
        val expected = when (request.action) { "options" -> "options"; "models" -> "models"; else -> "set" }
        if (m.optString("result") != expected) {
            log("model reply to ${request.action} for ${request.address} not taken: result ${m.optString("result")}")
            return false
        }
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
                    val now = ModelSelection.from(m.getJSONObject("current")) ?: throw JSONException("no current model")
                    val applies = m.getString("applies")
                    options[address]?.let { options[address] = it.copy(current = now, revision = m.getString("revision"), applies = applies) }
                    confirmed[address] = (confirmed[address] ?: 0) + 1
                    notices[address] = if (applies == "live") "Saved. ${now.label} answers from the agent's next message."
                    else "Saved. ${now.label} applies once the runtime restarts."
                }
            }
            finish(request)
            errors.remove(address)
        } catch (e: JSONException) {
            unreadable(request, e)
        } catch (e: IllegalArgumentException) {
            unreadable(request, e)
        }
        return true
    }

    /** A refusal: its text by its code, the host's reason kept. */
    private fun refused(request: ModelRequest, code: String, reason: String?) {
        finish(request)
        val address = request.address
        if (request.action == "models") {
            val key = modelsKey(address, request.provider!!)
            modelErrors[key] = when (code) {
                // Asked again, the same list fails the same way.
                "unsupported" -> ModelListError(reason ?: "This model provider lists too many models to show on the phone.", retry = false)
                "not_found" -> ModelListError(reason ?: "The runtime no longer lists this model provider. Refresh.", retry = false)
                else -> ModelListError(reason ?: "The host could not list its models.", retry = true)
            }
            return
        }
        // A save refused as not found or not allowed changed nothing: the options still hold.
        if (request.action != "set" || code !in setOf("not_found", "not_allowed", "bad_message")) stale(address)
        errors[address] = when {
            request.action == "set" && code == "unknown" ->
                "The host could not confirm whether the model changed. Refresh and check it before trying again." + (reason?.let { " ($it)" } ?: "")
            code == "conflict" -> "The agent's model changed since this page read it. Refresh to see it, then choose again."
            else -> reason ?: "The host could not complete the request. Refresh to check the agent's model."
        }
    }

    private fun unreadable(request: ModelRequest, e: Exception) {
        log("model reply to ${request.action} for ${request.address} not read: ${e.message}")
        finish(request)
        val address = request.address
        if (request.action == "models") {
            modelErrors[modelsKey(address, request.provider!!)] = ModelListError("The host's list could not be read: ${e.message}", retry = true)
            return
        }
        stale(address)
        errors[address] = if (request.action == "set") "The host's reply could not be read: the model may have changed. Refresh to check it before trying again."
        else "The host's reply could not be read. Refresh to try again."
    }

    fun ended(host: String) {
        options.keys.filter { it.startsWith("$host/") }.forEach(::stale)
        notices.keys.filter { it.startsWith("$host/") }.forEach { notices.remove(it) }
        requests.values.filter { it.address.startsWith("$host/") }.toList().forEach {
            finish(it)
            when (it.action) {
                "set" -> errors[it.address] = "The connection ended before the host confirmed the change. Reconnect and refresh before trying again."
                "models" -> modelErrors[modelsKey(it.address, it.provider!!)] = ModelListError("The connection ended.", retry = true)
                else -> errors[it.address] = "The connection ended. Reconnect and refresh."
            }
        }
    }

    fun forget(host: String) {
        ended(host)
        options.keys.filter { it.startsWith("$host/") }.forEach { options.remove(it) }
        models.keys.filter { it.startsWith("$host/") }.forEach { models.remove(it) }
        modelErrors.keys.filter { it.startsWith("$host/") }.forEach { modelErrors.remove(it) }
        errors.keys.filter { it.startsWith("$host/") }.forEach { errors.remove(it) }
        notices.keys.filter { it.startsWith("$host/") }.forEach { notices.remove(it) }
        confirmed.keys.filter { it.startsWith("$host/") }.forEach { confirmed.remove(it) }
    }
}
