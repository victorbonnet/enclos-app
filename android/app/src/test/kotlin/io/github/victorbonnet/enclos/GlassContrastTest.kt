package io.github.victorbonnet.enclos

import androidx.compose.ui.graphics.Color
import io.github.victorbonnet.enclos.ui.DarkPalette
import io.github.victorbonnet.enclos.ui.LightPalette
import io.github.victorbonnet.enclos.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * What may be drawn on the bars' glass (`docs/spec.md`, "The bars"): over the
 * worst backdrops a bar can have, black and white, `text` holds 7:1 and
 * `textMuted` 4.5:1 in both themes, while `textFaint` and light `accent` do not
 * (dark `accent` just clears 4.5:1), which is why only the first two are drawn
 * straight on glass.
 */
class GlassContrastTest {
    private fun linear(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The glass as the screen shows it: the tint over a backdrop (the blur of it, here a flat colour). */
    private fun over(palette: Palette, backdrop: Color): Color {
        val a = palette.glass.alpha
        return Color(
            red = a * palette.glass.red + (1 - a) * backdrop.red,
            green = a * palette.glass.green + (1 - a) * backdrop.green,
            blue = a * palette.glass.blue + (1 - a) * backdrop.blue,
        )
    }

    @Test
    fun the_tint_is_bg_at_point_seven_six_and_the_veil_is_bg() {
        for (p in listOf(LightPalette, DarkPalette)) {
            assertEquals(194f / 255f, p.glass.alpha, 0.001f)
            assertEquals(p.bg.copy(alpha = p.glass.alpha), p.glass)
            assertEquals(p.bg, p.veil)
            assertEquals(1f, p.veil.alpha, 0f)
        }
    }

    @Test
    fun text_and_text_muted_hold_over_black_and_white_in_both_themes() {
        for ((name, p) in listOf("light" to LightPalette, "dark" to DarkPalette)) {
            for (backdrop in listOf(Color.Black, Color.White)) {
                val glass = over(p, backdrop)
                assertTrue("$name text over $backdrop: ${contrast(p.text, glass)}", contrast(p.text, glass) >= 7.0)
                assertTrue("$name textMuted over $backdrop: ${contrast(p.textMuted, glass)}", contrast(p.textMuted, glass) >= 4.5)
            }
        }
    }

    @Test
    fun text_faint_and_accent_match_the_spec_figures_in_the_worst_case() {
        // Spec 2.3: light over black 3.39 / 3.43, dark over white 3.25 / 4.57. The first three fail 4.5:1,
        // which is why faint and accent text sit on their own `surface` pill instead of on the glass.
        val light = over(LightPalette, Color.Black)
        val dark = over(DarkPalette, Color.White)
        assertEquals(3.39, contrast(LightPalette.textFaint, light), 0.03)
        assertEquals(3.43, contrast(LightPalette.accent, light), 0.03)
        assertEquals(3.25, contrast(DarkPalette.textFaint, dark), 0.03)
        assertEquals(4.57, contrast(DarkPalette.accent, dark), 0.03)
    }
}
