package io.github.victorbonnet.enclos

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

/** Refused work releases only its own bytes, even before the person dismisses its notice. */
class TerminalFileBudgetTest {
    @Test fun plugin_operation_failed_is_terminal_even_if_it_races_the_receipt() {
        val budget = PreparedBudget(100)
        val state = SendingFiles(budget)
        val key = FileKey("host", "one")
        val job = Job()
        assertTrue(budget.reserve(10))
        assertTrue(state.preparing(key, job))
        state.prepared(key, job, listOf(SendFile("notes.pdf", "application/pdf", ByteArray(10))))
        val op = state.begin(key, "read this")!!
        assertTrue(state.refused("host", op.id, "operation_failed", "notes.pdf could not be stored"))
        assertTrue("the approved backend terminal code is not uncertainty", state.phase(key) is FilePhase.Failed)
        assertNull(state.retry(key))
        assertEquals(0L, budget.used())
    }

    @Test fun terminal_refusal_releases_only_its_own_payload_once() {
        val budget = PreparedBudget(100)
        val state = SendingFiles(budget)
        val first = FileKey("host", "one")
        val other = FileKey("host", "two")
        fun add(key: FileKey, size: Int) {
            assertTrue(budget.reserve(size))
            val job = Job()
            assertTrue(state.preparing(key, job))
            assertTrue(state.prepared(key, job, listOf(SendFile("notes.pdf", "application/pdf", ByteArray(size)))))
        }
        add(first, 60)
        add(other, 40)
        val send = state.begin(first, "original")!!
        state.transmitted(first, send.id)
        assertTrue(state.refused("host", send.id, "not_allowed", "notes.pdf was refused"))
        assertTrue(state.phase(first) is FilePhase.Failed)
        assertNull(state.retry(first))
        assertEquals("a terminal notice must not retain the refused payload", 40L, budget.used())
        state.acknowledged("host", send.id)
        state.refused("host", send.id, "not_allowed", "notes.pdf was refused")
        state.dismiss(first)
        state.forget(first)
        assertEquals("dismissal and duplicate callbacks must not release another draft", 40L, budget.used())
        assertEquals(40, state.draft(other).single().bytes.size)
        state.forget(other)
        assertEquals(0L, budget.used())
    }
}
