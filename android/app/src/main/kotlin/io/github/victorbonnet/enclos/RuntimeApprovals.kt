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

    /** The runtime's explanation as shown: hidden characters written out, so it cannot read as something else. */
    val shownDescription: String get() = Visible.revealed(description)

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

/** At most this many runtime patterns name what a session or always grant covers. */
internal const val MAX_APPROVAL_PATTERNS = 16

data class RuntimeApprovalDefinition(
    val command: String,
    val reason: String,
    val actions: List<RuntimeApprovalAction>,
    val smartDenied: Boolean = false,
    val scopes: RuntimeApprovalScopes? = null,
    /** The runtime's names of what a session or always grant covers: every command matching one, not this one alone. */
    val patterns: List<String> = emptyList(),
) {
    /** Whether an offered action grants more than this one command. */
    val grantsPatterns: Boolean get() = RuntimeApprovalAction.Session in actions || RuntimeApprovalAction.Always in actions

    /**
     * The patterns rule: without [scopes], a Session or Always grant must name the patterns it
     * covers; with [scopes], the runtime's description stands in for them, and patterns, when
     * sent, are checked the same way but not needed.
     */
    fun validate() {
        require(questionTextValid(command) && questionTextValid(reason)) { "An approval must show its complete command and reason." }
        require(RuntimeApprovalAction.Deny in actions && actions == actions.distinct().sortedBy { it.ordinal } &&
            (RuntimeApprovalAction.Once in actions || actions == listOf(RuntimeApprovalAction.Deny))) { "Invalid approval actions." }
        scopes?.validate()
        require(scopes != null || RuntimeApprovalAction.Always !in actions || RuntimeApprovalAction.Session in actions) { "Legacy always allow requires the session scope." }
        require(!smartDenied || actions.all { it == RuntimeApprovalAction.Once || it == RuntimeApprovalAction.Deny }) { "Only a one-time approval can override a smart denial." }
        require(patterns.size <= MAX_APPROVAL_PATTERNS && patterns.distinct().size == patterns.size &&
            patterns.all { questionTextValid(it) && !Visible.hasHidden(it) }) { "Invalid approval patterns." }
        require(scopes != null || !grantsPatterns || patterns.isNotEmpty()) { "A session or always grant must name the patterns it covers." }
    }

    internal fun jsonText(): String = "{\"command\":${jsonString(command)},\"reason\":${jsonString(reason)},\"actions\":[${actions.joinToString(",") { jsonString(it.wire) }}],\"smart_denied\":$smartDenied" +
        (if (patterns.isEmpty()) "" else ",\"patterns\":[${patterns.joinToString(",") { jsonString(it) }}]") +
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
            val patterns = if (json.has("patterns") && !json.isNull("patterns")) json.getJSONArray("patterns").let { list ->
                (0 until list.length()).map { index -> list.get(index) as? String ?: throw IllegalArgumentException("Invalid approval pattern") }
            } else emptyList()
            return RuntimeApprovalDefinition(command, reason, actions, smartDenied, scopes, patterns).also { it.validate() }
        }
    }
}
