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

/** Results kept on screen while the next background search completes. */
fun inboxSearchResults(query: String, listed: List<Found>, searched: List<Found>?): List<Found> {
    if (query.isBlank()) return listed
    val keys = listed.mapTo(HashSet()) { ConversationKey(it.conversation.host, it.conversation.id) }
    return searched.orEmpty().filter { ConversationKey(it.conversation.host, it.conversation.id) in keys }
}

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
 * Agents and conversations raised to the top of the Inbox's Agents section by
 * queued or running tasks, keyed by each agent's address (`host/sandbox/agent`)
 * and, within each agent, by conversation id, each mapped to its newest task's
 * start time (`AgentTask.since`).
 */
data class Raised(
    val agents: Map<String, Long> = emptyMap(),
    val conversations: Map<String, Map<String, Long>> = emptyMap(),
) {
    companion object {
        val None = Raised()
    }
}

/**
 * Builds the [Raised] set for [addresses] from each agent's raising tasks.
 * Only tasks in `queued` or `running` raise; each agent and each named
 * conversation takes the newest [AgentTask.since] among its raising tasks.
 */
fun raisedBy(addresses: Iterable<String>, tasks: (String) -> List<AgentTask>): Raised {
    val agents = LinkedHashMap<String, Long>()
    val conversations = LinkedHashMap<String, MutableMap<String, Long>>()
    for (address in addresses) {
        val active = tasks(address).filter { it.state == TaskState.Queued || it.state == TaskState.Running }
        if (active.isEmpty()) continue
        agents[address] = active.maxOf { it.since }
        for (task in active) {
            val conversation = task.conversation ?: continue
            val map = conversations.getOrPut(address) { LinkedHashMap() }
            val current = map[conversation]
            if (current == null || task.since > current) map[conversation] = task.since
        }
    }
    return if (agents.isEmpty()) Raised.None else Raised(agents, conversations)
}

/**
 * The inbox by agent. [sorted] is every listed conversation, the newest
 * first; [found] what the search and filter let through, in that order.
 * Each agent once, [raised] agents first (newest task start, then newest
 * matching message via [lastAt], then address ascending), then the rest in
 * the order of their newest conversation. Under an agent, its [raised]
 * conversations come first (newest task start, then [lastAt]), then its
 * other non-Home conversations newest first: every one while it is in
 * [expanded], else the [RECENT] newest, any older one with something
 * [unread], and every raised one. While [narrowed] (a search or a filter),
 * an agent shows only if something of it was found, and everything found
 * shows, with [raised] reordering only what is already admitted.
 */
fun byAgent(
    sorted: List<Conversation>,
    found: List<Found>,
    expanded: Set<String>,
    unread: (String) -> Int,
    narrowed: Boolean,
    raised: Raised = Raised.None,
    lastAt: (Conversation) -> Long = { 0L },
): List<AgentEntry> {
    val all = sorted.groupBy { it.address }
    val foundByAgent = if (narrowed) found.groupBy { it.conversation.address } else emptyMap()
    val shown = (if (narrowed) found.map { it.conversation } else sorted).map { it.address }.distinct()
    val ordered = if (raised.agents.isEmpty()) {
        shown
    } else {
        val (up, rest) = shown.partition { it in raised.agents }
        val newestAt: (String) -> Long = { address ->
            val scope = if (narrowed) foundByAgent[address].orEmpty().map { it.conversation } else all[address].orEmpty()
            scope.maxOfOrNull(lastAt) ?: 0L
        }
        up.sortedWith(
            compareByDescending<String> { raised.agents.getValue(it) }
                .thenByDescending(newestAt)
                .thenBy { it },
        ) + rest
    }
    return ordered.map { address ->
        val its = all[address].orEmpty()
        val home = its.firstOrNull { it.home }
        val others = its.filter { !it.home }
        val raisedUnder = raised.conversations[address].orEmpty()
        val under = if (narrowed) {
            val matching = foundByAgent[address].orEmpty().filter { !it.conversation.home }
            if (raisedUnder.isEmpty()) {
                matching
            } else {
                val (up, rest) = matching.partition { it.conversation.id in raisedUnder }
                up.sortedWith(
                    compareByDescending<Found> { raisedUnder.getValue(it.conversation.id) }
                        .thenByDescending { lastAt(it.conversation) },
                ) + rest
            }
        } else {
            val visible = others.filterIndexed { i, c ->
                address in expanded || i < RECENT || unread(c.id) > 0 || c.id in raisedUnder
            }
            val orderedVisible = if (raisedUnder.isEmpty()) {
                visible
            } else {
                val (up, rest) = visible.partition { it.id in raisedUnder }
                up.sortedWith(
                    compareByDescending<Conversation> { raisedUnder.getValue(it.id) }
                        .thenByDescending(lastAt),
                ) + rest
            }
            orderedVisible.map { Found(it, null) }
        }
        val homeMatch = found.firstOrNull { it.conversation.id == home?.id }?.match
        val first = its.firstOrNull() ?: found.first { it.conversation.address == address }.conversation
        AgentEntry(address, home, homeMatch, under, others.size, PhotoKey(first.host, first.agent))
    }
}

