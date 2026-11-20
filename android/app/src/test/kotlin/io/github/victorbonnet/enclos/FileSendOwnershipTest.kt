package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class FileSendOwnershipTest {
    @Test fun old_original_discard_preserves_newer_ordinary_notice() = settleOriginal(false, "ordinary")
    @Test fun old_original_ack_preserves_newer_ordinary_notice() = settleOriginal(true, "ordinary")
    @Test fun old_original_discard_preserves_newer_literal_notice() = settleOriginal(false, "literal")
    @Test fun old_original_ack_preserves_newer_literal_notice() = settleOriginal(true, "literal")
    @Test fun discard_clears_own_original_notice() = settleOriginal(false, null)
    @Test fun ack_clears_own_original_notice() = settleOriginal(true, null)

    private fun settleOriginal(ack: Boolean, newer: String?) {
        val out = Outgoing()
        out.files.drafts["c"] = listOf(SendFile("original.txt", "text/plain", byteArrayOf(1)))
        assertTrue(out.send("c", "h", "original") { "original" })
        out.ended("h", "original loss")
        assertNotNull(out.notSent["c"])
        if (newer == "ordinary") {
            assertTrue(out.send("c", "h", "new ordinary", withFiles = false) { "newer" })
            out.ended("h", "new ordinary loss")
        } else if (newer == "literal") {
            val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
            assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
                assertTrue(out.send("c", "h", text, withFiles = false) { "newer" })
                null
            })
            out.ended("h", "new literal loss")
        }
        val notice = out.notSent["c"]
        assertNotNull(notice)
        out.write("c", "independent composer")
        val selected = SendFile("selected.txt", "text/plain", byteArrayOf(2))
        out.files.drafts["c"] = listOf(selected)
        if (ack) out.acknowledged("h", "original") else out.discardRetry("c")
        assertNull(out.files.retries["c"])
        assertEquals("independent composer", out.unsent["c"])
        assertEquals(listOf(selected), out.files.drafts["c"])
        if (newer == null) assertNull("own notice must retire", out.notSent["c"])
        else assertEquals("old original must preserve newer notice", notice, out.notSent["c"])
        // Outcome metadata must retire with its notice, or remain with the newer outcome.
        val owners = Outgoing::class.java.getDeclaredField("noticeOwner").apply { isAccessible = true }.get(out) as Map<*, *>
        assertEquals(if (newer == null) null else "newer", owners["c"])
    }

    @Test fun literal_confirmation_on_wrong_host_and_file_ack_cannot_settle_literal() {
        val out = Outgoing()
        val line = Line(1, "agent", "Choose", "reply", 1, suggestedReplies = listOf("Yes"))
        out.write("c", "Yes")
        assertNull(out.sendSuggested("c", line, "Yes", listOf(line)) { text ->
            out.send("c", "h", text, withFiles = false) { "literal" }
            null
        })
        out.ended("h", "lost")
        val notice = out.notSent["c"]
        out.shown("c", "Yes", "other-host")
        out.acknowledged("h", "literal")
        assertEquals(notice, out.notSent["c"])
        assertEquals("Yes", out.unsent["c"])
        out.shown("c", "Yes", "h")
        assertNull(out.notSent["c"])
        assertEquals("Yes", out.unsent["c"])
    }

    @Test fun pending_file_cannot_be_discarded_but_pending_text_does_not_lock_retry() {
        val out = Outgoing()
        out.files.drafts["c"] = listOf(SendFile("original.txt", "text/plain", byteArrayOf(1)))
        out.send("c", "h", "original") { "original" }
        assertTrue(out.waitingFiles("c"))
        out.discardRetry("c")
        assertNotNull(out.files.retries["c"])
        out.ended("h", "lost")
        out.send("c", "h", "text", withFiles = false) { "text" }
        assertFalse(out.waitingFiles("c"))
        assertTrue(out.waiting("c"))
        out.discardRetry("c")
        assertNull(out.files.retries["c"])
    }

    @Test fun delayed_text_refusal_and_original_receipt_preserve_new_upload() {
        val out = Outgoing()
        out.send("c", "h", "text", withFiles = false) { "text" }
        out.files.drafts["c"] = listOf(SendFile("old.txt", "text/plain", byteArrayOf(1)))
        out.send("c", "h", "old") { "old" }
        out.ended("h", "lost")
        out.discardRetry("c")
        out.files.drafts["c"] = listOf(SendFile("new.txt", "text/plain", byteArrayOf(2)))
        out.send("c", "h", "new") { "new" }
        val token = Any()
        out.files.active["c"] = token
        out.files.progress["c"] = 37
        out.files.errors["c"] = "newer error"
        out.notSent["c"] = "newer reason"
        out.refused("text", "late text refusal")
        assertSame(token, out.files.active["c"])
        assertEquals(37, out.files.progress["c"])
        assertEquals("newer reason", out.notSent["c"])
        out.acknowledged("h", "old")
        out.refused("old", "late original refusal")
        assertSame(token, out.files.active["c"])
        assertEquals(37, out.files.progress["c"])
        assertEquals("new", out.files.retries["c"]?.id)
        assertEquals("newer error", out.files.errors["c"])
        out.refused("new", "own refusal")
        assertNull(out.files.active["c"])
        assertNull(out.files.progress["c"])
    }
}
