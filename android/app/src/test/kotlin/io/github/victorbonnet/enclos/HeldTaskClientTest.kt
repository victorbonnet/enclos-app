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

    @Test fun raw_inbound_false_before_home_cache_and_linked_history_stays_visible() {
        val card = HeldTask.from("mac", held.json().put("resumable", false))
        val client = HeldTaskClient(send = { error("Must not send") }, connected = { false },
            accepts = { true }, changed = {}, later = { _, _ -> })
        val raw = frame("held_task", card).toString().toByteArray(Charsets.UTF_8)
        val inbound = inbound(String(raw, Charsets.UTF_8)) as Inbound.Use
        assertTrue(client.received("mac", inbound.message))
        val home = Fixture().home
        assertEquals(card, client.current(home, card.task))
        assertEquals(listOf(card), client.unlinked(home, emptySet()))
        assertTrue(client.unlinked(home, setOf(card.task)).isEmpty())
        client.merge(HeldTask.from("mac", JSONObject(card.json().toString())), cached = true)
        client.authenticated("mac"); client.synced("mac")
        assertEquals(listOf(card), client.unlinked(home, emptySet()))
        assertFalse(client.offersResume(card))
    }

    @Test fun malformed_capability_in_result_keeps_uncertainty() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        val result = frame("held_task.result", held, id)
        result.getJSONObject("held_task").put("resumable", "false")
        f.client.received("mac", result)
        assertTrue(f.client.uncertain[held.key] == true)
        assertTrue(f.client.pending.isEmpty())
        assertEquals(1, f.sent.size)
        assertEquals(listOf("mac"), f.resyncs)
    }

    @Test fun false_stays_visible_and_never_sends_even_from_a_stale_opening() {
        val f = Fixture(); f.ready(held)
        val blocked = HeldTask.from("mac", held.json().put("resumable", false).put("revision", 4))
        f.client.merge(blocked)
        assertEquals(listOf(blocked), f.client.unlinked(f.home, emptySet()))
        assertEquals(blocked, f.client.current(f.home, held.task))
        assertFalse(f.client.offersResume(blocked))
        assertNull(f.client.resume(blocked))
        assertNull(f.client.resume(held))
        assertTrue(f.sent.isEmpty()); assertTrue(f.client.pending.isEmpty()); assertTrue(f.timers.isEmpty())
        f.client.merge(held.copy(revision = 4)); f.client.merge(held)
        assertEquals(blocked, f.client.current(f.home, held.task))
        val enabled = held.copy(revision = 5)
        f.client.merge(enabled)
        assertNotNull(f.client.resume(enabled))
        assertEquals(1, f.sent.size)
    }

    @Test fun unsupported_refreshes_with_neutral_copy_and_never_resends() {
        val f = Fixture(); f.ready(held)
        val id = f.client.resume(held)!!
        f.client.failure("mac", id, "unsupported", "upgrade runtime")
        assertEquals("This request could not resume the task.", f.client.notes[held.key])
        assertEquals(listOf("mac"), f.resyncs)
        assertFalse(f.client.canResume(held))
        assertNull(f.client.resume(held))
        val no = HeldTask.from("mac", held.json().put("resumable", false).put("revision", 4))
        f.client.received("mac", frame("held_task", no))
        f.client.synced("mac")
        assertEquals(listOf(no), f.client.unlinked(f.home, emptySet()))
        assertEquals("Held · waiting for you", f.client.status(no))
        assertFalse(f.client.offersResume(no))
        assertNull(f.client.resume(held)); assertNull(f.client.resume(no))
        assertTrue(f.client.pending.isEmpty())
        assertEquals(1, f.sent.size)
    }

    @Test fun unsupported_sent_refresh_checks_until_sync() = checkUnsupportedRefresh("sent")
    @Test fun unsupported_failed_refresh_requires_reconnect() = checkUnsupportedRefresh("failed")
    @Test fun unsupported_offline_refusal_requires_reconnect() = checkUnsupportedRefresh("offline")
    @Test fun unsupported_disconnect_during_check_requires_reconnect() = checkUnsupportedRefresh("disconnected")

    private fun checkUnsupportedRefresh(path: String) {
        run {
            val f = Fixture(); f.ready(held)
            val id = f.client.resume(held)!!
            f.resyncing = path != "failed"
            f.connected = path != "offline"
            assertTrue(f.client.failure("mac", id, "unsupported"))
            if (path == "disconnected") {
                assertEquals("Checking with mac…", f.client.unavailable(held))
                f.connected = false
                f.client.ended("mac")
            }
            val expected = if (path == "sent") "Checking with mac…" else "Reconnect to mac to check the task."
            assertEquals(path, expected, f.client.unavailable(held))
            assertEquals("This request could not resume the task.", f.client.notes[held.key])
            assertTrue(f.client.uncertain.isEmpty())
            assertTrue(f.client.pending.isEmpty())
            assertEquals(if (path == "offline") emptyList<String>() else listOf("mac"), f.resyncs)
            assertNull(f.client.resume(held))
            f.timers.forEach { it.second() }
            assertEquals(expected, f.client.unavailable(held))
            f.connected = true
            f.client.authenticated("mac")
            if (path != "sent") assertEquals("Reconnect to mac to check the task.", f.client.unavailable(held))
            f.client.synced("mac")
            assertNull(f.client.notes[held.key])
            assertTrue(f.client.canResume(held))
            assertEquals(held, f.client.cards[held.key])
            assertEquals(1, f.sent.size)
        }
    }

    @Test fun unsupported_refresh_is_cleared_when_conversation_or_host_is_forgotten() {
        for (conversationOnly in listOf(true, false)) {
            val f = Fixture(); f.ready(held)
            f.client.failure("mac", f.client.resume(held)!!, "unsupported")
            if (conversationOnly) f.client.forgetConversation("mac", held.conversation) else f.client.forget("mac")
            assertTrue(f.client.cards.isEmpty())
            assertTrue(f.client.notes.isEmpty())
            f.ready(held)
            assertNull(f.client.unavailable(held))
            assertEquals(1, f.sent.size)
        }
    }

    @Test fun malformed_live_capability_cannot_replace_the_last_valid_card() {
        val f = Fixture(); f.ready(held)
        for (bad in listOf(JSONObject.NULL, "false", 0, 0.5, JSONObject(), org.json.JSONArray())) {
            val incoming = frame("held_task", held.copy(revision = 9))
            incoming.getJSONObject("held_task").put("resumable", bad)
            f.client.received("mac", incoming)
            assertEquals(held, f.client.current(f.home, held.task))
        }
        assertTrue(f.sent.isEmpty())
    }

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
        for (code in listOf("agent_unavailable", "not_allowed", "bad_message", "unknown_type")) {
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

    // #82: the text typed in Your answer, kept in memory for the same request and connection.

    /** A fixture whose host takes answers, with [held] synced. */
    private fun answering(): Fixture = Fixture().apply {
        client.authenticated("mac", answers = true)
        client.merge(held)
        client.synced("mac")
    }

    /** The text kept for [held]'s task, in the field or apart. */
    private fun Fixture.kept(card: HeldTask = held): String? = client.draftFor(card).ifEmpty { client.earlier(card)?.text }

    @Test fun the_same_request_keeps_its_text_and_typing_sends_nothing() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        assertEquals("Wait for Monday", f.client.draftFor(held))
        assertFalse(f.client.draftAttempted(held))
        assertNull(f.client.earlier(held))
        // A card that is not the current one is not typed into.
        f.client.edit(held.copy(revision = 2), "Stale")
        assertEquals("Wait for Monday", f.client.draftFor(held))
        assertEquals("", f.client.draftFor(held.copy(revision = 2)))
        // Emptying the field removes the text.
        f.client.edit(held, "")
        assertEquals("", f.client.draftFor(held))
        assertNull(f.client.earlier(held))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun another_host_of_the_same_agent_and_task_or_another_task_never_gets_the_text() {
        val sent = mutableListOf<HeldTaskRequest>()
        val client = HeldTaskClient(send = { sent += it; true }, connected = { true }, accepts = { true }, changed = {}, later = { _, _ -> })
        val mini = held.copy(host = "mini")
        val other = held.copy(task = "t_2")
        listOf("mac", "mini").forEach { client.authenticated(it, answers = true) }
        listOf(held, mini, other).forEach { client.merge(it) }
        listOf("mac", "mini").forEach { client.synced(it) }
        client.edit(held, "For mac only")
        assertEquals("", client.draftFor(mini)); assertNull(client.earlier(mini))
        assertEquals("", client.draftFor(other)); assertNull(client.earlier(other))
        // Forgetting the other host keeps this one's text.
        client.forget("mini")
        assertEquals("For mac only", client.draftFor(held))
        assertTrue(sent.isEmpty())
    }

    @Test fun a_new_revision_keeps_the_text_apart_and_send_does_not_use_it() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        val newer = held.copy(revision = 4, reason = "Asked again")
        f.client.merge(newer)
        assertEquals("", f.client.draftFor(newer))
        assertEquals(HeldTaskEarlier("Wait for Monday", HeldTaskEarlierReason.RequestChanged), f.client.earlier(newer))
        // The old card is not answered, and the current field is empty.
        assertNull(f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday")))
        assertTrue(f.sent.isEmpty())
        // Typing into the current field keeps both texts; Use waits for an empty field.
        f.client.edit(newer, "New words")
        assertEquals("New words", f.client.draftFor(newer))
        assertEquals("Wait for Monday", f.client.earlier(newer)?.text)
        assertFalse(f.client.useEarlier(newer))
        f.client.answer(newer, HeldTaskAnswer.Text(f.client.draftFor(newer)))
        assertEquals(HeldTaskAnswer.Text("New words"), f.sent.single().answer)
        assertEquals("Wait for Monday", f.client.earlier(newer)?.text)
    }

    @Test fun offline_keeps_the_text_in_place_and_a_new_connection_asks_for_review_before_reuse() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        f.connected = false
        f.client.ended("mac")
        assertEquals("Wait for Monday", f.client.draftFor(held))
        assertNull(f.client.earlier(held))
        f.connected = true
        f.client.authenticated("mac", answers = true)
        assertEquals("", f.client.draftFor(held))
        assertEquals(HeldTaskEarlier("Wait for Monday", HeldTaskEarlierReason.ConnectionChanged), f.client.earlier(held))
        // Not before the sync, nor offline.
        assertFalse(f.client.useEarlier(held))
        f.client.synced("mac")
        f.connected = false
        assertFalse(f.client.useEarlier(held))
        f.connected = true
        assertTrue(f.client.useEarlier(held))
        assertEquals("Wait for Monday", f.client.draftFor(held))
        assertNull(f.client.earlier(held))
        assertFalse(f.client.draftAttempted(held))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun discarding_earlier_text_sends_nothing() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        f.client.authenticated("mac", answers = true); f.client.synced("mac")
        f.client.discardEarlier(held)
        assertNull(f.client.earlier(held))
        assertEquals("", f.client.draftFor(held))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun no_outcome_but_a_taken_answer_drops_the_text() {
        fun sent(): Fixture = answering().apply {
            client.edit(held, "Wait for Monday")
            assertNotNull(client.answer(held, HeldTaskAnswer.Text("Wait for Monday")))
        }
        // Waiting: kept, and no longer "not sent".
        sent().let { f ->
            assertEquals("Wait for Monday", f.client.draftFor(held))
            assertTrue(f.client.draftAttempted(held))
        }
        // Not sent at all: kept, still not sent.
        answering().let { f ->
            f.sending = false
            f.client.edit(held, "Wait for Monday")
            assertNull(f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday")))
            assertEquals("Wait for Monday", f.client.draftFor(held))
            assertFalse(f.client.draftAttempted(held))
        }
        for (code in listOf("not_allowed", "bad_message", "unsupported", "unknown_type", "conflict", "internal", "not_found", "something_new")) {
            val f = sent()
            f.client.received("mac", f.error(f.sent.single().id, code))
            assertEquals(code, "Wait for Monday", f.kept())
        }
        // No answer in time, a connection that ended, an answer that cannot be read.
        sent().let { f ->
            f.timers.single().second()
            assertEquals("Wait for Monday", f.kept())
        }
        sent().let { f ->
            f.connected = false
            f.client.ended("mac")
            assertEquals("Wait for Monday", f.client.draftFor(held))
        }
        sent().let { f ->
            val unreadable = frame("held_task.result", held.copy(stateWire = "answered", revision = 4), f.sent.single().id)
            unreadable.getJSONObject("held_task").put("resumable", "false")
            f.client.received("mac", unreadable)
            assertEquals(true, f.client.uncertain[held.key])
            assertEquals("Wait for Monday", f.kept())
        }
        // An answered card before the result, then held again: kept, apart.
        sent().let { f ->
            f.client.received("mac", frame("held_task", held.copy(stateWire = "answered", revision = 4, answer = "Wait for Monday")))
            assertEquals("Wait for Monday", f.kept())
            val again = held.copy(revision = 5)
            f.client.merge(again)
            assertEquals(HeldTaskEarlier("Wait for Monday", HeldTaskEarlierReason.RequestChanged), f.client.earlier(again))
        }
    }

    @Test fun the_matching_result_drops_only_the_text_it_sent() {
        val f = answering()
        f.client.edit(held, " Wait for Monday ")
        val id = f.client.answer(held, HeldTaskAnswer.Text(" Wait for Monday "))!!
        val answered = held.copy(stateWire = "answered", revision = 4, answer = "Wait for Monday")
        // The bridge sends the answered card before the result.
        f.client.received("mac", frame("held_task", answered))
        f.client.received("mac", frame("held_task.result", answered, id))
        assertNull(f.kept(answered))
        // Another answer's result leaves the text.
        val g = answering()
        g.client.edit(held, "Wait for Monday")
        val agent = g.client.answer(held, HeldTaskAnswer.Agent)!!
        g.client.received("mac", frame("held_task.result", answered.copy(answer = "Asked the agent to look into it."), agent))
        assertEquals("Wait for Monday", g.kept(answered))
    }

    @Test fun a_result_after_a_newer_edit_keeps_the_newer_text() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        val id = f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday"))!!
        f.client.edit(held, "Wait for Tuesday")
        f.client.received("mac", frame("held_task.result", held.copy(stateWire = "answered", revision = 4, answer = "Wait for Monday"), id))
        assertEquals("Wait for Tuesday", f.kept())
    }

    @Test fun a_late_result_after_a_timeout_and_a_newer_edit_keeps_the_newer_text() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        val id = f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday"))!!
        f.timers.single().second()
        // The sync still lists it as held: the person edits again.
        f.client.synced("mac")
        assertTrue(f.client.canAnswer(held))
        f.client.edit(held, "Wait for Tuesday")
        assertFalse(f.client.draftAttempted(held))
        f.client.received("mac", frame("held_task.result", held.copy(stateWire = "answered", revision = 4, answer = "Wait for Monday"), id))
        assertEquals("Wait for Tuesday", f.kept())
    }

    @Test fun text_a_send_was_tried_with_stays_tried_when_used_again_after_a_reconnect() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        assertNotNull(f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday")))
        // The connection ends before the host confirms; the next one still lists it as held.
        f.connected = false
        f.client.ended("mac")
        f.connected = true
        f.client.authenticated("mac", answers = true)
        f.client.merge(held)
        f.client.synced("mac")
        assertEquals("Checked: mac still lists it as waiting for you.", f.client.notes[held.key])
        assertTrue(f.client.useEarlier(held))
        assertEquals("Wait for Monday", f.client.draftFor(held))
        // It may have reached the agent: not labelled as never sent.
        assertTrue(f.client.draftAttempted(held))
        assertEquals(1, f.sent.size)
        // An edit is a new text, not sent yet.
        f.client.edit(held, "Wait for Tuesday")
        assertFalse(f.client.draftAttempted(held))
        assertEquals(1, f.sent.size)
    }

    @Test fun text_a_send_was_tried_with_stays_tried_when_used_again_after_the_request_changed() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        val id = f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday"))!!
        f.client.received("mac", f.error(id, "conflict"))
        val newer = held.copy(revision = 4, reason = "Asked again")
        f.client.merge(newer)
        f.client.synced("mac")
        assertEquals(HeldTaskEarlier("Wait for Monday", HeldTaskEarlierReason.RequestChanged), f.client.earlier(newer))
        assertTrue(f.client.useEarlier(newer))
        assertEquals("Wait for Monday", f.client.draftFor(newer))
        assertTrue(f.client.draftAttempted(newer))
        assertEquals(1, f.sent.size)
    }

    @Test fun the_same_text_kept_once_stays_tried_if_a_send_was_tried_with_it() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        assertNotNull(f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday")))
        f.connected = false; f.client.ended("mac")
        f.connected = true; f.client.authenticated("mac", answers = true); f.client.synced("mac")
        // The person types the same words again, and the connection changes again before Send.
        f.client.edit(held, "Wait for Monday")
        assertFalse(f.client.draftAttempted(held))
        f.client.authenticated("mac", answers = true); f.client.synced("mac")
        assertTrue(f.client.useEarlier(held))
        assertNull(f.client.earlier(held))
        assertTrue(f.client.draftAttempted(held))
        assertEquals(1, f.sent.size)
    }

    @Test fun a_late_result_after_the_connection_ended_clears_no_text() {
        val f = answering()
        f.client.edit(held, "Wait for Monday")
        val id = f.client.answer(held, HeldTaskAnswer.Text("Wait for Monday"))!!
        f.connected = false; f.client.ended("mac")
        f.client.received("mac", frame("held_task.result", held.copy(stateWire = "answered", revision = 4, answer = "Wait for Monday"), id))
        assertEquals("Wait for Monday", f.kept(held.copy(stateWire = "answered", revision = 4, answer = "Wait for Monday")))
        assertEquals(1, f.sent.size)
    }

    @Test fun another_agent_with_the_same_host_and_task_never_gets_the_text() {
        val sent = mutableListOf<HeldTaskRequest>()
        val client = HeldTaskClient(send = { sent += it; true }, connected = { true }, accepts = { true }, changed = {}, later = { _, _ -> })
        val other = held.copy(agent = "lab/other", conversation = "home.lab.other")
        client.authenticated("mac", answers = true)
        listOf(held, other).forEach { client.merge(it) }
        client.synced("mac")
        client.edit(held, "For main only")
        assertEquals("", client.draftFor(other)); assertNull(client.earlier(other))
        assertFalse(client.useEarlier(other))
        assertEquals("For main only", client.draftFor(held))
        assertTrue(sent.isEmpty())
    }

    @Test fun a_newer_card_of_the_task_in_another_conversation_keeps_the_text_apart() {
        val sent = mutableListOf<HeldTaskRequest>()
        val client = HeldTaskClient(send = { sent += it; true }, connected = { true }, accepts = { true }, changed = {}, later = { _, _ -> })
        client.authenticated("mac", answers = true); client.merge(held); client.synced("mac")
        client.edit(held, "For home only")
        val moved = held.copy(conversation = "other.conv", revision = 4)
        client.merge(moved)
        assertEquals(moved, client.cards[held.key])
        // Not in Your answer: shown apart, for the person to review.
        assertEquals("", client.draftFor(moved))
        assertEquals(HeldTaskEarlier("For home only", HeldTaskEarlierReason.RequestChanged), client.earlier(moved))
        // The card shown before is neither typed into nor answered.
        client.edit(held, "x")
        assertEquals("", client.draftFor(moved))
        assertNull(client.answer(held, HeldTaskAnswer.Text("For home only")))
        assertTrue(sent.isEmpty())
        // Only the person's Use puts it in Your answer; it sends nothing.
        assertTrue(client.useEarlier(moved))
        assertEquals("For home only", client.draftFor(moved))
        assertTrue(sent.isEmpty())
    }

    @Test fun forgetting_the_home_or_the_host_drops_the_text_and_a_new_client_has_none() {
        val newer = held.copy(revision = 4)
        fun typed(): Fixture = answering().apply {
            client.edit(held, "Earlier words")
            client.merge(newer)
            client.edit(newer, "Current words")
        }
        typed().let { f ->
            f.client.forgetConversation("mac", "home.lab.main")
            f.ready(newer)
            assertNull(f.kept(newer))
        }
        typed().let { f ->
            // Forgetting the host, clearing the cache, or pairing it again.
            f.client.forget("mac")
            f.client.authenticated("mac", answers = true); f.client.merge(newer); f.client.synced("mac")
            assertNull(f.kept(newer))
        }
        // The app's model ended: nothing is kept elsewhere.
        assertNull(answering().kept())
    }

    @Test fun the_log_never_carries_the_text() {
        val f = answering()
        f.client.edit(held, "SECRET words")
        val id = f.client.answer(held, HeldTaskAnswer.Text("SECRET words"))!!
        val unreadable = frame("held_task.result", held.copy(revision = 4), id)
        unreadable.getJSONObject("held_task").put("resumable", "false")
        f.client.received("mac", unreadable)
        f.client.merge(held.copy(host = "elsewhere"))
        assertTrue(f.dropped.isNotEmpty())
        assertTrue(f.dropped.none { "SECRET" in it })
    }
}
