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
    private val selectedUris = mutableListOf<Uri>()
    private fun providerPick(file: File): Uri {
        val resolver = runner.targetContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, sendFileType(file.name))
        }
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values))
        selectedUris.add(uri)
        resolver.openOutputStream(uri)!!.use { it.write(file.readBytes()) }
        return uri
    }
    @org.junit.After fun removeSelectedProviderFiles() {
        selectedUris.forEach { runner.targetContext.contentResolver.delete(it, null, null) }
        selectedUris.clear()
    }
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
    private fun clickControl(node: AccessibilityNodeInfo?): Boolean {
        var target = node
        while (target != null) {
            if (target.isEnabled && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = target.parent
        }
        return false
    }
    private fun noFailure() {
        assertFalse("normal send must not draw failure", tree().any {
            it.text?.toString()?.let { text -> text.contains("Delivery unknown") || text.contains("Not sent:") || text.contains("Retry original") } == true
        })
    }

    @Test fun photo_pdf_python_progress_ack_and_explicit_refusal() = journey(false)

    @Test fun genuine_camera_rotation_and_accepted_result() = journey(true)

    @Test fun genuine_camera_activity_recreation_and_accepted_result() = journey(true, true)

    private fun journey(camera: Boolean, recreate: Boolean = false) {
        val context = runner.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val newCamera = camera && arguments.getString("newConversationCamera") == "true"
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
        var c = Conversation("fixture-files", "box/agent", "file-journey-${UUID.randomUUID()}")
        var key = FileKey(c.host, c.id)
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
                val agent = Agent(c.host, c.agent, "hermes", listOf("incoming_files", "incoming_images"))
                m.agents.add(agent)
                if (newCamera) {
                    val id = m.newConversation(agent)
                    c = m.conversations.single { it.id == id }
                    key = FileKey(c.host, c.id)
                } else m.conversations.add(c)
                m.links[c.host] = HostLink.Connected("fixture-person")
                field<MutableMap<String, Set<String>>>(m, "hostCapabilities")[c.host] = setOf("message.begin")
                field<MutableMap<String, BridgeConnection>>(m, "connections")[c.host] = connection
                m.openedConversation.value = ConversationKey(c.host, c.id)
            }
            until("real conversation composer did not open") { tree().any { it.contentDescription?.toString() == "Attach files" } }
            val priorFiles = mutableListOf<SendFile>()
            if (!newCamera) {
                for ((file, source) in listOf(photo to FileSource.Photos, pdf to FileSource.Files, python to FileSource.Files)) {
                    runner.runOnMainSync { m.prepareFiles(c, listOf(providerPick(file)), source) }
                    until("file preparation failed: ${file.name}") { m.outgoing.files.draft(key).isNotEmpty() }
                    noFailure()
                    tap("Send")
                    until("upload did not await receipt") { m.outgoing.files.phase(key) is FilePhase.AwaitingAck }
                    noFailure()
                    val op = m.outgoing.files.phase(key).send()!!
                    priorFiles.addAll(op.files)
                    assertEquals(if (source == FileSource.Photos) "photo.jpg" else file.name, op.files.single().name)
                    val begin = synchronized(frames) { frames.first { it.optString("id") == op.id } }
                    assertEquals("message.begin", begin.getString("type"))
                    runner.runOnMainSync { m.outgoing.files.acknowledged(c.host, op.id) }
                    until("receipt did not settle") { m.outgoing.files.phase(key) == FilePhase.Draft }
                    noFailure()
                    SystemClock.sleep(650)
                }
                runner.runOnMainSync { m.prepareFiles(c, listOf(providerPick(python)), FileSource.Files) }
                until("refused-file preparation failed") { m.outgoing.files.draft(key).isNotEmpty() }
                tap("Send")
                until("refusal upload did not await receipt") { m.outgoing.files.phase(key) is FilePhase.AwaitingAck }
                val op = m.outgoing.files.phase(key).send()!!
                val refusedFile = op.files.single()
                priorFiles.add(refusedFile)
                val host = Host(c.host, "https://example.invalid", ByteArray(32), "fixture", byteArrayOf(), "fixture", byteArrayOf())
                runner.runOnMainSync {
                    AppModel::class.java.getDeclaredMethod("received", Host::class.java, JSONObject::class.java).apply { isAccessible = true }
                        .invoke(m, host, message("error", "of" to op.id, "code" to "not_allowed", "message" to "example.py was refused by the test agent."))
                }
                until("safe filename/refusal did not reach the composer") { tree().any { it.text?.toString()?.contains("example.py was refused") == true } }
                assertTrue(m.outgoing.files.phase(key) is FilePhase.Failed)
                assertFalse(tree().any { it.text?.toString()?.contains("Retry original") == true })
                SystemClock.sleep(1_000)
                runner.runOnMainSync {
                    m.outgoing.files.dismiss(key)
                    assertTrue("dismissing a failed send releases its files", m.outgoing.files.draft(key).isEmpty())
                }
            }
            if (!camera) return
            runner.runOnMainSync { assertTrue("camera must start with an empty draft", m.outgoing.files.draft(key).isEmpty()) }
            // Launch exactly as production does. App-scoped resolveActivity is filtered by
            // package visibility and cannot establish that the external camera is absent.
            tap("Attach files")
            until("Camera source missing") { clickControl(tree().firstOrNull { it.text?.toString() == "Camera" }) }
            until("external camera did not open") { runner.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it != context.packageName } == true }
            val capture = m.outgoing.files[key].capture
            assertNotNull("original destination must own the capture", capture)
            println("CAMERA PATH stored=${capture!!.path} canonical=${capture.canonicalPath} directory=${cameraDir(context).path}")
            assertEquals(cameraDir(context).canonicalFile, capture.canonicalFile.parentFile)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", capture)
            runner.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            SystemClock.sleep(1200)
            assertNotEquals(context.packageName, runner.uiAutomation.rootInActiveWindow?.packageName?.toString())
            assertEquals("rotation must retain original capture ownership", capture, m.outgoing.files[key].capture)
            // Diagnostic only: parent kills this process externally, then accepts in the
            // genuine camera. Instrumentation is interrupted, not a passing test. Cleanup
            // after a kill requires the parent's exclusive disposable-VM reset.
            if (arguments.getString("holdCameraForProcessDeath") == "true") {
                val ready = "CAMERA_PROCESS_READY pid=${android.os.Process.myPid()} capture=${capture.absolutePath} uri=$uri conversation=${c.id} newConversation=$newCamera"
                println(ready)
                android.util.Log.i("FileSendJourneyTest", ready)
                SystemClock.sleep(90_000)
                fail("Process-death diagnostic timed out without external kill; no process-death acceptance result")
            }
            // Optional Activity recreation is distinct from process death: instrumentation
            // runs in the target process and cannot survive killing that process.
            if (recreate) {
                runner.runOnMainSync { activity!!.recreate() }
                SystemClock.sleep(1200)
            }
            fun cameraControl(vararg labels: String) {
                until("Missing real camera control: ${labels.toList()}") {
                    tree().firstOrNull { node ->
                        val words = listOf(node.contentDescription?.toString(), node.text?.toString(), node.viewIdResourceName?.substringAfterLast('/'))
                        words.any { word -> labels.any { it.equals(word, ignoreCase = true) } }
                    }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
            }
            cameraControl("Shutter", "Take picture", "shutter_button")
            cameraControl("Done", "OK", "Accept", "Review done", "done_button", "review_done")
            until("accepted camera result did not complete in original conversation") {
                var complete = false
                runner.runOnMainSync {
                    val state = m.outgoing.files[key]
                    val image = state.draft.singleOrNull()
                    complete = state.capture == null && state.phase == FilePhase.Draft &&
                        image != null && priorFiles.none { it === image } &&
                        image.mime == "image/jpeg" && image.bytes.isNotEmpty()
                }
                complete
            }
            assertTrue("camera image must be a fresh prepared file", priorFiles.none { it === m.outgoing.files.draft(key).single() })
            assertEquals(FilePhase.Draft, m.outgoing.files.phase(key))
            assertEquals("image/jpeg", m.outgoing.files.draft(key).single().mime)
            assertTrue(m.outgoing.files.draft(key).single().bytes.isNotEmpty())
            assertNull(m.outgoing.files[key].capture)
            until("original camera destination was not deleted") { !capture.exists() }
            assertTrue("the original URI must no longer open", runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() } }.isFailure)
            println("CAMERA ACCEPTED: external camera rotated; original destination prepared and deleted. Process death NOT RUN.")
        } finally {
            runner.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            runner.runOnMainSync {
                model?.let { m -> m.outgoing.files.forget(key); m.conversations.removeAll { it.id == c.id }; m.agents.removeAll { it.host == c.host }; m.links.remove(c.host); field<MutableMap<String, BridgeConnection>>(m, "connections").remove(c.host) }
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
                activity?.finish()
            }
            connection.close()
            dir.deleteRecursively()
        }
    }
}
