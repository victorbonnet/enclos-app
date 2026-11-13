package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTest {
    private val weather = Conversation("mac", "lab/main", "c-1", title = "Weather in Lyon", titledBy = "agent")
    private val code = Conversation("mac", "lab/coder", "c-2")
    private val pi = Conversation("pi", "home/main", "c-3")
    private val all = listOf(weather, code, pi)
    private val lines = mapOf(
        "c-1" to listOf(Line(1, "victor", "Météo à Lyon demain ?", "message", 0), Line(2, "agent", "**14°**, light rain.", "reply", 0)),
        "c-2" to listOf(Line(3, "victor", "Fix the failing test", "message", 0), Line(4, "agent", "Done: `412 tests` pass.", "reply", 0)),
        "c-3" to emptyList(),
    )
    private val unread = mapOf("c-1" to 0, "c-2" to 1, "c-3" to 0)

    private fun find(query: String, filter: InboxFilter = InboxFilter.All) =
        search(all, { lines[it].orEmpty() }, { unread[it] ?: 0 }, query, filter)

    @Test
    fun a_blank_query_shows_every_conversation_in_order() {
        assertEquals(listOf("c-1", "c-2", "c-3"), find("  ").map { it.conversation.id })
    }

    @Test
    fun the_name_and_the_agent_match_without_a_message() {
        assertEquals(listOf(Found(weather, null)), find("weather lyon"))
        assertEquals(listOf("c-2"), find("CODER").map { it.conversation.id })
        assertEquals(listOf("c-3"), find("pi/home").map { it.conversation.id })
    }

    @Test
    fun a_message_matches_without_accents_or_marks_and_is_shown() {
        val found = find("meteo")
        assertEquals(listOf("c-1"), found.map { it.conversation.id })
        assertEquals(1L, found.single().match?.seq)
        // The agent's markdown is searched as its words: `412 tests` and **14°**.
        assertEquals(4L, find("412 tests").single().match?.seq)
        assertEquals(2L, find("14° rain").single().match?.seq)
    }

    @Test
    fun every_word_must_be_found() {
        assertEquals(emptyList<Found>(), find("weather paris"))
    }

    @Test
    fun the_unread_filter_keeps_conversations_with_something_unread() {
        assertEquals(listOf("c-2"), find("", InboxFilter.Unread).map { it.conversation.id })
        assertNull(find("rain", InboxFilter.Unread).firstOrNull())
    }

    private val home = Conversation("mac", "lab/main", "h-1", title = "Home", home = true)
    private val again = Conversation("mac", "lab/main", "c-4")
    /** Newest first: coder's, then main's (its Home among them), then pi's. */
    private val sorted = listOf(code, weather, home, pi, again)
    private val unreadToo = mapOf("c-4" to 2)
    private fun entries(expanded: Set<String> = emptySet(), found: List<Found> = sorted.map { Found(it, null) }, narrowed: Boolean = false) =
        byAgent(sorted, found, expanded, { unreadToo[it] ?: 0 }, narrowed)

    @Test
    fun each_agent_once_in_the_order_of_its_newest_conversation_its_home_on_its_row() {
        val agents = entries()
        assertEquals(listOf("mac/lab/coder", "mac/lab/main", "pi/home/main"), agents.map { it.address })
        assertEquals("h-1", agents[1].home?.id)
        assertNull(agents[0].home)
        assertEquals(listOf(1, 2, 1), agents.map { it.others })
    }

    @Test
    fun highlights_follow_the_search_rules_on_the_original_text() {
        // Accents and case, as search ignores them.
        assertEquals(listOf(0..4), matches("Météo à Lyon demain ?", "meteo"))
        assertEquals(listOf(0..4, 8..11), matches("Météo à Lyon", "METEO lyon"))
        // An accent written as its own mark is folded away, and highlighted with its letter.
        assertEquals(listOf(0..6), matches("Météo", "meteo"))
        // Spaces collapse as search collapses them.
        assertEquals(listOf(7..11), matches("rain   light rain", "light"))
        assertEquals(emptyList<IntRange>(), matches("Météo", "  "))
        assertEquals(emptyList<IntRange>(), matches("Météo", "paris"))
    }

    @Test
    fun the_words_may_be_in_different_messages_and_the_row_shows_the_newest() {
        // "failing" is in the person's message, "412" in the agent's answer.
        val found = find("failing 412").single()
        assertEquals("c-2", found.conversation.id)
        assertEquals(4L, found.match?.seq)
    }

    @Test
    fun each_message_is_folded_once_however_often_it_is_searched() {
        var folded = 0
        val folds = Folds { folded++; fold(words(it)) }
        repeat(3) { search(all, { lines[it].orEmpty() }, { 0 }, "rain", InboxFilter.All, folds::of) }
        assertEquals(4, folded)
        assertEquals(2L, search(all, { lines[it].orEmpty() }, { 0 }, "rain", InboxFilter.All, folds::of).single().match?.seq)
    }

    @Test
    fun an_agent_shows_its_newest_conversations_and_older_unread_ones_expanded_all() {
        // Eight of main's, newest first; o-8, the oldest, has something unread.
        val many = (1..8).map { Conversation("mac", "lab/main", "o-$it") }
        val list = listOf(home) + many
        val unread = mapOf("o-8" to 1)
        val entry = { expanded: Set<String> ->
            byAgent(list, list.map { Found(it, null) }, expanded, { unread[it] ?: 0 }, narrowed = false).single()
        }
        assertEquals(listOf("o-1", "o-2", "o-3", "o-4", "o-5", "o-8"), entry(emptySet()).under.map { it.conversation.id })
        assertEquals(8, entry(emptySet()).others)
        assertEquals(many.map { it.id }, entry(setOf("mac/lab/main")).under.map { it.conversation.id })
    }

    @Test
    fun narrowed_only_agents_with_something_found_and_all_of_it_shows() {
        val said = Line(9, "agent", "rain at noon", "notify", 0)
        val found = listOf(Found(home, said), Found(weather, null))
        val agents = entries(found = found, narrowed = true)
        assertEquals(listOf("mac/lab/main"), agents.map { it.address })
        assertEquals(said, agents[0].homeMatch)
        assertEquals(listOf("c-1"), agents[0].under.map { it.conversation.id })
    }

    @Test
    fun archive_partitions_direct_group_and_home_without_dropping_history() {
        val keptHome = home.copy(archived = true)
        val keptDirect = weather.copy(archived = true)
        val keptGroup = Conversation("mac", "lab", "g-1", title = "Research", members = listOf("lab/main", "lab/coder"), archived = true)
        val conversations = listOf(keptHome, code, keptGroup, keptDirect)
        assertEquals(listOf(code), inboxConversations(conversations))
        assertEquals(listOf(keptHome, keptGroup, keptDirect), inboxConversations(conversations, archived = true))
        // A later answer stays searchable in Archived and never puts the conversation back in Active.
        val incoming = lines + (keptDirect.id to (lines[keptDirect.id].orEmpty() + Line(9, "agent", "Snow tomorrow", "notify", 99)))
        val active = search(conversations, { incoming[it].orEmpty() }, { unread[it] ?: 0 }, "snow", InboxFilter.All)
        val archived = search(conversations, { incoming[it].orEmpty() }, { unread[it] ?: 0 }, "snow", InboxFilter.All, archived = true)
        assertEquals(emptyList<Found>(), active)
        assertEquals(listOf(keptDirect.id), archived.map { it.conversation.id })
        assertEquals(9L, archived.single().match?.seq)
        // Unarchive restores the original identity and the same messages, without a new conversation.
        val restored = conversations.map { if (it.id == keptDirect.id) it.copy(archived = false) else it }
        assertEquals(listOf(keptDirect.id), search(restored, { incoming[it].orEmpty() }, { 0 }, "snow", InboxFilter.All).map { it.conversation.id })
    }

    @Test
    fun archived_unread_is_kept_but_does_not_enter_the_active_inbox_totals() {
        val archived = code.copy(archived = true)
        val conversations = listOf(weather, archived)
        assertEquals(0, inboxConversations(conversations).sumOf { unread[it.id] ?: 0 })
        assertEquals(1, inboxConversations(conversations, archived = true).sumOf { unread[it.id] ?: 0 })
        assertEquals(emptyList<Found>(), search(conversations, { lines[it].orEmpty() }, { unread[it] ?: 0 }, "", InboxFilter.Unread))
        assertEquals(listOf(archived.id), search(conversations, { lines[it].orEmpty() }, { unread[it] ?: 0 }, "", InboxFilter.Unread, archived = true).map { it.conversation.id })
    }

    @Test
    fun archived_children_never_open_an_active_home_and_all_remain_reachable() {
        val children = (1..8).map { Conversation("mac", "lab/main", "saved-$it", archived = true) }
        val stored = listOf(home) + children
        val archived = inboxConversations(stored, archived = true)
        val entries = byAgent(archived, archived.map { Found(it, null) }, emptySet(), { 0 }, narrowed = true)
        assertNull(entries.single().home)
        assertEquals(children.map { it.id }, entries.single().under.map { it.conversation.id })
        val savedHome = home.copy(archived = true)
        val withHome = listOf(savedHome) + archived
        assertEquals(savedHome, byAgent(withHome, withHome.map { Found(it, null) }, emptySet(), { 0 }, narrowed = true).single().home)
    }
}
