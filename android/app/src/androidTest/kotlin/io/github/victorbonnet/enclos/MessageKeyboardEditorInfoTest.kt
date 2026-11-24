package io.github.victorbonnet.enclos

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * A1/A2 of #100: the real Activity's composer hands the keyboard a short-message,
 * sentence-capitalizing, multi-line field with no IME action. The answer boxes and
 * the unchanged fields are not claimed by this test.
 */
@RunWith(AndroidJUnit4::class)
class MessageKeyboardEditorInfoTest {
    private val runner get() = InstrumentationRegistry.getInstrumentation()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(model: AppModel, name: String): T =
        AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model) as T

    private fun until(why: String, deadlineMs: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(why)
    }

    private fun nodes(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::nodes).orEmpty() }

    private fun tree(): List<AccessibilityNodeInfo> {
        runner.uiAutomation.clearCache()
        return runner.uiAutomation.rootInActiveWindow?.let(::nodes).orEmpty()
    }

    @Test
    fun composer_hands_keyboard_short_message_sentence_capitals_multiline_and_no_ime_action() {
        val context = runner.targetContext
        check(!PushSetup.configured) { "Use the push-disabled disposable test pair." }
        check(context.getSharedPreferences("hosts", Context.MODE_PRIVATE).getString("hosts", "[]") == "[]") {
            "Use an empty disposable emulator."
        }
        var activity: MainActivity? = null
        var model: AppModel? = null
        val host = "fixture-keyboard"
        val conversation = Conversation(host, "box/agent", "keyboard-${UUID.randomUUID()}")
        val connection = BridgeConnection(
            "https://example.invalid",
            ByteArray(32),
            { error("No network") },
            { error("No handshake") },
            {},
            {},
            {},
        )
        try {
            activity = runner.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            runner.runOnMainSync { model = ViewModelProvider(activity!!)[AppModel::class.java] }
            until("model cache did not load") { model!!.ready }
            val m = model!!
            runner.runOnMainSync {
                m.agents.add(Agent(host, conversation.agent, "hermes", listOf("incoming_files", "incoming_images")))
                m.conversations.add(conversation)
                m.links[host] = HostLink.Connected("fixture-person")
                field<MutableMap<String, Set<String>>>(m, "hostCapabilities")[host] = setOf("message.begin")
                field<MutableMap<String, BridgeConnection>>(m, "connections")[host] = connection
                m.openedConversation.value = ConversationKey(host, conversation.id)
            }
            until("composer did not open") { tree().any { it.contentDescription?.toString() == "Attach files" } }
            until("composer field is not in the tree") {
                tree().firstOrNull { it.className?.toString() == "android.widget.EditText" && it.isEnabled }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            var info: EditorInfo? = null
            until("the Compose host did not take focus") {
                runner.runOnMainSync {
                    val focused = activity!!.window.decorView.findFocus()
                    if (focused != null && focused.javaClass.name.contains("AndroidComposeView")) {
                        val candidate = EditorInfo()
                        if (focused.onCreateInputConnection(candidate) != null) info = candidate
                    }
                }
                info != null
            }
            val editor = info!!
            // A1: text | short message | cap sentences | multi-line | auto-correct.
            // The literal 0x0002C041 of spec §3A is not asserted: this emulator's IME
            // ORs one further bit (0x200000) into inputType, observed 0x0022C041 on
            // 2026-11-24. Every component the card names is asserted instead.
            assertEquals(InputType.TYPE_CLASS_TEXT, editor.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals(0x40, editor.inputType and InputType.TYPE_MASK_VARIATION)
            assertEquals(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, editor.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            assertEquals(InputType.TYPE_TEXT_FLAG_MULTI_LINE, editor.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            assertEquals(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, editor.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            // A2: NO_ENTER_ACTION | NO_FULLSCREEN, action unspecified
            assertEquals(0x42000000, editor.imeOptions)
            assertEquals(EditorInfo.IME_ACTION_UNSPECIFIED, editor.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertNotEquals(EditorInfo.IME_ACTION_SEND, editor.imeOptions and EditorInfo.IME_MASK_ACTION)
        } finally {
            runner.runOnMainSync { activity?.finish() }
        }
    }
}
