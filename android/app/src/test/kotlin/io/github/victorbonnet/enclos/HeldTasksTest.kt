package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeldTasksTest {
    private fun wire(state: String = "held", revision: Long = 3) = JSONObject(
        """{"agent":"lab/main","conversation":"home.lab.main","task":"t_1","title":"Write the report",
           "assignee":"writer","reason":"Gave up after 3 failures","last_error":"rate limited",
           "since":1763000000,"resume":"Runs it again with a fresh retry budget.","state":"$state","revision":$revision}""",
    )

    private val home = Conversation("mac", "lab/main", "home.lab.main", home = true)

    @Test fun a_card_reads_every_field_of_the_bridge() {
        val card = HeldTask.from("mac", wire())
        assertEquals(HeldTaskKey("mac", "lab/main", "t_1"), card.key)
        assertEquals("home.lab.main", card.conversation)
        assertEquals("Write the report", card.title)
        assertEquals("writer", card.assignee)
        assertEquals("Gave up after 3 failures", card.reason)
        assertEquals("rate limited", card.lastError)
        assertEquals(1_763_000_000L, card.since)
        assertEquals("Runs it again with a fresh retry budget.", card.resume)
        assertEquals(HeldTaskState.Held, card.state)
        assertEquals(3L, card.revision)
        // The cache's form reads back the same.
        assertEquals(card, HeldTask.from("mac", card.json()))
    }

    @Test fun optional_fields_may_be_absent_or_null() {
        val json = wire()
        json.remove("assignee")
        json.put("last_error", JSONObject.NULL)
        val card = HeldTask.from("mac", json)
        assertNull(card.assignee)
        assertNull(card.lastError)
        assertFalse(card.json().has("assignee"))
        assertFalse(card.json().has("last_error"))
    }

    @Test fun a_state_this_app_does_not_know_is_kept_and_offers_no_resume() {
        val card = HeldTask.from("mac", wire(state = "paused"))
        assertEquals(HeldTaskState.Unknown, card.state)
        assertEquals("paused", card.stateWire)
        // Kept as it came, so the cache does not turn it into another state.
        assertEquals("paused", card.json().getString("state"))
        assertTrue(heldTaskStatus(card).contains("paused"))
        for ((wire, state) in listOf("held" to HeldTaskState.Held, "resumed" to HeldTaskState.Resumed,
            "released" to HeldTaskState.Released, "expired" to HeldTaskState.Expired)) {
            assertEquals(state, HeldTask.from("mac", wire(state = wire)).state)
        }
    }

    @Test fun a_card_missing_a_field_or_with_a_wrong_one_is_refused() {
        for (field in listOf("agent", "conversation", "task", "title", "reason", "since", "resume", "state", "revision")) {
            val json = wire().apply { remove(field) }
            assertThrows("without $field", IllegalArgumentException::class.java) { HeldTask.from("mac", json) }
        }
        val wrong = listOf(
            "revision" to -1, "revision" to "3", "revision" to 1.5, "since" to "yesterday",
            "agent" to "main", "agent" to "lab/main/extra", "agent" to "lab/", "task" to "", "task" to "t‮1",
            "task" to "x".repeat(129), "conversation" to "home\n", "state" to "", "assignee" to 7, "last_error" to false,
            "title" to 1,
        )
        for ((field, value) in wrong) {
            val json = wire().put(field, value)
            assertThrows("$field = $value", IllegalArgumentException::class.java) { HeldTask.from("mac", json) }
        }
    }

    @Test fun texts_are_read_as_the_bridge_cuts_them() {
        val json = wire()
            .put("title", "  Fix‮ the\nbuild " + "t".repeat(300))
            .put("reason", "   ")
            .put("last_error", "line 1\nline 2​" + "e".repeat(3_000))
            .put("assignee", "​")
            .put("resume", "Runs\nit " + "r".repeat(300))
        val card = HeldTask.from("mac", json)
        assertFalse(Visible.hasHidden(card.title))
        assertEquals(MAX_HELD_TITLE_CHARS, card.title.length)
        assertTrue(card.title.startsWith("Fix thebuild "))
        assertEquals(HELD_NO_REASON, card.reason)
        assertTrue(card.lastError!!.startsWith("line 1\nline 2e"))
        assertEquals(MAX_HELD_ERROR_CHARS, card.lastError!!.length)
        assertNull(card.assignee)
        assertEquals(MAX_HELD_RESUME_CHARS, card.resume.length)
        assertFalse('\n' in card.resume)
        // A blank title reads as the task's id.
        assertEquals("t_1", HeldTask.from("mac", wire().put("title", "​ ")).title)
    }

    @Test fun the_highest_revision_wins_and_a_tie_keeps_the_current_card() {
        val held = HeldTask.from("mac", wire(revision = 3))
        val resumed = HeldTask.from("mac", wire(state = "resumed", revision = 4))
        assertSame(held, newestHeldTask(null, held))
        assertSame(resumed, newestHeldTask(held, resumed))
        assertSame(resumed, newestHeldTask(resumed, held))
        assertSame(resumed, newestHeldTask(resumed, held.copy(revision = 4)))
        // Another card is no older version of this one.
        val other = held.copy(task = "t_2", revision = 0)
        assertSame(other, newestHeldTask(held, other))
    }

    @Test fun a_card_belongs_to_its_agents_home_alone() {
        val card = HeldTask.from("mac", wire())
        assertTrue(card.belongsTo(home))
        assertFalse(card.belongsTo(home.copy(home = false)))
        assertFalse(card.belongsTo(home.copy(host = "pi")))
        assertFalse(card.belongsTo(home.copy(agent = "lab/other")))
        assertFalse(card.belongsTo(home.copy(id = "c-2")))
        assertFalse(card.belongsTo(home.copy(members = listOf("lab/main", "lab/other"))))
    }

    @Test fun the_cache_keeps_valid_cards_of_kept_homes_under_their_key() {
        val card = HeldTask.from("mac", wire())
        val rows = listOf(
            HeldTaskRow("mac", "lab/main", "t_1", card.json().toString()),
            HeldTaskRow("mac", "lab/main", "t_2", card.json().toString()),
            HeldTaskRow("mac", "lab/main", "t_3", "{not json"),
            HeldTaskRow("pi", "lab/main", "t_1", card.json().toString()),
        )
        val (kept, stale) = keptHeldTasks(rows, listOf(home))
        assertEquals(listOf(card), kept)
        assertEquals(
            listOf(HeldTaskKey("mac", "lab/main", "t_2"), HeldTaskKey("mac", "lab/main", "t_3"), HeldTaskKey("pi", "lab/main", "t_1")),
            stale,
        )
    }

    @Test fun a_message_links_a_card_by_its_task_id() {
        val m = JSONObject("""{"seq":7,"from":"agent","text":"Held: Write the report","kind":"notify","time":1,"held_task":"t_1"}""")
        assertEquals("t_1", lineIn(m).heldTask)
        assertNull(lineIn(m.put("held_task", "t‮1")).heldTask)
        assertNull(lineIn(m.put("held_task", 4)).heldTask)
        m.remove("held_task")
        assertNull(lineIn(m).heldTask)
    }

    @Test fun the_card_is_drawn_at_the_newest_introduction_of_its_task() {
        val lines = listOf(
            Line(1, "agent", "Held: report", "notify", 1, heldTask = "t_1"),
            Line(2, "agent", "Resumed from the app: report", "status", 2, heldTask = "t_1"),
            Line(3, "agent", "Held: report", "notify", 3, heldTask = "t_1"),
            Line(4, "agent", "Held: deploy", "notify", 4, heldTask = "t_2"),
            Line(5, "person", "what is t_3?", "message", 5, heldTask = "t_3"),
            Line(6, "agent", "a reply", "reply", 6),
        )
        assertEquals(mapOf("t_1" to 3L, "t_2" to 4L), heldTaskIntroductions(lines))
    }

    @Test fun each_state_says_what_happened() {
        val card = HeldTask.from("mac", wire())
        assertEquals("Held · waiting for you", heldTaskStatus(card))
        assertEquals("Resumed from this phone", heldTaskStatus(card.copy(stateWire = "resumed"), here = true))
        assertEquals("Resumed from a paired device", heldTaskStatus(card.copy(stateWire = "resumed")))
        assertTrue(heldTaskStatus(card.copy(stateWire = "released")).startsWith("No longer held"))
        assertTrue(heldTaskStatus(card.copy(stateWire = "expired")).startsWith("No longer tracked"))
    }

    @Test fun a_long_last_error_is_folded() {
        assertFalse(foldsError("rate limited"))
        assertTrue(foldsError("e".repeat(FOLDED_ERROR_CHARS + 1)))
        assertTrue(foldsError((1..FOLDED_ERROR_LINES + 1).joinToString("\n") { "line $it" }))
        assertFalse(foldsError((1..FOLDED_ERROR_LINES).joinToString("\n") { "line $it" }))
    }
}
