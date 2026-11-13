package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Test

class TimesTest {
    private val now = 1_800_000_000L
    private val minute = 60L
    private val hour = 3_600L
    private val day = 86_400L

    private fun ago(before: Long) = Times.ago(now - before, now)

    @Test
    fun how_long_ago_in_the_largest_whole_unit() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59))
        assertEquals("1 min ago", ago(minute))
        assertEquals("59 min ago", ago(hour - 1))
        assertEquals("1 hour ago", ago(hour))
        assertEquals("3 hours ago", ago(3 * hour + 20 * minute))
        assertEquals("23 hours ago", ago(day - 1))
        assertEquals("yesterday", ago(day))
        assertEquals("yesterday", ago(2 * day - 1))
        assertEquals("2 days ago", ago(2 * day))
        assertEquals("6 days ago", ago(7 * day - 1))
        assertEquals("1 week ago", ago(7 * day))
        assertEquals("4 weeks ago", ago(29 * day))
        assertEquals("1 month ago", ago(30 * day))
        assertEquals("12 months ago", ago(364 * day))
        assertEquals("1 year ago", ago(365 * day))
        assertEquals("2 years ago", ago(800 * day))
    }

    @Test
    fun a_time_ahead_of_the_phone_s_clock_is_just_now() {
        assertEquals("just now", Times.ago(now + 5 * minute, now))
    }
}
