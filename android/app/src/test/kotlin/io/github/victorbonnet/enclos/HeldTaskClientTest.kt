package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeldTaskClientTest {
    private val held = HeldTask(
        host = "mac", agent = "lab/main", conversation = "home.lab.main", task = "t_1", title = "Write the report",
        reason = "Gave up after 3 failures", since = 1_763_000_000, resume = "Runs it again with a fresh retry budget.",
        stateWire = "held", revision = 3,
    )

    private class Fixture {
        var connected = true
        var sending = true
        var resyncing = true
        val sent = mutableListOf<HeldTaskRequest>()
        val changed = mutableListOf<HeldTask>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val resyncs = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val home = Conversation("mac", "lab/main", "home.lab.main", home = true)
        val client = HeldTaskClient(
            send = { sent += it; sending }, connected = { connected }, accepts = { it.belongsTo(home) },
            changed = { changed += it }, later = { wait, callback -> timers += wait to callback },
            resync = { resyncs += it; resyncing }, dropped = { dropped += it },
        )

        fun ready(card: HeldTask) {
            client.authenticated(card.host)
            client.merge(card)
            client.synced(card.host)
        }

        fun error(of: String, code: String, said: String = "") =
            JSONObject().put("v", 1).put("id", "e-1").put("type", "error").put("of", of).put("code", code).put("message", said)
    }

    private fun frame(type: String, card: HeldTask, of: String? = null) = JSONObject().put("v", 1).put("id", "b-1").put("type", type)
        .put("held_task", card.json()).apply { of?.let { put("of", it) } }

    @Test fun resume_sends_the_cards_agent_task_and_bridge_revision_under_a_fresh_id() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        val command = f.sent.single().command()
        assertEquals("held_task.resume", command.getString("type"))
        assertEquals(1, command.getInt("v"))
        assertEquals(id, command.getString("id"))
        assertEquals("lab/main", command.getString("agent"))
        assertEquals("t_1", command.getString("task"))
        assertEquals(3L, command.getLong("revision"))
        assertEquals(setOf("v", "id", "type", "agent", "task", "revision"), command.keys().asSequence().toSet())
        // Another resume gets another id.
        val f2 = Fixture(); f2.ready(held)
        assertNotEquals(id, f2.client.resume(held))
    }

    @Test fun resume_is_offered_only_while_held_connected_synced_and_not_in_flight() {
        val f = Fixture()
        // From the cache, before any sync: shown, not resumable.
        f.client.merge(held, cached = true)
        assertTrue(f.client.offersResume(held))
        assertFalse(f.client.canResume(held))
        assertNull(f.client.resume(held))
        f.client.authenticated("mac")
        assertEquals("Waiting for the host to sync…", f.client.unavailable(held))
        f.client.synced("mac")
        assertTrue(f.client.canResume(held))
        f.connected = false
        assertFalse(f.client.canResume(held))
        assertNull(f.client.resume(held))
        f.connected = true
        f.client.resume(held)!!
        assertFalse(f.client.canResume(held))
        assertNull(f.client.resume(held))
        assertEquals(1, f.sent.size)
        for (state in listOf("resumed", "released", "expired", "later")) {
            val card = held.copy(stateWire = state)
            assertFalse(f.client.offersResume(card))
            assertFalse(f.client.canResume(card))
        }
    }

    @Test fun a_card_shown_before_it_changed_is_not_resumed() {
        val f = Fixture(); f.ready(held)
        f.client.merge(held.copy(reason = "Blocked for a decision", revision = 4))
        assertNull(f.client.resume(held))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun only_the_exact_result_finishes_the_request() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        val resumed = held.copy(stateWire = "resumed", revision = 4)
        assertFalse(f.client.result("pi", id, resumed.copy(host = "pi")))
        assertFalse(f.client.result("mac", "another", resumed))
        assertFalse(f.client.result("mac", id, resumed.copy(task = "t_2")))
        assertTrue(held.key in f.client.pending)
        assertTrue(f.client.received("mac", frame("held_task.result", resumed, of = id)))
        assertFalse(held.key in f.client.pending)
        assertEquals(resumed, f.client.cards[held.key])
        assertEquals(id, f.client.receipts[held.key])
        assertEquals(resumed, f.changed.last())
        assertFalse(f.client.offersResume(f.client.cards[held.key]!!))
    }

    @Test fun cards_keep_the_newest_revision_whatever_order_they_come_in() {
        val f = Fixture(); f.ready(held)
        val released = held.copy(stateWire = "released", revision = 5)
        f.client.received("mac", frame("held_task", released))
        f.client.received("mac", frame("held_task", held.copy(revision = 4)))
        f.client.merge(held, cached = true)
        assertEquals(released, f.client.cards[held.key])
        assertEquals(listOf(held, released), f.changed)
    }

    @Test fun cards_of_no_kept_home_or_unreadable_are_dropped() {
        val f = Fixture()
        assertFalse(f.client.merge(held.copy(conversation = "c-2")))
        assertTrue(f.client.received("mac", JSONObject("""{"type":"held_task","held_task":{"task":"t_1"}}""")))
        assertTrue(f.client.cards.isEmpty())
        assertEquals(2, f.dropped.size)
    }

    @Test fun refusals_before_sending_are_certain_and_leave_resume_available() {
        for (code in listOf("agent_unavailable", "not_allowed", "bad_message", "unknown_type", "unsupported")) {
            val f = Fixture(); f.ready(held)
            val id = f.client.resume(held)!!
            assertFalse(f.client.received("pi", f.error(id, code)))
            assertFalse(f.client.received("mac", f.error("another", code)))
            assertTrue(f.client.received("mac", f.error(id, code)))
            assertFalse(held.key in f.client.pending)
            assertNull(f.client.uncertain[held.key])
            assertTrue(code, f.client.canResume(held))
            assertNotNull(f.client.notes[held.key])
            assertTrue(f.resyncs.isEmpty())
        }
    }

    @Test fun an_unconfirmed_resume_is_never_resent_and_asks_for_a_sync() {
        for (code in listOf("unconfirmed", "conflict", "internal", "a_later_code")) {
            val f = Fixture(); f.ready(held)
            val id = f.client.resume(held)!!
            assertTrue(f.client.received("mac", f.error(id, code, "runtime‮ said no")))
            assertEquals(listOf("mac"), f.resyncs)
            assertEquals(true, f.client.uncertain[held.key])
            assertFalse(code, f.client.canResume(held))
            assertNull(f.client.resume(held))
            assertFalse(Visible.hasHidden(f.client.notes[held.key]!!))
            // The sync settles it: still held, Resume again, by a new tap only.
            f.client.synced("mac")
            assertNull(f.client.uncertain[held.key])
            assertTrue(f.client.canResume(held))
            assertEquals(1, f.sent.size)
            assertNotNull(f.client.notes[held.key])
        }
    }

    @Test fun an_unconfirmed_resume_that_did_resume_shows_resumed_after_the_sync() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        f.client.received("mac", f.error(id, "unconfirmed"))
        // The runtime did resume it: its card comes, newer, before the sync ends.
        val resumed = held.copy(stateWire = "resumed", revision = 4)
        f.client.received("mac", frame("held_task", resumed))
        f.client.synced("mac")
        assertEquals(resumed, f.client.cards[held.key])
        assertNull(f.client.uncertain[held.key])
        assertFalse(f.client.offersResume(resumed))
        assertFalse(f.client.canResume(resumed))
        assertNull(f.client.resume(resumed))
        assertEquals(1, f.sent.size)
        // Not this phone's confirmed receipt: the card does not claim it.
        assertNull(f.client.receipts[held.key])
        assertFalse(f.client.status(resumed).contains("this phone"))
        assertNull(f.client.notes[held.key])
    }

    @Test fun not_found_hides_resume_until_a_newer_card_comes() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        assertTrue(f.client.received("mac", f.error(id, "not_found")))
        assertFalse(held.key in f.client.pending)
        assertNull(f.client.uncertain[held.key])
        assertTrue(f.resyncs.isEmpty())
        // Still `held` as the bridge last said, but the host has no such task: no Resume.
        assertEquals(held, f.client.cards[held.key])
        assertFalse(f.client.offersResume(held))
        assertFalse(f.client.canResume(held))
        assertNull(f.client.resume(held))
        assertNotEquals(heldTaskStatus(held), f.client.status(held))
        assertNotEquals(heldTaskStatus(held.copy(stateWire = "expired")), f.client.status(held))
        // The same revision again (a sync) changes nothing; a newer card replaces it.
        f.client.merge(held)
        assertFalse(f.client.offersResume(held))
        val again = held.copy(reason = "Blocked again", revision = 5)
        f.client.merge(again)
        assertTrue(f.client.canResume(again))
        assertEquals(heldTaskStatus(again), f.client.status(again))
    }

    @Test fun a_task_held_again_after_this_phone_resumed_it_offers_resume_again() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        val resumed = held.copy(stateWire = "resumed", revision = 4)
        f.client.result("mac", id, resumed)
        assertTrue(f.client.status(resumed).contains("this phone"))
        val again = held.copy(reason = "Gave up again", since = 1_763_100_000, revision = 7)
        f.client.received("mac", frame("held_task", again))
        assertEquals(again, f.client.cards[held.key])
        assertNull(f.client.receipts[held.key])
        assertTrue(f.client.canResume(again))
        assertNotNull(f.client.resume(again))
        assertEquals(listOf(3L, 7L), f.sent.map { it.card.revision })
    }

    @Test fun the_same_task_on_two_hosts_is_two_cards() {
        val f = Fixture()
        val other = held.copy(host = "pi")
        val client = HeldTaskClient(send = { f.sent += it; true }, connected = { true }, accepts = { true },
            changed = {}, later = { _, _ -> }, resync = { true })
        listOf("mac", "pi").forEach(client::authenticated)
        client.merge(held); client.merge(other.copy(stateWire = "released", revision = 9))
        listOf("mac", "pi").forEach(client::synced)
        assertEquals(2, client.cards.size)
        assertTrue(client.canResume(held))
        assertFalse(client.canResume(client.cards.getValue(other.key)))
        val id = client.resume(held)!!
        // An answer naming the other host's card, or from it, settles nothing.
        assertFalse(client.failure("pi", id, "not_found"))
        assertFalse(client.result("pi", id, other.copy(stateWire = "resumed", revision = 10)))
        assertTrue(held.key in client.pending)
        assertTrue(client.failure("mac", id, "agent_unavailable"))
        assertNull(client.gone[other.key])
        client.forget("pi")
        assertEquals(setOf(held.key), client.cards.keys)
    }

    @Test fun a_card_that_comes_before_its_home_shows_once_the_home_does() {
        var homes = emptyList<Conversation>()
        val f = Fixture()
        val client = HeldTaskClient(send = { true }, connected = { true }, accepts = { true },
            changed = {}, later = { _, _ -> })
        assertTrue(client.merge(held))
        assertNull(homes.firstNotNullOfOrNull { client.current(it, "t_1") })
        homes = listOf(f.home)
        assertEquals(held, homes.firstNotNullOfOrNull { client.current(it, "t_1") })
        assertEquals(listOf(held), client.unlinked(f.home, emptySet()))
    }

    @Test fun held_cards_no_loaded_message_introduces_are_drawn_below_the_history() {
        val f = Fixture(); f.ready(held)
        val older = held.copy(task = "t_0", since = held.since - 60)
        val elsewhere = held.copy(task = "t_9", agent = "lab/other", conversation = "home.lab.other")
        val released = held.copy(task = "t_5", stateWire = "released")
        listOf(older, released).forEach { f.client.merge(it) }
        f.client.merge(elsewhere, cached = true)
        // Linked tasks are drawn at their message; the others, held, oldest first.
        assertEquals(listOf(older, held), f.client.unlinked(f.home, emptySet()))
        assertEquals(listOf(older), f.client.unlinked(f.home, setOf("t_1")))
        assertTrue(f.client.unlinked(f.home.copy(home = false), emptySet()).isEmpty())
        // A card whose resume is still unsettled stays until its outcome is shown.
        f.client.received("mac", f.error(f.client.resume(older)!!, "unconfirmed"))
        f.client.merge(older.copy(stateWire = "resumed", revision = 4))
        assertEquals(listOf("t_0", "t_1"), f.client.unlinked(f.home, emptySet()).map { it.task })
        f.client.synced("mac")
        assertEquals(listOf(held), f.client.unlinked(f.home, emptySet()))
        // From a message's link, through the timeline's own selection.
        val lines = listOf(Line(1, "agent", "Held: report", "notify", 1, heldTask = "t_1"))
        assertTrue(f.client.unlinked(f.home, heldTaskIntroductions(lines).keys).isEmpty())
    }

    @Test fun a_sync_that_cannot_be_asked_waits_for_the_next_connection() {
        val f = Fixture(); f.ready(held)
        f.resyncing = false
        val id = f.client.resume(held)!!
        f.client.received("mac", f.error(id, "unconfirmed"))
        assertEquals("The task may have resumed. Reconnect to mac to check.", f.client.notes[held.key])
        assertFalse(f.client.canResume(held))
        f.client.ended("mac")
        f.client.authenticated("mac")
        assertFalse(f.client.canResume(held))
        f.client.synced("mac")
        assertTrue(f.client.canResume(held))
        assertEquals(1, f.sent.size)
    }

    @Test fun a_connection_that_ends_before_the_answer_leaves_the_outcome_to_the_next_sync() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        f.client.ended("mac")
        assertFalse(held.key in f.client.pending)
        assertEquals(true, f.client.uncertain[held.key])
        assertTrue(f.client.notes[held.key]!!.contains("may have resumed"))
        // A late answer on another connection is no answer to this request.
        assertFalse(f.client.result("mac", id, held.copy(stateWire = "resumed", revision = 4)))
        f.client.authenticated("mac")
        f.client.synced("mac")
        assertTrue(f.client.canResume(held))
        assertEquals(1, f.sent.size)
    }

    @Test fun no_answer_in_time_doubts_without_resending_and_a_late_answer_still_settles() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        val (wait, timeout) = f.timers.single()
        assertTrue(wait > 25_000)
        timeout()
        assertFalse(held.key in f.client.pending)
        assertEquals(true, f.client.uncertain[held.key])
        assertEquals(listOf("mac"), f.resyncs)
        assertEquals(1, f.sent.size)
        val resumed = held.copy(stateWire = "resumed", revision = 4)
        assertTrue(f.client.result("mac", id, resumed))
        assertEquals(resumed, f.client.cards[held.key])
        assertNull(f.client.uncertain[held.key])
    }

    @Test fun a_timer_after_the_answer_does_nothing() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        f.client.result("mac", id, held.copy(stateWire = "resumed", revision = 4))
        f.timers.single().second()
        assertNull(f.client.uncertain[held.key])
        assertTrue(f.resyncs.isEmpty())
    }

    @Test fun a_send_that_fails_is_not_pending_and_says_so() {
        val f = Fixture(); f.ready(held)
        f.sending = false
        assertNull(f.client.resume(held))
        assertFalse(held.key in f.client.pending)
        assertEquals("Not sent. Reconnect and try again.", f.client.notes[held.key])
        assertTrue(f.timers.isEmpty())
    }

    @Test fun an_unreadable_answer_to_this_phones_resume_is_doubted() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        val broken = JSONObject().put("type", "held_task.result").put("of", id).put("held_task", JSONObject().put("task", "t_1"))
        assertTrue(f.client.received("mac", broken))
        assertFalse(held.key in f.client.pending)
        assertEquals(true, f.client.uncertain[held.key])
        assertEquals(listOf("mac"), f.resyncs)
    }

    @Test fun a_card_that_leaves_held_elsewhere_clears_the_last_note() {
        val f = Fixture(); f.ready(held)
        f.client.received("mac", f.error(f.client.resume(held)!!, "agent_unavailable"))
        assertNotNull(f.client.notes[held.key])
        f.client.merge(held.copy(stateWire = "released", revision = 4))
        assertNull(f.client.notes[held.key])
    }

    @Test fun forgetting_a_home_or_a_host_drops_its_cards_and_requests() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        f.client.forgetConversation("mac", "home.lab.main")
        assertTrue(f.client.cards.isEmpty())
        assertTrue(f.client.pending.isEmpty())
        assertFalse(f.client.result("mac", id, held.copy(stateWire = "resumed", revision = 4)))
        f.ready(held)
        f.client.forget("mac")
        assertTrue(f.client.cards.isEmpty())
        assertNull(f.client.current(f.home, "t_1"))
    }

    @Test fun the_card_of_a_message_is_found_in_its_agents_home_only() {
        val f = Fixture(); f.ready(held)
        assertEquals(held, f.client.current(f.home, "t_1"))
        assertNull(f.client.current(f.home, "t_2"))
        assertNull(f.client.current(f.home, null))
        assertNull(f.client.current(f.home.copy(home = false), "t_1"))
        assertNull(f.client.current(f.home.copy(agent = "lab/other"), "t_1"))
    }

    @Test fun other_frames_are_left_to_other_clients() {
        val f = Fixture()
        assertFalse(f.client.received("mac", JSONObject().put("type", "question")))
        assertFalse(f.client.received("mac", f.error("not-ours", "conflict")))
    }
}
