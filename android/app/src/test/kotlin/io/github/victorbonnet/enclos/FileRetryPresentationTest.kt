package io.github.victorbonnet.enclos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Synthetic 320dp / 2x font fixture; no device or transport claim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileRetryPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun both_original_actions_scroll_into_view_and_dispatch_unless_upload_pending() {
        var pending by mutableStateOf(false)
        val clicks = mutableListOf<String>()
        val retry = SendingFiles.Retry("original", "Original message with enough detail to wrap at large font sizes",
            listOf(SendFile("original-document-with-details.txt", "text/plain", byteArrayOf(1))))
        compose.setContent { EnclosTheme(dark = false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    FileRetryPresentation(retry, pending, { clicks += "retry" }, { clicks += "discard" })
                }
            }
        } }
        for ((label, action) in listOf("Retry original message" to "retry", "Discard original retry" to "discard")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled().performClick()
            assertEquals(action, clicks.last())
        }
        assertEquals(listOf("retry", "discard"), clicks)
        compose.runOnIdle { pending = true }
        for (label in listOf("Retry original message", "Discard original retry")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        }
        assertEquals(listOf("retry", "discard"), clicks)
    }
}
