package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class RoutineRequestTest {
    @Test fun routines_are_offered_only_to_the_person_the_host_lets_manage_them() {
        val host = Agent("mac", "lab/coder", "hermes", listOf("routines"), canManageRoutines = true)
        assertTrue(host.offersRoutines)
        assertFalse(host.copy(canManageRoutines = false).offersRoutines) // a guest, or an older host
        assertFalse(host.copy(capabilities = emptyList()).offersRoutines)
        val sent = RoutineClient(send = { _, _ -> true }, connected = { true }, supported = { false }, later = { _, _ -> })
        sent.refresh("mac/lab/coder")
        assertTrue("no request for an agent whose routines are not offered", sent.pending.isEmpty())
    }

    @Test fun fresh_get_revision_replaces_older_list_before_editing() {
        val listed = RoutineSummary("r1", "old", "Brief", RoutineSchedule.Cron("0 9 * * *"), true, "Old instruction", "Home", true, null, null, null)
        val fetched = listed.copy(revision = "new", preview = "Changed natively")
        assertFalse(sameRoutineRevision(listOf(listed).single(), fetched))
        val merged = mergeRoutineSummary(listOf(listed), fetched)
        assertTrue(sameRoutineRevision(merged.single(), fetched))
        assertFalse(sameRoutineRevision(merged.single(), listed))
        assertFalse(sameRoutineRevision(merged.single(), fetched.copy(id = "r2")))
    }

    @Test fun receipts_are_scoped_to_host_agent_and_exact_pending_request() {
        val pending = RoutineRequest("request-2", "mac/lab/coder", "update", "routine-1")
        assertTrue(pending.matches("mac", "request-2", "lab/coder"))
        assertFalse(pending.matches("pi", "request-2", "lab/coder"))
        assertFalse(pending.matches("mac", "request-2", "lab/other"))
        assertFalse(pending.matches("mac", "request-1", "lab/coder"))
        assertFalse(pending.matches("mac", "timed-out-request", "lab/coder"))
    }

    @Test fun response_must_match_action_and_routine_before_it_can_finish_pending_work() {
        val update = RoutineRequest("1", "mac/lab/coder", "update", "r1")
        assertTrue(update.accepts("routine", "r1"))
        assertFalse(update.accepts("routine", "r2"))
        assertFalse(update.accepts("deleted", "r1"))
        assertFalse(update.accepts("run", "r1"))
        assertTrue(RoutineRequest("2", "mac/lab/coder", "run", "r1").accepts("run", "r1"))
        assertFalse(RoutineRequest("2", "mac/lab/coder", "run", "r1").accepts("routine", "r1"))
        assertTrue(RoutineRequest("3", "mac/lab/coder", "list").accepts("list", null))
        assertTrue(RoutineRequest("4", "mac/lab/coder", "create").accepts("routine", "new-id"))
    }
}
