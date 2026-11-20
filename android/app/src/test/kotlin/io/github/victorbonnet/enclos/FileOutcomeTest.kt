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
class FileOutcomeTest {
    @get:Rule val compose = createComposeRule()

    private val send = FileSend("original", "h", "c", "Original message with enough detail to wrap at large font sizes",
        listOf(SendFile("original-document-with-details.txt", "text/plain", byteArrayOf(1))))

    @Test fun unknown_offers_retry_and_discard_in_view_and_an_upload_under_way_offers_neither() {
        var phase by mutableStateOf<FilePhase>(FilePhase.Unknown(send))
        val clicks = mutableListOf<String>()
        compose.setContent { EnclosTheme(dark = false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    FileOutcome(phase, send.text, send.files.map { it.name }, { clicks += "retry" }, { clicks += "discard" })
                }
            }
        } }
        compose.onNodeWithText("Delivery unknown. Check history before sending it again.").performScrollTo().assertIsDisplayed()
        for ((label, action) in listOf("Retry original message" to "retry", "Discard" to "discard")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled().performClick()
            assertEquals(action, clicks.last())
        }
        assertEquals(listOf("retry", "discard"), clicks)
        for (pending in listOf(FilePhase.Sending(send, 40), FilePhase.AwaitingAck(send))) {
            compose.runOnIdle { phase = pending }
            for (label in listOf("Retry original message", "Discard")) compose.onNodeWithText(label).assertDoesNotExist()
        }
        assertEquals(listOf("retry", "discard"), clicks)
    }

    @Test fun a_final_refusal_says_why_and_offers_only_dismiss() {
        val clicks = mutableListOf<String>()
        compose.setContent { EnclosTheme(dark = false) {
            FileOutcome(FilePhase.Failed(send.id, send.text, send.files.map { it.name }, "file name refused"), send.text, send.files.map { it.name }, { clicks += "retry" }, { clicks += "dismiss" })
        } }
        compose.onNodeWithText("Not sent: file name refused").assertExists()
        compose.onNodeWithText("Retry original message").assertDoesNotExist()
        compose.onNodeWithText("Dismiss").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("dismiss"), clicks)
    }
}
