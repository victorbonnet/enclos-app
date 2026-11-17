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
        assertEquals(2, index.count())
        assertEquals(1, index.count("one/lab/main"))
        assertTrue(index.items.values.single { it.host == "one" }.expired)
        index.received("one", frame(2, item("q3"), complete = false))
        assertEquals(setOf("q1", "q2"), index.items.values.map { it.id }.toSet())
        index.ended("one")
        assertEquals(2, index.count())
        index.received("one", frame(3))
        assertEquals(listOf("q2"), index.items.values.map { it.id })
        assertFalse(index.received("one", frame(1, item("old"))))
        assertEquals(3, persisted.size)
        index.authenticated("one")
        index.received("one", frame(0, item("after-restart")))
        assertEquals(2, index.count())
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
        assertFalse(index.received("one", frame(3, item("bad").put("since", "yesterday"), reset = false)))
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

}
