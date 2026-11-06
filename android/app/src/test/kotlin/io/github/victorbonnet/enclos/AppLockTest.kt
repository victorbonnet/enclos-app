package io.github.victorbonnet.enclos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    @Test
    fun it_locks_on_return_after_a_minute_away_when_on() {
        assertTrue(AppLock.locksOnReturn(on = true, leftAt = 1_000, now = 61_000))
        assertTrue(AppLock.locksOnReturn(on = true, leftAt = 1_000, now = 10_000_000))
    }

    @Test
    fun it_does_not_lock_after_a_short_absence_when_off_or_before_leaving() {
        assertFalse(AppLock.locksOnReturn(on = true, leftAt = 1_000, now = 60_999))
        assertFalse(AppLock.locksOnReturn(on = false, leftAt = 1_000, now = 10_000_000))
        assertFalse(AppLock.locksOnReturn(on = true, leftAt = null, now = 10_000_000))
    }
}
