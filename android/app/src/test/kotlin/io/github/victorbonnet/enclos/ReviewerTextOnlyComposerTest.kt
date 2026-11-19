package io.github.victorbonnet.enclos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewerTextOnlyComposerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ordinary_text_pending_must_not_turn_on_attachment_receipt_UI() {
        val outgoing = Outgoing()
        outgoing.send("group-c", "host", "earlier ordinary text") { "text-id" }
        compose.setContent {
            EnclosTheme(dark = false) {
                FileComposerControls(emptyList(), offersFiles = false, waiting = outgoing.waiting("group-c"),
                    onCamera = {}, onPhotos = {}, onFiles = {}, onRemove = {}) { attach, enabled ->
                    Composer("second text", {}, "Message the group", canSend = enabled, attachment = attach, onSend = {})
                }
            }
        }
        compose.onNodeWithContentDescription("Attach files").assertDoesNotExist()
        compose.onNodeWithText("Waiting for receipt…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }
}
