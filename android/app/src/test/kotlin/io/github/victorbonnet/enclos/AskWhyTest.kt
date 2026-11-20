package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskWhyTest {
    // 2026-11-20 05:41:00 UTC.
    private val at = 1_795_153_260L

    private fun request(agent: String, kind: String = "network", target: String = "s3.us-east-1.amazonaws.com:443", scope: String = "s3.us-east-1.amazonaws.com") =
        PolicyRequest("host-a", "r1", "lab", agent, null, kind, target, false, "Connect", at, "pending", "Allow…", false, "rev", true, null, scope)

    private fun agent(host: String, name: String) = Agent(host, name, "openclaw", emptyList())

    private val agents = listOf(
        agent("host-a", "lab/main"),
        agent("host-a", "lab/data"),
        agent("host-a", "desk/main"),
        agent("host-b", "lab/main"),
    )

    @Test fun the_agent_that_asked_is_asked() {
        val asked = askWhyAgent(request("lab/data"), agents) { 0L }
        assertEquals("host-a/lab/data", asked?.address)
    }

    @Test fun an_agent_the_phone_no_longer_lists_is_not_replaced_by_another() {
        assertNull(askWhyAgent(request("lab/gone"), agents) { 0L })
    }

    @Test fun a_proxy_refusal_asks_its_sandboxs_agent_active_last() {
        val last = mapOf("host-a/lab/main" to 10L, "host-a/lab/data" to 20L, "host-a/desk/main" to 99L, "host-b/lab/main" to 99L)
        val asked = askWhyAgent(request(""), agents) { last[it] }
        // Not another sandbox's agent, nor the same sandbox name on another host.
        assertEquals("host-a/lab/data", asked?.address)
    }

    @Test fun a_sandbox_with_no_listed_agent_offers_no_one() {
        assertNull(askWhyAgent(request("").copy(sandbox = "empty"), agents) { 0L })
    }

    @Test fun the_question_names_the_scope_the_time_in_utc_and_who_decides() {
        val q = askWhyQuestion(request("lab/main"))
        assertTrue(q, q.contains("lab sandbox's policy refused a connection to s3.us-east-1.amazonaws.com, which you tried at 2026-11-20 05:41 UTC."))
        assertTrue(q, q.contains("Why do you need it"))
        assertTrue(q, q.endsWith("I'll decide whether to allow it once I know."))
        assertFalse(q, q.contains("cannot tell which agent"))
    }

    @Test fun a_proxy_refusal_asks_who_tried_it() {
        val q = askWhyQuestion(request(""))
        assertTrue(q, q.contains("refused a connection to s3.us-east-1.amazonaws.com at 2026-11-20 05:41 UTC; its proxy cannot tell which agent tried it. Was it you, or do you know who?"))
    }

    @Test fun a_file_scope_reads_as_the_host_resolved_it_with_hidden_characters_written_out() {
        assertTrue(askWhyQuestion(request("lab/main", "write", "/work/out", "/data/out")).contains("refused writing to /data/out,"))
        assertTrue(askWhyQuestion(request("lab/main", "read", "/etc/x‮y", "")).contains("refused reading /etc/x⟨U+202E⟩y,"))
    }
}
