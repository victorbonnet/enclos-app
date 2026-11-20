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

/** Regression from the independent R4 review, exercising production state owners; synthetic enqueue, not native/network proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class R4AttachmentEchoTest {
    @Suppress("UNCHECKED_CAST") private fun <T> field(m: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(m) as T
    @Test fun file_echo_must_not_delete_equal_unsent_ordinary_text() = exercise("Yes", false, true)
    @Test fun file_echo_must_not_retire_equal_literal_uncertainty() = exercise("Yes", true, true)
    @Test fun different_file_text_preserves_ordinary_control() = exercise("different file text", false, true)
    @Test fun ordinary_canonical_echo_deduplicates_restored_text_control() = exercise("Yes", false, false)
    private fun exercise(fileText: String, literal: Boolean, attached: Boolean) {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            field<ExecutorService>(model, "saving").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val c = Conversation("fixture-host", "box/agent", "r4-c")
            model.conversations.add(c)
            model.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files")))
            model.links[c.host] = HostLink.Connected("fixture-person")
            field<MutableMap<String, Set<String>>>(model, "hostCapabilities")[c.host] = setOf("message.begin")
            val frames = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
            val connection = BridgeConnection("https://example.invalid", ByteArray(32), { ByteArray(32) }, { error("unused") }, {}, {}, {})
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
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            model.lines[c.id] = listOf(line)
            val out = model.outgoing
            out.files.drafts[c.id] = listOf(SendFile("original.txt", "text/plain", byteArrayOf(1, 2, 3)))
            assertNull(model.send(c, fileText))
            field<ExecutorService>(model, "fileWorker").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            out.ended(c.host, "first file loss")
            val original = out.files.retries.getValue(c.id)
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
            val selected = SendFile("newer.txt", "text/plain", byteArrayOf(9))
            out.files.drafts[c.id] = listOf(selected)
            assertNotNull(beforeNotice)
            assertEquals(if (literal) "unrelated draft" else "Yes", beforeDraft)
            val canonical = message("message", "conversation" to c.id, "agent" to c.agent,
                "seq" to 2, "from" to "person", "text" to (if (attached) fileText else "Yes"), "kind" to "message")
            if (attached) canonical.put("attachments", org.json.JSONArray().put(JSONObject().put("id", "fixture-attachment").put("name", "original.txt").put("mime", "text/plain").put("size", 3)))
            assertNotEquals(original.id, canonical.getString("id"))
            AppModel::class.java.getDeclaredMethod("received", Host::class.java, JSONObject::class.java).apply { isAccessible = true }.invoke(model,
                Host(c.host, "https://example.invalid", ByteArray(32), "device", byteArrayOf(), "fixture", byteArrayOf()), canonical)
            assertEquals(if (attached) 1 else 0, model.lines[c.id]!!.last().attachments.size)
            assertSame(original, out.files.retries[c.id])
            assertEquals(listOf(selected), out.files.drafts[c.id])
            println("R4_ATTACHMENT_ECHO attached=$attached literal=$literal fileText=$fileText beforeDraft=$beforeDraft afterDraft=${out.unsent[c.id]} beforeNotice=$beforeNotice afterNotice=${out.notSent[c.id]} frames=${frames.map { it.optString("type") }}")
            if (attached) {
                assertEquals("file history must not delete ordinary text restored from a different operation", beforeDraft, out.unsent[c.id])
                assertEquals("file history must not settle a different text-only operation notice", beforeNotice, out.notSent[c.id])
            } else {
                assertNull(out.unsent[c.id])
                assertNull(out.notSent[c.id])
            }
        } finally {
            AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
            try { for (name in listOf("saving", "work", "disk", "fileWorker")) assertTrue(field<ExecutorService>(model, name).awaitTermination(5, TimeUnit.SECONDS)) }
            finally { field<Store>(model, "store").close() }
        }
    }
}
