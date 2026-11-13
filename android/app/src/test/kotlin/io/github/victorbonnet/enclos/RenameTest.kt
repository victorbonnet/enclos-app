package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameTest {
    private val c = Conversation("mac", "lab/main", "c-1", title = "Weather", titledBy = "agent")

    @Test
    fun a_name_that_could_not_be_sent_changes_nothing() {
        val list = mutableListOf(c)
        assertFalse(renamed(list, c, "Trip") { false })
        assertEquals(listOf(c), list)
    }

    @Test
    fun a_name_sent_is_shown_as_the_persons() {
        val list = mutableListOf(c)
        var sent: String? = null
        assertTrue(renamed(list, c, "  Trip  ") { sent = it; true })
        assertEquals("Trip", sent)
        assertEquals(c.copy(title = "Trip", titledBy = "person"), list.single())
        assertTrue(renamed(list, c, "") { true })
        assertEquals(c.copy(title = null, titledBy = null), list.single())
    }

    @Test
    fun a_title_drops_the_characters_that_change_how_it_reads() {
        assertEquals("niam", shownTitle("\u202Eniam"))
        assertEquals("Weather in Lyon", shownTitle(" Weather\u200B in Lyon "))
        assertNull(shownTitle("\u200B\u2066"))
        assertNull(shownTitle(""))
        assertNull(shownTitle(null))
    }
}
