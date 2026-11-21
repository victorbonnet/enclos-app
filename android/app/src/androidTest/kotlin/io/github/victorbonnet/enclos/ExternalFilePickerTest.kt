package io.github.victorbonnet.enclos

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.security.MessageDigest

/** Opt-in support for an exclusive disposable-emulator driver. No peer, keys or transport.
 * A killed run is interrupted instrumentation, never a passing process-death test.
 * The driver must reset app data exclusively afterwards, including after interruption. */
@RunWith(AndroidJUnit4::class)
class ExternalFilePickerTest {
    private val runner get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun tree(): List<AccessibilityNodeInfo> {
        runner.uiAutomation.clearCache()
        return runner.uiAutomation.rootInActiveWindow?.let(::nodes).orEmpty()
    }
    private fun until(why: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(why)
    }
    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var target = node
        while (target != null) {
            if (target.isEnabled && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = target.parent
        }
        return false
    }
    private fun report(message: String) { println(message); Log.i("ExternalFilePickerTest", message) }
    private fun shell(command: String): String = runner.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }

    @Test fun openAndHold() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit externalPicker=true opt-in required", args.getString("externalPicker") == "true")
        val source = args.getString("source") ?: "Files"
        check(source in listOf("Files", "Photos", "Camera"))
        val awaitReturn = args.getString("awaitReturn") == "true"
        val expectedName = args.getString("expectedName")
        check(!awaitReturn || !expectedName.isNullOrBlank()) { "awaitReturn requires expectedName (prepared filename)" }
        val fresh = args.getString("newConversation") == "true"
        val landscape = args.getString("landscape") == "true"
        check(!landscape || source == "Camera") { "Landscape probe is camera-only" }
        val context = runner.targetContext
        // Read-only guards precede Store construction, launch and all fixture mutations.
        check(!PushSetup.configured) { "Use the push-disabled disposable test pair" }
        check(context.getSharedPreferences("hosts", Context.MODE_PRIVATE).getString("hosts", "[]") == "[]") {
            "Use an empty disposable emulator"
        }
        val host = "external-picker-fixture"
        val agent = Agent(host, "box/agent", "hermes", listOf("incoming_files", "incoming_images"))
        var conversation = Conversation(host, agent.name, "external-picker-${UUID.randomUUID()}")
        // Existing conversations survive real process death through the ordinary cache API.
        // A new conversation deliberately stays ephemeral: no paired host is fabricated.
        if (!fresh) Store(context).use { store ->
            store.agents(host, listOf(agent))
            store.conversation(conversation)
            check(store.recent().conversations.any { it.id == conversation.id })
        }
        val activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
            // --no-restart may leave the singleTask Activity alive. Replace it so the
            // lifecycle runner sees a fresh launch and model reads the seeded Store.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var model: AppModel
        runner.runOnMainSync { model = ViewModelProvider(activity)[AppModel::class.java] }
        until("cache did not load") { model.ready }
        runner.runOnMainSync {
            if (fresh) {
                model.agents.add(agent)
                val id = model.newConversation(agent)
                conversation = model.conversations.single { it.id == id }
            }
            // Only UI availability is faked. No BridgeConnection/session/socket is created.
            model.links[host] = HostLink.Connected("fixture-person")
            val field = AppModel::class.java.getDeclaredField("hostCapabilities").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val capabilities = field.get(model) as MutableMap<String, Set<String>>
            capabilities[host] = setOf("message.begin")
            model.openedConversation.value = ConversationKey(host, conversation.id)
        }
        until("Attach files unavailable") { click(tree().firstOrNull { it.contentDescription?.toString() == "Attach files" }) }
        until("Attachment source unavailable: $source") { click(tree().firstOrNull { it.text?.toString() == source }) }
        until("External system activity not visible (grant camera permission before run)") {
            tree().firstOrNull()?.packageName?.toString()?.let { it != context.packageName && it != "com.android.systemui" } == true
        }
        val identity = "pid=${Process.myPid()} conversation=${conversation.id} source=$source newConversation=$fresh"
        report("EXTERNAL_PICKER_OPEN $identity package=${tree().firstOrNull()?.packageName}")
        if (landscape) {
            // These are observed external Activity resource configuration and display state,
            // not requestedOrientation or the app's portrait configuration. Driver rotates.
            until("External camera did not report landscape resources and quarter-turn display", 120_000) {
                val top = shell("dumpsys activity top")
                val display = shell("dumpsys window displays")
                val external = tree().firstOrNull()?.packageName?.toString()
                val externalDump = top.substringAfter("ACTIVITY $external/", "").substringBefore("ACTIVITY ")
                external != null && external != context.packageName &&
                    Regex("mCurrentConfig[^\\n]*\\bland\\b").containsMatchIn(externalDump) &&
                    Regex("mRotation=(ROTATION_90|ROTATION_270|1|3)\\b").containsMatchIn(display)
            }
            report("EXTERNAL_CAMERA_LANDSCAPE_OBSERVED $identity\n${shell("dumpsys activity top")}\n${shell("dumpsys window displays")}")
        }
        report("EXTERNAL_PICKER_READY $identity holdSeconds=120 awaitReturn=$awaitReturn")
        if (awaitReturn) {
            var files = emptyList<SendFile>()
            until("System return did not prepare a draft for $identity", 120_000) {
                val foreground = tree().firstOrNull()?.packageName?.toString() == context.packageName
                runner.runOnMainSync {
                    files = model.outgoing.files.draft(FileKey(host, conversation.id)).toList()
                }
                foreground && files.isNotEmpty()
            }
            assertEquals(listOf(expectedName), files.map { it.name })
            runner.runOnMainSync {
                assertEquals(ConversationKey(host, conversation.id), model.openedConversation.value)
            }
            files.forEach { file ->
                assertTrue("Empty returned bytes", file.bytes.isNotEmpty())
                val hash = MessageDigest.getInstance("SHA-256").digest(file.bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                report("EXTERNAL_PICKER_RETURN $identity name=${file.name} mime=${file.mime} bytes=${file.bytes.size} sha256=$hash")
            }
            SystemClock.sleep(3_000) // Driver captures the actual returned draft's pixels.
            report("EXTERNAL_PICKER_RETURN_PASS $identity")
            return
        }
        // Do not tap shutter/done, select synthetic URIs, recreate, or kill from instrumentation.
        SystemClock.sleep(120_000)
        fail("External-driver hold expired; no process-death acceptance result. Reset disposable app data.")
    }
}
