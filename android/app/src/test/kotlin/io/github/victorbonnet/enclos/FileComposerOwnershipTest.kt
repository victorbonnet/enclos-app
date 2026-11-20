package io.github.victorbonnet.enclos

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileComposerOwnershipTest {
    @get:Rule val compose = createComposeRule()
    @Test fun retained_unknown_retry_and_pending_text_allow_normal_send() = exercise(true, false)
    @Test fun direct_without_retry_and_pending_text_allow_normal_send() = exercise(false, false)
    @Test fun group_pending_text_allows_normal_send() = exercise(false, true)

    @Test fun pending_file_disables_retry_discard_and_send() = exercise(true, false, pendingFile = true)

    private fun exercise(retry: Boolean, group: Boolean, pendingFile: Boolean = false) {
        val model = AppModel(ApplicationProvider.getApplicationContext())
        val saving = AppModel::class.java.getDeclaredField("saving").apply { isAccessible = true }.get(model) as ExecutorService
        saving.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val c = Conversation("fixture", "box/agent", "composer-fixture", members = if (group) listOf("box/a") else emptyList())
        model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
        @Suppress("UNCHECKED_CAST")
        val caps = AppModel::class.java.getDeclaredField("hostCapabilities").apply { isAccessible = true }.get(model) as MutableMap<String, Set<String>>
        caps[c.host] = setOf("message.begin")
        val key = FileKey(c.host, c.id)
        if (retry) {
            model.outgoing.files.drafted(key, SendFile("original.txt", "text/plain", byteArrayOf(1)))
            val original = model.outgoing.files.begin(key, "original")!!
            model.outgoing.files.transmitted(key, original.id)
            if (!pendingFile) model.outgoing.ended(c.host, "lost")
        }
        model.outgoing.send(c.id, c.host, "pending text") { "text" }
        try {
            compose.setContent { EnclosTheme(dark = false) {
                FileComposer(model, c) { attach, enabled ->
                    Composer("next text", {}, "Message", canSend = enabled, attachment = attach, onSend = {})
                }
            } }
            if (pendingFile) {
                compose.onNodeWithText("Waiting for receipt…").assertExists()
                compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
                // While the original is under way there is nothing to retry or discard.
                compose.onNodeWithText("Retry original message").assertDoesNotExist()
                compose.onNodeWithText("Discard").assertDoesNotExist()
                return
            }
            compose.onNodeWithText("Waiting for receipt…").assertDoesNotExist()
            compose.onNodeWithContentDescription("Send").assertIsEnabled()
            if (retry) {
                compose.onNodeWithText("Retry original message").assertIsEnabled()
                compose.onNodeWithText("Discard").assertIsEnabled().performScrollTo().performClick()
                compose.onNodeWithText("Discard").assertDoesNotExist()
                compose.onNodeWithText("Retry original message").assertDoesNotExist()
            }
        } finally {
            model.endFixture()
        }
    }
}
