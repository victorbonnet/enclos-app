package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class AskWhyFileOwnershipTest {
    @Test fun leaving_question_keeps_retained_original_and_newer_files() {
        val out = Outgoing()
        out.files.drafts["c"] = listOf(SendFile("original.txt", "text/plain", byteArrayOf(1)))
        out.send("c", "h", "original") { "original" }
        out.ended("h", "lost")
        val original = out.files.retries["c"]
        val newer = SendFile("newer.txt", "text/plain", byteArrayOf(2))
        out.files.drafts["c"] = listOf(newer)
        assertTrue(out.prefill("c", "Why?"))
        out.leave("c")
        assertNull(out.unsent["c"])
        assertSame(original, out.files.retries["c"])
        assertEquals(listOf(newer), out.files.drafts["c"])
        assertTrue(out.prefill("c", "Why again?"))
    }

    @Test fun forget_and_clear_retire_notes_prefills_and_files() {
        for (all in listOf(false, true)) {
            val out = Outgoing()
            out.prefill("c", "Why?")
            out.write("c", "my draft")
            assertFalse(out.prefill("c", "Another question?"))
            out.files.drafts["c"] = listOf(SendFile("original.txt", "text/plain", byteArrayOf(1)))
            out.send("c", "h", "original", preserveDraft = true) { "original" }
            out.ended("h", "lost")
            if (all) out.clear() else out.forget(listOf("c"))
            assertNull(out.notes["c"])
            assertNull(out.unsent["c"])
            assertNull(out.files.retries["c"])
            assertNull(out.files.drafts["c"])
            // Reuse must not mistake equal user text for the forgotten question.
            out.write("c", "Why?")
            assertFalse(out.prefill("c", "Replacement?"))
            out.leave("c")
            assertEquals("Why?", out.unsent["c"])
        }
    }
}
