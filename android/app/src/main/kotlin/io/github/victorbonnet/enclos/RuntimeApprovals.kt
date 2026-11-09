package io.github.victorbonnet.enclos

import org.json.JSONObject

internal const val NATIVE_APPROVAL_SCOPES = "runtime_approval_scopes"

/** Native persistence target and instructions; never inferred from action labels. */
data class RuntimeApprovalScopes(val description: String, val fallback: String) {
    fun validate() {
        require(questionTextValid(description) && questionTextValid(fallback)) { "Native scopes need complete descriptions and instructions." }
        require(jsonText().toByteArray(Charsets.UTF_8).size <= 8 * 1024) { "Native approval scope information is too large." }
    }
    internal fun jsonText(): String = "{\"description\":${jsonString(description)},\"fallback\":${jsonString(fallback)}}"
    companion object {
        fun from(json: JSONObject): RuntimeApprovalScopes = RuntimeApprovalScopes(
            json.get("description") as? String ?: throw IllegalArgumentException("Invalid scope description"),
            json.get("fallback") as? String ?: throw IllegalArgumentException("Invalid native instructions"),
        ).also { it.validate() }
    }
}

/** These are native runtime scopes; they never change sandbox policy. */
enum class RuntimeApprovalAction(val wire: String, val label: String, val result: String) {
    Once("once", "Allow once", "Allowed once"),
    Session("session", "Allow for session", "Allowed for session"),
    Always("always", "Always allow", "Allowed always"),
    Deny("deny", "Deny", "Denied"),
}

data class RuntimeApprovalDefinition(
    val command: String,
    val reason: String,
    val actions: List<RuntimeApprovalAction>,
    val smartDenied: Boolean = false,
    val scopes: RuntimeApprovalScopes? = null,
) {
    fun validate() {
        require(questionTextValid(command) && questionTextValid(reason)) { "An approval must show its complete command and reason." }
        require(RuntimeApprovalAction.Deny in actions && actions == actions.distinct().sortedBy { it.ordinal } &&
            (RuntimeApprovalAction.Once in actions || actions == listOf(RuntimeApprovalAction.Deny))) { "Invalid approval actions." }
        scopes?.validate()
        require(scopes != null || RuntimeApprovalAction.Always !in actions || RuntimeApprovalAction.Session in actions) { "Legacy always allow requires the session scope." }
        require(!smartDenied || actions.all { it == RuntimeApprovalAction.Once || it == RuntimeApprovalAction.Deny }) { "Only a one-time approval can override a smart denial." }
    }

    internal fun jsonText(): String = "{\"command\":${jsonString(command)},\"reason\":${jsonString(reason)},\"actions\":[${actions.joinToString(",") { jsonString(it.wire) }}],\"smart_denied\":$smartDenied" +
        (scopes?.let { ",\"scopes\":${it.jsonText()}" } ?: "") + "}"
    fun json(): JSONObject = JSONObject(jsonText())

    companion object {
        fun from(json: JSONObject): RuntimeApprovalDefinition {
            val command = json.get("command") as? String ?: throw IllegalArgumentException("Invalid approval command")
            val reason = json.get("reason") as? String ?: throw IllegalArgumentException("Invalid approval reason")
            val actions = json.getJSONArray("actions").let { list -> (0 until list.length()).map { index ->
                val raw = list.get(index) as? String ?: throw IllegalArgumentException("Invalid approval action")
                RuntimeApprovalAction.entries.firstOrNull { it.wire == raw } ?: throw IllegalArgumentException("Unknown approval action")
            } }
            val smartDenied = if (json.has("smart_denied")) json.get("smart_denied") as? Boolean ?: throw IllegalArgumentException("Invalid smart denial") else false
            val scopes = if (json.has("scopes") && !json.isNull("scopes")) RuntimeApprovalScopes.from(json.getJSONObject("scopes")) else null
            return RuntimeApprovalDefinition(command, reason, actions, smartDenied, scopes).also { it.validate() }
        }
    }
}
