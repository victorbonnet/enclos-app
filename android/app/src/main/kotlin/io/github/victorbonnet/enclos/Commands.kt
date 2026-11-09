package io.github.victorbonnet.enclos

/**
 * A command the person may type to an agent's runtime, as its plugin
 * listed it: `/` and [name], then what [args] says (`[name]`, `<prompt>`).
 */
data class AgentCommand(val name: String, val description: String, val args: String?)

/**
 * The commands to offer while the person types [draft]: while it is `/`
 * and part of a name (no space yet), those whose name starts with that
 * part, then those that have it elsewhere, whatever the case, each in its
 * list order. Nothing once a space follows the name, or for anything else.
 */
fun suggest(commands: List<AgentCommand>, draft: String): List<AgentCommand> {
    if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
    val typed = draft.drop(1).lowercase()
    val starting = commands.filter { it.name.lowercase().startsWith(typed) }
    val within = commands.filter { typed.isNotEmpty() && typed in it.name.lowercase() && it !in starting }
    return starting + within
}

/** The `@` word being typed at the end of [draft], or none. */
private fun mentionWord(draft: String): String? =
    draft.split(' ', '\n').last().takeIf { it.startsWith("@") }

/**
 * The members to offer while the person types an `@` word at the end of
 * [draft] in a group: those whose handle starts with what follows the
 * `@`, whatever the case, in the group's order, then `all` if it matches.
 * Nothing otherwise.
 */
fun mentionsFor(handles: List<String>, draft: String, label: (String) -> String = { it }): List<String> {
    val typed = fold(mentionWord(draft)?.drop(1) ?: return emptyList())
    return (handles + "all").distinct().filter { h -> fold(h).startsWith(typed) || (h != "all" && fold(label(h)).split(' ').any { it.startsWith(typed) }) }
}

/** What picking [handle] makes of [draft]: its `@` word replaced with `@handle` and a space. */
fun mentioned(draft: String, handle: String): String {
    val word = mentionWord(draft) ?: return "$draft@$handle "
    return draft.dropLast(word.length) + "@$handle "
}

/** What picking [command] puts in the field: `/` and its name, and a space for what follows. */
fun picked(command: AgentCommand): String = "/${command.name} "
