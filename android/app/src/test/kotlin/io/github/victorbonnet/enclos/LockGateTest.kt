package io.github.victorbonnet.enclos

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    /** State the screens changed comes back as it was left, not as it started. */
    @Test
    fun what_the_screens_changed_is_kept_while_locked() {
        var locked by mutableStateOf(false)
        compose.setContent {
            LockGate(locked, lockScreen = { Text("Enclos is locked") }) {
                var taps by rememberSaveable { mutableStateOf(0) }
                Button(onClick = { taps++ }) { Text("Tapped $taps") }
            }
        }
        compose.onNodeWithText("Tapped 0").performClick()
        compose.onNodeWithText("Tapped 1").performClick()
        compose.onNodeWithText("Tapped 2").assertExists()

        locked = true
        compose.waitForIdle()
        compose.onNodeWithText("Tapped 2").assertDoesNotExist()

        locked = false
        compose.waitForIdle()
        compose.onNodeWithText("Tapped 2").assertExists()
    }

    /** Recreated while locked (a rotation at the lock screen), the screens still come back as they were. */
    @Test
    fun what_the_screens_changed_survives_a_recreation_while_locked() {
        var locked by mutableStateOf(false)
        val restore = StateRestorationTester(compose)
        restore.setContent {
            LockGate(locked, lockScreen = { Text("Enclos is locked") }) {
                var taps by rememberSaveable { mutableStateOf(0) }
                Button(onClick = { taps++ }) { Text("Tapped $taps") }
            }
        }
        compose.onNodeWithText("Tapped 0").performClick()
        locked = true
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        locked = false
        compose.waitForIdle()
        compose.onNodeWithText("Tapped 1").assertExists()
    }
}
