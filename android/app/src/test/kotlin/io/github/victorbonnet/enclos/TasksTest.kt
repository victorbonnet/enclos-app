package io.github.victorbonnet.enclos

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksTest {
    private fun message(
        id: String = "t1",
        title: String = "Fix the login page",
        assignee: String = "lab/engineer",
        state: String = "running",
        since: Long = 1_000,
        extra: String = "",
    ) = JSONObject(
        """{"v":1,"type":"task","id":"d1","task":{"id":"$id","title":${JSONObject.quote(title)},"assignee":"$assignee","state":"$state","since":$since$extra}}""",
    )

    private fun task(host: String = "mac", id: String = "t1", state: String = "running", since: Long = 1_000, extra: String = "", assignee: String = "lab/engineer") =
        AgentTask.from(host, message(id = id, state = state, since = since, extra = extra, assignee = assignee))

    @Test
    fun a_task_reads_with_its_optional_fields() {
        val t = task(extra = ""","asked_by":"lab/lead","conversation":"c1","summary":"done it"""")
        assertEquals(TaskKey("mac", "lab", "t1"), t.key)
        assertEquals("mac/lab/engineer", t.assigneeAddress)
        assertEquals("lab/lead", t.askedBy)
        assertEquals("c1", t.conversation)
        assertEquals(TaskState.Running, t.state)
        assertEquals(1_000L, t.placed)
        assertEquals(t, AgentTask.kept(t.json().toString()))
        val bare = task()
        assertNull(bare.askedBy)
        assertNull(bare.conversation)
        assertNull(bare.summary)
        assertEquals(bare, AgentTask.kept(bare.json().toString()))
    }

    @Test
    fun a_task_the_app_cannot_read_is_refused() {
        assertThrows(JSONException::class.java) { AgentTask.from("mac", message(state = "archived")) }
        assertThrows(JSONException::class.java) { AgentTask.from("mac", message(assignee = "engineer")) }
        assertThrows(JSONException::class.java) { AgentTask.from("mac", message(id = "")) }
        assertThrows(JSONException::class.java) { AgentTask.from("mac", message(title = " ​\n")) }
        assertNull(AgentTask.kept("not json"))
    }

    @Test
    fun its_text_is_one_line_without_hidden_characters_and_cut() {
        val t = AgentTask.from("mac", message(title = "Fix\nthe‮ login​  page"))
        assertEquals("Fix the login page", t.title)
        val long = AgentTask.from("mac", message(title = "é".repeat(500)))
        assertEquals(AgentTask.MAX_TITLE, long.title.length)
        val summary = task(state = "done", extra = ""","summary":"${"x".repeat(2000)}"""").summary
        assertEquals(AgentTask.MAX_SUMMARY, summary?.length)
        assertNull(task(extra = ""","summary":"​"""").summary)
        // A character outside the BMP is never cut in half.
        assertEquals("😀".repeat(3), oneLine("😀".repeat(5), 3))
    }

    @Test
    fun a_task_changed_keeps_the_place_it_first_had() {
        val cache = TaskCache()
        cache.received(task(state = "queued", since = 100))
        val kept = cache.received(task(state = "done", since = 900))
        assertEquals(TaskState.Done, kept.state)
        assertEquals(100L, kept.placed)
        assertEquals(1, cache.all.size)
    }

    @Test
    fun tasks_are_keyed_by_host_and_sandbox_and_id() {
        val cache = TaskCache()
        cache.received(task("mac"))
        cache.received(task("pi"))
        cache.received(task("mac", assignee = "work/engineer"))
        assertEquals(3, cache.all.size)
        assertEquals(TaskKey("mac", "lab", "t1"), cache.removed("mac", "lab/engineer", "t1"))
        assertNull(cache.removed("mac", "lab/engineer", "t1"))
        assertEquals(2, cache.all.size)
    }

    @Test
    fun a_sync_drops_the_hosts_tasks_it_did_not_send() {
        val held = mutableMapOf<TaskKey, AgentTask>()
        val cache = TaskCache(held)
        cache.load(listOf(task("mac", "old"), task("mac", "kept"), task("pi", "other")))
        cache.syncing("mac")
        cache.received(task("mac", "kept"))
        cache.received(task("mac", "new"))
        assertEquals(listOf(TaskKey("mac", "lab", "old")), cache.synced("mac"))
        assertEquals(setOf("kept", "new", "other"), held.values.map { it.id }.toSet())
        // A second `synced` with no sync followed (asked again over the connection) drops nothing.
        assertEquals(emptyList<TaskKey>(), cache.synced("mac"))
    }

    @Test
    fun a_sync_cut_by_the_connection_drops_nothing() {
        val cache = TaskCache()
        cache.load(listOf(task("mac", "old")))
        cache.syncing("mac")
        cache.ended("mac")
        assertEquals(emptyList<TaskKey>(), cache.synced("mac"))
        assertEquals(1, cache.all.size)
    }

    @Test
    fun forgetting_a_host_removes_its_tasks_only() {
        val cache = TaskCache()
        cache.received(task("mac", "a"))
        cache.received(task("pi", "b"))
        assertEquals(listOf(TaskKey("mac", "lab", "a")), cache.forget("mac"))
        assertEquals(listOf("b"), cache.all.map { it.id })
        cache.clear()
        assertTrue(cache.all.isEmpty())
    }

    @Test
    fun an_agents_tasks_list_those_still_going_first_then_the_ended_newest_first() {
        val cache = TaskCache()
        cache.received(task(id = "done-old", state = "done", since = 100))
        cache.received(task(id = "running", state = "running", since = 200))
        cache.received(task(id = "failed-new", state = "failed", since = 900))
        cache.received(task(id = "queued", state = "queued", since = 500))
        cache.received(task(id = "blocked", state = "blocked", since = 300))
        cache.received(task(id = "theirs", state = "running", since = 999, assignee = "lab/lead"))
        assertEquals(listOf("queued", "blocked", "running", "failed-new", "done-old"), cache.of("mac/lab/engineer").map { it.id })
    }

    @Test
    fun a_conversations_cards_are_its_hosts_in_the_order_they_were_placed() {
        val cache = TaskCache()
        cache.received(task("mac", "b", since = 300, extra = ""","conversation":"c1""""))
        cache.received(task("mac", "a", since = 200, extra = ""","conversation":"c1""""))
        cache.received(task("pi", "c", since = 100, extra = ""","conversation":"c1""""))
        cache.received(task("mac", "d", since = 100, extra = ""","conversation":"c2""""))
        assertEquals(listOf("a", "b"), cache.askedIn("mac", "c1").map { it.id })
    }

    @Test
    fun a_card_follows_the_last_message_at_or_before_its_place() {
        val early = task(id = "early", since = 5)
        val middle = task(id = "middle", since = 20)
        val late = task(id = "late", since = 99)
        val placed = placedAfter(listOf(10, 20, 30), listOf(early, middle, late))
        assertEquals(listOf(early), placed[-1])
        assertEquals(listOf(middle), placed[1])
        assertEquals(listOf(late), placed[2])
        assertEquals(mapOf(-1 to listOf(late)), placedAfter(emptyList(), listOf(late)))
    }

    @Test
    fun a_duration_is_in_its_largest_units() {
        assertEquals("<1 min", taskDuration(59))
        assertEquals("<1 min", taskDuration(-5))
        assertEquals("12 min", taskDuration(12 * 60 + 30))
        assertEquals("3 h", taskDuration(3 * 3600))
        assertEquals("3 h 5 min", taskDuration(3 * 3600 + 5 * 60))
        assertEquals("2 d", taskDuration(2 * 86_400 + 7200))
    }

    @Test
    fun a_card_says_how_long_a_task_has_gone_and_how_it_ended() {
        val running = task(since = 1_000)
        assertEquals("Engineer · Fix the login page · running 12 min", taskLine("Engineer", running, 1_000 + 12 * 60))
        val queued = task(state = "queued", since = 1_000)
        assertEquals("queued <1 min", taskStatus(queued, 1_010))
        val done = task(state = "done", since = 1_000, extra = ""","summary":"Fixed in a1b2c3"""")
        assertEquals("Engineer · Fix the login page · done", taskLine("Engineer", done, 99_999))
        assertEquals("Fixed in a1b2c3", done.summary)
        assertEquals("failed", taskStatus(task(state = "failed"), 99_999))
        assertEquals("cancelled", taskStatus(task(state = "cancelled"), 99_999))
    }
}
