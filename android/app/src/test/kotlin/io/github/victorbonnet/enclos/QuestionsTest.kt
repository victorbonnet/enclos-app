package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class QuestionsTest {
    private val question = QuestionInfo("mac", "q-1", "What should the hub show?", listOf("Results (Recommended)", "Ranking"), false,
        "c-1", "lab/main", QuestionState.Pending, 0)

    @Test fun selection_is_explicit_and_other_excludes_listed_choices() {
        val empty = QuestionSelection()
        assertNull(empty.answer(question))
        val first = empty.choose(0, false)
        assertEquals(QuestionAnswer.Choices(listOf(0)), first.answer(question))
        assertEquals(setOf(1), first.choose(1, false).indices)
        val other = first.chooseOther().copy(text = "A different view")
        assertTrue(other.indices.isEmpty())
        assertEquals(QuestionAnswer.Text("A different view"), other.answer(question))
        assertFalse(other.choose(1, false).other)
        assertEquals(QuestionAnswer.Choices(listOf(1)), other.choose(1, false).answer(question))
        assertNull(empty.chooseOther().answer(question))
    }

    @Test fun multiple_answers_are_distinct_in_definition_order_and_can_be_toggled_off() {
        val many = question.copy(multiple = true)
        val selected = QuestionSelection().choose(1, true).choose(0, true)
        assertEquals(QuestionAnswer.Choices(listOf(0, 1)), selected.answer(many))
        assertEquals(setOf(0), selected.choose(1, true).indices)
        assertNull(selected.choose(1, true).choose(0, true).answer(many))
        val textOnly = question.copy(choices = emptyList())
        assertNull(QuestionSelection().answer(textOnly))
        assertEquals(QuestionAnswer.Text("Custom"), QuestionSelection(text = "Custom").answer(textOnly))
    }

    @Test fun malformed_states_choices_control_sentinels_and_encoded_sizes_are_rejected() {
        question.validate()
        for (invalid in listOf(
            question.copy(choices = List(13) { "Choice" }), question.copy(choices = listOf(" ")),
            question.copy(choices = emptyList(), multiple = true), question.copy(text = "\u0000skipped"),
            question.copy(text = "🤖".repeat(6144)), question.copy(text = "\n".repeat(12 * 1024) + "x"),
            question.copy(state = QuestionState.Answered), question.copy(answer = QuestionAnswer.Skip),
            question.copy(state = QuestionState.Skipped, answer = QuestionAnswer.Text("No")), question.copy(revision = -1),
        )) assertTrue(invalid.toString().take(80), runCatching { invalid.validate() }.isFailure)
        for (answer in listOf(
            QuestionAnswer.Choices(emptyList()), QuestionAnswer.Choices(listOf(0, 1)), QuestionAnswer.Choices(listOf(9)),
            QuestionAnswer.Choices(listOf(0, 0)), QuestionAnswer.Text(" \n\t"), QuestionAnswer.Text("\u0000skipped"),
            QuestionAnswer.Text("answer\u001b[2J"), QuestionAnswer.Text("x".repeat(8192)), QuestionAnswer.Text("\uD800"),
        )) assertTrue(runCatching { question.validateAnswer(answer) }.isFailure)
        question.validateAnswer(QuestionAnswer.Text("Line one\nLine two with detail"))
        question.validateAnswer(QuestionAnswer.Skip)
    }

    /** The cases of enclos-protocol's `hidden_characters_and_duplicate_labels_are_refused`, with its verdicts. */
    @Test fun hidden_characters_and_duplicate_labels_are_refused_as_the_protocol_refuses_them() {
        for (choices in listOf(
            listOf("Deny‮wolla", "Allow"), listOf("Allow​", "Allow"), listOf("Allow", "Allow"),
            listOf("Two\nlines"), listOf("Tab\there"),
        )) assertTrue(choices.toString(), runCatching { question.copy(choices = choices).validate() }.isFailure)
        for (text in listOf("Which⁦?", "Which\r\n?", "Which\t?", "﻿Which?")) {
            assertTrue(text, runCatching { question.copy(text = text).validate() }.isFailure)
        }
        question.copy(text = "Which one?\nPick carefully.").validate()
        for (text in listOf("ok‍", "a\tb", "a\r\nb", "­")) {
            assertTrue(text, runCatching { question.validateAnswer(QuestionAnswer.Text(text)) }.isFailure)
        }
    }

    @Test fun the_cache_drops_questions_of_conversations_it_no_longer_keeps_and_unreadable_rows() {
        val kept = Conversation("mac", "lab/main", "c-1")
        // org.json is Android's: the rows' text stands for the parsed question here.
        val parsed = mapOf("one" to question, "gone" to question.copy(id = "q-2", conversation = "c-gone"), "pi" to question.copy(host = "pi"))
        val rows = listOf(Triple("mac", "q-1", "one"), Triple("mac", "q-2", "gone"), Triple("pi", "q-1", "pi"), Triple("mac", "q-3", "{not json"))
        val (questions, stale) = keptQuestions(rows, listOf(kept)) { _, info -> parsed[info] }
        assertEquals(listOf(question), questions)
        assertEquals(listOf(QuestionKey("mac", "q-2"), QuestionKey("pi", "q-1"), QuestionKey("mac", "q-3")), stale)
    }

    @Test fun a_pasted_line_break_is_sent_as_a_newline_and_a_tab_is_not_sent() {
        val textOnly = question.copy(choices = emptyList())
        assertEquals(QuestionAnswer.Text("one\ntwo\nthree"), QuestionSelection(text = "one\r\ntwo\rthree").answer(textOnly))
        assertNull(QuestionSelection(text = "one\ttwo").answer(textOnly))
    }

    @Test fun newer_terminal_metadata_wins_over_old_cache_and_can_never_reopen_or_change_definition() {
        val answered = question.copy(state = QuestionState.Answered, revision = 2, answer = QuestionAnswer.Choices(listOf(1)))
        assertEquals(answered, newestQuestion(question, answered))
        assertEquals(answered, newestQuestion(answered, question))
        assertEquals(answered, newestQuestion(answered, question.copy(revision = 3)))
        assertEquals(answered, newestQuestion(answered, answered.copy(answer = QuestionAnswer.Text("Other"), revision = 3)))
        assertEquals(question, newestQuestion(question, question.copy(text = "A replacement", revision = 4)))
        assertEquals(answered, newestQuestion(answered, answered.copy(state = QuestionState.Expired, answer = null, revision = 3)))
    }

    @Test fun origin_and_conversation_match_including_group_membership_and_host_scope() {
        val direct = Conversation("mac", "lab/main", "c-1")
        assertTrue(question.belongsTo(direct))
        assertFalse(question.belongsTo(direct.copy(host = "pi")))
        assertFalse(question.belongsTo(direct.copy(id = "c-2")))
        assertFalse(question.belongsTo(direct.copy(agent = "lab/other")))
        val group = direct.copy(agent = "lab", members = listOf("lab/main", "lab/other"))
        assertTrue(question.belongsTo(group))
        assertFalse(question.copy(agent = "lab/outsider").belongsTo(group))
        assertNotEquals(question.key, question.copy(host = "pi").key)
    }
}
