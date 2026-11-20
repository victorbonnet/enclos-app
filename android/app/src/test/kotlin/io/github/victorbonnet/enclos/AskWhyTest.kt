package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskWhyTest {
    // 2026-11-20 05:41:00 UTC.
    private val at = 1_795_153_260L

    private fun request(agent: String, kind: String = "network", target: String = "s3.us-east-1.amazonaws.com:443", scope: String = "s3.us-east-1.amazonaws.com",
                        host: String = "host-a", conversation: String? = null, state: String = "pending", available: Boolean = true, action: String = "Connect") =
        PolicyRequest(host, "r1", "lab", agent, conversation, kind, target, false, action, at, state, "Allow…", false, "rev", available, null, scope)

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

    @Test fun the_agent_that_asked_is_the_one_of_the_requests_host() {
        // host-a's lab/main is listed first: the request is host-b's.
        assertEquals("host-b/lab/main", askWhyAgent(request("lab/main", host = "host-b"), agents) { 0L }?.address)
    }

    @Test fun an_agent_the_phone_no_longer_lists_is_not_replaced_by_another() {
        assertNull(askWhyAgent(request("lab/gone"), agents) { 0L })
    }

    @Test fun a_proxy_refusal_asks_its_sandboxs_agent_with_the_newest_message() {
        val last = mapOf("host-a/lab/main" to 10L, "host-a/lab/data" to 20L, "host-a/desk/main" to 99L, "host-b/lab/main" to 99L)
        val asked = askWhyAgent(request(""), agents) { last[it] }
        // Not another sandbox's agent, nor the same sandbox name on another host.
        assertEquals("host-a/lab/data", asked?.address)
    }

    @Test fun a_sandbox_with_no_listed_agent_offers_no_one() {
        assertNull(askWhyAgent(request("").copy(sandbox = "empty"), agents) { 0L })
    }

    @Test fun only_a_pending_request_the_phone_can_approve_offers_asking() {
        assertTrue(askWhyOffered(request("lab/main")))
        // Failed was decided already; the others are settled or never decided here.
        for (state in listOf("failed", "applying", "approved", "denied", "closed", "diagnostic")) {
            assertFalse(state, askWhyOffered(request("lab/main", state = state)))
        }
        // Unavailable can only be closed.
        assertFalse(askWhyOffered(request("lab/main", available = false)))
    }

    @Test fun the_question_names_the_scope_when_it_was_recorded_and_who_decides() {
        val q = askWhyQuestion(request("lab/main"))
        assertTrue(q, q.contains("does not allow a connection to s3.us-east-1.amazonaws.com, which you asked for"))
        assertTrue(q, q.contains("recorded the request at 2026-11-20 05:41 UTC"))
        assertTrue(q, q.contains("Why do you need it"))
        assertTrue(q, q.endsWith("I'll decide whether to allow it once I know."))
        assertFalse(q, q.contains("cannot tell which agent"))
    }

    @Test fun a_proxy_refusal_asks_who_tried_it() {
        val q = askWhyQuestion(request(""))
        assertTrue(q, q.contains("proxy refused a connection to s3.us-east-1.amazonaws.com"))
        assertTrue(q, q.contains("2026-11-20 05:41 UTC"))
        assertTrue(q, q.contains("cannot tell which agent tried it"))
        assertTrue(q, q.contains("Was it you, or do you know who?"))
    }

    @Test fun a_file_scope_reads_as_the_host_resolved_it_with_hidden_characters_written_out() {
        val linked = askWhyQuestion(request("lab/main", "write", "/work/out", "/data/out"))
        assertTrue(linked, linked.contains("writing to /data/out,"))
        // The path the agent named, a link to that folder, is said too.
        assertTrue(linked, linked.contains("You named /work/out, which leads there."))
        assertTrue(askWhyQuestion(request("lab/main", "read", "/etc/x‮y", "")).contains("reading /etc/x⟨U+202E⟩y,"))
        // Another kind quotes the agent's own label, without its hidden characters.
        assertFalse(Visible.hasHidden(askWhyQuestion(request("lab/main", "other", action = "do‮thing"))))
    }

    private val own = Conversation("host-a", "lab/main", "c1")

    @Test fun the_requests_own_conversation_gets_the_question_in_its_field() {
        val out = Outgoing()
        assertEquals("c1", askInConversation(request("lab/main", conversation = "c1"), "Why?", listOf(own), out))
        assertEquals("Why?", out.unsent["c1"])
        assertNull(out.notes["c1"])
    }

    @Test fun a_draft_the_person_wrote_is_kept_and_the_field_says_so() {
        val out = Outgoing()
        out.write("c1", "my draft")
        assertEquals("c1", askInConversation(request("lab/main", conversation = "c1"), "Why?", listOf(own), out))
        assertEquals("my draft", out.unsent["c1"])
        assertEquals("Your draft is kept: the question was not added.", out.notes["c1"])
        // Writing again takes the note away.
        out.write("c1", "my draft!")
        assertNull(out.notes["c1"])
    }

    @Test fun an_untouched_question_gives_way_to_the_next_and_goes_when_left() {
        val out = Outgoing()
        askInConversation(request("lab/main", conversation = "c1"), "Why A?", listOf(own), out)
        askInConversation(request("lab/main", conversation = "c1"), "Why B?", listOf(own), out)
        assertEquals("Why B?", out.unsent["c1"])
        out.leave("c1")
        assertNull(out.unsent["c1"])
    }

    @Test fun a_question_the_person_changed_is_theirs() {
        val out = Outgoing()
        askInConversation(request("lab/main", conversation = "c1"), "Why A?", listOf(own), out)
        out.write("c1", "Why A? And when?")
        askInConversation(request("lab/main", conversation = "c1"), "Why B?", listOf(own), out)
        assertEquals("Why A? And when?", out.unsent["c1"])
        out.leave("c1")
        assertEquals("Why A? And when?", out.unsent["c1"])
    }

    @Test fun no_other_conversation_is_asked_in() {
        val asked = request("lab/main", conversation = "c1")
        val cases = mapOf(
            "another host's" to listOf(own.copy(host = "host-b")),
            "a group" to listOf(own.copy(members = listOf("lab/main", "lab/data"))),
            "archived" to listOf(own.copy(archived = true)),
            "another agent's" to listOf(own.copy(agent = "lab/data")),
            "not held" to emptyList(),
        )
        for ((case, conversations) in cases) {
            val out = Outgoing()
            assertNull(case, askInConversation(asked, "Why?", conversations, out))
            assertTrue(case, out.unsent.isEmpty())
        }
        val out = Outgoing()
        assertNull(askInConversation(request("lab/main"), "Why?", listOf(own), out))
        // A proxy refusal asks in a new conversation, whatever it carries.
        assertNull(askInConversation(request("", conversation = "c1"), "Why?", listOf(own), out))
        assertTrue(out.unsent.isEmpty())
    }

    @Test fun routes_from_a_request_are_kept_and_lead_back_to_it() {
        val key = "host-a/r 1\nx"
        val routes = listOf(Route.Chat("c1"), Route.Chat("c 1\n2", about = key), Route.AskWhy(key, "host-a/lab/main"), Route.ApprovalPage(key), Route.NewChat, Route.Tabs)
        for (r in routes) assertEquals(r.toString(), r, Route.restore(r.save()))
        assertEquals(Route.ApprovalPage(key), Route.Chat("c1", about = key).up)
        assertEquals(Route.ApprovalPage(key), Route.AskWhy(key, "host-a/lab/main").up)
        assertEquals(Route.Tabs, Route.Chat("c1").up)
        assertTrue(Route.Chat("c1", about = key).depth > Route.ApprovalPage(key).depth)
    }
}
