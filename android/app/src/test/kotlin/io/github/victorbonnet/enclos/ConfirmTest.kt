package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmTest {
    /** A prompt that answers [ok] with [why], and counts what it was asked. */
    private class Prompt(val ok: Boolean, val why: String? = null) {
        val titles = mutableListOf<String>()
        fun ask(title: String, done: (Boolean, String?) -> Unit) {
            titles += title
            done(ok, why)
        }
    }

    @Test
    fun a_decision_is_sent_once_the_person_proved_it_is_them() {
        val prompt = Prompt(ok = true)
        var sent = 0
        val held = mutableListOf<String?>()
        Confirm.decide("Approve the request", screenLock = true, ask = prompt::ask, send = { sent++ }, held = { held += it })
        assertEquals(listOf("Approve the request"), prompt.titles)
        assertEquals(1, sent)
        assertTrue(held.isEmpty())
    }

    @Test
    fun a_cancelled_prompt_sends_nothing() {
        val prompt = Prompt(ok = false, why = "Authentication canceled by user.")
        var sent = 0
        val held = mutableListOf<String?>()
        Confirm.decide("Deny the request", screenLock = true, ask = prompt::ask, send = { sent++ }, held = { held += it })
        assertEquals(0, sent)
        assertEquals(listOf<String?>("Authentication canceled by user."), held)
    }

    @Test
    fun every_decision_asks_again() {
        // Whatever the app-lock setting, and however recently the person proved it.
        val prompt = Prompt(ok = true)
        var sent = 0
        repeat(2) { Confirm.decide("Approve the request", screenLock = true, ask = prompt::ask, send = { sent++ }, held = {}) }
        assertEquals(2, prompt.titles.size)
        assertEquals(2, sent)
    }

    @Test
    fun a_phone_without_a_screen_lock_cannot_decide() {
        val prompt = Prompt(ok = true)
        var sent = 0
        val held = mutableListOf<String?>()
        Confirm.decide("Save the policy", screenLock = false, ask = prompt::ask, send = { sent++ }, held = { held += it })
        assertTrue(prompt.titles.isEmpty())
        assertEquals(0, sent)
        assertEquals(listOf<String?>(Confirm.NO_SCREEN_LOCK), held)
    }
}
