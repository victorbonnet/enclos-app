package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDetailsTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/protocol/$name")!!.readText())

    private fun task(id: String = "t_c03489b7", state: String = "running", comments: Int? = null, host: String = "mac") = AgentTask.from(
        host,
        JSONObject(
            """{"v":1,"type":"task","id":"d1","task":{"id":"$id","title":"Fix the review threads","assignee":"lab/engineer","state":"$state","since":1000""" +
                (comments?.let { ""","comments":$it""" } ?: "") + "}}",
        ),
    )

    /** A client over [tasks], with what it sent, whether the host is [up], and the waits it set. */
    private class Harness(vararg tasks: AgentTask) {
        val kept = tasks.associateBy { it.key }.toMutableMap()
        val sent = mutableListOf<Pair<String, JSONObject>>()
        var up = true
        var delivers = true
        val waits = mutableListOf<() -> Unit>()
        val client = TaskDetails(
            send = { host, command -> sent += host to command; delivers },
            task = { kept[it] },
            available = { up },
            later = { _, action -> waits += action },
        )

        fun answer(type: String = "task.detail", of: String = sent.last().second.getString("id"), extra: String = ""","agent":"lab/engineer","task":"t_c03489b7","detail":{"body":"Do it"}""") =
            client.received("mac", JSONObject("""{"v":1,"id":"b1","type":"$type","of":"$of"$extra}"""))
    }

    @Test
    fun the_request_is_the_protocol_fixture() {
        val h = Harness(task())
        h.client.show(task().key)
        assertTrue(h.client.fetch())
        val (host, command) = h.sent.single()
        assertEquals("mac", host)
        val fixture = fixture("from_device_task_get.json")
        for (field in listOf("v", "type", "agent", "task")) assertEquals(field, fixture.get(field), command.get(field))
        assertTrue(command.getString("id").isNotEmpty())
        assertTrue(h.client.loading)
    }

    @Test
    fun the_detail_fixture_reads_whole() {
        val m = fixture("to_device_task_detail.json")
        val d = TaskDetail.from(m.getJSONObject("detail"))
        assertEquals("Fix the five review threads on the backend.\n\nDo not resolve a thread before the fix is verified.", d.body)
        assertEquals(2, d.related.size)
        assertEquals(RelatedTask("t_451499a3", "Shared contract for held tasks", Relation.Parent, TaskState.Done, "lab/pm"), d.related[0])
        assertEquals(RelatedTask("t_0e599023", "Review delivery: verify fixes and reply to the threads", Relation.Child, TaskState.Queued, null), d.related[1])
        assertEquals(listOf("lead", "default"), d.comments.map { it.author })
        assertEquals("lab/lead", d.comments[0].agent)
        assertNull(d.comments[1].agent)
        assertEquals(1763164760L, d.comments[1].time)
        assertEquals(5, d.commentsTotal)
        assertEquals(3, d.earlier)
        assertEquals(
            listOf(HistoryKind.Created, HistoryKind.Blocked, HistoryKind.Unblocked, HistoryKind.Attempt, HistoryKind.Attempt),
            d.history.map { it.kind },
        )
        assertEquals("lab/lead", d.history[0].by)
        assertEquals("Waits for t_451499a3", d.history[1].text)
        val crashed = d.history[3]
        assertEquals(1763164650L, crashed.until)
        assertEquals(Outcome.Crashed, crashed.outcome)
        assertEquals("worker exited 1", crashed.text)
        assertNull(d.history[4].until)
    }

    @Test
    fun the_fixture_answers_the_sheet_waiting_for_it() {
        val h = Harness(task())
        h.client.show(task().key)
        h.client.fetch()
        val m = fixture("to_device_task_detail.json")
        m.put("of", h.sent.last().second.getString("id"))
        assertTrue(h.client.received("mac", m))
        assertFalse(h.client.loading)
        assertEquals(2, h.client.detail?.comments?.size)
        assertNull(h.client.error)
    }

    @Test
    fun a_detail_reads_leniently() {
        assertEquals(TaskDetail(), TaskDetail.from(null))
        assertEquals(TaskDetail(), TaskDetail.from(JSONObject("{}")))
        val d = TaskDetail.from(
            JSONObject(
                """{
                "body":" ​\n",
                "related":[{"id":"t_1","title":"No relation"},{"id":"t_2","title":"Archived","relation":"child","state":"archived"},{"id":"","title":"No id","relation":"parent"},"not an object"],
                "comments":[{"author":"lead","text":"no time"},{"author":"","time":1,"text":"no author"},{"author":"lead","time":2,"text":"Kept","agent":"not-an-agent"}],
                "comments_total":"many",
                "history":[{"kind":"teleported","time":1},{"kind":"attempt","time":5,"until":3,"outcome":"vanished"},{"kind":"done"},{"kind":"review","time":9,"by":"lab/reviewer","text":"​"}]
                }""",
            ),
        )
        assertNull(d.body)
        assertEquals(listOf(RelatedTask("t_2", "Archived", Relation.Child, null, null)), d.related)
        assertEquals(listOf(TaskComment("lead", null, 2, "Kept")), d.comments)
        // At least the comments it carries.
        assertEquals(1, d.commentsTotal)
        assertEquals(0, d.earlier)
        assertEquals(2, d.history.size)
        val attempt = d.history[0]
        // An outcome a later version names reads as ended; an end before the start is the start.
        assertEquals(Outcome.Ended, attempt.outcome)
        assertEquals(5L, attempt.until)
        assertNull(d.history[1].text)
        assertEquals("lab/reviewer", d.history[1].by)
    }

    @Test
    fun its_text_is_cleaned_and_cut_with_lines_kept() {
        val d = TaskDetail.from(
            JSONObject()
                .put("body", "One\r\ntwo‮ \n\n\n\tthree  \n")
                .put("comments", org.json.JSONArray().put(JSONObject().put("author", "a\nb").put("time", 1).put("text", "é".repeat(5_000))))
                .put("history", org.json.JSONArray().put(JSONObject().put("kind", "blocked").put("time", 1).put("text", "why\nnot"))),
        )
        assertEquals("One\ntwo\n\n three", d.body)
        assertEquals("a b", d.comments[0].author)
        assertEquals(MAX_TASK_COMMENT_CHARS, d.comments[0].text.length)
        assertEquals("why not", d.history[0].text)
        assertEquals("", paragraphs("\n ​\n", 100))
    }

    @Test
    fun it_keeps_the_newest_comments_and_history_within_bounds() {
        val comments = org.json.JSONArray()
        (0 until 25).forEach { comments.put(JSONObject().put("author", "lead").put("time", it).put("text", "c$it")) }
        val history = org.json.JSONArray()
        (0 until 40).forEach { history.put(JSONObject().put("kind", "unblocked").put("time", it)) }
        val d = TaskDetail.from(JSONObject().put("comments", comments).put("history", history).put("comments_total", 30))
        assertEquals(MAX_TASK_COMMENTS, d.comments.size)
        assertEquals("c5", d.comments.first().text)
        assertEquals(MAX_TASK_HISTORY, d.history.size)
        assertEquals(10L, d.history.first().time)
        assertEquals(10, d.earlier)
    }

    @Test
    fun a_task_carries_its_comment_count() {
        assertEquals(4, task(comments = 4).comments)
        assertNull(task().comments)
        assertNull(AgentTask.from("mac", JSONObject("""{"type":"task","task":{"id":"t1","title":"T","assignee":"lab/a","state":"queued","since":1,"comments":-1}}""")).comments)
        assertNull(AgentTask.from("mac", JSONObject("""{"type":"task","task":{"id":"t1","title":"T","assignee":"lab/a","state":"queued","since":1,"comments":"3"}}""")).comments)
        val counted = task(comments = 4)
        assertEquals(counted, AgentTask.kept(counted.json().toString()))
        assertEquals(task(), AgentTask.kept(task().json().toString()))
    }

    @Test
    fun a_sheet_asks_again_when_the_state_or_the_comments_change() {
        assertTrue(asksAgain(null, task()))
        assertFalse(asksAgain(task(comments = 2), task(comments = 2)))
        assertTrue(asksAgain(task(comments = 2), task(comments = 3)))
        assertTrue(asksAgain(task(), task(comments = 1)))
        assertTrue(asksAgain(task(state = "running"), task(state = "done")))
    }

    @Test
    fun an_update_of_the_open_task_fetches_again() {
        val h = Harness(task(comments = 1))
        h.client.show(task().key)
        h.client.fetch()
        h.answer()
        // The same task again, as a sync sends it: nothing asked.
        h.client.updated(task(comments = 1), task(comments = 1))
        assertEquals(1, h.sent.size)
        // Another task: nothing asked.
        h.client.updated(task(id = "other"), task(id = "other", comments = 3))
        assertEquals(1, h.sent.size)
        h.client.updated(task(comments = 1), task(comments = 2))
        assertEquals(2, h.sent.size)
        // A change while that request waits: asked once it is answered.
        h.client.updated(task(comments = 2), task(state = "done", comments = 2))
        assertEquals(2, h.sent.size)
        h.answer()
        assertEquals(3, h.sent.size)
        assertTrue(h.client.loading)
    }

    @Test
    fun an_error_shows_its_message_and_retry_asks_again() {
        val h = Harness(task())
        h.client.show(task().key)
        h.client.fetch()
        assertTrue(h.answer(type = "error", extra = ""","code":"agent_unavailable","message":"The agent's channel is not connected.‮""""))
        assertEquals("The agent's channel is not connected.", h.client.error)
        assertFalse(h.client.loading)
        assertTrue(h.client.fetch())
        assertNull(h.client.error)
        h.answer(type = "error", extra = ""","code":"internal"""")
        assertEquals("The host could not get this task's details.", h.client.error)
    }

    @Test
    fun an_error_for_another_request_is_left_to_others() {
        val h = Harness(task())
        h.client.show(task().key)
        h.client.fetch()
        assertFalse(h.answer(type = "error", of = "someone-else", extra = ""","code":"internal""""))
        assertFalse(h.client.received("mac", JSONObject("""{"type":"agent.name","of":"x"}""")))
        // A detail nobody waits for is dropped.
        assertTrue(h.answer(of = "late"))
        assertNull(h.client.detail)
        // From another host, under the same id: not this sheet's.
        assertFalse(h.client.received("pi", JSONObject("""{"type":"error","of":"${h.sent.last().second.getString("id")}","code":"internal"}""")))
        assertTrue(h.client.loading)
    }

    @Test
    fun no_answer_or_no_send_says_so_and_nothing_is_resent() {
        val h = Harness(task())
        h.client.show(task().key)
        h.client.fetch()
        h.waits.single()()
        assertEquals("The host did not reply. Try again.", h.client.error)
        assertFalse(h.client.loading)
        assertEquals(1, h.sent.size)
        // A late answer to it still shows.
        h.answer()
        assertEquals("Do it", h.client.detail?.body)
        h.delivers = false
        assertFalse(h.client.fetch())
        assertEquals("The request was not sent. Reconnect and try again.", h.client.error)
    }

    @Test
    fun nothing_is_asked_while_the_detail_is_unavailable() {
        val h = Harness(task())
        h.up = false
        assertFalse(h.client.fetch())
        h.client.show(task().key)
        assertFalse(h.client.fetch())
        h.up = true
        h.client.show(TaskKey("mac", "lab", "missing"))
        assertFalse(h.client.fetch())
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun a_connection_that_ends_drops_the_detail_and_forgetting_closes_the_sheet() {
        val h = Harness(task())
        h.client.show(task().key)
        h.client.fetch()
        h.answer()
        h.client.fetch()
        h.client.ended("pi")
        assertTrue(h.client.loading)
        h.client.ended("mac")
        assertFalse(h.client.loading)
        assertNull(h.client.detail)
        assertEquals(task().key, h.client.open)
        h.client.forget("mac")
        assertNull(h.client.open)
    }

    @Test
    fun another_task_opened_starts_empty() {
        val other = task(id = "t_2")
        val h = Harness(task(), other)
        h.client.show(task().key)
        h.client.fetch()
        h.answer()
        h.client.show(other.key)
        assertNull(h.client.detail)
        assertFalse(h.client.loading)
        // The first task's answer no longer shows here.
        assertTrue(h.answer(of = h.sent.first().second.getString("id")))
        assertNull(h.client.detail)
        h.client.close()
        assertNull(h.client.open)
    }

    @Test
    fun history_reads_in_the_persons_words() {
        val name = { agent: String -> agent.substringAfter('/').replaceFirstChar(Char::uppercase) }
        fun entry(kind: HistoryKind, until: Long? = null, outcome: Outcome? = null, by: String? = null) = HistoryEntry(kind, 1_000, until, outcome, by, null)
        assertEquals("Created by Lead", historyLine(entry(HistoryKind.Created, by = "lab/lead"), name, 2_000))
        assertEquals("Attempt by Engineer · crashed after <1 min", historyLine(entry(HistoryKind.Attempt, 1_050, Outcome.Crashed, "lab/engineer"), name, 2_000))
        assertEquals("Attempt · timed out after 5 min", historyLine(entry(HistoryKind.Attempt, 1_300, Outcome.TimedOut), name, 2_000))
        assertEquals("Attempt · ended after 1 min", historyLine(entry(HistoryKind.Attempt, 1_060), name, 2_000))
        assertEquals("Attempt · going for 16 min", historyLine(entry(HistoryKind.Attempt), name, 2_000))
        assertEquals("Blocked", historyLine(entry(HistoryKind.Blocked), name, 2_000))
        assertEquals("Review asked for", historyLine(entry(HistoryKind.Review), name, 2_000))
        assertEquals("Done", historyLine(entry(HistoryKind.Done), name, 2_000))
        assertEquals(Outcome.GaveUp, Outcome.of("gave_up"))
        assertEquals("ended", Outcome.of("vanished").label)
    }
}
