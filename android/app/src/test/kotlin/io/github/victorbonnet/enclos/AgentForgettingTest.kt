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

    @Test fun the_host_page_keeps_its_removed_and_failed_actions_reachable() {
        val f = Fixture()
        f.listed("mac", "gone/coder")
        f.listed("pi", "gone/coder")
        assertEquals(listOf(f.coder), f.client.offered("mac"))
        assertTrue(f.client.forget(f.coder))
        assertEquals(listOf(f.coder), f.client.offered("mac"))
        f.receipt("error")
        // Reauthentication clears the old inventory while a new list is requested.
        f.client.authenticated("mac")
        assertEquals(listOf(f.coder), f.client.offered("mac"))
        f.listed("mac", "gone/coder")
        assertTrue(f.client.forget(f.coder))
        f.receipt("ack")
        assertEquals(emptyList<AgentKey>(), f.client.offered("mac"))
        assertEquals(listOf(AgentKey("pi", "gone/coder")), f.client.offered("pi"))
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

    @Test fun an_answer_after_a_timeout_or_a_disconnect_still_settles_the_forget() {
        val f = Fixture()
        f.listed("mac", "gone/coder")
        f.client.forget(f.coder)
        f.timers.last()()
        assertTrue(f.client.errors.getValue(f.coder).contains("not confirmed"))
        assertTrue(f.receipt("ack"))
        assertFalse(f.coder in f.client.errors)
        assertTrue(f.client.forgotten[f.coder] == true)
        assertFalse(f.client.isRemoved(f.coder))
        // After a disconnect, a refusal that still comes is shown.
        val main = AgentKey("mac", "gone/main")
        f.listed("mac", "gone/main")
        f.client.forget(main)
        f.client.ended("mac")
        assertTrue(f.receipt("error"))
        assertEquals("Refused", f.client.errors[main])
        // A newer request decides, not the answer to one given up on.
        f.client.forget(main)
        val given = f.sent.last().second.getString("id")
        f.client.ended("mac")
        f.client.forget(main)
        assertFalse(f.receipt("ack", of = given))
        assertTrue(main in f.client.pending)
    }

    @Test fun a_stale_timer_does_not_fail_a_retry_and_offline_sends_nothing() {
        val f = Fixture()
        f.listed("mac", "gone/coder")
        f.client.forget(f.coder)
        f.receipt("error")
        f.client.forget(f.coder)
        f.timers.first()()
        assertTrue(f.coder in f.client.pending)
        assertFalse(f.coder in f.client.errors)
        f.receipt("ack")
        f.listed("mac", "gone/main")
        f.online = false
        val before = f.sent.size
        assertFalse(f.client.forget(AgentKey("mac", "gone/main")))
        assertEquals(before, f.sent.size)
    }

    @Test fun a_forget_whose_answer_was_lost_is_not_offered_again_once_the_host_stops_listing_it() {
        val f = Fixture()
        f.listed("mac", "gone/coder", "gone/main")
        f.client.forget(f.coder)
        f.client.ended("mac")
        assertTrue(f.client.offers(f.coder))
        f.client.authenticated("mac")
        f.listed("mac", "gone/main")
        assertFalse(f.coder in f.client.errors)
        assertFalse(f.client.offers(f.coder))
        assertTrue(f.client.offers(AgentKey("mac", "gone/main")))
    }

    @Test fun the_forgotten_flag_closes_one_page_and_a_new_session_drops_it() {
        val f = Fixture()
        f.listed("mac", "gone/coder")
        f.client.forget(f.coder)
        f.receipt("ack")
        assertTrue(f.client.forgotten[f.coder] == true)
        f.client.closed(f.coder)
        assertNull(f.client.forgotten[f.coder])
        f.listed("mac", "gone/coder")
        f.client.forget(f.coder)
        f.receipt("ack")
        // Made again and listed by a restarted bridge: its page opens.
        f.client.authenticated("mac")
        assertNull(f.client.forgotten[f.coder])
    }

    @Test fun the_page_says_why_it_offers_nothing_until_the_host_lists_the_agent() {
        val f = Fixture()
        f.online = false
        assertTrue(f.client.status(f.coder)!!.contains("Connect"))
        f.online = true
        f.supports = false
        assertTrue(f.client.status(f.coder)!!.contains("Update"))
        f.supports = true
        f.client.refresh("mac")
        assertTrue(f.client.status(f.coder)!!.contains("Asking"))
        // Asked once at a time.
        f.client.refresh("mac")
        assertEquals(1, f.sent.size)
        assertTrue(f.receipt("error"))
        assertEquals("Refused", f.client.status(f.coder))
        f.client.refresh("mac")
        f.timers.last()()
        assertTrue(f.client.status(f.coder)!!.contains("did not say"))
        f.client.refresh("mac")
        f.listed("mac")
        assertNull(f.client.status(f.coder))
        f.listed("mac", "gone/coder")
        assertNull(f.client.status(f.coder))
        assertTrue(f.client.offers(f.coder))
        f.sending = false
        f.client.refresh("mac")
        assertTrue(f.client.listErrors.getValue("mac").contains("could not be asked"))
    }

    @Test fun a_bad_list_keeps_the_last_one_and_names_hold_at_most_128_bytes() {
        val f = Fixture()
        f.listed("mac", "gone/coder")
        assertThrows(org.json.JSONException::class.java) { f.listed("mac", "gone/coder", "bad") }
        assertTrue(f.client.isRemoved(f.coder))
        val longest = "s/" + "a".repeat(126)
        assertTrue(f.listed("mac", longest))
        assertThrows(org.json.JSONException::class.java) { f.listed("mac", longest + "a") }
        assertTrue(f.client.isRemoved(AgentKey("mac", longest)))
    }
}
