package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A refusal after the receipt (`error.of` the original begin id): it names the
 * original conversation and files from metadata only, and the bridge's
 * persisted `kind=status` line stays readable history without any live
 * correlation, across a restart (JVM, Robolectric's SQLite).
 */
@RunWith(RobolectricTestRunner::class)
class LateFileRefusalHistoryTest {
    private val host = "fixture-host"
    private val agent = Agent(host, "box/agent", "hermes", listOf("incoming_files"))

    @Test fun late_refusal_after_receipt_names_the_original_conversation_and_holds_no_bytes() {
        val model = fixtureModel()
        val bridge = FakeBridge()
        try {
            model.agents.add(agent)
            bridge.attach(model, host)
            for (id in listOf("one", "two")) {
                model.receive(host, message("conversation", "conversation" to id, "agent" to agent.name))
            }
            val one = model.conversations.single { it.id == "one" }
            val a = FileKey(host, "one")
            val b = FileKey(host, "two")
            val before = PreparedBudget.process.used()
            val notes = SendFile("notes.pdf", "application/pdf", ByteArray(5_000) { 7 })
            model.outgoing.files.drafted(a, notes)
            assertNull(model.send(one, "read this"))
            settle { model.outgoing.files.phase(a) is FilePhase.AwaitingAck }
            val id = model.outgoing.files.phase(a).send()!!.id
            model.receive(host, message("ack", "of" to id))
            assertEquals(FilePhase.Draft, model.outgoing.files.phase(a))
            assertEquals("the receipt releases the bytes", before, PreparedBudget.process.used())
            // A newer, unrelated draft in another conversation of the same host.
            val other = SendFile("other.txt", "text/plain", ByteArray(10))
            model.outgoing.files.drafted(b, other)
            model.openedConversation.value = ConversationKey(host, "two")
            val frames = bridge.frames.size

            model.receive(host, message("error", "of" to id, "code" to "unsupported", "message" to "this model does not read PDFs"))

            val error = model.outgoing.files[a].error.orEmpty()
            assertTrue(error, error.contains("notes.pdf"))
            assertTrue(error, error.contains("this model does not read PDFs"))
            assertEquals(FilePhase.Draft, model.outgoing.files.phase(a))
            assertNull("another conversation is not told", model.outgoing.files[b].error)
            assertEquals(listOf(other), model.outgoing.files.draft(b))
            assertEquals("nothing is resent", frames, bridge.frames.size)
            assertEquals("only the newer draft's bytes are held", before + 10, PreparedBudget.process.used())
            // The same refusal again is consumed: told once, and never as the host's own error
            // or in another conversation, though the correlation keeps names only.
            model.outgoing.files.error(a, null)
            model.said.remove(host)
            model.receive(host, message("error", "of" to id, "code" to "unsupported", "message" to "again"))
            model.receive(host, message("error", "of" to id, "code" to "internal", "message" to "and again"))
            assertNull(model.outgoing.files[a].error)
            assertNull("a repeat is not a generic host error", model.said[host])
            assertNull(model.outgoing.files[b].error)
            assertEquals(listOf(other), model.outgoing.files.draft(b))
            assertEquals("nothing is resent", frames, bridge.frames.size)
            assertEquals(before + 10, PreparedBudget.process.used())
        } finally {
            model.outgoing.clear()
            model.endFixture()
        }
    }

    @Test fun persisted_status_stays_in_history_after_restart_without_a_live_correlation() {
        val text = "notes.pdf was not delivered: this model does not read PDFs."
        val first = fixtureModel()
        try {
            first.agents.add(agent)
            first.receive(host, message("conversation", "conversation" to "one", "agent" to agent.name))
            // Fields a bridge might add are not read into the line: the status is words only.
            first.receive(host, message("message", "conversation" to "one", "agent" to agent.name, "seq" to 7L,
                "from" to "agent", "kind" to "status", "text" to text, "time" to 1_700_000_000L,
                "of" to "11111111-1111-1111-1111-111111111111", "operation" to "failed", "files" to listOf("notes.pdf")))
            assertEquals(listOf(Line(7, "agent", text, "status", 1_700_000_000L)), first.lines["one"])
            assertTrue(first.listed(first.conversations.single { it.id == "one" }))
        } finally { first.endFixture() }

        val again = fixtureModel()
        try {
            settle { again.lines["one"].orEmpty().isNotEmpty() }
            assertEquals("the kept line is the same words, no machine-readable delivery state",
                listOf(Line(7, "agent", text, "status", 1_700_000_000L)), again.lines["one"])
            // No live correlation survives a restart: the refusal changes no draft and the history stays.
            again.receive(host, message("error", "of" to "11111111-1111-1111-1111-111111111111", "code" to "unsupported", "message" to "late"))
            assertNull(again.outgoing.files[FileKey(host, "one")].error)
            assertEquals(FilePhase.Draft, again.outgoing.files.phase(FileKey(host, "one")))
            assertEquals(text, again.lines["one"]!!.single().text)
        } finally { again.endFixture() }
    }
}
