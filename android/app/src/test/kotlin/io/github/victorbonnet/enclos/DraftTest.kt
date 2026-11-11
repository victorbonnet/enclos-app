package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftTest {
    private val draft = Conversation("mac", "lab/main", "c-1")

    @Test
    fun a_drafts_first_message_starts_it_on_the_bridge_first() {
        assertEquals(
            listOf(
                Outgoing("conversation.new", listOf("conversation" to "c-1", "agent" to "lab/main")),
                Outgoing("message.send", listOf("conversation" to "c-1", "text" to "hi")),
            ),
            firstSent(draft, draft = true, text = "hi"),
        )
    }

    @Test
    fun a_drafts_name_goes_right_after_it_starts() {
        val named = draft.copy(title = "Weather", titledBy = "person")
        assertEquals(
            listOf("conversation.new", "conversation.rename", "message.send"),
            firstSent(named, draft = true, text = "hi").map { it.type },
        )
        assertEquals("title" to "Weather", firstSent(named, draft = true, text = "hi")[1].fields[1])
    }

    @Test
    fun a_name_the_agent_gave_is_not_sent_back() {
        val named = draft.copy(title = "Weather", titledBy = "agent")
        assertEquals(listOf("conversation.new", "message.send"), firstSent(named, draft = true, text = "hi").map { it.type })
    }

    @Test
    fun a_conversation_the_bridge_knows_gets_the_message_only() {
        val named = draft.copy(title = "Weather", titledBy = "person")
        assertEquals(listOf("message.send"), firstSent(named, draft = false, text = "hi").map { it.type })
    }
}
