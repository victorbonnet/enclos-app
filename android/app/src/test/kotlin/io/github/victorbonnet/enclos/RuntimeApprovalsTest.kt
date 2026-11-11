package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class RuntimeApprovalsTest {
    private val approval = RuntimeApprovalDefinition("printf '%s\\n' '**literal**'", "The command changes a local file.", RuntimeApprovalAction.entries, patterns = listOf("overwrite a file"))
    private val question = QuestionInfo("mac", "approval-1", "Command approval", emptyList(), false, "c-1", "lab/main", QuestionState.Pending, 0, approval = approval)

    @Test fun only_offered_typed_actions_can_answer_an_approval() {
        question.validate()
        for (action in RuntimeApprovalAction.entries) question.validateAnswer(QuestionAnswer.Approval(action))
        for (answer in listOf(QuestionAnswer.Text("/approve always"), QuestionAnswer.Choices(listOf(0)), QuestionAnswer.Skip)) {
            assertTrue(runCatching { question.validateAnswer(answer) }.isFailure)
        }
        val restricted = question.copy(approval = approval.copy(actions = listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Deny), smartDenied = true))
        restricted.validate()
        assertTrue(runCatching { restricted.validateAnswer(QuestionAnswer.Approval(RuntimeApprovalAction.Session)) }.isFailure)
        assertTrue(runCatching { restricted.validateAnswer(QuestionAnswer.Approval(RuntimeApprovalAction.Always)) }.isFailure)
        assertNull(QuestionSelection(text = "/approve").answer(question))
    }

    @Test fun actions_are_canonical_and_smart_denial_cannot_enable_pattern_grants() {
        for (actions in listOf(
            emptyList(), listOf(RuntimeApprovalAction.Once), listOf(RuntimeApprovalAction.Session, RuntimeApprovalAction.Deny),
            listOf(RuntimeApprovalAction.Deny, RuntimeApprovalAction.Once),
            listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Once, RuntimeApprovalAction.Deny),
            listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Always, RuntimeApprovalAction.Deny),
        )) assertTrue(runCatching { approval.copy(actions = actions).validate() }.isFailure)
        assertTrue(runCatching { approval.copy(smartDenied = true).validate() }.isFailure)
        approval.copy(actions = listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Session, RuntimeApprovalAction.Deny)).validate()
        val denyOnly = question.copy(approval = approval.copy(actions = listOf(RuntimeApprovalAction.Deny), smartDenied = true))
        denyOnly.validate()
        denyOnly.validateAnswer(QuestionAnswer.Approval(RuntimeApprovalAction.Deny))
        assertTrue(runCatching { denyOnly.validateAnswer(QuestionAnswer.Approval(RuntimeApprovalAction.Once)) }.isFailure)
    }

    @Test fun ordinary_questions_and_approval_intros_never_cross_render_or_answer_modes() {
        val ordinary = question.copy(approval = null, choices = listOf("Approve"))
        ordinary.validate()
        assertTrue(runCatching { ordinary.validateAnswer(QuestionAnswer.Approval(RuntimeApprovalAction.Once)) }.isFailure)
        assertTrue(ordinary.matchesMessageKind("question"))
        assertFalse(ordinary.matchesMessageKind("runtime_approval"))
        assertTrue(question.matchesMessageKind("runtime_approval"))
        assertFalse(question.matchesMessageKind("question"))
        assertFalse(question.matchesMessageKind("reply"))
        assertTrue(runCatching { question.copy(choices = listOf("Allow once")).validate() }.isFailure)
        assertTrue(runCatching { question.copy(multiple = true).validate() }.isFailure)
    }

    @Test fun independent_native_actions_need_scope_semantics_without_inventing_session() {
        val offered = listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Always, RuntimeApprovalAction.Deny)
        assertTrue(runCatching { approval.copy(actions = offered).validate() }.isFailure)
        val scopes = RuntimeApprovalScopes("Always remembers exact argv and working directory.", "Use native request native-7.")
        val native = question.copy(approval = approval.copy(actions = offered, scopes = scopes))
        native.validate()
        native.validateAnswer(QuestionAnswer.Approval(RuntimeApprovalAction.Always))
        assertTrue(runCatching { native.validateAnswer(QuestionAnswer.Approval(RuntimeApprovalAction.Session)) }.isFailure)
        assertTrue(runCatching { native.copy(approval = native.approval!!.copy(smartDenied = true)).validate() }.isFailure)
        assertEquals(question, newestQuestion(question, question.copy(revision = 1, approval = approval.copy(scopes = scopes))))
        assertEquals(native, newestQuestion(native, native.copy(revision = 1, approval = native.approval!!.copy(scopes = scopes.copy(description = "A different persistence target")))))
        for (invalid in listOf(scopes.copy(description = ""), scopes.copy(fallback = "bad\u0000value"), scopes.copy(description = "x".repeat(8 * 1024)))) {
            assertTrue(runCatching { invalid.validate() }.isFailure)
        }
    }

    @Test fun without_scopes_session_or_always_names_patterns_with_scopes_the_description_stands_in() {
        val scopes = RuntimeApprovalScopes("Always remembers exact argv and working directory.", "Use native request native-7.")
        val offered = listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Always, RuntimeApprovalAction.Deny)
        // Legacy: Session or Always needs patterns; Once and Deny alone do not.
        assertTrue(runCatching { approval.copy(patterns = emptyList()).validate() }.isFailure)
        approval.copy(actions = listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Deny), patterns = emptyList()).validate()
        // Scoped: with or without patterns.
        approval.copy(actions = offered, scopes = scopes, patterns = emptyList()).validate()
        approval.copy(actions = offered, scopes = scopes, patterns = listOf("argv")).validate()
        approval.copy(scopes = scopes, patterns = emptyList()).validate()
        for (invalid in listOf(listOf(""), listOf("a\u202Eb"), listOf("a", "a"), (0..MAX_APPROVAL_PATTERNS).map { "p$it" })) {
            assertTrue(runCatching { approval.copy(patterns = invalid).validate() }.isFailure)
            assertTrue(runCatching { approval.copy(actions = offered, scopes = scopes, patterns = invalid).validate() }.isFailure)
        }
        assertTrue("\"patterns\":[\"overwrite a file\"]" in approval.jsonText())
        assertFalse("patterns" in approval.copy(scopes = scopes, patterns = emptyList()).jsonText())
    }

    @Test fun a_scoped_grant_is_confirmed_with_the_runtimes_description_never_with_patterns() {
        val scopes = RuntimeApprovalScopes("Always remembers exact argv.", "Use native request native-7.")
        val offered = listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Always, RuntimeApprovalAction.Deny)
        val scoped = approval.copy(actions = offered, scopes = scopes, patterns = emptyList())
        val always = grantConfirmation(scoped, RuntimeApprovalAction.Always)!!
        assertTrue(always, scopes.shownDescription in always && SCOPES_FRAME in always)
        assertFalse(always, "matching" in always || "  " in always)
        assertNull(grantConfirmation(scoped, RuntimeApprovalAction.Once))
        assertNull(grantConfirmation(scoped, RuntimeApprovalAction.Deny))
        // Legacy: both grants beyond this command are confirmed, naming the patterns.
        assertTrue("overwrite a file" in grantConfirmation(approval, RuntimeApprovalAction.Session)!!)
        assertTrue("overwrite a file" in grantConfirmation(approval, RuntimeApprovalAction.Always)!!)
        // The app's words frame the runtime's description, and Once keeps its own line.
        assertEquals(listOf("Allow once applies only to this command request.", SCOPES_FRAME), runtimeApprovalScopeNotes(scoped))
        assertEquals(listOf(SCOPES_FRAME), runtimeApprovalScopeNotes(scoped.copy(actions = listOf(RuntimeApprovalAction.Deny))))
        assertTrue(runtimeApprovalScopeNotes(approval).any { "every command matching: overwrite a file" in it })
    }

    @Test fun the_runtimes_own_answer_instructions_show_only_where_the_card_cannot_answer() {
        val scopes = RuntimeApprovalScopes("Always remembers exact argv.", "Reply \u202E/approve native-7.")
        val scoped = question.copy(approval = approval.copy(scopes = scopes))
        val note = nativeAnswerNote(scoped, "Connect to mac to answer.", pending = false)!!
        assertTrue(note, "⟨U+202E⟩/approve native-7" in note)
        assertNull(nativeAnswerNote(scoped, null, pending = false))
        assertNull(nativeAnswerNote(scoped, "Waiting for the agent to confirm…", pending = true))
        assertNull(nativeAnswerNote(scoped.copy(state = QuestionState.Expired), "This question is expired.", pending = false))
        assertNull(nativeAnswerNote(question, "Connect to mac to answer.", pending = false))
    }

    @Test fun the_device_declares_native_scopes_in_its_auth() {
        val fields = authFields("device-1", "c2ln").toMap()
        assertEquals("device-1", fields["device"])
        assertEquals("c2ln", fields["sig"])
        assertEquals(listOf("runtime_approval_scopes"), fields["capabilities"])
    }

    @Test fun a_native_scope_description_is_shown_with_its_hidden_characters_written_out() {
        val scopes = RuntimeApprovalScopes("Always remembers \u202Eexact argv\u200B.", "Use native request native-7.")
        scopes.validate()
        assertEquals("Always remembers ⟨U+202E⟩exact argv⟨U+200B⟩.", scopes.shownDescription)
        assertEquals("Plain text.", RuntimeApprovalScopes("Plain text.", "Use native request.").shownDescription)
    }

    @Test fun complete_literal_command_reason_and_encoded_bounds_are_validated_without_truncation() {
        val literal = approval.copy(command = "printf '<script> **text**'\nnext\targument")
        question.copy(approval = literal).validate()
        assertEquals("printf '<script> **text**'\nnext\targument", literal.command)
        for (invalid in listOf(
            approval.copy(command = " "), approval.copy(reason = ""), approval.copy(command = "echo x\u0000"),
            approval.copy(reason = "Bad\u001b[2J"), approval.copy(command = "x".repeat(24 * 1024)),
            approval.copy(command = "\\".repeat(12 * 1024)), approval.copy(reason = "🤖".repeat(6144)),
        )) assertTrue(runCatching { question.copy(approval = invalid).validate() }.isFailure)
    }

    @Test fun resolved_is_neutral_terminal_only_for_approvals_and_never_a_receipt_for_an_action() {
        val resolved = question.copy(state = QuestionState.Resolved, revision = 1)
        resolved.validate()
        assertEquals("Resolved in the runtime", runtimeApprovalStatus(resolved))
        assertTrue(runCatching { question.copy(approval = null, state = QuestionState.Resolved).validate() }.isFailure)
        assertTrue(runCatching { question.copy(state = QuestionState.Skipped).validate() }.isFailure)
        assertTrue(runCatching { resolved.copy(answer = QuestionAnswer.Approval(RuntimeApprovalAction.Once)).validate() }.isFailure)
        val request = QuestionRequest("request", question, QuestionAnswer.Approval(RuntimeApprovalAction.Once))
        assertFalse(request.confirms(resolved))
        assertEquals(resolved, newestQuestion(resolved, question.copy(revision = 2)))
        for (action in RuntimeApprovalAction.entries) {
            val answered = question.copy(state = QuestionState.Answered, revision = 1, answer = QuestionAnswer.Approval(action))
            answered.validate()
            assertEquals(action.result, runtimeApprovalStatus(answered))
        }
    }

    @Test fun immutable_approval_scope_and_command_cannot_change_in_a_later_revision() {
        assertEquals(question, newestQuestion(question, question.copy(revision = 1, approval = approval.copy(command = "different"))))
        assertEquals(question, newestQuestion(question, question.copy(revision = 1, approval = approval.copy(actions = listOf(RuntimeApprovalAction.Once, RuntimeApprovalAction.Deny)))))
        assertEquals(question, newestQuestion(question, question.copy(revision = 1, approval = null)))
    }

    @Test fun approval_receipts_match_the_exact_action_host_request_and_canonical_terminal_state() {
        val sent = mutableListOf<QuestionRequest>()
        val timers = mutableListOf<() -> Unit>()
        val c = Conversation("mac", "lab/main", "c-1")
        val client = QuestionClient(send = { sent += it; true }, connected = { true }, accepts = { it.belongsTo(c) }, changed = {}, later = { _, callback -> timers += callback })
        client.authenticated("mac"); client.merge(question); client.synced("mac")
        assertNull(client.submit(question, QuestionAnswer.Text("/approve")))
        assertTrue(sent.isEmpty())
        val request = client.submit(question, QuestionAnswer.Approval(RuntimeApprovalAction.Session))!!
        assertEquals(1, sent.size)
        val answered = question.copy(state = QuestionState.Answered, revision = 1, answer = QuestionAnswer.Approval(RuntimeApprovalAction.Session))
        client.merge(answered)
        assertTrue(question.key in client.pending)
        assertFalse(client.result("pi", request, answered.copy(host = "pi")))
        assertFalse(client.result("mac", "other", answered))
        assertFalse(client.result("mac", request, answered.copy(answer = QuestionAnswer.Approval(RuntimeApprovalAction.Once))))
        assertTrue(question.key in client.pending)
        assertTrue(client.result("mac", request, answered))
        assertEquals(request, client.receipts[question.key])
        assertFalse(question.key in client.pending)
    }

    @Test fun deny_uses_the_same_ambiguous_outcome_fence_and_never_retries_automatically() {
        var sends = 0
        val client = QuestionClient(send = { sends++; true }, connected = { true }, accepts = { true }, changed = {}, later = { _, _ -> })
        client.authenticated("mac"); client.merge(question); client.synced("mac")
        val request = client.submit(question, QuestionAnswer.Approval(RuntimeApprovalAction.Deny))!!
        client.failure("mac", request, "The runtime did not confirm.", "agent_unavailable")
        assertNull(client.submit(question, QuestionAnswer.Approval(RuntimeApprovalAction.Deny)))
        assertEquals(1, sends)
        assertTrue(client.uncertain[question.key] == true)
    }
}
