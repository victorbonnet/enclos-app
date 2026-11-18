package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptTest {
    private fun line(seq: Long) = Line(seq, "agent", "m$seq", "reply", seq)

    @Test
    fun after_a_failed_write_no_later_message_of_that_host_is_kept() {
        val gaps = Gaps()
        assertTrue(gaps.keeps("mac", 11))
        gaps.failed("mac", 10)
        assertFalse(gaps.keeps("mac", 11))
        assertFalse(gaps.keeps("mac", 10))
        assertTrue(gaps.keeps("mac", 9))
        assertTrue(gaps.keeps("pi", 11))
    }

    @Test
    fun the_lowest_failed_write_counts() {
        val gaps = Gaps()
        gaps.failed("mac", 10)
        gaps.failed("mac", 12)
        assertFalse(gaps.keeps("mac", 11))
        gaps.failed("mac", 7)
        assertFalse(gaps.keeps("mac", 8))
    }

    @Test
    fun a_forgotten_host_keeps_again() {
        val gaps = Gaps()
        gaps.failed("mac", 10)
        gaps.clear("mac")
        assertTrue(gaps.keeps("mac", 11))
    }

    @Test
    fun the_second_pass_puts_the_older_messages_before_what_came_since() {
        val older = (1L..5).map(::line)
        val now = listOf(line(4), line(5), line(6))
        assertEquals((1L..6).map(::line), merged(older, now))
    }

    @Test
    fun the_second_pass_leaves_what_already_holds_everything() {
        val older = (1L..3).map(::line)
        val now = (1L..4).map(::line)
        assertSame(now, merged(older, now))
        assertSame(now, merged(emptyList(), now))
    }

    @Test
    fun a_conversation_of_another_host_is_not_this_hosts() {
        val all = listOf(Conversation("mac", "lab/main", "c-1"))
        assertTrue(ownedByAnother(all, "pi", "c-1"))
        assertFalse(ownedByAnother(all, "mac", "c-1"))
        assertFalse(ownedByAnother(all, "pi", "c-2"))
        assertFalse(ownedByAnother(all, "pi", ""))
    }
}
