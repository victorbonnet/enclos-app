package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.ui.Markdown
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

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
