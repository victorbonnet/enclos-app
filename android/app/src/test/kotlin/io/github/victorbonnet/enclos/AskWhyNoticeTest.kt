package io.github.victorbonnet.enclos

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Asking why puts a question in the field without retiring an unconfirmed send's notice: actual dispatch, Ask why and received path; synthetic loss and socket. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AskWhyNoticeTest {
    @Test fun literal_unknown_empty_composer_ask_why_keeps_notice() = exercise(true, true, "")
    @Test fun literal_unknown_existing_draft_ask_why_keeps_notice_positive() = exercise(true, true, "my separate draft")
    @Test fun literal_unknown_no_ask_keeps_notice_positive() = exercise(true, false, "")
    @Test fun ordinary_restored_ask_why_preserves_draft_notice_positive() = exercise(false, true, "")
    private fun exercise(literal: Boolean, ask: Boolean, draft: String) {
        val model = fixtureModel()
        try {
            val c = Conversation("fixture-host", "box/agent", "r5-notice-c")
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
            if (literal) assertNull(model.sendSuggested(c, line, "Yes"))
            else assertNull(model.send(c, "Yes", includeFiles = false))
            assertEquals(listOf("message.send"), bridge.types())
            out.ended(c.host, "synthetic loss")
            val before = out.notSent[c.id]
            assertNotNull("accepted positive before mutation", before)
            if (literal) assertEquals(REPLY_UNCONFIRMED, before)
            val request = PolicyRequest(c.host, "fixture-request", "box", c.agent, c.id, "network", "example.invalid:443", false,
                "connect", 1, "pending", "", false, "fixture-revision", true, null)
            val question = askWhyQuestion(request)
            if (ask) assertEquals(c.id, model.askInConversation(request, question))
            val after = out.notSent[c.id]
            assertEquals("asking doesn't send", listOf("message.send"), bridge.types())
            assertEquals(listOf(file), out.files.draft(key))
            val expectedDraft = if (!literal) "Yes" else if (ask && draft.isEmpty()) question else draft.ifEmpty { null }
            assertEquals(expectedDraft, out.unsent[c.id])
            assertFalse(out.waiting(c.id))
            assertEquals(listOf("Yes"), offeredReplies(line, model.lines[c.id].orEmpty()))
            val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                "seq" to 2, "from" to "person", "text" to "Yes", "kind" to "message")
            model.receive(c.host, canonical)
            assertNull("genuine received retires its notice", out.notSent[c.id])
            if (literal) assertEquals(expectedDraft, out.unsent[c.id])
            assertEquals(listOf(file), out.files.draft(key))
            println("R5_NOTICE literal=$literal ask=$ask existingDraft=${draft.isNotEmpty()} before=$before afterAsk=$after frames=${bridge.frames.size} actualReceived=true")
            assertEquals("Ask why owns only its question, not an unconfirmed send's warning", before, after)
        } finally {
            model.endFixture()
        }
    }
}
