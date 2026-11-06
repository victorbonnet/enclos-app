package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.ui.Markdown
import java.text.Normalizer

/** Which conversations the inbox shows, besides what is searched. */
sealed interface InboxFilter {
    data object All : InboxFilter
    data object Unread : InboxFilter
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

/**
 * An agent in the inbox, by its address `host/sandbox/agent`: its [home]
 * (which its row opens) and the message of it a search found, the
 * conversations listed [under] it, and how many [others] it has besides
 * its Home, folded or not.
 */
data class AgentEntry(
    val address: String,
    val home: Conversation?,
    val homeMatch: Line?,
    val under: List<Found>,
    val others: Int,
)

private val Conversation.address get() = "$host/$agent"

/**
 * The inbox by agent. [sorted] is every listed conversation, the newest
 * first; [found] what the search and filter let through, in that order.
 * Each agent once, the one with the newest conversation first. Under it,
 * newest first, its conversations other than Home: every one while it is
 * in [unfolded], else only those with something [unread]. While [narrowed]
 * (a search or a filter), an agent shows only if something of it was
 * found, and everything found shows, folded or not.
 */
fun byAgent(
    sorted: List<Conversation>,
    found: List<Found>,
    unfolded: Set<String>,
    unread: (String) -> Int,
    narrowed: Boolean,
): List<AgentEntry> {
    val all = sorted.groupBy { it.address }
    val shown = (if (narrowed) found.map { it.conversation } else sorted).map { it.address }.distinct()
    return shown.map { address ->
        val its = all[address].orEmpty()
        val home = its.firstOrNull { it.home }
        val others = its.filter { !it.home }
        val under = if (narrowed) {
            found.filter { it.conversation.address == address && !it.conversation.home }
        } else {
            others.filter { address in unfolded || unread(it.id) > 0 }.map { Found(it, null) }
        }
        val homeMatch = found.firstOrNull { it.conversation.id == home?.id }?.match
        AgentEntry(address, home, homeMatch, under, others.size)
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
