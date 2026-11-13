package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.ui.statusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLineTest {
    private fun line(seq: Long, from: String, kind: String) = Line(seq, from, "t$seq", kind, seq)

    @Test
    fun a_status_line_starts_with_its_runtime_and_holds_one_line() {
        assertEquals("hermes: gateway online", statusText("hermes", "gateway online"))
        assertEquals("hermes: Connection lost pair again", statusText("hermes", "Connection lost\n\npair again"))
    }

    @Test
    fun a_status_line_drops_what_reorders_or_hides_its_text() {
        val shown = statusText("hermes", "pair again at \u202Eelpmaxe\u202C.evil \u200Bnow")
        assertEquals("hermes: pair again at elpmaxe.evil now", shown)
        assertFalse(Visible.hasHidden(shown))
    }

    @Test
    fun a_status_line_never_counts_as_unread() {
        val lines = listOf(line(1, "agent", "reply"), line(2, "agent", "status"), line(3, "victor", "message"), line(4, "agent", "reply"))
        assertEquals(2, unreadAmong(lines, read = 0))
        assertEquals(1, unreadAmong(lines, read = 1))
        assertEquals(0, unreadAmong(listOf(line(5, "agent", "status")), read = 0))
    }

    @Test
    fun the_inbox_lists_a_home_always_and_a_conversation_once_something_was_said() {
        val home = Conversation("mac", "lab/main", "h", home = true)
        val other = Conversation("mac", "lab/main", "c")
        assertTrue(listedWith(home, emptyList()))
        assertFalse(listedWith(other, emptyList()))
        assertTrue(listedWith(other, listOf(line(1, "agent", "status"))))
    }
}
