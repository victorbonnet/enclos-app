package io.github.victorbonnet.enclos

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class FileSendOwnershipTest {
    @Test fun old_original_discard_preserves_newer_ordinary_notice() = settleOriginal(false, "ordinary")
    @Test fun old_original_ack_preserves_newer_ordinary_notice() = settleOriginal(true, "ordinary")
    @Test fun old_original_discard_preserves_newer_literal_notice() = settleOriginal(false, "literal")
    @Test fun old_original_ack_preserves_newer_literal_notice() = settleOriginal(true, "literal")
    @Test fun discard_settles_own_original_outcome() = settleOriginal(false, null)
    @Test fun ack_settles_own_original_outcome() = settleOriginal(true, null)

    private val key = FileKey("h", "c")

    private fun settleOriginal(ack: Boolean, newer: String?) {
        val out = Outgoing()
        out.files.drafted(key, SendFile("original.txt", "text/plain", byteArrayOf(1)))
        val original = out.files.begin(key, "original")!!
        out.ended("h", "original loss")
        assertTrue(out.files.phase(key) is FilePhase.Unknown)
        assertNull("a file outcome is its own, not the text field's notice", out.notSent["c"])
        if (newer == "ordinary") {
            assertTrue(out.send("c", "h", "new ordinary") { "newer" })
            out.ended("h", "new ordinary loss")
        } else if (newer == "literal") {
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
                assertTrue(out.send("c", "h", text) { "newer" })
                null
            })
            out.ended("h", "new literal loss")
        }
        val notice = out.notSent["c"]
        if (newer != null) assertNotNull(notice)
        out.write("c", "independent composer")
        assertFalse("newer files wait for the original's outcome", out.files.preparing(key, Job()))
        if (ack) assertTrue(out.files.acknowledged("h", original.id)) else out.files.dismiss(key)
        assertEquals(FilePhase.Draft, out.files.phase(key))
        assertNull(out.files[key].error)
        assertEquals("independent composer", out.unsent["c"])
        val selected = SendFile("selected.txt", "text/plain", byteArrayOf(2))
        out.files.drafted(key, selected)
        assertEquals(listOf(selected), out.files.draft(key))
        if (newer == null) assertNull("nothing else speaks for the field", out.notSent["c"])
        else assertEquals("old original must preserve newer notice", notice, out.notSent["c"])
        if (newer == "literal") {
            // History keeps the warning while the person has replaced the recovered text.
            out.shown("c", "Yes", "h")
            assertEquals(notice, out.notSent["c"])
        }
        out.clear()
    }

    @Test fun literal_confirmation_on_wrong_host_and_file_ack_cannot_settle_literal() {
        val out = Outgoing()
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
        out.write("c", "Yes")
        assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
            out.send("c", "h", text) { "literal" }
            null
        })
        out.ended("h", "lost")
        val notice = out.notSent["c"]
        out.shown("c", "Yes", "other-host")
        assertFalse("a receipt for files settles no text message", out.files.acknowledged("h", "literal"))
        assertEquals(notice, out.notSent["c"])
        assertEquals("Yes\n\nYes", out.unsent["c"])
        out.shown("c", "Yes", "h")
        assertEquals(notice, out.notSent["c"])
        assertEquals("Yes\n\nYes", out.unsent["c"])
    }

    @Test fun pending_file_cannot_be_discarded_but_pending_text_does_not_lock_retry() {
        val out = Outgoing()
        out.files.drafted(key, SendFile("original.txt", "text/plain", byteArrayOf(1)))
        val original = out.files.begin(key, "original")!!
        assertTrue(out.files.busy("h"))
        out.files.dismiss(key)
        assertSame("an upload under way is not discarded", original, out.files.phase(key).send())
        out.ended("h", "lost")
        assertTrue(out.send("c", "h", "text") { "text" })
        assertFalse(out.files.busy("h"))
        assertTrue(out.waiting("c"))
        assertSame("retry is offered while text waits", original, out.files.retry(key))
        out.files.interrupted(key, original.id)
        out.files.dismiss(key)
        assertNull(out.files.phase(key).send())
        out.clear()
    }

    @Test fun delayed_text_refusal_and_original_receipt_preserve_new_upload() {
        val out = Outgoing()
        out.send("c", "h", "text") { "text" }
        out.files.drafted(key, SendFile("old.txt", "text/plain", byteArrayOf(1)))
        val old = out.files.begin(key, "old")!!
        out.ended("h", "lost")
        out.files.dismiss(key)
        out.files.drafted(key, SendFile("new.txt", "text/plain", byteArrayOf(2)))
        val new = out.files.begin(key, "new")!!
        val token = Job()
        out.files.started(key, new.id, token)
        out.files.progress(key, new.id, 37)
        out.files.error(key, "newer error")
        out.notSent["c"] = "newer reason"
        out.refused("text", "late text refusal")
        fun sending() = out.files.phase(key) as FilePhase.Sending
        assertSame(token, sending().job)
        assertFalse(token.isCancelled)
        assertEquals(37, sending().progress)
        assertEquals("newer reason", out.notSent["c"])
        assertFalse("a discarded original's receipt settles nothing", out.files.acknowledged("h", old.id))
        assertFalse(out.files.refused("h", old.id, "internal", "late original refusal"))
        assertSame(token, sending().job)
        assertEquals(37, sending().progress)
        assertEquals(new.id, sending().send.id)
        assertEquals("newer error", out.files[key].error)
        assertTrue(out.files.refused("h", new.id, "internal", "own refusal"))
        assertTrue(token.isCancelled)
        assertTrue(out.files.phase(key) is FilePhase.Unknown)
        out.clear()
    }
}
