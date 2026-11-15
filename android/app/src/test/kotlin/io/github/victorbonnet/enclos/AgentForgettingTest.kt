package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentForgettingTest {
    private class Fixture {
        var online = true
        var supports = true
        var sending = true
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val timers = mutableListOf<() -> Unit>()
        val client = AgentForgetting({ online }, { supports }, { host, command -> sent += host to command; sending }, { _, callback -> timers += callback })
        val coder = AgentKey("mac", "gone/coder")
        fun listed(host: String = "mac", vararg agents: String) =
            client.received(host, JSONObject().put("type", "agents.removed.list").put("agents", JSONArray(agents.toList())))
        fun receipt(type: String, host: String = "mac", of: String = sent.last().second.getString("id"), code: String = "not_allowed") =
            client.received(host, JSONObject().put("type", type).put("of", of).put("code", code).put("message", "Refused"))
    }

    @Test fun an_address_names_its_host_and_agent() {
        assertEquals(AgentKey("mac", "lab/coder"), AgentKey.of("mac/lab/coder"))
        assertEquals("mac/lab/coder", AgentKey("mac", "lab/coder").address)
    }

    @Test fun the_removed_list_is_asked_only_of_a_bridge_that_forgets() {
        val f = Fixture()
        f.client.authenticated("mac")
        assertEquals("agents.removed", f.sent.single().second.getString("type"))
        f.supports = false
        f.client.authenticated("pi")
        assertEquals(1, f.sent.size)
        assertTrue(f.listed("mac", "gone/coder"))
        assertTrue(f.client.isRemoved(f.coder))
        // A new session drops what the last one listed.
        f.supports = true
        f.client.authenticated("mac")
        assertFalse(f.client.isRemoved(f.coder))
    }

    @Test fun only_a_listed_agent_is_forgotten_and_the_ack_settles_it() {
        val f = Fixture()
        assertFalse(f.client.forget(f.coder))
        assertTrue(f.client.unavailable(f.coder)!!.contains("does not confirm"))
        f.listed("mac", "gone/coder", "gone/main")
        assertNull(f.client.unavailable(f.coder))
        assertTrue(f.client.forget(f.coder))
        val command = f.sent.single().second
        assertEquals("agent.forget", command.getString("type"))
        assertEquals("gone/coder", command.getString("agent"))
        assertFalse(f.client.forget(f.coder))
        assertFalse(f.receipt("ack", host = "pi"))
        assertFalse(f.receipt("ack", of = "another-request"))
        assertTrue(f.receipt("ack"))
        assertFalse(f.coder in f.client.pending)
        assertTrue(f.client.forgotten[f.coder] == true)
        assertFalse(f.client.isRemoved(f.coder))
        assertTrue(f.client.isRemoved(AgentKey("mac", "gone/main")))
        // The timeout of a settled request changes nothing.
        f.timers.single()()
        assertFalse(f.coder in f.client.errors)
    }

    @Test fun refusals_disconnects_and_timeouts_leave_a_retry() {
        val f = Fixture()
        f.listed("mac", "gone/coder")
        f.client.forget(f.coder)
        assertTrue(f.receipt("error"))
        assertEquals("Refused", f.client.errors[f.coder])
        assertTrue(f.client.isRemoved(f.coder))
        f.client.forget(f.coder)
        assertTrue(f.receipt("error", code = "unknown_type"))
        assertTrue(f.client.errors.getValue(f.coder).contains("Update"))
        f.client.forget(f.coder)
        f.client.ended("mac")
        assertFalse(f.coder in f.client.pending)
        assertTrue(f.client.errors.getValue(f.coder).contains("connection ended"))
        f.client.forget(f.coder)
        f.timers.last()()
        assertTrue(f.client.errors.getValue(f.coder).contains("not confirmed"))
        f.sending = false
        assertFalse(f.client.forget(f.coder))
        assertTrue(f.client.errors.getValue(f.coder).contains("not sent"))
        f.online = false
        assertTrue(f.client.unavailable(f.coder)!!.contains("Connect"))
    }

    @Test fun malformed_lists_fail_as_protocol_errors_and_hosts_are_kept_apart() {
        val f = Fixture()
        for (bad in listOf("coder", "gone/", "a/b/c", "gone/co\u0000der")) {
            assertThrows(org.json.JSONException::class.java) { f.listed("mac", bad) }
        }
        assertFalse(f.client.isRemoved(f.coder))
        f.listed("mac", "gone/coder")
        f.listed("pi", "gone/coder")
        f.client.clear("pi")
        assertTrue(f.client.isRemoved(f.coder))
        assertFalse(f.client.isRemoved(AgentKey("pi", "gone/coder")))
        f.client.clear()
        assertFalse(f.client.isRemoved(f.coder))
    }
}
