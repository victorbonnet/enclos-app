package io.github.victorbonnet.enclos

import android.app.Application
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A message with files shown back settles no text message; production state owners, synthetic socket, not native/network proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AttachmentEchoTest {
    @Test fun file_echo_must_not_delete_equal_unsent_ordinary_text() = exercise("Yes", false, true)
    @Test fun file_echo_must_not_retire_equal_literal_uncertainty() = exercise("Yes", true, true)
    @Test fun different_file_text_preserves_ordinary_control() = exercise("different file text", false, true)
    @Test fun ordinary_canonical_echo_deduplicates_restored_text_control() = exercise("Yes", false, false)
    private fun exercise(fileText: String, literal: Boolean, attached: Boolean) {
        val model = fixtureModel()
        try {
            val c = Conversation("fixture-host", "box/agent", "r4-c")
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            val bridge = FakeBridge()
            bridge.attach(model, c.host)
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            model.lines[c.id] = listOf(line)
            val out = model.outgoing
            val key = FileKey(c.host, c.id)
            out.files.drafted(key, SendFile("original.txt", "text/plain", byteArrayOf(1, 2, 3)))
            assertNull(model.send(c, fileText))
            settle { out.files.phase(key) is FilePhase.AwaitingAck }
            out.ended(c.host, "first file loss")
            val original = out.files.phase(key)
            assertTrue(original is FilePhase.Unknown)
            if (literal) {
                out.write(c.id, "unrelated draft")
                assertNull(model.sendSuggested(c, line, "Yes"))
            } else {
                out.write(c.id, "Yes")
                assertNull(model.send(c, "Yes", includeFiles = false))
            }
            out.ended(c.host, "ordinary loss")
            val beforeDraft = out.unsent[c.id]
            val beforeNotice = out.notSent[c.id]
            assertFalse("newer files wait for the original's outcome", out.files.preparing(key, Job()))
            assertNotNull(beforeNotice)
            assertEquals(if (literal) "unrelated draft" else "Yes", beforeDraft)
            val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                "seq" to 2, "from" to "person", "text" to (if (attached) fileText else "Yes"), "kind" to "message")
            if (attached) canonical.put("attachments", org.json.JSONArray().put(JSONObject().put("id", "fixture-attachment").put("name", "original.txt").put("mime", "text/plain").put("size", 3)))
            assertNotEquals(original.send()!!.id, canonical.getString("id"))
            model.receive(c.host, canonical)
            assertEquals(if (attached) 1 else 0, model.lines[c.id]!!.last().attachments.size)
            assertSame("history without the original's id settles nothing", original, out.files.phase(key))
            assertTrue(out.files.draft(key).isEmpty())
            println("R4_ATTACHMENT_ECHO attached=$attached literal=$literal fileText=$fileText beforeDraft=$beforeDraft afterDraft=${out.unsent[c.id]} beforeNotice=$beforeNotice afterNotice=${out.notSent[c.id]} frames=${bridge.types()}")
            if (attached) {
                assertEquals("file history must not delete ordinary text restored from a different operation", beforeDraft, out.unsent[c.id])
                assertEquals("file history must not settle a different text-only operation notice", beforeNotice, out.notSent[c.id])
            } else {
                assertNull(out.unsent[c.id])
                assertNull(out.notSent[c.id])
            }
        } finally {
            model.endFixture()
        }
    }
}
