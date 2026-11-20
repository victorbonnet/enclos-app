package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingTest {
    private fun written(text: String) = Outgoing().apply { write("c1", text) }

    @Test
    fun what_could_not_be_handed_to_the_connection_stays_in_the_field() {
        val out = written("hello")
        assertFalse(out.send("c1", "mac", "hello") { null })
        assertEquals("hello", out.unsent["c1"])
        assertNull(out.notSent["c1"])
    }

    @Test
    fun what_was_handed_over_leaves_the_field_and_is_sent_once_the_bridge_shows_it() {
        val out = written("hello")
        assertTrue(out.send("c1", "mac", "hello") { "m1" })
        assertNull(out.unsent["c1"])
        out.shown("c1", "hello")
        // Shown back: the connection ending after that puts nothing back.
        out.ended("mac", "the connection ended")
        assertNull(out.unsent["c1"])
        assertNull(out.notSent["c1"])
    }

    @Test
    fun a_connection_that_ends_first_puts_the_text_back_saying_it_was_not_sent() {
        val out = written("hello")
        out.send("c1", "mac", "hello") { "m1" }
        out.write("c1", "and more")
        out.ended("mac", "the connection ended")
        assertEquals("hello\n\nand more", out.unsent["c1"])
        assertEquals("the connection ended", out.notSent["c1"])
        // Not twice: a second end puts nothing more back.
        out.ended("mac", "the connection ended")
        assertEquals("hello\n\nand more", out.unsent["c1"])
    }

    @Test
    fun only_the_ended_hosts_messages_come_back() {
        val out = Outgoing()
        out.send("c1", "mac", "to mac") { "m1" }
        out.send("c2", "pi", "to pi") { "m2" }
        out.ended("pi", "the connection ended")
        assertNull(out.unsent["c1"])
        assertEquals("to pi", out.unsent["c2"])
    }

    @Test
    fun an_error_naming_the_frame_puts_the_text_back_with_the_bridges_words() {
        val out = written("hello")
        out.send("c1", "mac", "hello") { "m1" }
        assertNull(out.refused("other", "no such file"))
        assertEquals("c1", out.refused("m1", "agent unavailable"))
        assertEquals("hello", out.unsent["c1"])
        assertEquals("agent unavailable", out.notSent["c1"])
        // Refused, it is not waited for any more.
        out.ended("mac", "the connection ended")
        assertEquals("agent unavailable", out.notSent["c1"])
    }

    @Test
    fun a_message_the_bridge_had_after_all_leaves_the_field_again_at_the_sync() {
        val out = written("hello")
        out.send("c1", "mac", "hello") { "m1" }
        out.ended("mac", "the connection ended")
        assertEquals("hello", out.unsent["c1"])
        out.shown("c1", "hello")
        assertNull(out.unsent["c1"])
        assertNull(out.notSent["c1"])
    }

    @Test
    fun a_text_the_person_changed_since_it_came_back_stays() {
        val out = written("hello")
        out.send("c1", "mac", "hello") { "m1" }
        out.ended("mac", "the connection ended")
        out.write("c1", "hello, again")
        out.shown("c1", "hello")
        assertEquals("hello, again", out.unsent["c1"])
    }

    @Test
    fun sending_again_clears_why_it_was_not_sent() {
        val out = written("hello")
        out.send("c1", "mac", "hello") { "m1" }
        out.ended("mac", "the connection ended")
        out.send("c1", "mac", "hello") { "m2" }
        assertNull(out.unsent["c1"])
        assertNull(out.notSent["c1"])
        // Shown back: the one sent again, so a later end puts nothing back.
        out.shown("c1", "hello")
        out.ended("mac", "the connection ended")
        assertNull(out.unsent["c1"])
    }

    @Test
    fun forgetting_a_conversation_or_everything_drops_its_text() {
        val out = Outgoing()
        out.write("c1", "one")
        out.write("c2", "two")
        out.forget(listOf("c1"))
        assertNull(out.unsent["c1"])
        assertEquals("two", out.unsent["c2"])
        out.clear()
        assertTrue(out.unsent.isEmpty())
    }

    @Test
    fun an_emptied_field_keeps_nothing() {
        val out = written("hello")
        out.write("c1", "")
        assertFalse("c1" in out.unsent)
    }
    @Test fun review_a_restored_send_does_not_block_future_reply_buttons() {
        val out = Outgoing()
        out.send("c1", "mac", "old reply") { "m1" }
        assertTrue(out.waiting("c1"))
        out.ended("mac", "Disconnected")
        out.write("c1", "a rewritten draft")
        assertFalse(out.waiting("c1"))
        out.shown("c1", "old reply")
        assertEquals("a rewritten draft", out.unsent["c1"])
    }

    @Test fun suggested_reply_uses_the_real_send_path_and_restores_literal_text_on_disconnect() {
        val out = Outgoing()
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Keep paused"))
        out.write("home", "Existing draft")
        assertNull(out.sendSuggested("home", line, "Keep paused", listOf(line)) { text ->
            assertEquals("Keep paused", text)
            assertTrue(out.send("home", "host", text) { "frame" })
            null
        })
        assertEquals("Existing draft", out.unsent["home"])
        assertNotNull(out.sendSuggested("home", line, "Keep paused", listOf(line)) { error("Double tap") })
        out.ended("host", "Disconnected")
        assertFalse(out.waiting("home"))
        assertEquals("Keep paused\n\nExisting draft", out.unsent["home"])
        out.write("home", "Existing draft")
        assertEquals("Disconnected", out.sendSuggested("home", line, "Keep paused", listOf(line)) { "Disconnected" })
        assertEquals("Keep paused\n\nExisting draft", out.unsent["home"])
        val owner = Line(2, "person", "New direction", "reply", 2)
        assertNotNull(out.sendSuggested("home", line, "Keep paused", listOf(line, owner)) { error("Old reply") })
        assertNotNull(out.sendSuggested("home", line, "invented", listOf(line)) { error("Invented reply") })
    }

}
