package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString

/** Receipts, refusals and history for a message with files; send pacing on a synthetic socket. */
@RunWith(RobolectricTestRunner::class)
class FileSendPathTest {
    private val key = FileKey("h", "c")

    @Test fun explicit_ack_settles_control() {
        val out = Outgoing()
        out.files.drafted(key, SendFile("f.txt", "text/plain", byteArrayOf(1)))
        val send = out.files.begin(key, "hello")!!
        assertTrue(out.files.acknowledged("h", send.id))
        assertFalse(out.files.busy("h"))
        assertEquals(FilePhase.Draft, out.files.phase(key))
        assertTrue(out.files.draft(key).isEmpty())
    }

    @Test fun canonical_history_without_origin_id_keeps_upload_unknown() {
        val out = Outgoing()
        out.files.drafted(key, SendFile("f.txt", "text/plain", byteArrayOf(1)))
        val send = out.files.begin(key, "hello")!!
        out.files.transmitted(key, send.id)
        // DeviceWriter::send chooses a new envelope id; ConversationMessage has no origin id.
        out.shown("c", "hello")
        out.shown("c", "hello", "h", hasAttachments = true)
        assertTrue("canonical history has no safe correlation; continue awaiting ACK", out.files.phase(key) is FilePhase.AwaitingAck)
        out.clear()
    }

    @Test fun canonical_history_then_refusal_retains_original_and_newer_text() {
        val out = Outgoing()
        out.files.drafted(key, SendFile("f.txt", "text/plain", byteArrayOf(1)))
        out.write("c", "hello")
        val send = out.files.begin(key, "hello")!!
        out.write("c", "")
        out.write("c", "next message")
        out.shown("c", "hello")
        assertTrue(out.files.refused("h", send.id, "unconfirmed", "the message is kept, and goes to the agent once it can be reached"))
        assertEquals("Only newer composer text should remain after stored message", "next message", out.unsent["c"])
        assertEquals("original payload retained", send.id, (out.files.phase(key) as FilePhase.Unknown).send.id)
        out.clear()
    }

    @Test fun unknown_retry_is_the_same_original_and_old_ack_preserves_newer_draft() {
        val out = Outgoing()
        val original = SendFile("original.txt", "text/plain", byteArrayOf(1))
        out.files.drafted(key, original)
        val send = out.files.begin(key, "original")!!
        // The draft's bytes moved to the operation: nobody else holds them to change them.
        assertSame(original, send.files.single())
        assertTrue(out.files.draft(key).isEmpty())
        out.ended("h", "disconnected")
        out.write("c", "new text")
        val retry = out.files.retry(key)!!
        assertSame(send, retry)
        assertEquals(send.id, retry.id)
        assertEquals("original", retry.text)
        assertSame(original, retry.files.single())
        assertTrue(out.files.refused("h", send.id, "internal", "post-storage refusal"))
        assertEquals("new text", out.unsent["c"])
        assertTrue(out.files.acknowledged("h", send.id))
        assertEquals("new text", out.unsent["c"])
        assertNull(out.files.phase(key).send())
        val newer = SendFile("new.txt", "text/plain", byteArrayOf(2))
        out.files.drafted(key, newer)
        assertEquals(listOf(newer), out.files.draft(key))
        out.clear()
    }

    @Test fun discarded_original_ack_cannot_clear_newer_retry_active_owner() {
        val out = Outgoing()
        out.files.drafted(key, SendFile("old.txt", "text/plain", byteArrayOf(1)))
        val old = out.files.begin(key, "old")!!
        out.ended("h", "disconnect")
        out.files.dismiss(key)
        out.files.drafted(key, SendFile("new.txt", "text/plain", byteArrayOf(2)))
        val new = out.files.begin(key, "new")!!
        val active = kotlinx.coroutines.Job()
        out.files.started(key, new.id, active)
        out.write("c", "newer draft")
        assertFalse(out.files.acknowledged("h", old.id))
        val now = out.files.phase(key) as FilePhase.Sending
        assertEquals(new.id, now.send.id)
        assertSame(active, now.job)
        assertFalse(active.isCancelled)
        assertEquals("newer draft", out.unsent["c"])
        assertTrue(out.files.busy("h"))
        out.clear()
    }

    private class QueuedSocket(val checked: CountDownLatch): WebSocket {
        override fun request() = Request.Builder().url("https://example.invalid/").build()
        override fun queueSize(): Long { checked.countDown(); return 128_001 }
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
    private fun connection(socket: WebSocket): BridgeConnection {
        val c = BridgeConnection("https://example.invalid", ByteArray(32), { ByteArray(32) },
            { error("unused handshake") }, {}, {}, {})
        BridgeConnection::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(c, socket)
        return c
    }
    @Test fun already_interrupted_worker_does_not_enter_small_queue() {
        var checks = 0
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://example.invalid/").build()
            override fun queueSize(): Long { checks++; return 0 }
            override fun send(text: String) = error("must not enqueue")
            override fun send(bytes: ByteString) = error("must not enqueue")
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {}
        }
        val c = connection(socket)
        try {
            Thread.currentThread().interrupt()
            assertFalse(c.sendPaced(message("message.part")))
            assertEquals(0, checks)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); c.close() }
    }

    @Test fun closing_connection_exits_pacing_cleanly_control() {
        val c = connection(QueuedSocket(CountDownLatch(1)))
        c.close("normal closure")
        assertFalse(c.sendPaced(message("message.part")))
    }
    @Test fun shutdownNow_must_not_escape_an_uncaught_worker_exception() {
        val checked = CountDownLatch(1)
        val uncaught = AtomicReference<Throwable?>()
        val handled = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "reviewer-owned-file-worker").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> uncaught.set(failure); handled.countDown() }
        } }
        val c = connection(QueuedSocket(checked))
        try {
            worker.execute { c.sendPaced(message("message.part")) }
            assertTrue("worker must reach actual queue pacing", checked.await(5, TimeUnit.SECONDS))
            worker.shutdownNow()
            assertTrue("worker should terminate", worker.awaitTermination(5, TimeUnit.SECONDS))
            handled.await(1, TimeUnit.SECONDS)
            assertNull("ViewModel shutdown must not throw to Android default handler", uncaught.get())
        } finally { c.close(); worker.shutdownNow() }
    }
}
