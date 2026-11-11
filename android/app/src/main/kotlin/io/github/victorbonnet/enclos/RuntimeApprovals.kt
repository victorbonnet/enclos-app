package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject

/** These are native runtime scopes; they never change sandbox policy. */
enum class RuntimeApprovalAction(val wire: String, val label: String, val result: String) {
    Once("once", "Allow once", "Allowed once"),
    Session("session", "Allow for session", "Allowed for session"),
    Always("always", "Always allow", "Allowed always"),
    Deny("deny", "Deny", "Denied"),
}

/** At most this many runtime patterns name what a session or always grant covers. */
internal const val MAX_APPROVAL_PATTERNS = 16

data class RuntimeApprovalDefinition(
    val command: String,
    val reason: String,
    val actions: List<RuntimeApprovalAction>,
    val smartDenied: Boolean = false,
    /** The runtime's names of what a session or always grant covers: every command matching one, not this one alone. */
    val patterns: List<String> = emptyList(),
) {
    /** Whether an offered action grants more than this one command. */
    val grantsPatterns: Boolean get() = RuntimeApprovalAction.Session in actions || RuntimeApprovalAction.Always in actions

    /** The command as it is shown: whole, with each hidden character written out. */
    val shownCommand: String get() = Visible.revealed(command)

    /** Whether the command holds a character outside ASCII, which may look like another. */
    val outsideAscii: Boolean get() = command.any { it.code > 0x7F }

    fun validate() {
        require(questionTextValid(command) && questionTextValid(reason)) { "An approval must show its complete command and reason." }
        require(shownCommand.toByteArray(Charsets.UTF_8).size <= 24 * 1024) { "The command is too long to show in full." }
        require(RuntimeApprovalAction.Deny in actions && actions == actions.distinct().sortedBy { it.ordinal } &&
            (RuntimeApprovalAction.Once in actions || actions == listOf(RuntimeApprovalAction.Deny))) { "Invalid approval actions." }
        require(RuntimeApprovalAction.Always !in actions || RuntimeApprovalAction.Session in actions) { "Always allow requires the session scope." }
        require(!smartDenied || actions.all { it == RuntimeApprovalAction.Once || it == RuntimeApprovalAction.Deny }) { "Only a one-time approval can override a smart denial." }
        require(patterns.size <= MAX_APPROVAL_PATTERNS && patterns.distinct().size == patterns.size &&
            patterns.all { questionTextValid(it) && !Visible.hasHidden(it) }) { "Invalid approval patterns." }
        require(!grantsPatterns || patterns.isNotEmpty()) { "A session or always grant must name the patterns it covers." }
    }

    internal fun jsonText(): String = "{\"command\":${jsonString(command)},\"reason\":${jsonString(reason)},\"actions\":[${actions.joinToString(",") { jsonString(it.wire) }}],\"smart_denied\":$smartDenied" +
        (if (patterns.isEmpty()) "" else ",\"patterns\":[${patterns.joinToString(",") { jsonString(it) }}]") + "}"
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
            val patterns = if (json.has("patterns") && !json.isNull("patterns")) strings(json.getJSONArray("patterns")) else emptyList()
            return RuntimeApprovalDefinition(command, reason, actions, smartDenied, patterns).also { it.validate() }
        }

        private fun strings(list: JSONArray): List<String> = (0 until list.length()).map { index ->
            list.get(index) as? String ?: throw IllegalArgumentException("Invalid approval pattern")
        }
    }
}
