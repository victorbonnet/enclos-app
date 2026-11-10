package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTest {
    @Test
    fun hiddenCharactersAreNamed() {
        listOf(0x202E, 0x2066, 0x200B, 0x200D, 0xFEFF, 0x061C, 0x0007, 0x00AD).forEach { assertTrue("%04X".format(it), Visible.isHidden(it)) }
        "aéß中 -".codePoints().forEach { assertFalse(Visible.isHidden(it)) }
        assertFalse(Visible.isHidden("😀".codePointAt(0)))
    }

    @Test
    fun newlinesCountOnlyWhenLinesAreRefused() {
        assertTrue(Visible.hasHidden("a\nb"))
        assertFalse(Visible.hasHidden("a\nb", lines = true))
        assertTrue(Visible.hasHidden("a‮b", lines = true))
    }

    @Test
    fun strippedAndRevealed() {
        assertEquals("niam", Visible.stripped("‮niam"))
        assertEquals("ls⟨U+202E⟩ -la⟨tab⟩x\ny", Visible.revealed("ls‮ -la\tx\ny"))
        assertEquals("echo ok", Visible.revealed("echo ok"))
    }
}
