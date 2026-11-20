package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class PrefillNoticeOwnershipTest {
    @Test fun literal_notice_survives_question_leave_until_confirmation() {
        val out = literalLoss()
        val notice = out.notSent["c"]
        assertTrue(out.prefill("c", "Why?"))
        assertEquals(notice, out.notSent["c"])
        out.leave("c")
        assertNull(out.unsent["c"])
        assertEquals(notice, out.notSent["c"])
        out.prefill("c", "Why again?")
        out.shown("c", "Yes", "other-host")
        assertEquals(notice, out.notSent["c"])
        out.shown("c", "Yes", "h")
        assertNull(out.notSent["c"])
        assertEquals("Why again?", out.unsent["c"])
    }

    @Test fun explicit_forget_and_clear_retire_owned_notice_after_prefill() {
        for (all in listOf(false, true)) {
            val out = literalLoss()
            out.prefill("c", "Why?")
            assertNotNull(out.notSent["c"])
            if (all) out.clear() else out.forget(listOf("c"))
            assertNull(out.notSent["c"])
            assertNull(out.unsent["c"])
            // No stale owner remains to protect a subsequent unowned warning.
            out.notSent["c"] = "stale"
            out.prefill("c", "New question?")
            assertNull(out.notSent["c"])
        }
    }

    @Test fun file_notice_survives_prefill_and_leave_until_original_ack_or_discard() {
        for (ack in listOf(false, true)) {
            val out = Outgoing()
            out.files.drafts["c"] = listOf(SendFile("original.txt", "text/plain", byteArrayOf(1)))
            out.send("c", "h", "original") { "original" }
            out.ended("h", "loss")
            val notice = out.notSent["c"]
            val original = out.files.retries["c"]
            val newer = SendFile("newer.txt", "text/plain", byteArrayOf(2))
            out.files.drafts["c"] = listOf(newer)
            out.prefill("c", "Why?")
            out.leave("c")
            assertEquals(notice, out.notSent["c"])
            assertSame(original, out.files.retries["c"])
            out.prefill("c", "Why again?")
            if (ack) out.acknowledged("h", "original") else out.discardRetry("c")
            assertNull(out.notSent["c"])
            assertNull(out.files.retries["c"])
            assertEquals("Why again?", out.unsent["c"])
            assertEquals(listOf(newer), out.files.drafts["c"])
        }
    }

    @Test fun ordinary_owned_warning_survives_empty_field_prefill_until_confirmation() {
        val out = Outgoing()
        out.send("c", "h", "ordinary", withFiles = false) { "ordinary" }
        out.ended("h", "loss")
        out.write("c", "")
        out.prefill("c", "Why?")
        assertEquals("loss", out.notSent["c"])
        out.shown("c", "ordinary", "h")
        assertNull(out.notSent["c"])
        assertEquals("Why?", out.unsent["c"])
    }

    @Test fun unowned_stale_composer_warning_clears_on_successful_prefill_only() {
        val out = Outgoing()
        out.notSent["c"] = "stale"
        out.write("c", "my draft")
        assertFalse(out.prefill("c", "Why?"))
        assertEquals("stale", out.notSent["c"])
        assertNotNull(out.notes["c"])
        out.write("c", "")
        assertTrue(out.prefill("c", "Why?"))
        assertNull(out.notSent["c"])
        assertNull(out.notes["c"])
        out.leave("c")
        assertNull(out.unsent["c"])
    }

    private fun literalLoss(): Outgoing = Outgoing().apply {
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
        assertNull(sendSuggested("c", line, "Yes", listOf(line)) { text ->
            assertTrue(send("c", "h", text, withFiles = false) { "literal" })
            null
        })
        ended("h", "loss")
        assertNull(unsent["c"])
        assertEquals("Reply unconfirmed. Check history before sending it again.", notSent["c"])
    }
}
