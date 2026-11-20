package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class AskWhyFileOwnershipTest {
    private val key = FileKey("h", "c")

    @Test fun leaving_question_keeps_retained_original_and_draft_files() {
        val out = Outgoing()
        out.files.drafted(key, SendFile("original.txt", "text/plain", byteArrayOf(1)))
        out.files.begin(key, "original")
        out.ended("h", "lost")
        val original = out.files.phase(key)
        assertTrue(original is FilePhase.Unknown)
        assertTrue(out.prefill("c", "Why?"))
        out.leave("c")
        assertNull(out.unsent["c"])
        assertSame(original, out.files.phase(key))
        out.files.dismiss(key)
        val newer = SendFile("newer.txt", "text/plain", byteArrayOf(2))
        out.files.drafted(key, newer)
        assertTrue(out.prefill("c", "Why again?"))
        out.leave("c")
        assertEquals(listOf(newer), out.files.draft(key))
        out.clear()
    }

    @Test fun forget_and_clear_retire_notes_prefills_and_files() {
        for (all in listOf(false, true)) {
            val out = Outgoing()
            out.prefill("c", "Why?")
            out.write("c", "my draft")
            assertFalse(out.prefill("c", "Another question?"))
            out.files.drafted(key, SendFile("original.txt", "text/plain", byteArrayOf(1)))
            out.files.begin(key, "original")
            out.ended("h", "lost")
            if (all) out.clear() else out.forget(listOf("c"))
            assertNull(out.notes["c"])
            assertNull(out.unsent["c"])
            assertNull(out.files.phase(key).send())
            assertFalse(out.files.owns("c"))
            assertTrue(out.files.draft(key).isEmpty())
            // Reuse must not mistake equal user text for the forgotten question.
            out.write("c", "Why?")
            assertFalse(out.prefill("c", "Replacement?"))
            out.leave("c")
            assertEquals("Why?", out.unsent["c"])
        }
    }
}
