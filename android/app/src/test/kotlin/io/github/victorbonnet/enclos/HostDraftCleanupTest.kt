package io.github.victorbonnet.enclos

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HostDraftCleanupTest {
    @Suppress("UNCHECKED_CAST") private fun <T> field(model: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model) as T
    @Test fun leaving_failed_first_send_must_drop_orphaned_file_draft() = leave(true)
    @Test fun leaving_never_sent_draft_discards_files() = leave(false)
    private fun leave(failed: Boolean) {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val agent = Agent("fixture-host", "box/agent", "hermes", listOf("incoming_files"))
            val id = model.newConversation(agent)
            if (failed) {
                model.outgoing.send(id, agent.host, "first text") { "owned-first-send" }
                model.outgoing.ended(agent.host, "synthetic disconnect before echo")
            }
            model.outgoing.files.drafts[id] = listOf(SendFile("orphan.txt", "text/plain", byteArrayOf(7)))
            model.left(id)
            assertFalse("left really discarded the conversation", model.conversations.any { it.id == id })
            assertNull("no unreachable file draft after leaving discarded conversation", model.outgoing.files.drafts[id])
        } finally {
            model.outgoing.clear()
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try {
                for (name in listOf("saving", "work", "disk", "fileWorker")) {
                    assertTrue("owned executor teardown: $name", field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS))
                }
            } finally { field<Store>(model, "store").close() }
        }
    }
    @Test fun explicit_dropDraft_cleanup_positive_control() {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val id = model.newConversation(Agent("fixture-host", "box/agent", "hermes", emptyList()))
            model.outgoing.files.drafts[id] = listOf(SendFile("draft.txt", "text/plain", byteArrayOf(7)))
            model.dropDraft(id)
            assertFalse(model.conversations.any { it.id == id })
            assertNull(model.outgoing.files.drafts[id])
        } finally {
            model.outgoing.clear()
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try {
                for (name in listOf("saving", "work", "disk", "fileWorker")) {
                    assertTrue("owned executor teardown: $name", field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS))
                }
            } finally { field<Store>(model, "store").close() }
        }
    }
    @Test fun replacement_host_reset_must_retire_previous_identity_file_draft() {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val c = Conversation("fixture-host", "box/agent", "home.box.agent")
            model.conversations.add(c)
            val old = SendFile("old-identity-private.txt", "text/plain", byteArrayOf(1, 2, 3))
            model.outgoing.files.drafts[c.id] = listOf(old)
            model.outgoing.files.preparing[c.id] = Any()
            // This is the exact reset callee used by pairedWith before connect(newHost).
            // Do not invoke pairing/connect: this bounded fixture opens no socket.
            AppModel::class.java.getDeclaredMethod("forget", String::class.java).apply { isAccessible = true }.invoke(model, c.host)
            assertFalse("old conversations reset positive control", model.conversations.any { it.host == c.host })
            println("SECURITY_RESET_OBSERVATION retainedDraft=${model.outgoing.files.drafts[c.id]?.map { it.name }} retainedPreparation=${c.id in model.outgoing.files.preparing}")
            assertNull("host identity reset must drop previous bridge's prepared bytes", model.outgoing.files.drafts[c.id])
            assertNull("host identity reset must cancel old preparation", model.outgoing.files.preparing[c.id])
        } finally {
            model.outgoing.clear()
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try {
                for (name in listOf("saving", "work", "disk", "fileWorker")) {
                    assertTrue("owned executor teardown: $name", field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS))
                }
            } finally { field<Store>(model, "store").close() }
        }
    }
}
