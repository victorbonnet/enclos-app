package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedAgentConversationsTest {
    private val main = Agent("mac", "lab/main", "hermes", emptyList())
    private val coder = Agent("mac", "lab/coder", "hermes", emptyList())
    private val director = Agent("mac", "lab/director", "hermes", emptyList())
    private val piMain = main.copy(host = "pi")
    private val home = Conversation("mac", "lab/main", "home-main", home = true)
    private val history = listOf(Line(1, "person", "hey", "message", 1))

    @Test
    fun removing_agents_hides_their_home_and_saved_conversations_in_both_inbox_views_and_search() {
        val saved = listOf(home,
            Conversation("mac", "lab/main", "main-chat"),
            Conversation("mac", "lab/coder", "coder-chat"),
            Conversation("mac", "lab/coder", "coder-archived", archived = true),
            Conversation("mac", "lab/director", "director-chat"),
            Conversation("pi", "lab/main", "pi-chat"))
        val removed = setOf(AgentKey("mac", "lab/main"), AgentKey("mac", "lab/coder"))
        fun visible(agents: List<Agent>) = saved.filter { listedWith(it, history, agents, AgentKey(it.host, it.agent) in removed) }
        assertEquals(saved, visible(listOf(main, coder, director, piMain)))

        val remaining = visible(listOf(director, piMain))
        assertEquals(listOf("director-chat", "pi-chat"), remaining.map { it.id })
        assertEquals(emptyList<Conversation>(), inboxConversations(remaining, archived = true))
        assertEquals(emptyList<Found>(), search(remaining, { history }, { 1 }, "coder", InboxFilter.All))
        assertEquals(listOf("mac/lab/director", "pi/lab/main"),
            byAgent(remaining, remaining.map { Found(it, null) }, emptySet(), { 1 }, false).map { it.address })
        // Visibility changes neither the saved metadata nor its messages.
        assertEquals(6, saved.size)
        assertEquals("hey", history.single().text)
        assertEquals(saved, visible(listOf(main, coder, director, piMain)))
    }

    @Test
    fun missing_channels_tokens_and_kept_data_do_not_hide_saved_history() {
        assertTrue(listedWith(home, emptyList(), listOf(main)))
        assertTrue(listedWith(home, history, emptyList()))
        assertTrue(listedWith(home, history, listOf(piMain)))
        val direct = home.copy(id = "ordinary", home = false, archived = true)
        assertTrue(listedWith(direct, history, emptyList()))
        assertFalse(listedWith(home, history, emptyList(), confirmedRemoved = true))
        // A fresh served inventory overrides a removal confirmation from before recreation.
        assertTrue(listedWith(home, history, listOf(main), confirmedRemoved = true))
    }

    @Test
    fun removing_a_group_member_does_not_hide_the_group() {
        val group = Conversation("mac", "lab", "team", members = listOf("lab/main", "lab/director"))
        assertTrue(listedWith(group, history, listOf(director)))
    }
}
