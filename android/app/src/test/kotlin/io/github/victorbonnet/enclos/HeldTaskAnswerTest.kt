package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A held task's ask, the person's answer and the `answered` state (enclos ADR 0073). */
class HeldTaskAnswerTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/protocol/$name.json")!!.readText())

    /** The enclos fixture's answered card, as it was held before the answer: revision 1, no answer. */
    private fun heldFromFixture(): HeldTask {
        val card = fixture("to_device_held_task_answered").getJSONObject("held_task")
        card.put("state", "held").put("revision", 1).remove("answer")
        return HeldTask.from("mac", card)
    }

    private val home = Conversation("mac", "lab/runtimes", "home.lab.runtimes", home = true)

    private class Fixture(val home: Conversation, var supported: Boolean = true) {
        var connected = true
        var sending = true
        val sent = mutableListOf<HeldTaskRequest>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val resyncs = mutableListOf<String>()
        val client = HeldTaskClient(
            send = { sent += it; sending }, connected = { connected }, accepts = { it.belongsTo(home) },
            changed = {}, later = { wait, callback -> timers += wait to callback },
            resync = { resyncs += it; true }, answerSupported = { supported },
        )

        fun ready(card: HeldTask) {
            client.authenticated(card.host)
            client.merge(card)
            client.synced(card.host)
        }

        fun error(of: String, code: String) =
            JSONObject().put("v", 1).put("id", "e-1").put("type", "error").put("of", of).put("code", code).put("message", "")
    }

    @Test fun the_answered_fixture_reads_with_its_ask_answer_and_state() {
        val m = fixture("to_device_held_task_answered")
        val card = HeldTask.from("mac", m.getJSONObject("held_task"))
        assertEquals(HeldTaskState.Answered, card.state)
        assertEquals("Allow one 60-min fix, then review and CI", card.answer)
        val ask = card.ask!!
        assertEquals("Allow one more hour to fix Hermes backups?", ask.decision)
        assertTrue(ask.blocked.startsWith("Both reviews found"))
        assertEquals(listOf("Allow one 60-min fix, then review and CI", "Keep #346 paused"), ask.choices)
        assertEquals(0, ask.recommended)
        assertEquals("Answered: Allow one 60-min fix, then review and CI", heldTaskStatus(card))
        // The cache's form reads back the same, ask and answer included.
        assertEquals(card, HeldTask.from("mac", JSONObject(card.json().toString())))
        assertEquals(listOf(card), keptHeldTasks(listOf(HeldTaskRow("mac", card.agent, card.task, card.json().toString())), listOf(home)).first)
        // Without an answer it still says it waits on the agent.
        assertEquals("Answered · waiting on the agent", heldTaskStatus(card.copy(answer = null)))
    }

    @Test fun the_request_is_the_protocol_fixture() {
        val f = Fixture(home)
        val card = heldFromFixture()
        f.ready(card)
        val id = f.client.answer(card, HeldTaskAnswer.Choice(0))!!
        val command = f.sent.single().command()
        val expected = fixture("from_device_held_task_answer")
        assertEquals(id, command.getString("id"))
        for (key in listOf("type", "agent", "task")) assertEquals(key, expected.getString(key), command.getString(key))
        for (key in listOf("v", "revision")) assertEquals(key, expected.getLong(key), command.getLong(key))
        val answer = command.getJSONObject("answer")
        assertEquals(expected.getJSONObject("answer").getString("kind"), answer.getString("kind"))
        assertEquals(expected.getJSONObject("answer").getInt("index"), answer.getInt("index"))
        assertEquals(2, answer.length())
        assertEquals(1, HeldTaskAnswer.Agent.json().length())
        assertEquals("agent", HeldTaskAnswer.Agent.json().getString("kind"))
        val text = HeldTaskAnswer.Text("Wait for Monday").json()
        assertEquals("text" to "Wait for Monday", text.getString("kind") to text.getString("text"))
    }

    @Test fun an_ask_of_the_wrong_type_refuses_the_card_and_one_the_bridge_would_not_send_reads_as_none() {
        fun with(ask: Any) = heldFromFixture().json().put("ask", ask)
        for (bad in listOf<Any>("ask", 1, JSONArray(), JSONObject().put("decision", 4),
            JSONObject().put("decision", "Go?").put("choices", "a"),
            JSONObject().put("decision", "Go?").put("choices", JSONArray().put(1)),
            JSONObject().put("decision", "Go?").put("blocked", 3),
            JSONObject().put("decision", "Go?").put("recommended", "0"))) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { HeldTask.from("mac", with(bad)) }
        }
        for (none in listOf(
            JSONObject().put("decision", "  "),
            JSONObject().put("decision", "Go?").put("choices", JSONArray(listOf("a", "b", "c", "d", "e"))),
            JSONObject().put("decision", "Go?").put("choices", JSONArray(listOf("a", " "))),
        )) assertNull(none.toString(), HeldTask.from("mac", with(none)).ask)
        assertNull(HeldTask.from("mac", heldFromFixture().json().put("ask", JSONObject.NULL)).ask)
        // A recommendation naming no choice is dropped; the choices keep their order and indexes.
        val ask = HeldTask.from("mac", with(JSONObject().put("decision", "Go?").put("choices", JSONArray(listOf("b", "a"))).put("recommended", 5))).ask!!
        assertNull(ask.recommended)
        assertEquals(listOf("b", "a"), ask.choices)
        assertEquals("", ask.blocked)
    }

    @Test fun an_ask_is_cut_as_the_bridge_cuts_it() {
        val long = "x".repeat(MAX_HELD_DECISION_CHARS + 10)
        val ask = HeldTask.from("mac", heldFromFixture().json().put("ask", JSONObject()
            .put("decision", "Allow\n one​ more hour?  $long")
            .put("blocked", "Line one\nline two" + "y".repeat(MAX_HELD_BLOCKED_CHARS))
            .put("choices", JSONArray(listOf("Fix\tit", "z".repeat(MAX_HELD_CHOICE_CHARS + 5)))))).ask!!
        assertTrue(ask.decision.startsWith("Allow one more hour? x"))
        assertEquals(MAX_HELD_DECISION_CHARS, ask.decision.length)
        assertTrue(ask.blocked.startsWith("Line one\nline two"))
        assertEquals(MAX_HELD_BLOCKED_CHARS, ask.blocked.length)
        assertEquals("Fix it", ask.choices[0])
        assertEquals(MAX_HELD_CHOICE_CHARS, ask.choices[1].length)
        val answer = HeldTask.from("mac", heldFromFixture().json().put("answer", "Keep\n paused" + "w".repeat(300))).answer!!
        assertTrue(answer.startsWith("Keep paused"))
        assertEquals(MAX_HELD_ANSWER_CHARS, answer.length)
    }

    @Test fun the_recommended_choice_is_shown_first_with_its_own_index() {
        val ask = HeldTaskAsk("Go?", "", listOf("A", "B", "C"), recommended = 2)
        assertEquals(listOf(2 to "C", 0 to "A", 1 to "B"), ask.shown())
        assertEquals(listOf(0 to "A", 1 to "B", 2 to "C"), ask.copy(recommended = null).shown())
    }

    @Test fun a_typed_answer_follows_the_bridges_rule() {
        assertEquals("Wait for Monday", heldAnswerText("  Wait for Monday \n"))
        assertEquals("Two\nlines", heldAnswerText("Two\nlines"))
        assertNull(heldAnswerText("   "))
        assertNull(heldAnswerText("tab\there"))
        assertNull(heldAnswerText("hidden‮text"))
        assertNotNull(heldAnswerText("a".repeat(MAX_HELD_ANSWER_TEXT_CHARS)))
        assertNull(heldAnswerText("a".repeat(MAX_HELD_ANSWER_TEXT_CHARS + 1)))
    }

    @Test fun an_older_bridge_gets_no_answer_and_nothing_is_sent() {
        val f = Fixture(home, supported = false)
        val card = heldFromFixture()
        f.ready(card)
        assertFalse(f.client.offersAnswer(card))
        assertFalse(f.client.answersOn("mac"))
        assertNull(f.client.answer(card, HeldTaskAnswer.Choice(0)))
        assertNull(f.client.answer(card, HeldTaskAnswer.Agent))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun only_a_choice_the_card_offers_or_a_text_the_bridge_takes_is_sent_and_only_from_the_current_card() {
        val f = Fixture(home)
        val card = heldFromFixture()
        f.ready(card)
        assertNull(f.client.answer(card, HeldTaskAnswer.Choice(2)))
        assertNull(f.client.answer(card, HeldTaskAnswer.Choice(-1)))
        assertNull(f.client.answer(card, HeldTaskAnswer.Text(" ")))
        assertNull(f.client.answer(card, HeldTaskAnswer.Text("a\tb")))
        // A card that changed since it was drawn is not answered.
        val newer = card.copy(revision = 2, reason = "Another reason")
        f.client.merge(newer)
        assertNull(f.client.answer(card, HeldTaskAnswer.Choice(0)))
        assertTrue(f.sent.isEmpty())
        // A card no agent wrote has no choice, but asks the agent.
        val unwritten = newer.copy(revision = 3, ask = null)
        f.client.merge(unwritten)
        assertNull(f.client.answer(unwritten, HeldTaskAnswer.Choice(0)))
        assertNotNull(f.client.answer(unwritten, HeldTaskAnswer.Agent))
        assertEquals(HeldTaskAnswer.Agent, f.sent.single().answer)
        // Trimmed before it goes.
        val g = Fixture(home); g.ready(card)
        g.client.answer(card, HeldTaskAnswer.Text("  Wait for Monday  "))
        assertEquals(HeldTaskAnswer.Text("Wait for Monday"), g.sent.single().answer)
    }

    @Test fun the_result_shows_the_answer_and_the_card_leaves_needs_you() {
        val f = Fixture(home)
        val card = heldFromFixture()
        f.ready(card)
        val item = OpenItem("mac", card.task, "held_task", card.agent, card.conversation, "Allow?", 1, false, version = "held:1")
        assertEquals(listOf(item), withoutAnswered(listOf(item), f.client.cards))
        val id = f.client.answer(card, HeldTaskAnswer.Choice(0))!!
        assertEquals("Sending your answer…", f.client.answerUnavailable(card))
        assertNull("one request at a time", f.client.answer(card, HeldTaskAnswer.Choice(1)))
        assertFalse(f.client.canResume(card))
        val result = fixture("to_device_held_task_answered").put("of", id)
        assertTrue(f.client.received("mac", result))
        val answered = f.client.cards[card.key]!!
        assertEquals(HeldTaskState.Answered, answered.state)
        assertEquals(id, f.client.answered[card.key])
        assertEquals("Answered: Allow one 60-min fix, then review and CI", f.client.status(answered))
        assertFalse(f.client.offersAnswer(answered))
        assertFalse(f.client.offersResume(answered))
        assertTrue(f.client.pending.isEmpty())
        assertNull(f.client.notes[card.key])
        // Still in its Home, below the history when nothing there introduces it.
        assertEquals(listOf(answered), f.client.unlinked(home, emptySet()))
        // Out of Needs you at the row's revision; a newer hold comes back.
        assertTrue(withoutAnswered(listOf(item), f.client.cards).isEmpty())
        val again = item.copy(version = "held:3")
        assertEquals(listOf(again), withoutAnswered(listOf(again), f.client.cards))
        // Nothing is sent again, whatever comes next.
        f.timers.forEach { it.second() }
        f.client.ended("mac")
        assertEquals(1, f.sent.size)
    }

    @Test fun a_conflict_refreshes_says_the_task_changed_and_never_resends() {
        val f = Fixture(home)
        val card = heldFromFixture()
        f.ready(card)
        val id = f.client.answer(card, HeldTaskAnswer.Choice(1))!!
        assertTrue(f.client.received("mac", f.error(id, "conflict")))
        assertEquals("The task changed since this card was shown. Refreshing…", f.client.notes[card.key])
        assertEquals(listOf("mac"), f.resyncs)
        assertEquals("Checking with mac…", f.client.answerUnavailable(card))
        assertNull(f.client.answer(card, HeldTaskAnswer.Choice(1)))
        f.client.synced("mac")
        assertTrue(f.client.notes[card.key]!!.contains("look there before answering again"))
        assertTrue(f.client.canAnswer(card))
        assertEquals(1, f.sent.size)
    }

    @Test fun certain_refusals_say_so_and_others_doubt_the_outcome() {
        val card = heldFromFixture()
        for ((code, note) in listOf(
            "not_allowed" to "Only the host's person can answer held tasks.",
            "bad_message" to "mac did not take this answer, so nothing was sent.",
            "unknown_type" to "The bridge on mac cannot take answers to held tasks. Update enclos there.",
        )) {
            val f = Fixture(home); f.ready(card)
            val id = f.client.answer(card, HeldTaskAnswer.Agent)!!
            f.client.failure("mac", id, code)
            assertEquals(code, note, f.client.notes[card.key])
            assertTrue(code, f.client.canAnswer(card))
            assertTrue(code, f.resyncs.isEmpty())
        }
        run {
            val f = Fixture(home); f.ready(card)
            val id = f.client.answer(card, HeldTaskAnswer.Agent)!!
            f.client.failure("mac", id, "not_found")
            assertFalse(f.client.offersAnswer(card))
            assertTrue(f.client.status(card).startsWith("Not known on the host"))
        }
        for (code in listOf("internal", "unconfirmed", "something_new")) {
            val f = Fixture(home); f.ready(card)
            val id = f.client.answer(card, HeldTaskAnswer.Agent)!!
            f.client.failure("mac", id, code)
            assertTrue(code, f.client.notes[card.key]!!.contains("Your answer may have reached the agent."))
            assertEquals(code, listOf("mac"), f.resyncs)
            assertFalse(code, f.client.canAnswer(card))
        }
    }

    @Test fun no_reply_or_a_lost_connection_doubts_the_answer_without_sending_it_again() {
        run {
            val f = Fixture(home); val card = heldFromFixture(); f.ready(card)
            f.client.answer(card, HeldTaskAnswer.Choice(0))
            assertEquals(HeldTaskClient.ANSWER_WAIT_MS, f.timers.single().first)
            f.timers.single().second()
            assertEquals("No answer from mac. Your answer may have reached the agent. Refreshing…", f.client.notes[card.key])
            assertEquals(1, f.sent.size)
        }
        run {
            val f = Fixture(home); val card = heldFromFixture(); f.ready(card)
            f.client.answer(card, HeldTaskAnswer.Choice(0))
            f.connected = false
            f.client.ended("mac")
            assertEquals("The connection ended before mac confirmed. Your answer may have reached the agent; reconnect to check.", f.client.notes[card.key])
            assertEquals("Reconnect to mac to check whether your answer arrived.", f.client.answerUnavailable(card))
            f.connected = true
            f.client.authenticated("mac"); f.client.synced("mac")
            assertTrue(f.client.canAnswer(card))
            assertEquals(1, f.sent.size)
        }
        run {
            val f = Fixture(home); val card = heldFromFixture(); f.ready(card)
            f.sending = false
            assertNull(f.client.answer(card, HeldTaskAnswer.Choice(0)))
            assertEquals("Not sent. Reconnect and try again.", f.client.notes[card.key])
            assertTrue(f.client.pending.isEmpty())
        }
    }

    @Test fun the_answer_waits_for_the_connection_and_its_sync() {
        val f = Fixture(home); val card = heldFromFixture()
        f.client.merge(card)
        f.connected = false
        assertEquals("Connect to mac to answer.", f.client.answerUnavailable(card))
        f.connected = true
        f.client.authenticated("mac")
        assertEquals("Waiting for the host to sync…", f.client.answerUnavailable(card))
        f.client.synced("mac")
        assertNull(f.client.answerUnavailable(card))
    }

    @Test fun a_row_is_kept_unless_its_card_is_known_answered_at_its_revision() {
        val card = heldFromFixture().copy(stateWire = "answered", revision = 2)
        val cards = mapOf(card.key to card)
        val row = OpenItem("mac", card.task, "held_task", card.agent, card.conversation, "Allow?", 1, false, version = "held:2")
        assertTrue(withoutAnswered(listOf(row), cards).isEmpty())
        for (kept in listOf(row.copy(version = "held:3"), row.copy(version = "v"), row.copy(host = "pi"), row.copy(kind = "question"))) {
            assertEquals(kept.toString(), listOf(kept), withoutAnswered(listOf(kept), cards))
        }
        assertEquals(listOf(row), withoutAnswered(listOf(row), mapOf(card.key to card.copy(stateWire = "held"))))
    }
}
