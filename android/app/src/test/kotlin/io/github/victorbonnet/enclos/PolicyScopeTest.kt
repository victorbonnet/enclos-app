package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
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
        val tricky = request("write", "/Users/me/a\u202Etxt.exe", "")
        assertFalse(Visible.hasHidden(tricky.shown))
        // A tag character or a line separator is written out too, not shown as nothing or a new line.
        assertEquals("/Users/alice/work\u27E8U+E0041\u27E9", request("write", "/Users/alice/work\uDB40\uDC41", "").shown)
        assertEquals("/Users/alice/a\u27E8U+2028\u27E9b", request("write", "/Users/alice/a\u2028b", "").shown)
    }

    private val wellFormed = """{"type":"approval","request":"r1","sandbox":"lab","agent":"lab/main",
        "access":{"kind":"write","target":"/Users/alice/work"},"action":"Write a file","time":1,
        "state":"pending","revision":"rev","available":true}"""

    @Test fun a_well_formed_request_is_read() {
        val read = PolicyRequest.parse("pi", JSONObject(wellFormed))
        assertEquals("pi/r1", read?.key)
        assertEquals("/Users/alice/work", read?.shown)
    }

    @Test fun a_request_missing_a_field_it_needs_is_dropped_not_fatal() {
        listOf("time", "access", "request", "state", "sandbox").forEach { field ->
            val m = JSONObject(wellFormed).apply { remove(field) }
            assertNull(field, PolicyRequest.parse("pi", m))
        }
        assertNull(PolicyRequest.parse("pi", JSONObject(wellFormed).put("access", JSONObject().put("kind", "write"))))
        assertNull(PolicyRequest.parse("pi", JSONObject(wellFormed).put("time", "soon")))
    }
}
