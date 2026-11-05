package io.github.victorbonnet.enclos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeChoiceTest {
    @Test
    fun nothing_saved_follows_the_system() {
        assertEquals(ThemeChoice.System, ThemeChoice.read(null))
        assertEquals(ThemeChoice.System, ThemeChoice.read("Sepia"))
    }

    @Test
    fun a_saved_choice_reads_back() {
        ThemeChoice.entries.forEach { assertEquals(it, ThemeChoice.read(it.name)) }
    }

    @Test
    fun the_system_decides_only_until_the_person_chooses() {
        assertTrue(ThemeChoice.System.dark(systemDark = true))
        assertFalse(ThemeChoice.System.dark(systemDark = false))
        assertFalse(ThemeChoice.Light.dark(systemDark = true))
        assertTrue(ThemeChoice.Dark.dark(systemDark = false))
    }
}
