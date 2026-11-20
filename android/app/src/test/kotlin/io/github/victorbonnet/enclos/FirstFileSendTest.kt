package io.github.victorbonnet.enclos

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.victorbonnet.enclos.ui.EnclosTheme
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A suggested reply's uncertainty, and a new conversation's first message
 * with files: it outlives leaving the screen, and an unknown outcome stays
 * reachable to retry or discard. Production AppModel; synthetic socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FirstFileSendTest {
    @get:Rule val compose = createComposeRule()
    @Test fun suggested_disconnect_restores_reply_before_draft() {
        val out = Outgoing()
        val key = FileKey("h", "c")
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
        val file = SendFile("private.txt", "text/plain", byteArrayOf(1))
        out.files.drafted(key, file)
        out.write("c", "unrelated draft")
        assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
            assertTrue(out.send("c", "h", text) { "literal" })
            null
        })
        assertTrue(out.waiting("c"))
        out.ended("h", "connection ended before reply confirmation")
        assertEquals("Yes\n\nunrelated draft", out.unsent["c"])
        assertEquals(listOf(file), out.files.draft(key))
        assertEquals(FilePhase.Draft, out.files.phase(key))
        assertFalse(out.waiting("c"))
        println("LITERAL_LOSS notice=${out.notSent["c"]} draft=${out.unsent["c"]}")
        assertEquals("connection ended before reply confirmation", out.notSent["c"])
        out.clear()
    }

    @Test fun ordinary_text_disconnect_has_notice_control() {
        val out = Outgoing()
        assertTrue(out.send("c", "h", "ordinary") { "text" })
        out.ended("h", "lost")
        assertEquals("ordinary", out.unsent["c"])
        assertEquals("lost", out.notSent["c"])
    }

    @Test fun literal_refusal_has_notice_control() {
        val out = Outgoing()
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
        out.write("c", "draft")
        assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
            out.send("c", "h", text) { "literal" }
            null
        })
        out.refused("literal", "refused")
        assertEquals("Yes\n\ndraft", out.unsent["c"])
        assertEquals("refused", out.notSent["c"])
    }

    @Test fun navigation_during_first_file_send_preserves_operation_until_confirmation() = firstFileSend(true)
    @Test fun first_file_send_without_navigation_positive_control() = firstFileSend(false)
    @Test fun unknown_first_file_send_survives_repeated_back_and_retries_original() = firstFileSend(true, "retry")
    @Test fun unknown_first_file_send_explicit_discard_retires_empty_conversation() = firstFileSend(true, "discard")
    @Test fun unknown_first_file_send_host_reset_retires_original() = firstFileSend(true, "reset")
    @Test fun retained_original_without_echo_is_reachable() = firstFileSend(true, "noecho")
    @Test fun retained_original_after_refusal_is_reachable() = firstFileSend(true, "refusal")
    @Test fun retained_original_after_deadline_is_reachable() = firstFileSend(true, "deadline")
    private fun firstFileSend(navigate: Boolean, after: String? = null) {
        val model = fixtureModel()
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        // The first upload waits on its own thread, as a slow connection would, until released.
        val bridge = FakeBridge { if (it.getString("type") == "message.begin" && first.getAndSet(false)) release.await(5, TimeUnit.SECONDS) }
        try {
            val agent = Agent("fixture-host", "box/agent", "hermes", listOf("incoming_files"))
            val id = model.newConversation(agent)
            val c = model.conversations.single { it.id == id }
            model.agents.add(agent)
            bridge.attach(model, c.host)
            val key = FileKey(c.host, id)
            val files = model.outgoing.files
            val drafted = SendFile("first.txt", "text/plain", ByteArray(100001) { (it % 251).toByte() })
            files.drafted(key, drafted)
            assertNull(model.send(c, "first file message"))
            val sending = files.phase(key) as FilePhase.Sending
            val original = sending.send
            assertSame("the draft's bytes move to the operation, never copied", drafted, original.files.single())
            assertTrue(files.draft(key).isEmpty())
            assertArrayEquals(ByteArray(100001) { (it % 251).toByte() }, original.files.single().bytes)
            val active = sending.job
            assertNotNull(active)
            // Real main-thread order: Send, then Back, before bridge's conversation echo.
            if (navigate) model.left(id)
            val retainedConversation = model.conversations.any { it.id == id }
            val retainedOperation = files.phase(key).send() != null
            assertSame(active, (files.phase(key) as FilePhase.Sending).job)
            release.countDown()
            settle { files.phase(key) is FilePhase.AwaitingAck }
            println("FIRST_FILE_BACK retainedConversation=$retainedConversation retainedOperation=$retainedOperation closes=${bridge.closes.get()} frames=${bridge.types()}")
            assertTrue("a user-initiated in-flight send is not an abandoned unsent draft", retainedOperation)
            assertEquals("ordinary Back must not close shared host connection", 0, bridge.closes.get())
            assertTrue(retainedConversation)
            assertSame(original, files.phase(key).send())
            assertEquals(listOf("conversation.new", "message.begin", "message.part", "message.part", "message.part"), bridge.types())
            val begin = bridge.ofType("message.begin").single().toString()
            val parts = bridge.ofType("message.part").map { JSONObject(it.toString()).apply { remove("id") }.toString() }
            assertEquals(original.id, bridge.frames[1].getString("id"))
            if (after != null) {
                when (after) {
                    "refusal" -> model.receive(c.host, message("error", "of" to original.id, "code" to "internal", "message" to "refused"))
                    // No receipt in time: this operation only, the connection kept.
                    "deadline" -> shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(model.fileReceiptMs + 1))
                    else -> model.outgoing.ended(c.host, "lost before echo")
                }
                model.left(id)
                model.left(id)
                assertTrue(model.conversations.any { it.id == id })
                assertTrue(files.phase(key) is FilePhase.Unknown)
                assertSame(original, files.phase(key).send())
                assertFalse(model.outgoing.waiting(id))
                assertEquals(5, bridge.frames.size) // navigation and loss never resend
                assertEquals(0, bridge.closes.get())
                assertTrue("no-echo retained original is reachable", model.listed(c))
                if (after != "noecho") model.receive(c.host, message("conversation", "conversation" to id, "agent" to c.agent))
                assertTrue("retained original must be eligible for Inbox/Search and agent count", model.listed(c))
                model.paired.add(fixtureHost(c.host))
                val eligible = inboxConversations(model.conversations.filter(model::listed), false)
                assertTrue(eligible.any { it.id == id })
                for (expanded in listOf(emptySet<String>(), setOf("${c.host}/${c.agent}"))) {
                    val entry = byAgent(eligible, eligible.map { Found(it, null) }, expanded, { 0 }, false)
                        .single { it.address == "${c.host}/${c.agent}" }
                    assertEquals(1, entry.others)
                    assertTrue(entry.under.any { it.conversation.id == id })
                }
                var opened by mutableStateOf(false)
                compose.setContent { EnclosTheme(dark = false) {
                    if (opened) ConversationScreen(model, id) {}
                    else InboxScreen(model, "agent", false, false, { opened = true }, {}, {}, {}, {}, {})
                } }
                compose.onNodeWithText("No name yet").performClick()
                compose.onNodeWithText("Retry original message").assertExists()
                compose.onNodeWithText("Discard").assertExists()
                assertEquals(5, bridge.frames.size)

                when (after) {
                    "retry" -> {
                        model.outgoing.write(id, "newer text")
                        assertFalse("newer files wait for the original's outcome", files.preparing(key, Job()))
                        model.left(id)
                        compose.onNodeWithText("Retry original message").performScrollTo().performClick()
                        settle { files.phase(key) is FilePhase.AwaitingAck }
                        assertEquals(2, bridge.ofType("message.begin").size)
                        assertEquals(begin, bridge.ofType("message.begin").last().toString())
                        assertEquals(parts, bridge.ofType("message.part").takeLast(3)
                            .map { JSONObject(it.toString()).apply { remove("id") }.toString() })
                        model.receive(c.host, message("ack", "of" to original.id))
                        assertNull(files.phase(key).send())
                        assertEquals("newer text", model.outgoing.unsent[id])
                        val newer = SendFile("new.txt", "text/plain", byteArrayOf(2))
                        files.drafted(key, newer)
                        assertEquals(listOf(newer), files.draft(key))
                        assertEquals(0, bridge.closes.get())
                    }
                    "discard" -> {
                        model.outgoing.write(id, "newer text")
                        compose.onNodeWithText("Discard").performScrollTo().performClick()
                        assertEquals("newer text", model.outgoing.unsent[id])
                        assertNull(files.phase(key).send())
                        model.left(id)
                        assertFalse(model.listed(c))
                        assertNull(files.phase(key).send())
                        assertFalse(files.owns(id))
                    }
                    "reset" -> {
                        AppModel::class.java.getDeclaredMethod("forget", String::class.java).apply { isAccessible = true }.invoke(model, c.host)
                        assertFalse(model.conversations.any { it.id == id })
                        assertNull(files.phase(key).send())
                        assertFalse(files.owns(id))
                        assertFalse("the reset host's old operation correlates no receipt", files.acknowledged(c.host, original.id))
                        assertNull(model.outgoing.notSent[id])
                    }
                }
            }
        } finally {
            release.countDown()
            model.outgoing.clear()
            model.endFixture()
        }
    }
}
