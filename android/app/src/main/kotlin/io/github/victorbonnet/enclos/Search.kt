package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.ui.Markdown
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Which conversations the inbox shows, besides what is searched. */
sealed interface InboxFilter {
    data object All : InboxFilter
    data object Unread : InboxFilter
}

/** A conversation the inbox shows, and the message that matched, when only a message did. */
data class Found(val conversation: Conversation, val match: Line?)

/** The inbox's two independent, restorable views; neither drops any history or read state. */
fun inboxConversations(conversations: List<Conversation>, archived: Boolean = false): List<Conversation> =
    conversations.filter { it.archived == archived }

/**
 * The inbox's search: a conversation matches when every word of [query]
 * is in its name, its agent's address, or one of its messages (the
 * agent's without their markdown), whatever the case and accents. The
 * words may be in different messages: the conversation is what is found,
 * and the row shows the message that holds the most of them. A blank
 * query matches every conversation [filter] lets through. Order is kept.
 * [folded] gives a message's folded words (see [Folds]).
 */
fun search(
    conversations: List<Conversation>,
    lines: (String) -> List<Line>,
    unread: (String) -> Int,
    query: String,
    filter: InboxFilter,
    folded: (String, Line) -> String = { _, line -> fold(words(line)) },
    archived: Boolean = false,
    agentNames: (Conversation) -> String = { "" },
): List<Found> {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    return inboxConversations(conversations, archived).mapNotNull { c ->
        when (filter) {
            InboxFilter.All -> Unit
            InboxFilter.Unread -> if (unread(c.id) == 0) return@mapNotNull null
        }
        if (words.isEmpty()) return@mapNotNull Found(c, null)
        val about = fold("${c.title.orEmpty()} ${c.host}/${c.agent} ${c.members.joinToString(" ")} ${agentNames(c)}")
        val said = lines(c.id).asReversed().map { it to folded(c.id, it) }
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
 * Each message's folded words, worked out once: an agent's message is
 * parsed as markdown, which costs, and search reads every message at each
 * keystroke. By conversation and `seq`, which a message keeps; safe from
 * any thread.
 */
class Folds(private val folding: (Line) -> String = { fold(words(it)) }) {
    private val done = ConcurrentHashMap<Pair<String, Long>, String>()

    fun of(conversation: String, line: Line): String = done.getOrPut(conversation to line.seq) { folding(line) }
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
    /** Its photo's key, from its conversations' host and agent. */
    val photo: PhotoKey? = null,
)

private val Conversation.address get() = "$host/$agent"

/** How many of an agent's conversations show under it, Home aside, before "more". */
const val RECENT = 5

/**
 * The inbox by agent. [sorted] is every listed conversation, the newest
 * first; [found] what the search and filter let through, in that order.
 * Each agent once, the one with the newest conversation first. Under it,
 * newest first, its conversations other than Home: every one while it is
 * in [expanded], else the [RECENT] newest and any older one with something
 * [unread]. While [narrowed] (a search or a filter), an agent shows only if
 * something of it was found, and everything found shows.
 */
fun byAgent(
    sorted: List<Conversation>,
    found: List<Found>,
    expanded: Set<String>,
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
            others.filterIndexed { i, c -> address in expanded || i < RECENT || unread(c.id) > 0 }.map { Found(it, null) }
        }
        val homeMatch = found.firstOrNull { it.conversation.id == home?.id }?.match
        val first = its.firstOrNull() ?: found.first { it.conversation.address == address }.conversation
        AgentEntry(address, home, homeMatch, under, others.size, PhotoKey(first.host, first.agent))
    }
}

/** A message's words: the agent's without the marks of its markdown; a file alone, by what it is. */
fun words(line: Line): String {
    val text = if (line.from == "agent") Markdown.plain(line.text) else line.text
    if (text.isNotBlank() || line.attachments.isEmpty()) return text
    return line.attachments.joinToString(", ") { if (it.image) "Image" else it.shownName }
}

/** [text] in lower case, without accents, its spaces single: what search compares. */
fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Where the words of [query] are in [text], by the rules [search] uses
 * ([fold]: case, accents, spaces), as ranges of [text]'s own characters, to
 * highlight. [text] is folded one character at a time, each folded character
 * keeping where it came from, since folding changes the length (an accent
 * written as its own mark goes, `İ` lowercases to two characters).
 */
fun matches(text: String, query: String): List<IntRange> {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val folded = StringBuilder()
    // For each folded character: where its source starts, and where it ends.
    val starts = mutableListOf<Int>()
    val ends = mutableListOf<Int>()
    var i = 0
    while (i < text.length) {
        val point = text.codePointAt(i)
        val next = i + Character.charCount(point)
        if (Character.isWhitespace(point)) {
            if (folded.isNotEmpty() && folded.last() != ' ') {
                folded.append(' ')
                starts += i
                ends += next
            }
        } else {
            val piece = fold(text.substring(i, next))
            if (piece.isEmpty() && ends.isNotEmpty()) {
                // A mark on its own: part of the character before it.
                ends[ends.size - 1] = next
            }
            piece.forEach {
                folded.append(it)
                starts += i
                ends += next
            }
        }
        i = next
    }
    val found = mutableListOf<IntRange>()
    for (word in words) {
        var at = folded.indexOf(word)
        while (at >= 0) {
            found += starts[at] until ends[at + word.length - 1]
            at = folded.indexOf(word, at + word.length)
        }
    }
    return found
}
