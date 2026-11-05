package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.ui.Markdown
import java.text.Normalizer

/** Which conversations the inbox shows, besides what is searched. */
sealed interface InboxFilter {
    data object All : InboxFilter
    data object Unread : InboxFilter
    /** One agent's, by its address `host/sandbox/agent`. */
    data class OfAgent(val address: String) : InboxFilter
}

/** A conversation the inbox shows, and the message that matched, when only a message did. */
data class Found(val conversation: Conversation, val match: Line?)

/**
 * The inbox's search: a conversation matches when every word of [query]
 * is in its name, its agent's address, or one of its messages (the
 * agent's without their markdown), whatever the case and accents. A
 * blank query matches every conversation [filter] lets through. Order is
 * kept.
 */
fun search(
    conversations: List<Conversation>,
    lines: (String) -> List<Line>,
    unread: (String) -> Int,
    query: String,
    filter: InboxFilter,
): List<Found> {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    return conversations.mapNotNull { c ->
        when (filter) {
            InboxFilter.All -> Unit
            InboxFilter.Unread -> if (unread(c.id) == 0) return@mapNotNull null
            is InboxFilter.OfAgent -> if ("${c.host}/${c.agent}" != filter.address) return@mapNotNull null
        }
        if (words.isEmpty()) return@mapNotNull Found(c, null)
        val about = fold("${c.title.orEmpty()} ${c.host}/${c.agent}")
        val said = lines(c.id).asReversed().map { it to fold(words(it)) }
        val all = (listOf(about) + said.map { it.second }).joinToString(" ")
        if (!words.all { it in all }) return@mapNotNull null
        // Found by its name or agent: the row as usual; else the newest
        // message that holds the most of the words.
        if (words.all { it in about }) return@mapNotNull Found(c, null)
        val best = said.maxByOrNull { (_, text) -> words.count { it in text } }
        Found(c, best?.first)
    }
}

/** An agent's conversations in the inbox, grouped: its address `host/sandbox/agent`, and what was found of them. */
data class Group(val address: String, val found: List<Found>)

/**
 * [found] by agent: each agent once, where its first conversation was (so
 * the agent with the newest conversation first), its conversations in the
 * order they were.
 */
fun byAgent(found: List<Found>): List<Group> =
    found.groupBy { "${it.conversation.host}/${it.conversation.agent}" }.map { (address, its) -> Group(address, its) }

/**
 * A short label for each agent address `host/sandbox/agent`: its name, or
 * `sandbox/agent` when another agent has the same name, or the whole
 * address when another host has the same sandbox and agent too.
 */
fun agentLabels(addresses: List<String>): Map<String, String> = addresses.associateWith { a ->
    val name = a.substringAfterLast('/')
    val inSandbox = a.substringAfter('/')
    when {
        addresses.count { it.substringAfterLast('/') == name } == 1 -> name
        addresses.count { it.substringAfter('/') == inSandbox } == 1 -> inSandbox
        else -> a
    }
}

/** A message's words: the agent's without the marks of its markdown; a file alone, by what it is. */
fun words(line: Line): String {
    val text = if (line.from == "agent") Markdown.plain(line.text) else line.text
    if (text.isNotBlank() || line.attachments.isEmpty()) return text
    return line.attachments.joinToString(", ") { if (it.image) "Image" else it.name }
}

/** [text] in lower case, without accents, its spaces single: what search compares. */
fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
