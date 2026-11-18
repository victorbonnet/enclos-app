package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningTest {
    private val installing = SandboxOperation("request-a", "runtime.install", "home", "hermes", message = "Installing Hermes…")

    @Test
    fun sandbox_names_follow_the_hosts_ascii_rule_and_boundaries() {
        listOf("home", "pi-bot", "a1", "a" + "b".repeat(40)).forEach { assertNull(it, sandboxNameError(it)) }
        listOf("", "a", "Home", "1home", "a_b", "a/b", "a b", "aé", "a\n", "a" + "b".repeat(41)).forEach {
            assertNotNull(it, sandboxNameError(it))
        }
    }

    @Test
    fun progress_and_completion_cannot_cross_requests_sandboxes_or_operation_types() {
        assertEquals(installing, installing.progress("request-b", "runtime.install", "home", "Other request"))
        assertEquals(installing, installing.progress("request-a", "runtime.install", "lab", "Other sandbox"))
        assertEquals(installing, installing.progress("request-a", "sandbox.create", "home", "Other operation"))
        assertEquals(installing, installing.completed("request-b", "runtime.install", "home", "Installed"))
        assertEquals(installing, installing.completed("request-a", "runtime.install", "lab", "Installed"))
        assertEquals(installing, installing.completed("request-a", "sandbox.create", "home", "Created"))
        assertEquals(installing, installing.failed("request-b", "Other request failed"))
    }

    @Test
    fun progress_stays_busy_and_completion_keeps_the_hosts_remaining_setup_command() {
        val progress = installing.progress("request-a", "runtime.install", "home", "Downloading Hermes")
        assertTrue(progress.busy)
        assertEquals("Downloading Hermes", progress.message)
        val done = progress.completed("request-a", "runtime.install", "home", "Hermes installed", "enclos connect home -- hermes setup")
        assertFalse(done.busy)
        assertTrue(done.done)
        assertNull(done.error)
        assertEquals("enclos connect home -- hermes setup", done.login)
        assertEquals(done, done.progress("request-a", "runtime.install", "home", "Late progress"))
        assertEquals(done, done.failed("request-a", "Late error"))
        assertEquals(done, done.disconnected())
    }

    @Test
    fun a_failure_or_disconnect_stops_pending_work_without_claiming_success() {
        val failed = installing.failed("request-a", "Installer failed")
        assertFalse(failed.busy)
        assertFalse(failed.done)
        assertEquals("Installer failed", failed.error)
        assertEquals(failed, failed.completed("request-a", "runtime.install", "home", "Late completion"))
        val disconnected = installing.disconnected()
        assertFalse(disconnected.busy)
        assertFalse(disconnected.done)
        assertNotNull(disconnected.error)
    }
}
