package io.github.victorbonnet.enclos

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual AppModel received path; no network or real pairing. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiteralReceivedOwnershipTest {
    @Suppress("UNCHECKED_CAST") private fun <T> field(m: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(m) as T
    @Test fun equal_existing_draft_survives_late_literal_received() = exercise("Yes", true, false, false)
    @Test fun equal_newer_draft_survives_late_literal_received() = exercise("before", true, true, false)
    @Test fun different_draft_survives_late_literal_received() = exercise("different", true, false, false)
    @Test fun equal_draft_survives_literal_received_without_loss() = exercise("Yes", false, false, false)
    @Test fun ordinary_restored_text_is_retired_by_received_positive() = exercise("", true, false, true)
    @Test fun late_literal_preserves_newer_ordinary_notice() = exercise("Yes", true, false, false, "ordinary")
    @Test fun late_literal_preserves_newer_file_notice() = exercise("Yes", true, false, false, "file")
    private fun exercise(draft: String, loss: Boolean, newer: Boolean, ordinary: Boolean, newerNotice: String? = null) {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val c = Conversation("fixture-host", "box/agent", "fixture-literal-c")
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            model.links[c.host] = HostLink.Connected("fixture-person")
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

            val out = model.outgoing
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            model.lines[c.id] = listOf(line)
            val file = SendFile("unrelated.txt", "text/plain", byteArrayOf(7))
            out.files.drafts[c.id] = listOf(file)
            out.write(c.id, draft)
            if (ordinary) {
                assertTrue(out.send(c.id, c.host, "Yes", withFiles = false) { "ordinary-id" })
            } else {
                assertNull(model.sendSuggested(c, line, "Yes"))
                assertEquals(listOf("message.send"), frames.map { it.getString("type") })
                assertEquals(draft, out.unsent[c.id])
            }
            if (loss) out.ended(c.host, "synthetic loss")
            if (newer) out.write(c.id, "Yes")
            if (newerNotice != null) {
                out.send(c.id, c.host, "new operation", withFiles = newerNotice == "file") { "new-id" }
                out.ended(c.host, "new ordinary notice")
            }
            val notice = out.notSent[c.id]
            val expected = if (newerNotice == "ordinary") "new operation" else if (newerNotice == "file") null else if (ordinary) null else if (newer) "Yes" else draft
            if (!ordinary && loss && newerNotice == null) assertEquals("Reply unconfirmed. Check history before sending it again.", out.notSent[c.id])
            val host = Host(c.host, "https://example.invalid", ByteArray(32), "device", byteArrayOf(), "fixture", byteArrayOf())
            val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                "seq" to 2, "from" to "person", "text" to "Yes", "kind" to "message")
            AppModel::class.java.getDeclaredMethod("received", Host::class.java, JSONObject::class.java).apply { isAccessible = true }.invoke(model, host, canonical)
            assertEquals("Yes", model.lines[c.id]!!.last().text)
            assertFalse(out.waiting(c.id))
            if (newerNotice == null) assertNull("settled operation retires its notice", out.notSent[c.id])
            else assertEquals("late literal owns no newer notice", notice, out.notSent[c.id])
            if (newerNotice != "file") assertEquals(listOf(file), out.files.drafts[c.id])
            println("LITERAL_RECEIVED loss=$loss newer=$newer ordinary=$ordinary expected=$expected actual=${out.unsent[c.id]} filesPreserved=true")
            assertEquals("received literal owns no unrelated composer draft", expected, out.unsent[c.id])
        } finally {
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try {
                for (name in listOf("saving", "work", "disk", "fileWorker")) assertTrue(field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS))
            } finally { field<Store>(model, "store").close() }
        }
    }
}
