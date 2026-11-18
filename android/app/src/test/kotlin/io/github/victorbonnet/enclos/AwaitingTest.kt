package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitingTest {
    @Test
    fun a_refused_message_stops_the_wait() {
        val a = Awaiting()
        a.sent("m1", "c1", at = 0)
        assertTrue(a.waiting("c1", now = 1))
        assertEquals("c1", a.refused("m1"))
        assertFalse(a.waiting("c1", now = 2))
    }

    @Test
    fun a_refusal_of_one_message_keeps_the_others() {
        val a = Awaiting()
        a.sent("m1", "c1", at = 0)
        a.sent("m2", "c1", at = 1)
        assertEquals("c1", a.refused("m1"))
        assertTrue(a.waiting("c1", now = 2))
    }

    @Test
    fun an_error_about_something_else_changes_nothing() {
        val a = Awaiting()
        a.sent("m1", "c1", at = 0)
        assertNull(a.refused("other"))
        assertTrue(a.waiting("c1", now = 1))
    }

    @Test
    fun an_answer_ends_every_wait_of_its_conversation_only() {
        val a = Awaiting()
        a.sent("m1", "c1", at = 0)
        a.sent("m2", "c1", at = 1)
        a.sent("m3", "c2", at = 1)
        a.answered("c1")
        assertFalse(a.waiting("c1", now = 2))
        assertTrue(a.waiting("c2", now = 2))
        assertNull(a.refused("m1"))
    }

    @Test
    fun no_message_waits_past_the_limit() {
        val a = Awaiting(limit = 100)
        a.sent("m1", "c1", at = 0)
        assertTrue(a.waiting("c1", now = 99))
        assertFalse(a.waiting("c1", now = 100))
        assertNull(a.refused("m1"))
    }
}
