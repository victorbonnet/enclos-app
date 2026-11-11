package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class QuestionClientTest {
    private val question = QuestionInfo("mac", "q-1", "Choose", listOf("One", "Two"), false, "c-1", "lab/main", QuestionState.Pending, 0)
    private class Fixture {
        var connected = true
        var sending = true
        val sent = mutableListOf<QuestionRequest>()
        val changed = mutableListOf<QuestionInfo>()
        val timers = mutableListOf<() -> Unit>()
        val conversation = Conversation("mac", "lab/main", "c-1")
        val resyncs = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val client = QuestionClient(send = { sent += it; sending }, connected = { connected }, accepts = { it.belongsTo(conversation) },
            changed = { changed += it }, later = { _, callback -> timers += callback },
            resync = { resyncs += it; sending }, dropped = { dropped += it })
        fun ready(question: QuestionInfo) {
            client.authenticated("mac")
            client.merge(question)
            client.synced("mac")
        }
    }

    @Test fun broadcast_updates_canonical_state_but_only_exact_result_confirms_submission() {
        val f = Fixture(); f.ready(question)
        val answer = QuestionAnswer.Choices(listOf(0))
        val request = f.client.submit(question, answer)!!
        assertEquals(question, f.client.questions[question.key])
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        assertEquals(1, f.sent.size)
        val settled = question.copy(state = QuestionState.Answered, revision = 1, answer = answer)
        f.client.merge(settled)
        assertEquals(settled, f.client.questions[question.key])
        assertTrue(question.key in f.client.pending)
        assertFalse(question.key in f.client.receipts)
        assertFalse(f.client.result("pi", request, settled.copy(host = "pi")))
        assertFalse(f.client.result("mac", "another-request", settled))
        assertFalse(f.client.result("mac", request, settled.copy(id = "other")))
        assertFalse(f.client.result("mac", request, settled.copy(conversation = "c-2")))
        assertTrue(f.client.result("mac", request, settled))
        assertFalse(question.key in f.client.pending)
        assertEquals(request, f.client.receipts[question.key])
    }

    @Test fun another_phones_answer_is_canonical_but_never_receipt_for_ours() {
        val f = Fixture(); f.ready(question)
        val request = f.client.submit(question, QuestionAnswer.Choices(listOf(0)))!!
        val other = question.copy(state = QuestionState.Answered, revision = 1, answer = QuestionAnswer.Choices(listOf(1)))
        assertFalse(f.client.result("mac", request, other))
        assertEquals(other, f.client.questions[question.key])
        assertTrue(question.key in f.client.pending)
        assertFalse(f.client.failure("pi", request, "No"))
        assertTrue(f.client.failure("mac", request, "Already answered"))
        assertFalse(question.key in f.client.pending)
        assertFalse(question.key in f.client.receipts)
        assertNull(f.client.submit(other, QuestionAnswer.Skip))
    }

    @Test fun wrong_definition_revision_and_state_cannot_acknowledge_a_request() {
        val f = Fixture(); f.ready(question)
        val request = f.client.submit(question, QuestionAnswer.Skip)!!
        assertFalse(f.client.result("mac", request, question))
        assertFalse(f.client.result("mac", request, question.copy(state = QuestionState.Skipped, revision = 0)))
        assertFalse(f.client.result("mac", request, question.copy(text = "Replacement", state = QuestionState.Skipped, revision = 1)))
        assertTrue(question.key in f.client.pending)
        assertEquals(question, f.client.questions[question.key])
        assertTrue(f.client.result("mac", request, question.copy(state = QuestionState.Skipped, revision = 1)))
    }

    @Test fun offline_stale_openings_invalid_answers_and_unsynced_cache_never_send() {
        val f = Fixture()
        f.client.merge(question, cached = true)
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        f.ready(question)
        f.connected = false
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        f.connected = true
        assertNull(f.client.submit(question, QuestionAnswer.Choices(emptyList())))
        val newer = question.copy(revision = 2)
        f.client.merge(newer)
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        assertTrue(f.sent.isEmpty())
        assertNotNull(f.client.submit(newer, QuestionAnswer.Skip))
    }

    @Test fun an_older_result_cannot_acknowledge_after_a_newer_terminal_update() {
        val f = Fixture(); f.ready(question)
        val request = f.client.submit(question, QuestionAnswer.Skip)!!
        val expired = question.copy(state = QuestionState.Expired, revision = 3)
        f.client.merge(expired)
        assertFalse(f.client.result("mac", request, question.copy(state = QuestionState.Skipped, revision = 1)))
        assertEquals(expired, f.client.questions[question.key])
        assertTrue(question.key in f.client.pending)
        assertFalse(question.key in f.client.receipts)
    }

    @Test fun timeout_never_resends_and_late_exact_result_can_resolve_ambiguous_outcome() {
        val f = Fixture(); f.ready(question)
        val request = f.client.submit(question, QuestionAnswer.Skip)!!
        f.timers.single()()
        assertTrue(f.client.uncertain[question.key] == true)
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        assertEquals(1, f.sent.size)
        assertTrue(f.client.result("mac", request, question.copy(state = QuestionState.Skipped, revision = 1)))
        assertFalse(question.key in f.client.uncertain)
        f.timers.single()()
        assertFalse(question.key in f.client.uncertain)
    }

    @Test fun disconnect_requires_canonical_reconnect_sync_before_explicit_retry() {
        val f = Fixture(); f.ready(question)
        val request = f.client.submit(question, QuestionAnswer.Skip)!!
        f.client.ended("mac")
        assertFalse(question.key in f.client.pending)
        assertTrue(f.client.uncertain[question.key] == true)
        assertFalse(f.client.result("mac", request, question.copy(state = QuestionState.Skipped, revision = 1)))
        f.client.merge(question, cached = true)
        f.client.authenticated("mac")
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        f.client.merge(question)
        f.client.synced("mac")
        assertFalse(question.key in f.client.uncertain)
        assertNotNull(f.client.submit(question, QuestionAnswer.Skip))
        assertEquals(2, f.sent.size)
    }

    @Test fun bridge_timeout_before_local_timer_requires_fresh_reconnect_snapshot_before_retry() {
        val f = Fixture(); f.ready(question)
        val request = f.client.submit(question, QuestionAnswer.Skip)!!
        assertTrue(f.client.failure("mac", request, "The agent did not confirm the answer.", "unconfirmed"))
        assertFalse(question.key in f.client.pending)
        assertTrue(f.client.uncertain[question.key] == true)
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        f.client.merge(question)
        f.timers.single()()
        assertTrue(f.client.uncertain[question.key] == true)
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        assertEquals(1, f.sent.size)
        f.client.ended("mac")
        f.client.authenticated("mac")
        f.client.merge(question, cached = true)
        f.client.synced("mac")
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        f.client.ended("mac")
        f.client.authenticated("mac")
        f.client.merge(question)
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        f.client.synced("mac")
        assertEquals(1, f.sent.size)
        assertNotNull(f.client.submit(question, QuestionAnswer.Skip))
        assertEquals(2, f.sent.size)
    }

    @Test fun definitive_rejections_finish_without_an_ambiguous_outcome_lock() {
        // `agent_unavailable` included: the bridge refused before writing to the agent.
        for (code in listOf("bad_message", "conflict", "unsupported", "not_found", "agent_unavailable")) {
            val f = Fixture(); f.ready(question)
            val request = f.client.submit(question, QuestionAnswer.Skip)!!
            assertTrue(f.client.failure("mac", request, "Answer rejected.", code))
            assertFalse(question.key in f.client.pending)
            assertFalse(question.key in f.client.uncertain)
            assertNotNull(f.client.submit(question, QuestionAnswer.Skip))
        }
    }

    @Test fun an_unconfirmed_or_unknown_code_locks_until_a_fresh_sync() {
        for (code in listOf("unconfirmed", "a_code_from_a_later_bridge", "")) {
            val f = Fixture(); f.ready(question)
            val request = f.client.submit(question, QuestionAnswer.Skip)!!
            assertTrue(f.client.failure("mac", request, "Not confirmed.", code))
            assertTrue(code, f.client.uncertain[question.key] == true)
            assertNull(f.client.submit(question, QuestionAnswer.Skip))
        }
    }

    @Test fun check_again_settles_an_unconfirmed_outcome_over_the_open_connection() {
        val f = Fixture(); f.ready(question)
        assertFalse(f.client.canCheckAgain(question))
        val request = f.client.submit(question, QuestionAnswer.Skip)!!
        assertFalse(f.client.checkAgain(question))
        f.timers.single()()
        assertTrue(f.client.canCheckAgain(question))
        assertTrue(f.client.checkAgain(question))
        assertEquals(listOf("mac"), f.resyncs)
        // While the sync runs, the card waits and cannot ask twice.
        assertFalse(f.client.canCheckAgain(question))
        assertEquals("Checking the outcome with mac…", f.client.unavailable(question))
        assertNull(f.client.submit(question, QuestionAnswer.Skip))
        // A sync that did not send the question again leaves the doubt.
        f.client.synced("mac")
        assertTrue(f.client.uncertain[question.key] == true)
        assertTrue(f.client.checkAgain(question))
        f.client.merge(question)
        f.client.synced("mac")
        assertFalse(question.key in f.client.uncertain)
        assertFalse(f.client.result("mac", request, question.copy(state = QuestionState.Skipped, revision = 1)))
        assertNotNull(f.client.submit(question, QuestionAnswer.Skip))
        assertEquals(2, f.sent.size)
    }

    @Test fun check_again_needs_a_connection_and_a_sent_sync() {
        val f = Fixture(); f.ready(question)
        val request = f.client.submit(question, QuestionAnswer.Skip)!!
        assertTrue(f.client.failure("mac", request, "Not confirmed.", "unconfirmed"))
        f.connected = false
        assertFalse(f.client.checkAgain(question))
        f.connected = true
        f.sending = false
        assertFalse(f.client.checkAgain(question))
        assertTrue(f.client.canCheckAgain(question))
        assertEquals("The outcome is unconfirmed. Check again before answering again.", f.client.unavailable(question))
    }

    @Test fun a_dropped_question_says_why_without_its_text() {
        val f = Fixture()
        assertFalse(f.client.merge(question.copy(conversation = "c-2")))
        assertFalse(f.client.merge(question.copy(text = "Secret\u202E")))
        assertEquals(2, f.dropped.size)
        assertTrue(f.dropped[1], f.dropped[1].endsWith("Invalid question text."))
        assertTrue(f.dropped.none { "Secret" in it || "Choose" in it })
    }

    @Test fun failed_send_is_not_pending_and_multi_choice_receipt_uses_definition_order() {
        val f = Fixture(); val many = question.copy(multiple = true); f.ready(many)
        f.sending = false
        assertNull(f.client.submit(many, QuestionAnswer.Skip))
        assertFalse(many.key in f.client.pending)
        f.sending = true
        val request = f.client.submit(many, QuestionAnswer.Choices(listOf(1, 0)))!!
        assertEquals(QuestionAnswer.Choices(listOf(0, 1)), f.sent.last().answer)
        assertTrue(f.client.result("mac", request, many.copy(state = QuestionState.Answered, revision = 1, answer = QuestionAnswer.Choices(listOf(1, 0)))))
    }

    @Test fun stale_cache_cannot_reopen_terminal_and_forgetting_host_removes_its_controls() {
        val f = Fixture(); f.ready(question)
        val settled = question.copy(state = QuestionState.Expired, revision = 3)
        f.client.merge(settled)
        f.client.merge(question, cached = true)
        assertEquals(settled, f.client.current(f.conversation, question.id))
        assertNull(f.client.current(f.conversation.copy(host = "pi"), question.id))
        assertFalse(f.client.merge(question.copy(host = "pi")))
        f.client.forget("mac")
        assertTrue(f.client.questions.isEmpty())
        assertTrue(f.client.pending.isEmpty())
    }
}
