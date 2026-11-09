package io.github.victorbonnet.enclos

import org.json.JSONObject

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
) {
    fun validate() {
        require(questionTextValid(command) && questionTextValid(reason)) { "An approval must show its complete command and reason." }
        require(RuntimeApprovalAction.Deny in actions && actions == actions.distinct().sortedBy { it.ordinal } &&
            (RuntimeApprovalAction.Once in actions || actions == listOf(RuntimeApprovalAction.Deny))) { "Invalid approval actions." }
        require(RuntimeApprovalAction.Always !in actions || RuntimeApprovalAction.Session in actions) { "Always allow requires the session scope." }
        require(!smartDenied || actions.all { it == RuntimeApprovalAction.Once || it == RuntimeApprovalAction.Deny }) { "Only a one-time approval can override a smart denial." }
    }

    internal fun jsonText(): String = "{\"command\":${jsonString(command)},\"reason\":${jsonString(reason)},\"actions\":[${actions.joinToString(",") { jsonString(it.wire) }}],\"smart_denied\":$smartDenied}"
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
            return RuntimeApprovalDefinition(command, reason, actions, smartDenied).also { it.validate() }
        }
    }
}
