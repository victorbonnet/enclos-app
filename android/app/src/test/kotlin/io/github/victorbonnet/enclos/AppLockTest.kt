package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** A lock with a clock and a screen lock the test moves, and what it kept. */
    private class Phone(on: Boolean) {
        var now = 1_000L
        var screenLock = true
        val kept = mutableListOf<Boolean>()
        val lock = LockState(on, screenLock = { screenLock }, clock = { now }, keep = { kept += it })

        /** The app leaves (not while a prompt is up) and comes back [away] later. */
        fun away(away: Long, asking: Boolean = false) {
            lock.stopped(asking)
            now += away
            lock.returned()
        }
    }

    @Test
    fun a_new_process_starts_locked_with_the_prompt_due_once() {
        val phone = Phone(on = true)
        assertTrue(phone.lock.locked.value)
        assertTrue(phone.lock.promptNow(asking = false))
        assertFalse(phone.lock.promptNow(asking = false))
    }

    @Test
    fun a_new_process_with_the_lock_off_starts_unlocked_and_never_prompts() {
        val phone = Phone(on = false)
        assertFalse(phone.lock.locked.value)
        assertFalse(phone.lock.promptNow(asking = false))
        phone.away(10 * AppLock.AWAY_MS)
        assertFalse(phone.lock.locked.value)
    }

    @Test
    fun it_locks_again_after_a_minute_away_not_before() {
        val phone = Phone(on = true)
        phone.lock.unlocked(ok = true, reason = null)
        phone.away(AppLock.AWAY_MS - 1)
        assertFalse(phone.lock.locked.value)
        assertFalse(phone.lock.promptNow(asking = false))
        phone.away(AppLock.AWAY_MS)
        assertTrue(phone.lock.locked.value)
        assertTrue(phone.lock.promptNow(asking = false))
    }

    @Test
    fun relocking_forgets_what_the_system_said_last_time() {
        val phone = Phone(on = true)
        phone.lock.unlocked(ok = false, reason = "Cancelled.")
        assertEquals("Cancelled.", phone.lock.said.value)
        phone.lock.unlocked(ok = true, reason = null)
        phone.away(AppLock.AWAY_MS)
        assertNull(phone.lock.said.value)
    }

    @Test
    fun time_on_the_screen_lock_page_the_prompt_opened_is_not_time_away() {
        val phone = Phone(on = true)
        phone.lock.unlocked(ok = true, reason = null)
        phone.away(10 * AppLock.AWAY_MS, asking = true)
        assertFalse(phone.lock.locked.value)
    }

    @Test
    fun a_cancelled_prompt_does_not_come_back_on_its_own_until_the_app_does() {
        val phone = Phone(on = true)
        assertTrue(phone.lock.promptNow(asking = false))
        phone.lock.unlocked(ok = false, reason = "Cancelled.")
        assertFalse(phone.lock.promptNow(asking = false))
        assertTrue(phone.lock.locked.value)
        // Left while locked, back a moment later: the prompt shows on its own again.
        phone.away(1_000)
        assertTrue(phone.lock.locked.value)
        assertTrue(phone.lock.promptNow(asking = false))
    }

    @Test
    fun leaving_while_the_prompt_is_up_brings_it_back_on_return() {
        val phone = Phone(on = true)
        assertTrue(phone.lock.promptNow(asking = false))
        phone.away(1_000, asking = true)
        assertTrue(phone.lock.promptNow(asking = false))
    }

    @Test
    fun the_prompt_does_not_show_twice_while_one_is_up() {
        val phone = Phone(on = true)
        assertFalse(phone.lock.promptNow(asking = true))
        // Its answer comes: a cancel leaves the lock screen's Unlock to ask again.
        phone.lock.unlocked(ok = false, reason = "Cancelled.")
        assertFalse(phone.lock.promptNow(asking = false))
    }

    @Test
    fun the_screen_lock_page_cancelled_does_not_open_the_prompt_again_at_once() {
        // Android 9: the prompt is a page of its own. The app stops while it
        // is up, starts again, gets the cancel, then is in front.
        val phone = Phone(on = true)
        assertTrue(phone.lock.promptNow(asking = false))
        phone.away(5_000, asking = true)
        phone.lock.unlocked(ok = false, reason = null)
        assertFalse(phone.lock.promptNow(asking = false))
    }

    @Test
    fun an_unlock_opens_the_app() {
        val phone = Phone(on = true)
        phone.lock.unlocked(ok = true, reason = null)
        assertFalse(phone.lock.locked.value)
        assertNull(phone.lock.said.value)
        assertFalse(phone.lock.promptNow(asking = false))
    }

    @Test
    fun the_lock_turns_itself_off_when_the_screen_lock_is_gone() {
        val phone = Phone(on = true)
        phone.screenLock = false
        phone.away(10 * AppLock.AWAY_MS)
        assertFalse(phone.lock.on.value)
        assertFalse(phone.lock.locked.value)
        assertTrue(phone.lock.dropped.value)
        assertFalse(phone.lock.promptNow(asking = false))
        assertEquals(listOf(false), phone.kept)
        // Turned on again: no longer said to be dropped.
        phone.screenLock = true
        phone.lock.set(true)
        assertFalse(phone.lock.dropped.value)
        assertEquals(listOf(false, true), phone.kept)
    }

    @Test
    fun turning_it_on_or_off_is_kept_and_does_not_lock_at_once() {
        val phone = Phone(on = false)
        phone.lock.set(true)
        assertTrue(phone.lock.on.value)
        assertFalse(phone.lock.locked.value)
        phone.lock.set(false)
        assertFalse(phone.lock.on.value)
        assertEquals(listOf(true, false), phone.kept)
    }
}
