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
        if (retry) {
            model.outgoing.files.drafts[c.id] = listOf(SendFile("original.txt", "text/plain", byteArrayOf(1)))
            model.outgoing.send(c.id, c.host, "original") { "original" }
            if (!pendingFile) model.outgoing.ended(c.host, "lost")
        }
        model.outgoing.send(c.id, c.host, "pending text", withFiles = false) { "text" }
        try {
            compose.setContent { EnclosTheme(dark = false) {
                FileComposer(model, c) { attach, enabled ->
                    Composer("next text", {}, "Message", canSend = enabled, attachment = attach, onSend = {})
                }
            } }
            if (pendingFile) {
                compose.onNodeWithText("Waiting for receipt…").assertExists()
                compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
                compose.onNodeWithText("Retry original message").assertIsNotEnabled()
                compose.onNodeWithText("Discard original retry").assertIsNotEnabled()
                return
            }
            compose.onNodeWithText("Waiting for receipt…").assertDoesNotExist()
            compose.onNodeWithContentDescription("Send").assertIsEnabled()
            if (retry) {
                compose.onNodeWithText("Retry original message").assertIsEnabled()
                compose.onNodeWithText("Discard original retry").assertIsEnabled().performScrollTo().performClick()
                compose.onNodeWithText("Discard original retry").assertDoesNotExist()
            }
        } finally {
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
        }
    }
}
