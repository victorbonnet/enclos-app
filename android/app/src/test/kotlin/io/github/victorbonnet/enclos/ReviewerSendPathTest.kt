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

/** Synthetic protocol/error and concurrency probes on pinned production classes. */
@RunWith(RobolectricTestRunner::class)
class ReviewerSendPathTest {
    @Test fun explicit_ack_settles_control() {
        val out = Outgoing()
        out.files.drafts["c"] = listOf(SendFile("f.txt", "text/plain", byteArrayOf(1)))
        out.send("c", "h", "hello") { "begin-id" }
        out.acknowledged("h", "begin-id")
        assertFalse(out.waiting("c"))
        assertNull(out.files.drafts["c"])
    }

    @Test fun canonical_history_without_origin_id_keeps_upload_unknown() {
        val out = Outgoing()
        out.files.drafts["c"] = listOf(SendFile("f.txt", "text/plain", byteArrayOf(1)))
        out.send("c", "h", "hello") { "begin-id" }
        // DeviceWriter::send chooses a new envelope id; ConversationMessage has no origin id.
        out.shown("c", "hello")
        assertTrue("canonical history has no safe correlation; continue awaiting ACK", out.waiting("c"))
    }

    @Test fun canonical_history_then_refusal_retains_original_and_newer_text() {
        val out = Outgoing()
        val file = SendFile("f.txt", "text/plain", byteArrayOf(1))
        out.files.drafts["c"] = listOf(file)
        val begin = out.files.id("c", "hello")
        out.send("c", "h", "hello") { begin }
        out.write("c", "next message")
        out.shown("c", "hello")
        out.refused(begin, "the message is kept, and goes to the agent once it can be reached")
        assertEquals("Only newer composer text should remain after stored message", "next message", out.unsent["c"])
        assertNotNull("original payload retained", out.files.retries["c"])
    }

    @Test fun unknown_retry_is_immutable_and_old_ack_preserves_newer_draft() {
        val out = Outgoing()
        val bytes = byteArrayOf(1)
        out.files.drafts["c"] = listOf(SendFile("original.txt", "text/plain", bytes))
        val id = out.files.id("c", "original")
        out.send("c", "h", "original") { id }
        bytes[0] = 9
        out.ended("h", "disconnected")
        out.write("c", "new text")
        val newer = SendFile("new.txt", "text/plain", byteArrayOf(2))
        out.files.drafts["c"] = listOf(newer)
        val retry = out.files.retries.getValue("c")
        assertEquals(id, retry.id)
        assertEquals("original", retry.text)
        assertArrayEquals(byteArrayOf(1), retry.files.single().bytes)
        out.send("c", "h", retry.text, withFiles = true, preserveDraft = true) { retry.id }
        out.refused(id, "post-storage refusal")
        assertEquals("new text", out.unsent["c"])
        out.acknowledged("h", id)
        assertEquals("new text", out.unsent["c"])
        assertEquals(listOf(newer), out.files.drafts["c"])
        assertNull(out.files.retries["c"])
    }

    @Test fun discarded_original_ack_cannot_clear_newer_retry_active_owner() {
        val out = Outgoing()
        out.files.drafts["c"] = listOf(SendFile("old.txt", "text/plain", byteArrayOf(1)))
        out.send("c", "h", "old") { "old-id" }
        out.ended("h", "disconnect")
        out.discardRetry("c")
        out.files.drafts["c"] = listOf(SendFile("new.txt", "text/plain", byteArrayOf(2)))
        out.send("c", "h", "new") { "new-id" }
        val active = Any()
        out.files.active["c"] = active
        out.write("c", "newer draft")
        out.acknowledged("h", "old-id")
        assertEquals("new-id", out.files.retries["c"]?.id)
        assertSame(active, out.files.active["c"])
        assertEquals("newer draft", out.unsent["c"])
        assertTrue(out.waiting("c"))
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
