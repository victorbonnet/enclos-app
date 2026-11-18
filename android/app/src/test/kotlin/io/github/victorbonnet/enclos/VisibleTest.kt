package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTest {
    @Test
    fun hiddenCharactersAreNamed() {
        listOf(
            0x202E, 0x2066, 0x200B, 0x200E, 0xFEFF, 0x061C, 0x0007, 0x0085, 0x00AD, 0x034F, 0x115F, 0x3164,
            0xFFA0, 0x2800, 0x2028, 0x2029, 0x206A, 0xFFF9, 0xFE00, 0xE0041, 0xE007F, 0xE0100, 0x200D,
            0xE000, 0xF8FF, 0xFDD0, 0x1FFFE, 0xF0000, 0x10FFFD,
        ).forEach {
            assertTrue("%04X".format(it), Visible.isHidden(it))
            assertTrue("%04X".format(it), Visible.hasHidden("a" + String(Character.toChars(it)) + "b", lines = true))
        }
        "a\u00E9\u00DF\u4E2D -\u06CC".codePoints().forEach { assertFalse(Visible.isHidden(it)) }
        assertFalse(Visible.isHidden("\uD83D\uDE00".codePointAt(0)))
    }

    @Test
    fun joinersAreTextBetweenNonAsciiCharactersOnly() {
        listOf("\uD83D\uDC69\u200D\uD83D\uDCBB", "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645", "\u2764\uFE0F").forEach {
            assertFalse(it, Visible.hasHidden(it))
            assertEquals(it, Visible.stripped(it))
            assertEquals(it, Visible.revealed(it))
        }
        listOf("ma\u200Din", "main\uFE0F", "\u200D\uD83D\uDCBB", "\uD83D\uDC69\u200D", "\uD83D\uDC69\u200D\u202E").forEach {
            assertTrue(it, Visible.hasHidden(it))
        }
        assertEquals("main", Visible.stripped("main\uFE0F"))
        assertEquals("ma⟨U+200D⟩in", Visible.revealed("ma\u200Din"))
    }

    @Test
    fun newlinesCountOnlyWhenLinesAreRefused() {
        assertTrue(Visible.hasHidden("a\nb"))
        assertFalse(Visible.hasHidden("a\nb", lines = true))
        assertTrue(Visible.hasHidden("a\u202Eb", lines = true))
        assertEquals("a\nb", Visible.stripped("a\nb\u202E", lines = true))
        assertEquals("ab", Visible.stripped("a\nb"))
    }

    @Test
    fun strippedRevealedAndBare() {
        assertEquals("niam", Visible.stripped("\u202Eniam"))
        assertEquals("ls⟨U+202E⟩ -la⟨tab⟩x\ny⟨U+E0041⟩", Visible.revealed("ls\u202E -la\tx\ny\uDB40\uDC41"))
        assertEquals("echo ok", Visible.revealed("echo ok"))
        assertEquals("main", Visible.bare("main\uFE0F"))
    }
}
