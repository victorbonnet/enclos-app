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

/** Adopted R5 actual dispatch/AskWhy/received regression; synthetic loss/socket/identity cipher. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class R5AskWhyNoticeTest {
    @Suppress("UNCHECKED_CAST") private fun <T> field(m: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(m) as T
    @Test fun literal_unknown_empty_composer_ask_why_keeps_notice() = exercise(true, true, "")
    @Test fun literal_unknown_existing_draft_ask_why_keeps_notice_positive() = exercise(true, true, "my separate draft")
    @Test fun literal_unknown_no_ask_keeps_notice_positive() = exercise(true, false, "")
    @Test fun ordinary_restored_ask_why_preserves_draft_notice_positive() = exercise(false, true, "")
    private fun exercise(literal: Boolean, ask: Boolean, draft: String) {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val c = Conversation("fixture-host", "box/agent", "r5-notice-c")
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            model.links[c.host] = HostLink.Connected("fixture-person")
            val connection = BridgeConnection("https://example.invalid", ByteArray(32), { ByteArray(32) }, { error("unused") }, {}, {}, {})
            val frames = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
            val socket = object : okhttp3.WebSocket {
                override fun request() = okhttp3.Request.Builder().url("https://example.invalid").build()
                override fun queueSize() = 0L
                override fun send(text: String) = false
                override fun send(bytes: okio.ByteString): Boolean { frames.add(JSONObject(bytes.utf8())); return true }
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
            if (literal) assertNull(model.sendSuggested(c, line, "Yes"))
            else assertNull(model.send(c, "Yes", includeFiles = false))
            assertEquals(listOf("message.send"), frames.map { it.getString("type") })
            out.ended(c.host, "synthetic loss")
            val before = out.notSent[c.id]
            assertNotNull("accepted positive before mutation", before)
            if (literal) assertEquals("Reply unconfirmed. Check history before sending it again.", before)
            val request = PolicyRequest(c.host, "fixture-request", "box", c.agent, c.id, "network", "example.invalid:443", false,
                "connect", 1, "pending", "", false, "fixture-revision", true, null)
            val question = askWhyQuestion(request)
            if (ask) assertEquals(c.id, model.askInConversation(request, question))
            val after = out.notSent[c.id]
            assertEquals("asking doesn't send", listOf("message.send"), frames.map { it.getString("type") })
            assertEquals(listOf(file), out.files.drafts[c.id])
            val expectedDraft = if (!literal) "Yes" else if (ask && draft.isEmpty()) question else draft.ifEmpty { null }
            assertEquals(expectedDraft, out.unsent[c.id])
            assertFalse(out.waiting(c.id))
            assertEquals(listOf("Yes"), offeredReplies(line, model.lines[c.id].orEmpty()))
            val host = Host(c.host, "https://example.invalid", ByteArray(32), "device", byteArrayOf(), "fixture", byteArrayOf())
            val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                "seq" to 2, "from" to "person", "text" to "Yes", "kind" to "message")
            AppModel::class.java.getDeclaredMethod("received", Host::class.java, JSONObject::class.java).apply { isAccessible = true }.invoke(model, host, canonical)
            assertNull("genuine received retires its notice", out.notSent[c.id])
            if (literal) assertEquals(expectedDraft, out.unsent[c.id])
            assertEquals(listOf(file), out.files.drafts[c.id])
            println("R5_NOTICE literal=$literal ask=$ask existingDraft=${draft.isNotEmpty()} before=$before afterAsk=$after frames=${frames.size} actualReceived=true")
            assertEquals("Ask why owns only its question, not an unconfirmed send's warning", before, after)
        } finally {
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try {
                for (name in listOf("saving", "work", "disk", "fileWorker")) assertTrue(field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS))
            } finally { field<Store>(model, "store").close() }
        }
    }
}
