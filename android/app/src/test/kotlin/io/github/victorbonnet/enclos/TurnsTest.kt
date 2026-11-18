package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnsTest {
    private fun start(now: List<Step>, call: String, label: String? = null) =
        stepped(now, call, "start", "terminal", label, ok = true, durationMs = null)

    private fun end(now: List<Step>, call: String, ok: Boolean = true) =
        stepped(now, call, "end", "terminal", null, ok = ok, durationMs = 40)

    @Test
    fun a_start_adds_a_step_and_its_end_marks_it_done() {
        val started = start(emptyList(), "c1", "ls -la")
        assertEquals(listOf(Step("c1", "terminal", "ls -la")), started)
        assertEquals(listOf(Step("c1", "terminal", "ls -la", done = true, ok = false, durationMs = 40)), end(started, "c1", ok = false))
    }

    @Test
    fun a_repeated_start_changes_nothing() {
        val started = start(emptyList(), "c1", "ls")
        assertEquals(started, start(started, "c1", "something else"))
    }

    @Test
    fun an_end_without_its_start_adds_the_step_done() {
        assertEquals(listOf(Step("c9", "terminal", null, done = true, durationMs = 40)), end(emptyList(), "c9"))
    }

    @Test
    fun a_turn_that_ends_leaves_no_step_running() {
        val steps = end(start(start(emptyList(), "c1"), "c2"), "c1", ok = false)
        assertTrue(steps.any { !it.done })
        val after = settled(steps)
        assertFalse(after.any { !it.done })
        // What had ended keeps its outcome.
        assertEquals(steps[0], after[0])
        assertEquals(2, after.size)
    }
}
