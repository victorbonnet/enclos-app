package io.github.victorbonnet.enclos

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class R2ReviewerRegressionTest {
    @Test fun literal_disconnect_reports_uncertainty_without_polluting_draft() {
        val out = Outgoing()
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
        val file = SendFile("private.txt", "text/plain", byteArrayOf(1))
        out.files.drafts["c"] = listOf(file)
        out.write("c", "unrelated draft")
        assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
            assertTrue(out.send("c", "h", text, withFiles = false) { "literal" })
            null
        })
        assertTrue(out.waiting("c"))
        out.ended("h", "connection ended before reply confirmation")
        assertEquals("unrelated draft", out.unsent["c"])
        assertEquals(listOf(file), out.files.drafts["c"])
        assertFalse(out.waiting("c"))
        println("LITERAL_LOSS notice=${out.notSent["c"]} draft=${out.unsent["c"]}")
        assertNotNull("unconfirmed literal reply needs a failure/unknown notice", out.notSent["c"])
    }

    @Test fun ordinary_text_disconnect_has_notice_control() {
        val out = Outgoing()
        assertTrue(out.send("c", "h", "ordinary", withFiles = false) { "text" })
        out.ended("h", "lost")
        assertEquals("ordinary", out.unsent["c"])
        assertEquals("lost", out.notSent["c"])
    }

    @Test fun literal_refusal_has_notice_control() {
        val out = Outgoing()
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
        out.write("c", "draft")
        assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
            out.send("c", "h", text, withFiles = false) { "literal" }
            null
        })
        out.refused("literal", "refused")
        assertEquals("draft", out.unsent["c"])
        assertEquals("refused", out.notSent["c"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(m: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(m) as T

    @Test fun navigation_during_first_file_send_preserves_operation_until_confirmation() = firstFileSend(true)
    @Test fun first_file_send_without_navigation_positive_control() = firstFileSend(false)
    @Test fun unknown_first_file_send_survives_repeated_back_and_retries_original() = firstFileSend(true, "retry")
    @Test fun unknown_first_file_send_explicit_discard_retires_empty_conversation() = firstFileSend(true, "discard")
    @Test fun unknown_first_file_send_host_reset_retires_original() = firstFileSend(true, "reset")
    private fun firstFileSend(navigate: Boolean, after: String? = null) {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val worker = field<ExecutorService>(model, "fileWorker")
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val closes = AtomicInteger()
        val frames = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        try {
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val agent = Agent("fixture-host", "box/agent", "hermes", listOf("incoming_files"))
            val id = model.newConversation(agent)
            val c = model.conversations.single { it.id == id }
            model.agents.add(agent)
            model.links[c.host] = HostLink.Connected("fixture-person")
            field<MutableMap<String, Set<String>>>(model, "hostCapabilities")[c.host] = setOf("message.begin")
            val connection = BridgeConnection("https://example.invalid", ByteArray(32), { ByteArray(32) }, { error("unused") }, {}, {}, {})
            val socket = object : WebSocket {
                override fun request() = Request.Builder().url("https://example.invalid").build()
                override fun queueSize() = 0L
                override fun send(text: String) = false
                override fun send(bytes: ByteString): Boolean { frames.add(JSONObject(bytes.utf8())); return true }
                override fun close(code: Int, reason: String?): Boolean { closes.incrementAndGet(); return true }
                override fun cancel() {}
            }
            val session = object : io.github.victorbonnet.enclos.core.DeviceSession(io.github.victorbonnet.enclos.core.NoHandle) {
                override fun encrypt(plaintext: ByteArray) = plaintext
            }
            BridgeConnection::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(connection, socket)
            BridgeConnection::class.java.getDeclaredField("session").apply { isAccessible = true }.set(connection, session)
            field<MutableMap<String, BridgeConnection>>(model, "connections")[c.host] = connection
            worker.execute { blocked.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            model.outgoing.files.drafts[id] = listOf(SendFile("first.txt", "text/plain", byteArrayOf(1)))
            assertNull(model.send(c, "first file message"))
            assertTrue(model.outgoing.waitingFiles(id))
            assertNotNull(model.outgoing.files.retries[id])
            val original = model.outgoing.files.retries.getValue(id)
            val active = model.outgoing.files.active[id]
            // Real main-thread order: Send, then Back, before bridge's conversation echo.
            if (navigate) model.left(id)
            val retainedConversation = model.conversations.any { it.id == id }
            val retainedRetry = model.outgoing.files.retries[id] != null
            assertSame(active, model.outgoing.files.active[id])
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            println("FIRST_FILE_BACK retainedConversation=$retainedConversation retainedRetry=$retainedRetry closes=${closes.get()} frames=${frames.map { it.optString("type") }}")
            assertTrue("a user-initiated in-flight send is not an abandoned unsent draft", retainedRetry)
            assertEquals("ordinary Back must not close shared host connection", 0, closes.get())
            assertTrue(retainedConversation)
            assertSame(original, model.outgoing.files.retries[id])
            assertEquals(listOf("conversation.new", "message.begin", "message.part"), frames.map { it.getString("type") })
            val begin = frames[1].toString()
            val part = frames[2].toString()
            assertEquals(original.id, frames[1].getString("id"))
            if (after != null) {
                model.outgoing.ended(c.host, "lost before echo")
                model.left(id)
                model.left(id)
                assertTrue(model.conversations.any { it.id == id })
                assertSame(original, model.outgoing.files.retries[id])
                assertFalse(model.outgoing.waiting(id))
                assertNotNull(model.outgoing.notSent[id])
                assertEquals(3, frames.size) // navigation and loss never resend
                when (after) {
                    "retry" -> {
                        model.outgoing.write(id, "newer text")
                        val newer = SendFile("new.txt", "text/plain", byteArrayOf(2))
                        model.outgoing.files.drafts[id] = listOf(newer)
                        model.left(id)
                        assertNull(model.retryFiles(c))
                        worker.submit {}.get(5, TimeUnit.SECONDS)
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals(listOf("conversation.new", "message.begin", "message.part", "conversation.new", "message.begin", "message.part"), frames.map { it.getString("type") })
                        assertEquals(begin, frames[4].toString())
                        // Part envelopes have fresh IDs; payload and begin reference are identical.
                        val originalPart = JSONObject(part).apply { remove("id") }
                        val retriedPart = JSONObject(frames[5].toString()).apply { remove("id") }
                        assertEquals(originalPart.toString(), retriedPart.toString())
                        assertEquals("newer text", model.outgoing.unsent[id])
                        assertEquals(listOf(newer), model.outgoing.files.drafts[id])
                        assertEquals(0, closes.get())
                    }
                    "discard" -> {
                        model.outgoing.discardRetry(id)
                        model.left(id)
                        assertFalse(model.conversations.any { it.id == id })
                        assertNull(model.outgoing.files.retries[id])
                        assertNull(model.outgoing.files.active[id])
                    }
                    "reset" -> {
                        val generation = model.outgoing.files.selection(c.host, id)
                        AppModel::class.java.getDeclaredMethod("forget", String::class.java).apply { isAccessible = true }.invoke(model, c.host)
                        assertFalse(model.conversations.any { it.id == id })
                        assertNull(model.outgoing.files.retries[id])
                        assertFalse(model.outgoing.files.owns(id, generation))
                        assertNull(model.outgoing.notSent[id])
                    }
                }
            }
        } finally {
            release.countDown()
            model.outgoing.clear()
            worker.shutdown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try {
                for (name in listOf("saving", "work", "disk", "fileWorker")) field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS)
            } finally { field<Store>(model, "store").close() }
        }
    }
}
