package io.github.victorbonnet.enclos

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.victorbonnet.enclos.core.DeviceSession
import io.github.victorbonnet.enclos.core.NoHandle
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID

/** Disposable-emulator check of the real Activity/composer. The peer and selected URIs are synthetic;
 * this is not a system-picker, Noise-handshake, real plugin or model-delivery test. */
@RunWith(AndroidJUnit4::class)
class FileSendJourneyTest {
    private val runner get() = InstrumentationRegistry.getInstrumentation()
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(model: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model) as T
    private fun until(why: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(50) }
        fail(why)
    }
    private fun nodes(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::nodes).orEmpty() }
    private fun tree(): List<AccessibilityNodeInfo> {
        runner.uiAutomation.clearCache()
        return runner.uiAutomation.rootInActiveWindow?.let(::nodes).orEmpty()
    }
    private fun tap(description: String) {
        until("Missing enabled control: $description") {
            tree().firstOrNull { it.contentDescription?.toString() == description && it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun noFailure() {
        assertFalse("normal send must not draw failure", tree().any {
            it.text?.toString()?.let { text -> text.contains("Delivery unknown") || text.contains("Not sent:") || text.contains("Retry original") } == true
        })
    }

    @Test fun photo_pdf_python_progress_ack_and_explicit_refusal() {
        val context = runner.targetContext
        // Refuse before launching any Activity or touching a model on a configured/paired app.
        check(!PushSetup.configured) { "Use the push-disabled disposable test pair." }
        check(context.getSharedPreferences("hosts", Context.MODE_PRIVATE).getString("hosts", "[]") == "[]") { "Use an empty disposable emulator." }
        val dir = File(context.cacheDir, "file-journey-${UUID.randomUUID()}").apply { mkdirs() }
        val photo = File(dir, "sunrise.jpg")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        try { photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } } finally { bitmap.recycle() }
        val pdf = File(dir, "report.pdf").apply { writeText("%PDF-1.4\n1 0 obj <</Type/Catalog>> endobj\n%%EOF") }
        val python = File(dir, "example.py").apply { writeText("print('hello')\n") }
        var activity: MainActivity? = null
        var model: AppModel? = null
        val c = Conversation("fixture-files", "box/agent", "file-journey-${UUID.randomUUID()}")
        val key = FileKey(c.host, c.id)
        val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
        val connection = BridgeConnection("https://example.invalid", ByteArray(32), { error("No network") }, { error("No handshake") }, {}, {}, {})
        try {
            activity = runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runner.runOnMainSync { model = ViewModelProvider(activity!!)[AppModel::class.java] }
            until("model cache did not load") { model!!.ready }
            val m = model!!
            val socket = object : WebSocket {
                override fun request() = Request.Builder().url("https://example.invalid").build()
                override fun queueSize() = 0L
                override fun send(text: String) = false
                override fun send(bytes: ByteString): Boolean { frames.add(JSONObject(bytes.utf8())); return true }
                override fun close(code: Int, reason: String?) = true
                override fun cancel() {}
            }
            val session = object : DeviceSession(NoHandle) { override fun encrypt(plaintext: ByteArray) = plaintext }
            BridgeConnection::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(connection, socket)
            BridgeConnection::class.java.getDeclaredField("session").apply { isAccessible = true }.set(connection, session)
            runner.runOnMainSync {
                m.conversations.add(c)
                m.agents.add(Agent(c.host, c.agent, "hermes", listOf("incoming_files", "incoming_images")))
                m.links[c.host] = HostLink.Connected("fixture-person")
                field<MutableMap<String, Set<String>>>(m, "hostCapabilities")[c.host] = setOf("message.begin")
                field<MutableMap<String, BridgeConnection>>(m, "connections")[c.host] = connection
                m.openedConversation.value = ConversationKey(c.host, c.id)
            }
            until("real conversation composer did not open") { tree().any { it.contentDescription?.toString() == "Attach files" } }
            for ((file, source) in listOf(photo to FileSource.Photos, pdf to FileSource.Files, python to FileSource.Files)) {
                runner.runOnMainSync { m.prepareFiles(c, listOf(Uri.fromFile(file)), source) }
                until("file preparation failed: ${file.name}") { m.outgoing.files.draft(key).isNotEmpty() }
                noFailure()
                tap("Send")
                until("upload did not await receipt") { m.outgoing.files.phase(key) is FilePhase.AwaitingAck }
                noFailure()
                val op = m.outgoing.files.phase(key).send()!!
                assertEquals(file.name, op.files.single().name)
                val begin = synchronized(frames) { frames.first { it.optString("id") == op.id } }
                assertEquals("message.begin", begin.getString("type"))
                runner.runOnMainSync { m.outgoing.files.acknowledged(c.host, op.id) }
                until("receipt did not settle") { m.outgoing.files.phase(key) == FilePhase.Draft }
                noFailure()
                SystemClock.sleep(650)
            }
            runner.runOnMainSync { m.prepareFiles(c, listOf(Uri.fromFile(python)), FileSource.Files) }
            until("refused-file preparation failed") { m.outgoing.files.draft(key).isNotEmpty() }
            tap("Send")
            until("refusal upload did not await receipt") { m.outgoing.files.phase(key) is FilePhase.AwaitingAck }
            val op = m.outgoing.files.phase(key).send()!!
            val host = Host(c.host, "https://example.invalid", ByteArray(32), "fixture", byteArrayOf(), "fixture", byteArrayOf())
            runner.runOnMainSync {
                AppModel::class.java.getDeclaredMethod("received", Host::class.java, JSONObject::class.java).apply { isAccessible = true }
                    .invoke(m, host, message("error", "of" to op.id, "code" to "not_allowed", "message" to "example.py was refused by the test agent."))
            }
            until("safe filename/refusal did not reach the composer") { tree().any { it.text?.toString()?.contains("example.py was refused") == true } }
            assertTrue(m.outgoing.files.phase(key) is FilePhase.Failed)
            assertFalse(tree().any { it.text?.toString()?.contains("Retry original") == true })
            SystemClock.sleep(1_000)
            runner.runOnMainSync { m.outgoing.files.dismiss(key) }
            if (context.packageManager.resolveActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), 0) == null) {
                println("CAMERA NOT RUN: emulator has no ACTION_IMAGE_CAPTURE handler")
            } else {
                tap("Attach files")
                until("Camera source missing") { tree().firstOrNull { it.text?.toString() == "Camera" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
                until("external camera did not open") { runner.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it != context.packageName } == true }
                runner.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
                SystemClock.sleep(1200)
                assertNotEquals("external camera must remain open during rotation", context.packageName, runner.uiAutomation.rootInActiveWindow?.packageName?.toString())
                println("CAMERA OPEN ROTATION: external camera stayed open; capture acceptance/process death NOT RUN")
                runner.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                runner.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            }
        } finally {
            runner.runOnMainSync {
                model?.let { m -> m.outgoing.files.forget(key); m.conversations.removeAll { it.id == c.id }; m.agents.removeAll { it.host == c.host }; m.links.remove(c.host); field<MutableMap<String, BridgeConnection>>(m, "connections").remove(c.host) }
                activity?.finish()
            }
            connection.close()
            dir.deleteRecursively()
        }
    }
}
