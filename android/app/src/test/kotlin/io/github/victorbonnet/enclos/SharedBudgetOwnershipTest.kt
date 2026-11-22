package io.github.victorbonnet.enclos

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * One budget for every conversation and operation: each file's bytes have one
 * owner and are released once, promptly on a final refusal, and never on
 * behalf of another owner (JVM).
 */
@RunWith(RobolectricTestRunner::class)
class SharedBudgetOwnershipTest {
    @Test fun the_model_drafts_and_its_preparation_share_the_process_budget() {
        val model = fixtureModel()
        try {
            assertSame(PreparedBudget.process, model.outgoing.files.budget)
            assertEquals(80_000_000L, PreparedBudget.process.limit)
        } finally { model.endFixture() }
    }

    @Test fun accounting_across_conversations_operations_and_a_newer_draft_releases_each_owner_once() {
        val budget = PreparedBudget(100)
        val files = SendingFiles(budget)
        val a = FileKey("h", "a")
        val b = FileKey("h", "b")
        val c = FileKey("other", "c")
        fun read(n: Int, name: String) = SendFile(name, "text/plain", readBounded(ByteArrayInputStream(ByteArray(n) { 1 }), budget))
        fun draft(key: FileKey, file: SendFile) {
            val job = Job()
            assertTrue(files.preparing(key, job))
            assertTrue(files.prepared(key, job, listOf(file)))
        }
        draft(a, read(30, "a1.txt"))
        val opA = files.begin(a, "first")!!
        draft(b, read(25, "b1.txt"))
        draft(c, read(15, "c1.txt"))
        val opC = files.begin(c, "")!!
        files.transmitted(c, opC.id)
        assertEquals(70L, budget.used())
        assertTrue("what does not fit is refused, nothing evicted", runCatching { read(40, "big.txt") }.exceptionOrNull() is PrepareRefused)
        assertEquals(70L, budget.used())

        // A final refusal releases its bytes at once; its notice keeps names only.
        assertTrue(files.refused("h", opA.id, "not_allowed", "a1.txt was refused"))
        val failed = files.phase(a) as FilePhase.Failed
        assertEquals(listOf("a1.txt"), failed.names)
        assertEquals("first", failed.text)
        assertEquals(opA.id, failed.id)
        assertTrue("its conversation stays reachable", files.owns("a"))
        assertEquals(40L, budget.used())

        // Dismissed, then a newer draft in the same conversation: old callbacks never touch it.
        files.dismiss(a)
        val newer = read(30, "a2.txt")
        draft(a, newer)
        assertEquals(70L, budget.used())
        files.acknowledged("h", opA.id)
        files.refused("h", opA.id, "not_allowed", "again")
        files.dismiss(a)
        assertEquals(listOf(newer), files.draft(a))
        assertEquals(70L, budget.used())

        // Unknown keeps the immutable original for a same-id retry from byte zero; nothing resends by itself.
        files.ended("other")
        val unknown = (files.phase(c) as FilePhase.Unknown).send
        assertSame(opC, unknown)
        assertEquals(70L, budget.used())
        val again = files.retry(c)!!
        assertSame(opC, again)
        assertEquals(0, fileParts(again.id, again.files).first().getInt("offset"))
        assertTrue(files.acknowledged("other", opC.id))
        assertEquals(55L, budget.used())
        files.forget(c)
        assertEquals(55L, budget.used())

        // A stray second release of one file never frees another owner's bytes.
        val stray = files.draft(a).single()
        files.discard(listOf(stray))
        assertEquals(25L, budget.used())
        files.remove(a, 0)
        assertEquals("b's 25 bytes stay reserved", 25L, budget.used())
        files.remove(b, 0)
        files.remove(b, 0)
        files.forget(b)
        assertEquals(0L, budget.used())
    }

    @Test fun cancelled_and_failed_preparations_and_forgetting_release_exactly_their_own() {
        val budget = PreparedBudget(100)
        val files = SendingFiles(budget)
        val a = FileKey("h", "a")
        val b = FileKey("h", "b")
        val held = SendFile("b.txt", "text/plain", readBounded(ByteArrayInputStream(ByteArray(20)), budget))
        Job().let { assertTrue(files.preparing(b, it)); assertTrue(files.prepared(b, it, listOf(held))) }
        // A preparation cancelled after reading hands back nothing it read.
        val cancelled = Job()
        assertTrue(files.preparing(a, cancelled))
        val late = SendFile("late.txt", "text/plain", readBounded(ByteArrayInputStream(ByteArray(30)), budget))
        files.forget(a)
        assertFalse(files.prepared(a, cancelled, listOf(late)))
        files.discard(listOf(late))
        assertEquals(20L, budget.used())
        // A failed preparation leaves the draft as it was.
        val failing = Job()
        assertTrue(files.preparing(a, failing))
        files.preparationFailed(a, failing, "These files could not be prepared.")
        assertEquals(FilePhase.Draft, files.phase(a))
        assertEquals(20L, budget.used())
        files.forgetHost("h")
        assertEquals(0L, budget.used())
    }
}
