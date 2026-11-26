package io.github.victorbonnet.enclos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.GlassScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeToEdgeTest {
    @get:Rule val rule = createComposeRule()

    private val band = Color(255, 0, 255)

    private fun lastRows(dark: Boolean, navDp: Int, withBar: Boolean = true): List<Int> {
        rule.setContent {
            EnclosTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(band)) {
                    GlassScaffold(
                        insets = WindowInsets(bottom = navDp.dp),
                        frost = false,
                        top = { AppBar { Box(Modifier.height(48.dp)) } },
                        bottom = if (withBar) ({ Box(Modifier.fillMaxWidth().height(56.dp)) }) else null,
                    ) { _ -> Box(Modifier.fillMaxSize().background(Color.Gray)) }
                }
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val x = bitmap.width / 2
        return listOf(1, 2, 3, navDp / 2).map { bitmap.getPixel(x, bitmap.height - it) }
    }

    private fun assertReaches(dark: Boolean, navDp: Int) {
        val rows = lastRows(dark, navDp)
        rows.forEach { assertNotEquals("window background shows at the bottom (dark=$dark nav=${navDp}dp)", band.toArgb(), it) }
        // The inset room and the bar above it are one material.
        assertEquals("bar and inset room differ (dark=$dark nav=${navDp}dp)", rows[0], rows[3])
    }

    @Test fun gesture_light() = assertReaches(dark = false, navDp = 24)
    @Test fun gesture_dark() = assertReaches(dark = true, navDp = 24)
    @Test fun three_button_light() = assertReaches(dark = false, navDp = 48)
    @Test fun three_button_dark() = assertReaches(dark = true, navDp = 48)
}
