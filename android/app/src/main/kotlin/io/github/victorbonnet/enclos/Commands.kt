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
 * Each name once (the first), as the list shown is keyed by name.
 */
fun suggest(commands: List<AgentCommand>, draft: String): List<AgentCommand> {
    if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
    val typed = draft.drop(1).lowercase()
    val starting = commands.distinctBy { it.name }.filter { it.name.lowercase().startsWith(typed) }
    val within = commands.distinctBy { it.name }.filter { typed.isNotEmpty() && typed in it.name.lowercase() && starting.none { s -> s.name == it.name } }
    return starting + within
}

/** What picking [command] puts in the field: `/` and its name, and a space for what follows. */
fun picked(command: AgentCommand): String = "/${command.name} "
