package io.github.victorbonnet.enclos

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A suggested reply sends its label alone; only the composer's own Send takes the draft's files. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SuggestedReplyFilesTest {
    @Test fun reply_label_must_not_schedule_the_composers_private_files() = exercise(false)
    @Test fun explicit_composer_send_schedules_files() = exercise(true)
    private fun exercise(composer: Boolean) {
        val model = fixtureModel()
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        // The first upload waits here, on its own thread, until released.
        val bridge = FakeBridge { if (it.getString("type") == "message.begin" && first.getAndSet(false)) release.await(5, TimeUnit.SECONDS) }
        try {
            val c = Conversation("fixture-host", "box/agent", "fixture-c")
            val key = FileKey(c.host, c.id)
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            bridge.attach(model, c.host)
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            model.lines[c.id] = listOf(line)
            val file = SendFile("private-notes.txt", "text/plain", byteArrayOf(1))
            val out = model.outgoing
            out.files.drafted(key, file)
            out.write(c.id, "Unrelated draft for these private notes")
            if (composer) {
                assertNull(model.send(c, "Explicit file message"))
                val sending = out.files.phase(key) as FilePhase.Sending
                assertEquals(0, sending.progress)
                assertEquals("Explicit file message", sending.send.text)
                assertEquals(listOf(file), sending.send.files)
                release.countDown()
                settle { out.files.phase(key) is FilePhase.AwaitingAck }
                val begin = bridge.ofType("message.begin").single()
                val id = begin.getString("id")
                assertEquals(sending.send.id, id)
                val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                    "seq" to 2, "from" to "person", "text" to "Explicit file message", "kind" to "message")
                model.receive(c.host, canonical)
                assertTrue("fresh canonical envelope cannot settle upload", out.files.phase(key) is FilePhase.AwaitingAck)
                out.ended(c.host, "disconnect")
                assertTrue(out.files.phase(key) is FilePhase.Unknown)
                out.write(c.id, "Newer draft")
                assertNull(model.retryFiles(c))
                settle { out.files.phase(key) is FilePhase.AwaitingAck }
                val retried = bridge.ofType("message.begin").last()
                assertEquals(2, bridge.ofType("message.begin").size)
                assertEquals(begin.toString(), retried.toString())
                model.receive(c.host, message("error", "of" to id, "code" to "internal", "message" to "post-storage refusal"))
                assertTrue(out.files.phase(key) is FilePhase.Unknown)
                assertEquals("Newer draft", out.unsent[c.id])
                assertTrue(out.files.draft(key).isEmpty())
                assertNull(model.send(c, "Later text", includeFiles = false))
                model.receive(c.host, message("ack", "of" to id))
                assertEquals(FilePhase.Draft, out.files.phase(key))
                assertTrue("new text still awaiting receipt", out.waiting(c.id))
                out.ended(c.host, "disconnect newer text")
                val newer = SendFile("new.txt", "text/plain", byteArrayOf(2))
                out.files.drafted(key, newer)
                assertNull(model.send(c, "New file original"))
                settle { out.files.phase(key) is FilePhase.AwaitingAck }
                val now = out.files.phase(key) as FilePhase.AwaitingAck
                assertNotEquals(id, now.send.id)
                model.receive(c.host, message("ack", "of" to id))
                assertSame("an old receipt settles nothing newer", now, out.files.phase(key))
                assertFalse(now.job!!.isCancelled)
                return
            }
            assertNull(model.sendSuggested(c, line, "Yes"))
            assertEquals(listOf("message.send"), bridge.types())
            assertEquals("Yes", bridge.frames.single().getString("text"))
            model.outgoing.refused(bridge.frames.single().getString("id"), "refused")
            assertNull(model.sendSuggested(c, line, "Yes"))
            model.outgoing.ended(c.host, "disconnect")
            assertFalse(model.outgoing.waiting(c.id))
            val notice = model.outgoing.notSent[c.id]
            assertEquals("disconnect", notice)
            model.links[c.host] = HostLink.Ended("fixture disconnect")
            model.links[c.host] = HostLink.Connected("fixture-person")
            release.countDown()
            settle { true }
            assertEquals(notice, model.outgoing.notSent[c.id])
            assertEquals(listOf("message.send", "message.send"), bridge.types())
            assertEquals("Yes\n\nYes\n\nUnrelated draft for these private notes", model.outgoing.unsent[c.id])
            assertEquals(listOf(file), out.files.draft(key))
            assertEquals("reply button must not schedule file transfer from unseen draft", FilePhase.Draft, out.files.phase(key))
        } finally {
            release.countDown()
            model.outgoing.clear()
            model.endFixture()
        }
    }
}
