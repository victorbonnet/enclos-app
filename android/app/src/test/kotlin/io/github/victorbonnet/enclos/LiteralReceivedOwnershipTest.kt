package io.github.victorbonnet.enclos

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual AppModel received path; no network or real pairing. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiteralReceivedOwnershipTest {
    @Test fun equal_existing_draft_keeps_restored_reply_prefix() = exercise("Yes", true, false, false)
    @Test fun equal_newer_draft_survives_late_literal_received() = exercise("before", true, true, false)
    @Test fun different_draft_survives_late_literal_received() = exercise("different", true, false, false)
    @Test fun equal_draft_survives_literal_received_without_loss() = exercise("Yes", false, false, false)
    @Test fun ordinary_restored_text_is_retired_by_received_positive() = exercise("", true, false, true)
    @Test fun late_literal_preserves_newer_ordinary_notice() = exercise("Yes", true, false, false, "ordinary")
    @Test fun late_literal_preserves_newer_file_outcome() = exercise("Yes", true, false, false, "file")
    @Test fun offered_reply_does_not_revoke_unchanged_ordinary_restoration() = restoredSuggested(false)
    @Test fun offered_reply_preserves_equal_user_rewrite_of_ordinary_restoration() = restoredSuggested(true)
    private fun restoredSuggested(equalUserEdit: Boolean) {
        val model = fixtureModel()
        try {
            val c = Conversation("fixture-host", "box/agent", "restored-owner")
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            val bridge = FakeBridge()
            bridge.attach(model, c.host, files = false)
            assertNull(model.send(c, "A"))
            model.outgoing.ended(c.host, "lost")
            assertEquals("A", model.outgoing.unsent[c.id])
            if (equalUserEdit) model.outgoing.write(c.id, "A")
            val key = FileKey(c.host, c.id)
            val file = SendFile("newer.txt", "text/plain", byteArrayOf(1))
            model.outgoing.files.drafted(key, file)
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("B"))
            model.lines[c.id] = listOf(line)
            assertNull(model.sendSuggested(c, line, "B"))
            assertEquals("A", model.outgoing.unsent[c.id])
            model.receive(c.host, message("message", "conversation" to c.id, "agent" to c.agent,
                "seq" to 2, "from" to "person", "text" to "A", "kind" to "message"))
            assertEquals(if (equalUserEdit) "A" else null, model.outgoing.unsent[c.id])
            assertEquals(listOf(file), model.outgoing.files.draft(key))
            assertTrue(model.outgoing.waiting(c.id))
            model.outgoing.ended(c.host, "literal loss")
            assertEquals(if (equalUserEdit) "B\n\nA" else "B", model.outgoing.unsent[c.id])
            assertEquals(listOf(file), model.outgoing.files.draft(key))
        } finally { model.endFixture() }
    }
    private fun exercise(draft: String, loss: Boolean, newer: Boolean, ordinary: Boolean, newerOperation: String? = null) {
        val model = fixtureModel()
        try {
            val c = Conversation("fixture-host", "box/agent", "fixture-literal-c")
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            val bridge = FakeBridge()
            bridge.attach(model, c.host, files = false)

            val out = model.outgoing
            val key = FileKey(c.host, c.id)
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            model.lines[c.id] = listOf(line)
            val file = SendFile("unrelated.txt", "text/plain", byteArrayOf(7))
            out.files.drafted(key, file)
            out.write(c.id, draft)
            if (ordinary) {
                assertTrue(out.send(c.id, c.host, "Yes") { "ordinary-id" })
            } else {
                assertNull(model.sendSuggested(c, line, "Yes"))
                assertEquals(listOf("message.send"), bridge.types())
                assertEquals(draft.ifEmpty { null }, out.unsent[c.id])
            }
            if (loss) out.ended(c.host, "synthetic loss")
            if (newer) out.write(c.id, "Yes")
            var operation: FileSend? = null
            when (newerOperation) {
                "ordinary" -> out.send(c.id, c.host, "new operation") { "new-id" }
                "file" -> {
                    // The draft's files go with these words, as the screen sends them.
                    operation = out.files.begin(key, "new operation")
                    out.write(c.id, "")
                }
            }
            if (newerOperation != null) out.ended(c.host, "new operation loss")
            val notice = out.notSent[c.id]
            val expected = if (newerOperation == "ordinary") "new operation" else if (newerOperation == "file") null else if (ordinary) null else if (newer) "Yes" else if (loss) "Yes\n\n$draft" else draft
            if (!ordinary && loss && newerOperation != "ordinary") assertEquals("synthetic loss", out.notSent[c.id])
            val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                "seq" to 2, "from" to "person", "text" to "Yes", "kind" to "message")
            model.receive(c.host, canonical)
            assertEquals("Yes", model.lines[c.id]!!.last().text)
            assertFalse(out.waiting(c.id))
            assertEquals("received literal owns no unrelated composer draft", expected, out.unsent[c.id])
            if (newerOperation == "ordinary") assertEquals("late literal owns no newer notice", notice, out.notSent[c.id])
            else if ((!loss || ordinary) && newerOperation != "file") assertNull(out.notSent[c.id]) else assertEquals(notice, out.notSent[c.id])
            if (operation != null) {
                val now = out.files.phase(key)
                assertTrue("the literal's history settles no file operation", now is FilePhase.Unknown)
                assertSame(operation, now.send())
                assertEquals(listOf(file), operation.files)
            } else assertEquals(listOf(file), out.files.draft(key))
            println("LITERAL_RECEIVED loss=$loss newer=$newer ordinary=$ordinary expected=$expected actual=${out.unsent[c.id]} filesPreserved=true")
        } finally {
            model.endFixture()
        }
    }
}
