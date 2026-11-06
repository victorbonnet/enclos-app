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
    fun filters_keep_unread_or_one_agents_conversations() {
        assertEquals(listOf("c-2"), find("", InboxFilter.Unread).map { it.conversation.id })
        assertEquals(listOf("c-3"), find("", InboxFilter.OfAgent("pi/home/main")).map { it.conversation.id })
        assertNull(find("test", InboxFilter.OfAgent("mac/lab/main")).firstOrNull())
    }

    private val home = Conversation("mac", "lab/main", "h-1", title = "Home", home = true)
    private val again = Conversation("mac", "lab/main", "c-4")
    /** Newest first: coder's, then main's (its Home among them), then pi's. */
    private val sorted = listOf(code, weather, home, pi, again)
    private val unreadToo = mapOf("c-4" to 2)
    private fun entries(unfolded: Set<String> = emptySet(), found: List<Found> = sorted.map { Found(it, null) }, narrowed: Boolean = false) =
        byAgent(sorted, found, unfolded, { unreadToo[it] ?: 0 }, narrowed)

    @Test
    fun each_agent_once_in_the_order_of_its_newest_conversation_its_home_on_its_row() {
        val agents = entries()
        assertEquals(listOf("mac/lab/coder", "mac/lab/main", "pi/home/main"), agents.map { it.address })
        assertEquals("h-1", agents[1].home?.id)
        assertNull(agents[0].home)
        assertEquals(listOf(1, 2, 1), agents.map { it.others })
    }

    @Test
    fun folded_an_agent_shows_only_its_unread_conversations_unfolded_all_newest_first() {
        assertEquals(listOf("c-4"), entries()[1].under.map { it.conversation.id })
        assertEquals(emptyList<String>(), entries()[0].under.map { it.conversation.id })
        val open = entries(unfolded = setOf("mac/lab/main"))[1]
        assertEquals(listOf("c-1", "c-4"), open.under.map { it.conversation.id })
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
    fun an_agent_is_labelled_by_the_shortest_of_its_names_no_other_has() {
        val labels = agentLabels(listOf("mac/lab/coder", "mac/lab/main", "mac/home/main", "pi/home/main"))
        assertEquals("coder", labels["mac/lab/coder"])
        assertEquals("lab/main", labels["mac/lab/main"])
        assertEquals("mac/home/main", labels["mac/home/main"])
        assertEquals("pi/home/main", labels["pi/home/main"])
    }
}
