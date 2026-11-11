package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyScopeTest {
    private fun request(kind: String, target: String, scope: String) = PolicyRequest("pi", "r1", "lab", "lab/main", null,
        kind, target, false, "Write a file", 1, "pending", "Allow…", false, "rev", true, null, scope)

    @Test fun the_host_scope_leads_over_the_agents_target() {
        val linked = request("write", "/Users/me/lab/out", "/Users/me/Documents/real")
        assertEquals("/Users/me/Documents/real", linked.shown)
        assertEquals("Write to /Users/me/Documents/real", linked.title)
        assertTrue(linked.retargeted)
    }

    @Test fun a_host_without_scopes_shows_the_target_and_flags_nothing() {
        val old = request("write", "/Users/me/work", "")
        assertEquals("/Users/me/work", old.shown)
        assertFalse(old.retargeted)
    }

    @Test fun normalizing_is_not_a_retarget() {
        assertFalse(request("write", "/Users/me/work/", "/Users/me/work").retargeted)
        // A destination's normalized form is shown, without the symlink warning.
        val net = request("network", "API.Example.com:443", "api.example.com")
        assertEquals("Connect to api.example.com", net.title)
        assertFalse(net.retargeted)
    }

    @Test fun hidden_characters_are_shown_not_obeyed() {
        val tricky = request("write", "/Users/me/a‮txt.exe", "")
        assertFalse(Visible.hasHidden(tricky.shown))
    }
}
