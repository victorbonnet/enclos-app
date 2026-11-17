package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeedsYouTest {
    private fun item(id: String, expired: Boolean = false) = JSONObject()
        .put("id", id).put("kind", "question").put("agent", "lab/main").put("conversation", "home")
        .put("summary", "Inbox filters reset. Allow a fix?").put("since", 1).put("expired", expired)
    private fun frame(revision: Long, vararg items: JSONObject, reset: Boolean = true, complete: Boolean = true) =
        JSONObject().put("revision", revision).put("reset", reset).put("complete", complete).put("items", JSONArray(items))

    @Test fun hosts_and_expired_questions_are_kept_until_a_complete_newer_snapshot_removes_them() {
        val persisted = mutableListOf<Pair<String, List<OpenItem>>>()
        val index = NeedsYou { host, rows -> persisted += host to rows }
        index.received("one", frame(1, item("q1", true)))
        index.received("two", frame(1, item("q2")))
        assertEquals(2, index.items.size)
        assertEquals(1, attentionCounts(index.items.values)["one/lab/main"])
        assertTrue(index.items.values.single { it.host == "one" }.expired)
        index.received("one", frame(2, item("q3"), complete = false))
        assertEquals(setOf("q1", "q2"), index.items.values.map { it.id }.toSet())
        index.ended("one")
        assertEquals(2, index.items.size)
        index.received("one", frame(3))
        assertEquals(listOf("q2"), index.items.values.map { it.id })
        assertFalse(index.received("one", frame(1, item("old"))))
        assertEquals(3, persisted.size)
        index.authenticated("one")
        index.received("one", frame(0, item("after-restart")))
        assertEquals(2, index.items.size)
    }

    @Test fun malformed_optional_replies_leave_a_message_readable_and_literal_choices_are_unchanged() {
        val replies = listOf("Fix Inbox filters", "Keep paused")
        assertEquals(replies, suggestedReplies(JSONArray(replies)))
        for (invalid in listOf(JSONArray(listOf("same", "same")), JSONArray(listOf("two\nlines")),
            JSONArray(listOf("Allow\u202eall")), JSONArray(listOf("x".repeat(257))), JSONArray(listOf(1)), JSONArray(List(5) { "$it" }))) {
            assertTrue(suggestedReplies(invalid).isEmpty())
        }
        val ask = Line(1, "agent", "Allow a fix?", "reply", 1, suggestedReplies = replies)
        assertEquals(replies, offeredReplies(ask, listOf(ask)))
        assertEquals(replies, offeredReplies(ask, listOf(ask, Line(2, "agent", "online", "status", 2))))
        assertTrue(offeredReplies(ask, listOf(ask, Line(3, "owner", "Keep paused", "message", 3))).isEmpty())
    }

    @Test fun runtime_notices_fold_only_when_consecutive() {
        val lines = listOf(Line(1, "agent", "online", "status", 1), Line(2, "agent", "restart", "status", 2),
            Line(3, "agent", "Needs you", "notify", 3), Line(4, "agent", "online", "status", 4))
        assertEquals(listOf(1L, 2L), statusRuns(lines)[2]?.map { it.seq })
        assertEquals(listOf(4L), statusRuns(lines)[4]?.map { it.seq })
    }

    @Test fun an_expired_question_keeps_its_native_terminal_state() {
        val expired = QuestionInfo("host", "q", "Allow a fix?", listOf("Fix it", "Pause"), false,
            "home", "lab/main", QuestionState.Expired, 1)
        val replied = expired.copy(state = QuestionState.Answered, revision = 2,
            answer = QuestionAnswer.Choices(listOf(0)), confirmed = true)
        assertEquals(expired, newestQuestion(expired, replied))
    }
    @Test fun failed_or_incomplete_refresh_keeps_cached_rows_until_a_valid_complete_snapshot() {
        val persisted = mutableListOf<List<OpenItem>>()
        val index = NeedsYou { _, rows -> persisted += rows }
        index.received("one", frame(1, item("kept")))
        assertFalse(index.received("one", frame(2).put("unavailable", true)))
        assertEquals("kept", index.items.values.single().id)
        assertTrue(index.staleHosts["one"] == true)
        assertEquals(1, persisted.size)
        index.received("one", frame(3, item("partial"), complete = false))
        assertFalse(index.received("one", frame(3, reset = false).put("items", "invalid envelope")))
        assertFalse(index.received("one", frame(3, reset = false)))
        assertEquals("kept", index.items.values.single().id)
        index.received("one", frame(4, item("fresh")))
        assertEquals("fresh", index.items.values.single().id)
        assertNull(index.staleHosts["one"])
        assertEquals(2, persisted.size)
    }

    @Test fun policy_rows_lead_with_the_host_resolved_target_and_never_duplicate_or_reopen() {
        val request = PolicyRequest("host", "p", "lab", "lab/main", "home", "write", "/input",
            false, "Allow the download?", 2, "pending", "", false, "r", true, null, "/resolved/private")
        val projected = OpenItem("host", "p", "policy_request", "lab/main", "home", request.action, 2, false)
        val shown = attentionItems(listOf(projected), listOf(request))
        assertEquals(1, shown.size)
        assertEquals("Write to /resolved/private", shown.single().summary)
        assertEquals("lab/main", shown.single().agent)
        assertTrue(attentionItems(listOf(projected), listOf(request.copy(state = "closed"))).isEmpty())
        assertEquals(shown, attentionItems(emptyList(), listOf(request)))
    }

    @Test fun an_empty_authoritative_snapshot_does_not_recreate_dismissed_policy_rows() {
        val request = PolicyRequest("host", "p", "lab", "lab/main", "home", "write", "/input",
            false, "Allow?", 2, "pending", "", false, "r", true, null)
        assertEquals(1, attentionItems(emptyList(), listOf(request)).size)
        assertTrue(attentionItems(emptyList(), listOf(request), setOf("host")).isEmpty())
        val index = NeedsYou()
        index.restore(emptyList(), setOf("host"))
        assertTrue("host" in index.indexedHosts)
        assertTrue(index.freshHosts.isEmpty())
        index.authenticated("host", supportsProjection = false)
        assertEquals(1, attentionItems(emptyList(), listOf(request), index.indexedHosts.keys).size)
    }

    @Test fun review_one_bad_or_future_item_does_not_discard_the_valid_snapshot() {
        val index = NeedsYou()
        assertTrue(index.received("one", frame(1, item("good"), item("invalid").put("since", "invalid"), item("future").put("kind", "future_kind"))))
        assertEquals(listOf("good"), index.items.values.map { it.id })
        assertTrue(index.staleHosts.isEmpty())
    }

    @Test fun stale_projection_keeps_new_policy_generations_without_reviving_covered_ones() {
        val p = PolicyRequest("one", "p", "lab", "main", "home", "read", "/tmp/a", false, "read", 10, "pending", "", false, "1", true, null)
        val policies = mutableListOf(p)
        val index = NeedsYou(policies = { policies })
        // An omitted policy was covered by this complete, authoritative snapshot.
        index.received("one", frame(1))
        val coverage = index.policyCoverage("one")
        index.received("one", frame(2).put("unavailable", true))
        fun shown() = attentionItems(index.items.values, policies, index.indexedHosts.keys, index.staleHosts.keys, index.coveredPolicies)
        assertTrue(shown().isEmpty())
        policies += p.copy(host = "old", id = "legacy", time = 11)
        policies[0] = p.copy(id = "new", time = 12)
        assertEquals(listOf("new", "legacy"), shown().map { it.id })
        policies[0] = p.copy(revision = "2", state = "failed", time = 13)
        assertEquals(listOf("p", "legacy"), shown().map { it.id })
        val restored = NeedsYou()
        restored.restore(emptyList(), setOf("one"), mapOf("one" to coverage))
        restored.received("one", frame(1).put("unavailable", true))
        assertTrue(attentionItems(emptyList(), listOf(p), restored.indexedHosts.keys, restored.staleHosts.keys, restored.coveredPolicies).isEmpty())
        index.authenticated("one", supportsProjection = false)
        assertFalse("one" in index.staleHosts)
        assertFalse("one" in index.indexedHosts)
    }

    @Test fun deleted_conversations_cannot_return_through_in_flight_chunks_and_forgetting_clears_host_state() {
        val index = NeedsYou()
        index.received("one", frame(1, item("first"), complete = false))
        index.forgetConversation("one", "home")
        index.received("one", frame(1, item("late"), reset = false))
        assertTrue(index.items.isEmpty())
        index.received("two", frame(1, item("kept")))
        index.forget("one")
        index.received("one", frame(1, item("repaired")))
        assertEquals(setOf("kept", "repaired"), index.items.values.map { it.id }.toSet())
        val restored = NeedsYou()
        restored.restore(index.items.values.toList(), deleted = setOf(ConversationKey("one", "home")))
        assertEquals(listOf("kept"), restored.items.values.map { it.id })
    }

    @Test fun shared_protocol_fixtures_match_discovery_replies_dismissal_and_held_detail_commands() {
        fun fixture(name: String) = JSONObject(javaClass.getResource("/protocol/$name.json")!!.readText())
        val index = NeedsYou()
        assertTrue(index.received("one", fixture("to_device_open_items")))
        assertEquals("q-expired", index.items.values.single().id)
        assertTrue(index.items.values.single().expired)
        val m = fixture("to_device_message_suggested_replies")
        assertEquals(listOf("Fix Inbox filters", "Keep paused"), suggestedReplies(m.get("suggested_replies")))
        val held = HeldTask("one", "lab/lead", "home", "app/t_same", "Review", "reviewer", "Decision", since = 1, resume = "", stateWire = "held", revision = 2, resumable = false)
        var command = JSONObject()
        val details = TaskDetails(send = { _, value -> command = value; true }, task = { null }, available = { false }, heldAvailable = { true }, later = { _, _ -> })
        details.showHeld(held); assertTrue(details.fetch())
        val expected = fixture("from_device_held_task_get")
        for (key in listOf("v", "type", "agent", "task")) assertEquals(expected.get(key), command.get(key))
        val request = fixture("from_device_open_item_dismiss")
        val item = OpenItem("one", request.getString("item"), request.getString("kind"), request.getString("agent"), "home", "Decision", 1, false, request.getString("version"))
        index.received("one", frame(4, item.json()))
        var requestId = ""
        val dismissals = ReminderDismissals(index, { true }, { true }, { _, id -> requestId = id; true }, { _, _ -> })
        assertTrue(dismissals.dismiss(item))
        assertTrue(dismissals.received("one", fixture("to_device_open_item_dismissed").put("of", requestId)))
        assertTrue(index.items.isEmpty())
    }

}
