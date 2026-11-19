package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A runtime's task events as task lines (enclos ADR 0071): reading, the fallback to `notify`, the words and Open task. */
class TaskEventsTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/protocol/$name.json")!!.readText())

    private val triage = TaskEvent("t_a09759c7", "S2a", "lab/reviewer", TaskEventKind.Triage, TaskState.Queued)

    /** A `message` of [kind], with [taskEvent] as its `task_event` when given (raw JSON). */
    private fun message(kind: String = "task_event", taskEvent: String? = null, extra: String = "") = JSONObject(
        """{"v":1,"id":"m-8","type":"message","conversation":"home.lab.main","agent":"lab/main","from":"agent",""" +
            """"text":"🛑 Kanban t_a09759c7 routed to TRIAGE","kind":"$kind","time":1763164801,"seq":43""" +
            (taskEvent?.let { ""","task_event":$it""" } ?: "") + extra + "}",
    )

    @Test
    fun the_enclos_fixture_reads_as_a_task_line_that_keeps_its_text() {
        val line = lineIn(fixture("to_device_message_task_event"))
        assertEquals("task_event", line.kind)
        assertEquals(triage, line.taskEvent)
        assertTrue(line.text.startsWith("🛑 [enclos-org] @reviewer Kanban t_a09759c7 routed to TRIAGE"))
        assertEquals(43L, line.seq)
        assertEquals(1763164801L, line.time)
        assertTrue(line.taskLine)
        assertTrue(line.quiet)
    }

    @Test
    fun a_task_event_without_details_it_can_read_is_a_notify() {
        for (raw in listOf(
            null,
            "null",
            "\"t_a09759c7\"",
            """{"title":"S2a","event":"done"}""",
            """{"task":"","title":"S2a","event":"done"}""",
            """{"task":"t_‮a","title":"S2a","event":"done"}""",
            """{"task":"${"t".repeat(129)}","title":"S2a","event":"done"}""",
            """{"task":"t_1","event":"done"}""",
            """{"task":"t_1","title":" ​\n","event":"done"}""",
            """{"task":"t_1","title":"S2a"}""",
            """{"task":"t_1","title":"S2a","event":7}""",
        )) {
            val line = lineIn(message(taskEvent = raw))
            assertEquals(raw, "notify", line.kind)
            assertNull(raw, line.taskEvent)
            assertEquals("🛑 Kanban t_a09759c7 routed to TRIAGE", line.text)
        }
    }

    @Test
    fun a_task_event_on_another_kind_is_left_out() {
        for (kind in listOf("reply", "notify", "status")) {
            val line = lineIn(message(kind, """{"task":"t_1","title":"S2a","event":"done"}"""))
            assertEquals(kind, line.kind)
            assertNull(line.taskEvent)
        }
    }

    @Test
    fun an_event_or_state_a_later_version_adds_reads_as_a_change_and_no_state() {
        val e = TaskEvent.from(JSONObject("""{"task":"t_1","title":"S2a","event":"reopened","state":"archived"}"""))!!
        assertEquals(TaskEventKind.Changed, e.event)
        assertNull(e.state)
        assertNull(TaskEvent.from(JSONObject("""{"task":"t_1","title":"S2a","event":"done","state":null}"""))!!.state)
    }

    @Test
    fun its_title_is_cleaned_and_an_assignee_that_is_not_a_name_left_out() {
        val e = TaskEvent.from(JSONObject("""{"task":"t_1","title":"Fix\nthe‮ login​  page","event":"done","assignee":"reviewer"}"""))!!
        assertEquals("Fix the login page", e.title)
        assertNull(e.assignee)
        assertEquals(AgentTask.MAX_TITLE, TaskEvent.from(JSONObject().put("task", "t_1").put("title", "é".repeat(500)).put("event", "done"))!!.title.length)
        for (assignee in listOf("", "lab/", "a/b/c", "lab/re‮viewer")) {
            assertNull(assignee, TaskEvent.from(JSONObject().put("task", "t_1").put("title", "x").put("event", "done").put("assignee", assignee))!!.assignee)
        }
    }

    @Test
    fun every_event_has_its_wire_name_and_its_words() {
        val expected = listOf(
            "blocked" to "blocked", "gave_up" to "gave up", "crashed" to "crashed", "timed_out" to "timed out", "done" to "done",
            "review" to "ready for review", "changes_requested" to "changes requested", "triage" to "sent to triage", "changed" to "changed",
        )
        assertEquals(expected, TaskEventKind.entries.map { it.wire to it.label })
        for ((wire, _) in expected) assertEquals(wire, TaskEventKind.of(wire).wire)
    }

    @Test
    fun the_events_are_those_of_the_crate_core_ffi_pins() {
        // core-ffi's tests check this copy against the pinned crate's kinds.
        val copy = javaClass.getResource("/task-event-kinds.txt")!!.readText()
            .lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(copy, TaskEventKind.entries.map { it.wire })
    }

    @Test
    fun a_task_line_reads_as_its_task_its_assignee_and_what_happened() {
        // Hermes's triage is queued: the event says it already.
        assertEquals("t_a09759c7 S2a · Reviewer · sent to triage", taskEventText(triage, "Reviewer"))
        val done = triage.copy(event = TaskEventKind.Done, state = TaskState.Done)
        assertEquals("t_a09759c7 S2a · Reviewer · done", taskEventText(done, "Reviewer"))
        assertEquals("t_a09759c7 S2a · gave up", taskEventText(triage.copy(assignee = null, event = TaskEventKind.GaveUp, state = null), null))
        assertEquals("t_a09759c7 S2a", taskEventSubject(triage))
        assertEquals("Reviewer · sent to triage", taskEventWhat(triage, "Reviewer"))
        assertEquals("Engineer · changed → running", taskEventWhat(triage.copy(event = TaskEventKind.Changed, state = TaskState.Running), "Engineer"))
        // In the inbox and search: its assignee by handle, never the runtime's ping.
        assertEquals("t_a09759c7 S2a · reviewer · sent to triage", words(lineIn(fixture("to_device_message_task_event"))))
    }

    @Test
    fun the_state_follows_only_when_the_event_does_not_already_say_it() {
        fun what(kind: TaskEventKind, state: TaskState?) = taskEventWhat(triage.copy(event = kind, state = state), null)
        // A review reads as `blocked` in Hermes: never "ready for review → blocked".
        for (state in TaskState.entries) assertEquals("$state", "ready for review", what(TaskEventKind.Review, state))
        assertEquals("sent to triage", what(TaskEventKind.Triage, TaskState.Queued))
        assertEquals("sent to triage → blocked", what(TaskEventKind.Triage, TaskState.Blocked))
        assertEquals("blocked", what(TaskEventKind.Blocked, TaskState.Blocked))
        assertEquals("done", what(TaskEventKind.Done, TaskState.Done))
        assertEquals("changes requested → queued", what(TaskEventKind.ChangesRequested, TaskState.Queued))
        assertEquals("gave up → blocked", what(TaskEventKind.GaveUp, TaskState.Blocked))
        assertEquals("crashed → queued", what(TaskEventKind.Crashed, TaskState.Queued))
        assertEquals("changed → running", what(TaskEventKind.Changed, TaskState.Running))
        assertEquals("timed out", what(TaskEventKind.TimedOut, null))
    }

    @Test
    fun a_conversation_draws_a_task_line_as_one_and_its_text_fallback_as_a_bubble() {
        assertEquals(Drawn.TaskLine, drawnAs(lineIn(fixture("to_device_message_task_event"))))
        // Details it cannot read: the runtime's text, as a `notify` bubble.
        val fallback = lineIn(message(taskEvent = """{"title":"S2a","event":"done"}"""))
        assertEquals("notify", fallback.kind)
        assertEquals(Drawn.Bubble, drawnAs(fallback))
        assertEquals(Drawn.Bubble, drawnAs(lineIn(message("notify"))))
        // A status line is its own quiet line, unless it carries files.
        val status = lineIn(message("status"))
        assertEquals(Drawn.Status, drawnAs(status))
        assertEquals(Drawn.Bubble, drawnAs(status.copy(attachments = listOf(Attachment("f-1", "text/plain", "notes.txt", 3)))))
        // A task line with files is still a task line: they show under it.
        val withFiles = lineIn(fixture("to_device_message_task_event")).copy(attachments = listOf(Attachment("f-1", "text/plain", "notes.txt", 3)))
        assertEquals(Drawn.TaskLine, drawnAs(withFiles))
    }

    @Test
    fun the_inbox_previews_and_last_active_leave_a_task_line_out() {
        val said = Line(41, "agent", "On it.", "reply", 1_763_164_700)
        val status = Line(42, "agent", "Gateway online", "status", 1_763_164_750)
        val task = lineIn(fixture("to_device_message_task_event"))
        assertEquals(said, previewOf(listOf(said, status, task)))
        assertEquals(1_763_164_700L, lastSpoke(listOf(said, status, task)))
        // With nothing else, the preview is the task line, and the agent has not spoken.
        assertEquals(task, previewOf(listOf(task)))
        assertNull(lastSpoke(listOf(task)))
        assertNull(previewOf(emptyList()))
        // Its text fallback is the agent's message, as before task lines.
        val fallback = lineIn(message(taskEvent = null))
        assertEquals(fallback, previewOf(listOf(said, fallback)))
        assertEquals(fallback.time, lastSpoke(listOf(said, fallback)))
        // The person's message previews, but is not the agent speaking.
        val mine = Line(44, "person", "Thanks", "reply", 1_763_164_900)
        assertEquals(mine, previewOf(listOf(said, task, mine)))
        assertEquals(1_763_164_700L, lastSpoke(listOf(said, task, mine)))
    }

    @Test
    fun a_task_line_offers_no_suggested_reply() {
        val line = lineIn(message(taskEvent = """{"task":"t_1","title":"S2a","event":"done"}""", extra = ""","suggested_replies":["OK"]"""))
        assertEquals(emptyList<String>(), line.suggestedReplies)
        assertEquals(listOf("OK"), lineIn(message("notify", extra = ""","suggested_replies":["OK"]""")).suggestedReplies)
    }

    @Test
    fun a_task_line_is_never_unread_and_its_text_fallback_is() {
        val task = lineIn(message(taskEvent = """{"task":"t_1","title":"S2a","event":"done"}"""))
        val fallback = lineIn(message(taskEvent = null))
        assertEquals(0, unreadAmong(listOf(task), read = 0))
        assertEquals(1, unreadAmong(listOf(task, fallback.copy(seq = 44)), read = 0))
    }

    @Test
    fun what_the_phone_keeps_reads_back_the_same() {
        assertEquals(triage, TaskEvent.from(triage.json()))
        val bare = triage.copy(assignee = null, state = null)
        assertEquals(bare, TaskEvent.from(bare.json()))
        // The wire's fields, which the protocol's own decoder reads too.
        val json = triage.json()
        assertEquals(listOf("assignee", "event", "state", "task", "title"), json.keys().asSequence().sorted().toList())
        assertEquals("task_event" to triage, taskEventOf("task_event", triage.json()))
        assertEquals("notify" to null, taskEventOf("task_event", null))
    }

    @Test
    fun open_task_needs_the_task_this_phone_keeps_for_that_assignee() {
        val kept = AgentTask("mac", "t_a09759c7", "S2a", "lab/reviewer", "lab/main", "home.lab.main", TaskState.Queued, 1000, null)
        val tasks = mapOf(kept.key to kept)
        assertEquals(TaskKey("mac", "lab", "t_a09759c7"), taskOpenedBy("mac", triage) { tasks[it] })
        // Another host, another agent of the sandbox, no assignee, a task not kept: none.
        assertNull(taskOpenedBy("pi", triage) { tasks[it] })
        assertNull(taskOpenedBy("mac", triage.copy(assignee = "lab/engineer")) { tasks[it] })
        assertNull(taskOpenedBy("mac", triage.copy(assignee = null)) { tasks[it] })
        assertNull(taskOpenedBy("mac", triage.copy(task = "t_other")) { tasks[it] })
        assertNull(taskOpenedBy("mac", triage) { null })
    }

    @Test
    fun the_device_announces_task_lines_in_its_auth() {
        assertTrue("task_events" in authFields("device-1", "c2ln").toMap()["capabilities"] as List<*>)
    }
}
