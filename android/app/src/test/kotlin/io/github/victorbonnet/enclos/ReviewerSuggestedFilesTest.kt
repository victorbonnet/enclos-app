package io.github.victorbonnet.enclos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReviewerSuggestedFilesTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(model: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model) as T

    @Test fun reply_label_must_not_schedule_the_composers_private_files() = exercise(false)
    @Test fun explicit_composer_send_schedules_files() = exercise(true)
    private fun exercise(composer: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = AppModel(app)
        val worker = field<ExecutorService>(model, "fileWorker")
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        try {
            // Finish synthetic cache initialization before setting model fixtures.
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val c = Conversation("fixture-host", "box/agent", "fixture-c")
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            model.links[c.host] = HostLink.Connected("fixture-person")
            field<MutableMap<String, Set<String>>>(model, "hostCapabilities")[c.host] = setOf("message.begin")
            val connection = BridgeConnection("https://example.invalid", ByteArray(32), { ByteArray(32) }, { error("unused") }, {}, {}, {})
            val frames = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
            val socket = object : okhttp3.WebSocket {
                override fun request() = okhttp3.Request.Builder().url("https://example.invalid").build()
                override fun queueSize() = 0L
                override fun send(text: String) = false
                override fun send(bytes: okio.ByteString): Boolean { frames.add(org.json.JSONObject(bytes.utf8())); return true }
                override fun close(code: Int, reason: String?) = true
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
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            model.lines[c.id] = listOf(line)
            val file = SendFile("private-notes.txt", "text/plain", byteArrayOf(1))
            model.outgoing.files.drafts[c.id] = listOf(file)
            model.outgoing.write(c.id, "Unrelated draft for these private notes")
            if (composer) {
                assertNull(model.send(c, "Explicit file message"))
                assertEquals(0, model.outgoing.files.progress[c.id])
                assertEquals("Explicit file message", model.outgoing.files.retries[c.id]?.text)
                release.countDown()
                worker.submit {}.get(5, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val begin = frames.first { it.getString("type") == "message.begin" }
                val id = begin.getString("id")
                val host = Host(c.host, "https://example.invalid", ByteArray(32), "device", byteArrayOf(), "fixture", byteArrayOf())
                val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                    "seq" to 2, "from" to "person", "text" to "Explicit file message", "kind" to "message")
                AppModel::class.java.getDeclaredMethod("received", Host::class.java, org.json.JSONObject::class.java)
                    .apply { isAccessible = true }.invoke(model, host, canonical)
                assertTrue("fresh canonical envelope cannot settle upload", model.outgoing.waiting(c.id))
                model.outgoing.ended(c.host, "disconnect")
                val newer = SendFile("new.txt", "text/plain", byteArrayOf(2))
                model.outgoing.files.drafts[c.id] = listOf(newer)
                model.outgoing.write(c.id, "Newer draft")
                assertNull(model.retryFiles(c))
                worker.submit {}.get(5, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val retried = frames.last { it.getString("type") == "message.begin" }
                assertEquals(begin.toString(), retried.toString())
                model.outgoing.refused(id, "post-storage refusal")
                assertEquals("Newer draft", model.outgoing.unsent[c.id])
                assertEquals(listOf(newer), model.outgoing.files.drafts[c.id])
                assertNull(model.send(c, "Later text", includeFiles = false))
                model.outgoing.acknowledged(c.host, id)
                assertTrue("new text still awaiting receipt", model.outgoing.waiting(c.id))
                assertEquals(listOf(newer), model.outgoing.files.drafts[c.id])
                model.outgoing.ended(c.host, "disconnect newer text")
                model.outgoing.files.drafts[c.id] = listOf(newer)
                assertNull(model.send(c, "New file original"))
                worker.submit {}.get(5, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val newId = model.outgoing.files.retries.getValue(c.id).id
                val active = model.outgoing.files.active[c.id]
                model.outgoing.acknowledged(c.host, id)
                assertEquals(newId, model.outgoing.files.retries[c.id]?.id)
                assertSame(active, model.outgoing.files.active[c.id])
                return
            }
            assertNull(model.sendSuggested(c, line, "Yes"))
            assertEquals(listOf("message.send"), frames.map { it.getString("type") })
            assertEquals("Yes", frames.single().getString("text"))
            model.outgoing.refused(frames.single().getString("id"), "refused")
            assertNull(model.sendSuggested(c, line, "Yes"))
            model.outgoing.ended(c.host, "disconnect")
            assertFalse(model.outgoing.waiting(c.id))
            val notice = model.outgoing.notSent[c.id]
            assertEquals("Reply unconfirmed. Check history before sending it again.", notice)
            model.links[c.host] = HostLink.Ended("fixture disconnect")
            model.links[c.host] = HostLink.Connected("fixture-person")
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(notice, model.outgoing.notSent[c.id])
            assertEquals(listOf("message.send", "message.send"), frames.map { it.getString("type") })
            assertEquals("Unrelated draft for these private notes", model.outgoing.unsent[c.id])
            assertEquals(listOf(file), model.outgoing.files.drafts[c.id])
            assertNull("reply button must not schedule file transfer from unseen draft", model.outgoing.files.progress[c.id])
        } finally {
            model.outgoing.clear()
            release.countDown()
            worker.shutdown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try {
                for (name in listOf("saving", "work", "disk", "fileWorker")) {
                    assertTrue(field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS))
                }
            } finally { field<Store>(model, "store").close() }
        }
    }
}
