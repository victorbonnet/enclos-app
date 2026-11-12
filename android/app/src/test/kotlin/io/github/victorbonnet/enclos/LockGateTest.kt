package io.github.victorbonnet.enclos

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app lock over the screens, in Compose on Robolectric. */
@RunWith(RobolectricTestRunner::class)
class LockGateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_dialog_is_gone_when_the_app_locks_and_back_once_unlocked() {
        var locked by mutableStateOf(false)
        compose.setContent {
            LockGate(locked, lockScreen = { Text("Enclos is locked") }) {
                // A dialog is a window of its own: covering the screens would leave it above the lock.
                var open by rememberSaveable { mutableStateOf(true) }
                Text("Inbox")
                if (open) {
                    AlertDialog(
                        onDismissRequest = { open = false },
                        confirmButton = { Text("Delete") },
                        text = { Text("Delete this conversation?") },
                    )
                }
            }
        }
        compose.onNodeWithText("Delete this conversation?").assertExists()

        locked = true
        compose.waitForIdle()
        compose.onNodeWithText("Enclos is locked").assertExists()
        compose.onNodeWithText("Delete this conversation?").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Inbox").assertDoesNotExist()

        // Unlocked, the screens come back where they were, the dialog with them.
        locked = false
        compose.waitForIdle()
        compose.onNodeWithText("Enclos is locked").assertDoesNotExist()
        compose.onNodeWithText("Delete this conversation?").assertExists()
    }
}
