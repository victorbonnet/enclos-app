package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** Current-connection metadata and correlated provisioning requests for every host. */
internal class ProvisioningClient(
    private val connected: (String) -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
    private val agents: (String) -> List<Agent> = { emptyList() },
) {
    val sandboxes = mutableStateMapOf<String, List<Sandbox>>()
    private val hostCapabilities = mutableStateMapOf<String, Set<String>>()
    val runtimes = mutableStateMapOf<String, List<RuntimeOption>>()
    val provisioning = mutableStateMapOf<String, SandboxOperation>()
    private val provisioningRequests = mutableMapOf<String, Pair<String, String>>()

    fun authenticated(host: String, capabilities: Set<String>) {
        ended(host)
        hostCapabilities[host] = capabilities
        if ("sandboxes" !in capabilities) {
            sandboxes.remove(host)
            provisioning.keys.filter { it == host || it.startsWith("$host/") }.forEach { provisioning.remove(it) }
        }
        refreshProvisioning(host)
    }

    /** Consume only provisioning replies, before other request handlers see errors. */
    fun received(host: String, m: JSONObject): Boolean {
        val type = m.optString("type")
        if (type in setOf("sandboxes.list", "runtimes.list") && !canManageSandboxes(host)) return true
        when (type) {
            "sandboxes.list" -> {
                val list = m.getJSONArray("sandboxes")
                sandboxes[host] = (0 until list.length()).map { i ->
                    val s = list.getJSONObject(i)
                    Sandbox(host, s.getString("sandbox"), s.optString("runtime").takeUnless { s.isNull("runtime") || it.isBlank() }, s.optString("state").ifBlank { null })
                }
            }
            "runtimes.list" -> {
                val list = m.getJSONArray("runtimes")
                runtimes[host] = (0 until list.length()).map { i ->
                    val r = list.getJSONObject(i)
                    RuntimeOption(r.getString("runtime"), r.getString("name"), r.optString("description"))
                }
            }
            "operation.progress" -> {
                val of = m.optString("of")
                val (requestHost, key) = provisioningRequests[of] ?: return false
                if (requestHost != host) return false
                provisioning[key]?.let {
                    provisioning[key] = it.progress(of, m.optString("operation"), m.optString("sandbox"), m.optString("message"))
                }
            }
            "sandbox.created", "runtime.installed" -> {
                val of = m.optString("of")
                val (requestHost, key) = provisioningRequests[of] ?: return false
                if (requestHost != host) return false
                val operation = provisioning[key] ?: return false
                val installing = m.optString("type") == "runtime.installed"
                val sandbox = m.optString("sandbox")
                val runtime = if (installing) m.optString("runtime").ifBlank { null } else null
                // A matching request id still must name the sandbox and operation that was sent.
                if ((installing && runtime != operation.runtime) || sandbox != operation.sandbox ||
                    operation.operation != if (installing) "runtime.install" else "sandbox.create") {
                    provisioningRequests.remove(of)
                    provisioning[key] = operation.failed(of, "The host answered for a different sandbox or runtime. Check it on the host.")
                    refreshProvisioning(host)
                    return true
                }
                val completed = operation.completed(
                    of, if (installing) "runtime.install" else "sandbox.create", sandbox,
                    if (installing) "${runtimes[host].orEmpty().firstOrNull { it.runtime == runtime }?.name ?: runtime} installed." else "$sandbox created.",
                    m.optString("login").takeUnless { m.isNull("login") || it.isBlank() },
                )
                if (!completed.done) {
                    provisioningRequests.remove(of)
                    provisioning[key] = operation.failed(of, "The host could not confirm this operation. Check the sandbox on the host.")
                    return true
                }
                provisioningRequests.remove(of)
                provisioning[key] = completed
                val listed = sandboxesOf(host)
                val old = listed.firstOrNull { it.name == sandbox }
                val confirmed = old?.copy(runtime = runtime ?: old.runtime) ?: Sandbox(host, sandbox, runtime, "created")
                sandboxes[host] = listed.filterNot { it.name == sandbox } + confirmed
                refreshProvisioning(host)
                send(host, message("agents"))
            }
            "error" -> {
                val of = m.optString("of")
                val location = provisioningRequests[of] ?: return false
                if (location.first != host) return false
                provisioningRequests.remove(of)
                provisioning[location.second]?.let { provisioning[location.second] = it.failed(of, m.optString("message")) }
                refreshProvisioning(host)
            }
            else -> return false
        }
        return true
    }

    /** Only the host's person, on a bridge that advertises these operations, may manage sandboxes. */
    fun canManageSandboxes(host: String) = connected(host) && "sandboxes" in hostCapabilities[host].orEmpty()

    /** On older bridges, the sandboxes the agent list implies remain visible. */
    fun sandboxesOf(host: String): List<Sandbox> = sandboxes[host]
        ?: agents(host).groupBy { it.sandbox }.map { (name, agents) ->
            Sandbox(host, name, agents.first().runtime.ifBlank { null })
        }

    /** Refreshes what the current bridge offers, without sending unknown requests to older bridges. */
    fun refreshProvisioning(host: String) {
        if (!canManageSandboxes(host)) return
        send(host, message("sandboxes"))
        send(host, message("runtimes"))
    }

    /** Clears an earlier result when its screen is opened again; running work keeps its correlation. */
    fun clearProvisioning(key: String) {
        if (provisioning[key]?.busy != true) provisioning.remove(key)
    }

    /** Creates a sandbox on the host. Choosing the template is explicit on the wire. */
    fun createSandbox(host: String, name: String, template: String = "minimal"): Boolean {
        val sandbox = name.trim()
        val request = message("sandbox.create", "sandbox" to sandbox, "template" to template)
        val operation = SandboxOperation(request.getString("id"), "sandbox.create", sandbox, message = "Creating $sandbox…")
        if (provisioning[host]?.busy == true) return false
        val invalid = sandboxNameError(sandbox)
            ?: if (sandboxesOf(host).any { it.name == sandbox }) "A sandbox named $sandbox already exists on $host." else null
        return submitProvisioning(host, host, request, operation, invalid)
    }

    /** Installs one of the runtimes the host advertised in a sandbox without a runtime. */
    fun installRuntime(host: String, sandbox: String, runtime: String): Boolean {
        val key = "$host/$sandbox"
        if (provisioning[key]?.busy == true) return false
        val offered = runtimes[host].orEmpty().firstOrNull { it.runtime == runtime }
        val request = message("runtime.install", "sandbox" to sandbox, "runtime" to runtime)
        val operation = SandboxOperation(request.getString("id"), "runtime.install", sandbox, runtime, message = "Installing ${offered?.name ?: runtime}…")
        val invalid = when {
            offered == null -> "This host has not offered that runtime for installation."
            sandboxesOf(host).none { it.name == sandbox } -> "This sandbox is no longer listed by its host."
            sandboxesOf(host).any { it.name == sandbox && it.runtime != null } -> "This sandbox already has a runtime."
            else -> null
        }
        return submitProvisioning(host, key, request, operation, invalid)
    }

    private fun submitProvisioning(host: String, key: String, request: JSONObject, operation: SandboxOperation, invalid: String?): Boolean {
        val why = when {
            !connected(host) -> "Not connected to $host. Reconnect before trying again."
            !canManageSandboxes(host) -> "This connection does not offer sandbox management. Update the host and connect as the host's person."
            else -> invalid
        }
        if (why != null) {
            provisioning[key] = operation.failed(operation.request, why)
            return false
        }
        provisioning[key] = operation
        provisioningRequests[operation.request] = host to key
        if (send(host, request) == true) return true
        provisioningRequests.remove(operation.request)
        provisioning[key] = operation.failed(operation.request, "The request could not be sent. Reconnect before trying again.")
        return false
    }

    fun ended(host: String) {
        hostCapabilities.remove(host)
        runtimes.remove(host)
        provisioningRequests.filterValues { it.first == host }.toMap().forEach { (request, location) ->
            provisioningRequests.remove(request)
            provisioning[location.second]?.let { provisioning[location.second] = it.disconnected() }
        }
    }

}
