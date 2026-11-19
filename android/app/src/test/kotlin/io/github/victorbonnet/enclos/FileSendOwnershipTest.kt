package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class FileSendOwnershipTest {
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