/**
 * A message's words: the agent's without the marks of its markdown; a file
 * alone, by what it is; a task line's as it reads, its assignee by its handle.
 */
fun words(line: Line): String {
    line.taskEvent?.let { return taskEventText(it, it.assignee?.substringAfter('/')) }
    val text = if (line.from == "agent") Markdown.plain(line.text) else line.text
    if (text.isNotBlank() || line.attachments.isEmpty()) return text
    return line.attachments.joinToString(", ") { if (it.image) "Image" else it.shownName }
}

private val MARKS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")

/** [text] in lower case, without accents, its spaces single: what search compares. */
fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase()
        .replace(SPACES, " ")
        .trim()

private const val FOLD_CACHE_LIMIT = 4096

private class FoldedSpans(val folded: String, val starts: IntArray, val ends: IntArray)

private class FoldedText(private val source: String) {
    val plain: String = fold(source)
    val spans: FoldedSpans by lazy(LazyThreadSafetyMode.PUBLICATION) { buildSpans(source) }
}

/** Texts longer than this are folded each time, never kept. */
private const val FOLD_CACHE_TEXT_MAX = 8_192

/** The most characters of source text the cache keeps, over all its entries. */
private const val FOLD_CACHE_CHARS = 262_144

/** Least recently read first; guarded by itself. */
private val foldedCache = LinkedHashMap<String, FoldedText>(256, 0.75f, true)
private var foldedChars = 0

private fun foldedText(text: String): FoldedText {
    if (text.length > FOLD_CACHE_TEXT_MAX) return FoldedText(text)
    synchronized(foldedCache) { foldedCache[text]?.let { return it } }
    val made = FoldedText(text)
    synchronized(foldedCache) {
        foldedCache[text]?.let { return it }
        foldedCache[text] = made
        foldedChars += text.length
        val oldest = foldedCache.entries.iterator()
        while ((foldedCache.size > FOLD_CACHE_LIMIT || foldedChars > FOLD_CACHE_CHARS) && oldest.hasNext()) {
            val gone = oldest.next()
            foldedChars -= gone.key.length
            oldest.remove()
        }
    }
    return made
}

/** What the cache holds now: entries and the characters of their sources. */
internal fun foldCacheSize(): Pair<Int, Int> = synchronized(foldedCache) { foldedCache.size to foldedChars }

/** Cached [fold] for repeated matching against the same label or id. */
internal fun folded(text: String): String = foldedText(text).plain

private fun buildSpans(text: String): FoldedSpans {
    val folded = StringBuilder(text.length)
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
    return FoldedSpans(folded.toString(), starts.toIntArray(), ends.toIntArray())
}

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
    val spans = foldedText(text).spans
    val found = mutableListOf<IntRange>()
    for (word in words) {
        var at = spans.folded.indexOf(word)
        while (at >= 0) {
            found += spans.starts[at] until spans.ends[at + word.length - 1]
            at = spans.folded.indexOf(word, at + word.length)
        }
    }
    return found
}
