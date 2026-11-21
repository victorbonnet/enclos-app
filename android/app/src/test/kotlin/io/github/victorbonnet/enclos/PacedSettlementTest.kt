package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.core.DeviceSession
import io.github.victorbonnet.enclos.core.NoHandle
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real model settlement under synthetic socket backpressure; no direct thread interrupt. */
@RunWith(RobolectricTestRunner::class)
class PacedSettlementTest {

    /** Terminal refusal while the upload worker waits in sendPaced: does another part go after the refusal? */
    @Test fun refusal_during_paced_wait() = settled(false)
    @Test fun ack_during_paced_wait() = settled(true)
    private fun settled(ack: Boolean) {
        val model = fixtureModel()
        val queue = AtomicLong(0)
        val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
        val refusedAt = AtomicLong(-1)
        val polledHigh = CountDownLatch(3)
        val closes = AtomicLong(0)
        val polls = AtomicLong(0)
        val retire = CountDownLatch(1)
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://example.invalid").build()
            override fun queueSize(): Long { val q = queue.get(); if (q > 128_000) { polledHigh.countDown(); polls.incrementAndGet(); if (polledHigh.count == 0L) { check(retire.await(5, TimeUnit.SECONDS)) } }; return q }
            override fun send(text: String) = false
            override fun send(bytes: ByteString): Boolean {
                val f = JSONObject(bytes.utf8())
                f.put("_afterRefusal", refusedAt.get() >= 0)
                frames.add(f)
                // After the begin, the connection's queue stays full: the next part waits in pacing.
                if (f.getString("type") == "message.begin") queue.set(200_000)
                return true
            }
            override fun close(code: Int, reason: String?): Boolean { closes.incrementAndGet(); return true }
            override fun cancel() {}
        }
        val connection = BridgeConnection("https://example.invalid", ByteArray(32), { ByteArray(32) }, { error("unused") }, {}, {}, {})
        BridgeConnection::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(connection, socket)
        BridgeConnection::class.java.getDeclaredField("session").apply { isAccessible = true }.set(connection, object : DeviceSession(NoHandle) {
            override fun encrypt(plaintext: ByteArray) = plaintext
        })
        try {
            val agent = Agent("probe-host", "box/agent", "hermes", listOf("incoming_files"))
            val id = model.newConversation(agent)
            val c = model.conversations.single { it.id == id }
            model.agents.add(agent)
            model.links[c.host] = HostLink.Connected("fixture-person")
            model.field<MutableMap<String, Set<String>>>("hostCapabilities")[c.host] = setOf("message.begin")
            model.field<MutableMap<String, BridgeConnection>>("connections")[c.host] = connection
            val key = FileKey(c.host, id)
            val files = model.outgoing.files
            val before = PreparedBudget.process.used()
            val size = 3 * SEND_PART_BYTES
            files.drafted(key, SendFile("probe.txt", "text/plain", ByteArray(size) { 7 }))
            assertEquals(before + size, PreparedBudget.process.used())
            assertNull(model.send(c, "probe"))
            val sending = files.phase(key) as FilePhase.Sending
            val op = sending.send
            val job = sending.job!!
            // The job resumes on the main looper between parts: run it until the worker polls a full queue.
            settle(5_000) { polledHigh.count == 0L }
            val framesBefore = frames.size
            // The bridge's definitive refusal of this operation, on the main thread.
            refusedAt.set(System.nanoTime())
            model.receive(c.host, if (ack) JSONObject().put("type", "ack").put("of", op.id) else JSONObject().put("type", "error").put("of", op.id).put("code", "not_allowed").put("message", "probe refusal"))
            val phase = files.phase(key)
            val usedAfterRefusal = PreparedBudget.process.used()
            assertEquals("worker still holds original bytes", before + size, usedAfterRefusal)
            assertFalse(job.isCompleted)
            retire.countDown()
            settle(2000) { job.isCompleted }
            val otherBridge = FakeBridge()
            val otherAgent = agent.copy(host = "other-host")
            model.agents.add(otherAgent)
            otherBridge.attach(model, otherAgent.host)
            val otherId = model.newConversation(otherAgent)
            val other = model.conversations.single { it.id == otherId }
            files.drafted(FileKey(other.host, other.id), SendFile("other.txt", "text/plain", byteArrayOf(1)))
            assertNull(model.send(other, "other"))
            settle(2000) { otherBridge.ofType("message.part").isNotEmpty() }
            val otherOp = files.phase(FileKey(other.host, other.id)).send()!!
            model.receive(other.host, JSONObject().put("type", "ack").put("of", otherOp.id))
            assertEquals(0L, closes.get())
            val pollsAtRefusal = polls.get()
            Thread.sleep(300)
            // Whether the cancelled worker still waits in pacing (its thread polls the queue) after the refusal.
            val stillPolling = polls.get() > pollsAtRefusal
            // The connection's queue drains.
            queue.set(0)
            Thread.sleep(500)
            settle(2_000) { true }
            Thread.sleep(200)
            settle(1_000) { true }
            val after = synchronized(frames) { frames.drop(framesBefore).map { it.getString("type") + "@" + it.optInt("offset", -1) + " afterRefusal=" + it.getBoolean("_afterRefusal") } }
            println("PROBE_PACED phaseAfterRefusal=${phase::class.simpleName} budgetDelta=${usedAfterRefusal - before} framesBeforeRefusal=$framesBefore framesAfterRefusal=$after stillPolling=$stillPolling")
            // These assertions exercise cancellation from the real model, without thread interruption.
            assertTrue(if (ack) phase is FilePhase.Draft else phase is FilePhase.Failed)
            assertTrue("worker retires without draining queue", job.isCompleted)
            assertFalse("retired worker stops polling", stillPolling)
            assertTrue("no part after settlement", after.isEmpty())
            settle(2000) { PreparedBudget.process.used() == before }
            assertEquals(before, PreparedBudget.process.used())
        } finally {
            retire.countDown()
            queue.set(0)
            model.endFixture()
        }
    }

}
