package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class PolicyRequestActionsTest {
    private val request = PolicyRequest("pi", "request-1", "hermes", "dev", "home", "write", "/work", false,
        "Write a file", 1, "pending", "Mount the workspace", false, "revision-1", true, null)

    private fun allowed(action: PolicyRequestAction, value: PolicyRequest = request, connected: Boolean = true,
        fresh: Boolean = true, supportsClose: Boolean = true, busy: Boolean = false) =
        action.allowed(value, connected, fresh, supportsClose, busy)

    @Test fun closing_sends_its_own_command_without_an_approval_or_denial() {
        assertEquals("approval.close", PolicyRequestAction.Close.type)
        assertEquals(mapOf("request" to "request-1"), PolicyRequestAction.Close.fields(request).toMap())
        assertEquals("approval.decide", PolicyRequestAction.Deny.type)
        assertEquals(mapOf("request" to "request-1", "revision" to "revision-1", "approve" to false),
            PolicyRequestAction.Deny.fields(request).toMap())
        assertEquals(true, PolicyRequestAction.Approve.fields(request).toMap()["approve"])
    }

    @Test fun unavailable_and_failed_requests_can_be_closed_without_enabling_approval() {
        val unavailable = request.copy(available = false)
        assertTrue(allowed(PolicyRequestAction.Close, unavailable))
        assertTrue(allowed(PolicyRequestAction.Deny, unavailable))
        assertFalse(allowed(PolicyRequestAction.Approve, unavailable))
        val failed = unavailable.copy(state = "failed", reason = "Restart failed after saving policy")
        assertTrue(allowed(PolicyRequestAction.Close, failed))
        assertFalse(allowed(PolicyRequestAction.Approve, failed))
        assertFalse(allowed(PolicyRequestAction.Deny, failed))
    }

    @Test fun closing_requires_a_fresh_connection_and_explicit_host_support() {
        assertFalse(allowed(PolicyRequestAction.Close, connected = false))
        assertFalse(allowed(PolicyRequestAction.Close, fresh = false))
        assertFalse(allowed(PolicyRequestAction.Close, supportsClose = false))
        // Older hosts still allow their existing decisions after syncing.
        assertTrue(allowed(PolicyRequestAction.Approve, supportsClose = false))
        assertTrue(allowed(PolicyRequestAction.Deny, supportsClose = false))
    }

    @Test fun no_action_can_race_an_inflight_action_or_change_a_terminal_request() {
        for (action in PolicyRequestAction.entries) {
            assertFalse(allowed(action, busy = true))
            for (state in listOf("applying", "approved", "denied", "closed", "diagnostic")) {
                assertFalse("$action while $state", allowed(action, request.copy(state = state)))
            }
        }
    }

    @Test fun a_closed_host_record_stops_needing_attention_but_keeps_its_context() {
        val failed = request.copy(state = "failed", reason = "Restart failed after saving policy")
        assertTrue(failed.attention)
        val closed = failed.copy(state = "closed")
        assertFalse(closed.pending)
        assertFalse(closed.attention)
        assertEquals(failed.reason, closed.reason)
        assertEquals(failed.change, closed.change)
        assertEquals(failed.revision, closed.revision)
    }

    @Test fun delayed_broadcasts_cannot_reopen_a_closed_request() {
        val closed = request.copy(state = "closed")
        for (state in listOf("pending", "applying", "failed", "approved", "denied")) {
            val delayed = request.copy(state = state, reason = "Earlier broadcast")
            assertSame(closed, newestPolicyRequest(closed, delayed))
            assertFalse(newestPolicyRequest(closed, delayed).attention)
        }
        assertSame(closed, newestPolicyRequest(request.copy(state = "failed"), closed))
    }

    @Test fun closed_state_does_not_hide_new_requests_and_can_be_restored_after_reauthentication() {
        val closed = request.copy(state = "closed")
        val next = request.copy(id = "request-2")
        assertSame(next, newestPolicyRequest(closed, next))
        val otherHost = request.copy(host = "other-pi")
        assertSame(otherHost, newestPolicyRequest(closed, otherHost))
        // Authentication clears the previous host snapshot, then sync supplies canonical records.
        assertSame(closed, newestPolicyRequest(null, closed))
        val refreshedClosed = closed.copy(reason = "Canonical host context")
        assertSame(refreshedClosed, newestPolicyRequest(closed, refreshedClosed))
    }
}
