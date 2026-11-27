package io.github.victorbonnet.enclos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.DarkPalette
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.GlassScaffold
import io.github.victorbonnet.enclos.ui.LightPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Edge to edge (the QA item in WORKFLOW.md): behind a loud magenta "window background" the bottom bar of
 * `GlassScaffold` must reach the last pixel row of the window, with the insets of gesture navigation (24 dp)
 * and of 3-button navigation (48 dp), light and dark. If a caller shrinks the scaffold above the navigation
 * bar, or the bar stops where the inset starts, the last rows are magenta and this fails.
 *
 * Each case stands on its own, whatever ran before it in the class:
 * - It draws its own Compose root into a bitmap (`View.draw`) rather than reading the window back
 *   (`captureToImage`). Under Robolectric, once a case of the class has failed, the window read-back of the
 *   later cases can stay an empty window (the platform's `#fafafa`) however long they wait, so a later case
 *   could pass on a picture with no band in it.
 * - It checks its own expected picture, not only "not magenta": every pixel row of the bottom bar and the inset
 *   room under it is the theme's `veil` (the bar's material without frost), and the middle of the screen is
 *   this case's own body colour, so a picture this case did not draw fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeToEdgeTest {
    @get:Rule val rule = createComposeRule()

    private val band = Color(255, 0, 255)
    private val barDp = 56

    private fun hex(argb: Int) = "%08x".format(argb)

    /** The colours of [rows] as runs from the bottom up, e.g. `1..24 ffff00ff, 25..80 fffafaf8`. */
    private fun runs(rows: List<Int>): String = buildList {
        var start = 0
        for (i in 1..rows.size) {
            if (i == rows.size || rows[i] != rows[start]) {
                add("${start + 1}..$i ${hex(rows[start])}")
                start = i
            }
        }
    }.joinToString()

    private fun assertReaches(dark: Boolean, navDp: Int) {
        val case = "dark=$dark nav=${navDp}dp"
        val veil = (if (dark) DarkPalette else LightPalette).veil.toArgb()
        // A body colour of this case alone, so a picture of another case cannot stand in for this one.
        val body = Color(0x40, navDp, if (dark) 0xC0 else 0x80)
        var root: View? = null
        rule.setContent {
            root = LocalView.current
            EnclosTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(band)) {
                    GlassScaffold(
                        insets = WindowInsets(bottom = navDp.dp),
                        frost = false,
                        top = { AppBar { Box(Modifier.height(48.dp)) } },
                        bottom = { Box(Modifier.fillMaxWidth().height(barDp.dp)) },
                    ) { _ -> Box(Modifier.fillMaxSize().background(body)) }
                }
            }
        }
        val bitmap = rule.runOnIdle {
            val view = checkNotNull(root) { "no Compose root ($case)" }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val x = bitmap.width / 2
        assertEquals("not this case's picture ($case)", hex(body.toArgb()), hex(bitmap.getPixel(x, bitmap.height / 2)))
        // The bar (barDp) and the inset room under it (navDp), from the last pixel row up, are all the bar's material.
        val reach = with(rule.density) { (barDp + navDp).dp.roundToPx() }
        val rows = (1..reach).map { bitmap.getPixel(x, bitmap.height - it) }
        assertEquals(
            "bottom $reach px, from the last row up, are not all the bar's veil ${hex(veil)} ($case): ${runs(rows)}",
            List(reach) { hex(veil) },
            rows.map(::hex),
        )
    }

    @Test fun gesture_light() = assertReaches(dark = false, navDp = 24)
    @Test fun gesture_dark() = assertReaches(dark = true, navDp = 24)
    @Test fun three_button_light() = assertReaches(dark = false, navDp = 48)
    @Test fun three_button_dark() = assertReaches(dark = true, navDp = 48)
}
