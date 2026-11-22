package io.github.victorbonnet.enclos

import android.app.Application
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HostDraftCleanupTest {
    @Test fun leaving_failed_first_send_must_drop_orphaned_file_draft() = leave(true)
    @Test fun leaving_never_sent_draft_discards_files() = leave(false)
    private fun leave(failed: Boolean) {
        val model = fixtureModel()
        try {
            val agent = Agent("fixture-host", "box/agent", "hermes", listOf("incoming_files"))
            val id = model.newConversation(agent)
            val key = FileKey(agent.host, id)
            if (failed) {
                model.outgoing.send(id, agent.host, "first text") { "owned-first-send" }
                model.outgoing.ended(agent.host, "synthetic disconnect before echo")
            }
            model.outgoing.files.drafted(key, SendFile("orphan.txt", "text/plain", byteArrayOf(7)))
            model.left(id)
            assertFalse("left really discarded the conversation", model.conversations.any { it.id == id })
            assertTrue("no unreachable file draft after leaving discarded conversation", model.outgoing.files.draft(key).isEmpty())
        } finally {
            model.outgoing.clear()
            model.endFixture()
        }
    }
    @Test fun explicit_dropDraft_cleanup_positive_control() {
        val model = fixtureModel()
        try {
            val agent = Agent("fixture-host", "box/agent", "hermes", emptyList())
            val id = model.newConversation(agent)
            val key = FileKey(agent.host, id)
            model.outgoing.files.drafted(key, SendFile("draft.txt", "text/plain", byteArrayOf(7)))
            model.dropDraft(id)
            assertFalse(model.conversations.any { it.id == id })
            assertTrue(model.outgoing.files.draft(key).isEmpty())
        } finally {
            model.outgoing.clear()
            model.endFixture()
        }
    }
    @Test fun replacement_host_reset_must_retire_previous_identity_file_draft() {
        val model = fixtureModel()
        try {
            val c = Conversation("fixture-host", "box/agent", "home.box.agent")
            val key = FileKey(c.host, c.id)
            model.conversations.add(c)
            val old = SendFile("old-identity-private.txt", "text/plain", byteArrayOf(1, 2, 3))
            model.outgoing.files.drafted(key, old)
            val preparation = Job()
            assertTrue(model.outgoing.files.preparing(key, preparation))
            // This is the exact reset callee used by pairedWith before connect(newHost).
            // Do not invoke pairing/connect: this bounded fixture opens no socket.
            AppModel::class.java.getDeclaredMethod("forget", String::class.java).apply { isAccessible = true }.invoke(model, c.host)
            assertFalse("old conversations reset positive control", model.conversations.any { it.host == c.host })
            println("SECURITY_RESET_OBSERVATION retainedDraft=${model.outgoing.files.draft(key).map { it.name }} retainedPreparation=${model.outgoing.files.phase(key)}")
            assertTrue("host identity reset must drop previous bridge's prepared bytes", model.outgoing.files.draft(key).isEmpty())
            assertEquals("host identity reset must cancel old preparation", FilePhase.Draft, model.outgoing.files.phase(key))
            assertTrue(preparation.isCancelled)
        } finally {
            model.outgoing.clear()
            model.endFixture()
        }
    }
}
