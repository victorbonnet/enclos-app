package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AuditTest {
    private fun entry(seq: Long) = HostAuditEntry(seq, 100, "alice", "host", "sandbox create", "lab", "ok", "created", 101)

    @Test fun refresh_replaces_old_entries_while_older_pages_append_without_duplicates() {
        val first = HostAudit().loading("first", null).received("first", listOf(entry(5), entry(4)), 4)
        val older = first.loading("older", 4).received("older", listOf(entry(4), entry(2)), null)
        assertEquals(listOf(5L, 4L, 2L), older.entries.map { it.seq })
        assertNull(older.nextBefore)
        val refreshed = older.loading("refresh", null).received("refresh", listOf(entry(6), entry(5)), 5)
        assertEquals(listOf(6L, 5L), refreshed.entries.map { it.seq })
        assertNull(refreshed.request)
    }

    @Test fun late_replies_and_errors_cannot_replace_a_new_request_or_finish_it_twice() {
        val waiting = HostAudit().loading("new", null)
        assertEquals(waiting, waiting.received("old", listOf(entry(1)), null))
        assertEquals(waiting, waiting.failed("old", "failed"))
        val done = waiting.received("new", listOf(entry(2)), null)
        assertEquals(done, done.received("new", listOf(entry(1)), 1))
        assertEquals(done, done.failed("new", "late timeout"))
    }

    @Test fun disconnect_or_timeout_keeps_entries_and_allows_retry_but_ignores_late_pages() {
        val loaded = HostAudit().loading("first", null).received("first", listOf(entry(3)), 3)
        val failed = loaded.loading("older", 3).failed("older", "disconnected")
        assertEquals(loaded.entries, failed.entries)
        assertEquals(3L, failed.nextBefore)
        assertNull(failed.request)
        assertEquals(failed, failed.received("older", listOf(entry(2)), null))
        assertNull(failed.loading("retry", 3).error)
        assertFalse(HostAudit().loaded)
    }
}
