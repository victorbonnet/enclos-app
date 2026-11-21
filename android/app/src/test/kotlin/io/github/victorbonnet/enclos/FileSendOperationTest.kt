package io.github.victorbonnet.enclos

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files as Paths

private fun createTempDir(): File = Paths.createTempDirectory("send-files").toFile().apply { deleteOnExit() }

/** One aggregate per host and conversation: phases, receipts, refusals, timeouts and the shared byte budget. */
@RunWith(RobolectricTestRunner::class)
class FileSendOperationTest {
    private val a = FileKey("host", "a")
    private val b = FileKey("host", "b")

    private fun drafted(files: SendingFiles, key: FileKey, budget: PreparedBudget, vararg sizes: Int) {
        val job = Job()
        assertTrue(files.preparing(key, job))
        files.prepared(key, job, sizes.map { n -> SendFile("f$n.txt", "text/plain", readBounded(ByteArrayInputStream(ByteArray(n)), budget)) })
    }

    @Test fun `a normal send goes through progress and its receipt without a failure`() {
        val budget = PreparedBudget(1_000)
        val files = SendingFiles(budget)
        drafted(files, a, budget, 100)
        val send = files.begin(a, "hello")!!
        assertTrue(files.draft(a).isEmpty())
        files.progress(a, send.id, 50)
        assertEquals(50, (files.phase(a) as FilePhase.Sending).progress)
        files.transmitted(a, send.id)
        assertTrue(files.phase(a) is FilePhase.AwaitingAck)
        assertNull(files[a].error)
        assertTrue(files.acknowledged("host", send.id))
        assertEquals(FilePhase.Draft, files.phase(a))
        assertNull(files[a].error)
        assertEquals(0L, budget.used())
    }

    @Test fun `a refusal is final with its reason, not unknown, and offers nothing to retry`() {
        val files = SendingFiles(PreparedBudget(1_000))
        drafted(files, a, PreparedBudget(1_000), 10)
        val send = files.begin(a, "")!!
        files.transmitted(a, send.id)
        assertTrue(files.refused("host", send.id, "not_allowed", bridgeReason("file name refused: x.pdf")))
        val failed = files.phase(a) as FilePhase.Failed
        assertEquals("file name refused: x.pdf", failed.reason)
        assertNull(files.retry(a))
        assertTrue("a late receipt does not undo a final refusal", files.acknowledged("host", send.id))
        assertTrue(files.phase(a) is FilePhase.Failed)
    }

    @Test fun `only a refusal the bridge names is final - internal, unconfirmed or unknown codes leave delivery unknown`() {
        for (code in listOf("unsupported", "not_allowed", "bad_message")) {
            val files = SendingFiles(PreparedBudget(1_000))
            drafted(files, a, PreparedBudget(1_000), 10)
            val send = files.begin(a, "")!!
            files.refused("host", send.id, code, "no")
            assertTrue(code, files.phase(a) is FilePhase.Failed)
        }
        for (code in listOf("internal", "unconfirmed", "something_new", "")) {
            val files = SendingFiles(PreparedBudget(1_000))
            drafted(files, a, PreparedBudget(1_000), 10)
            val send = files.begin(a, "")!!
            files.transmitted(a, send.id)
            files.refused("host", send.id, code, "no")
            assertTrue(code, files.phase(a) is FilePhase.Unknown)
            assertEquals("retry keeps the id", send.id, files.retry(a)!!.id)
        }
    }

    @Test fun `an error after the receipt is still correlated by host and id, naming the files without keeping their bytes`() {
        val budget = PreparedBudget(1_000)
        val files = SendingFiles(budget)
        drafted(files, a, budget, 10)
        val send = files.begin(a, "")!!
        files.acknowledged("host", send.id)
        assertEquals(0L, budget.used())
        assertFalse("another host's error is not correlated", files.refused("other", send.id, "not_allowed", "x"))
        assertTrue("nor does it consume this host's correlation", files.refused("host", send.id, "not_allowed", "agent unavailable"))
        assertEquals(FilePhase.Draft, files.phase(a))
        assertEquals("Not accepted after its receipt (f10.txt): agent unavailable", files[a].error)
    }

    @Test fun `forgetting a host drops its late-error correlation even with no aggregate left`() {
        val files = SendingFiles(PreparedBudget(1_000))
        drafted(files, a, PreparedBudget(1_000), 10)
        val send = files.begin(a, "")!!
        files.acknowledged("host", send.id)
        files.forgetHost("host")
        assertFalse(files.refused("host", send.id, "not_allowed", "x"))
        assertNull(files[a].error)
    }

    @Test fun `once settled, an upload is no longer uploading so no further part or progress goes`() {
        val files = SendingFiles(PreparedBudget(1_000))
        drafted(files, a, PreparedBudget(1_000), 10)
        val send = files.begin(a, "")!!
        val job = Job()
        files.started(a, send.id, job)
        assertTrue(files.uploading(a, send.id))
        files.acknowledged("host", send.id)
        assertTrue(job.isCancelled)
        assertFalse(files.uploading(a, send.id))
        files.progress(a, send.id, 90)
        assertEquals(FilePhase.Draft, files.phase(a))
    }

    @Test fun `a receipt cancels the wait for it, so an old timeout cannot settle a retry`() {
        val files = SendingFiles(PreparedBudget(1_000))
        drafted(files, a, PreparedBudget(1_000), 10)
        val send = files.begin(a, "")!!
        val wait = Job()
        files.started(a, send.id, wait)
        files.transmitted(a, send.id)
        files.ended("host")
        assertTrue("the connection's end cancels the receipt wait", wait.isCancelled)
    }

    @Test fun `a timeout settles only its own operation, and retry reuses the id from byte zero`() {
        val files = SendingFiles(PreparedBudget(1_000))
        drafted(files, a, PreparedBudget(1_000), 10)
        drafted(files, b, PreparedBudget(1_000), 10)
        val one = files.begin(a, "")!!
        val two = files.begin(b, "")!!
        files.transmitted(a, one.id)
        files.transmitted(b, two.id)
        files.timedOut(a, two.id)
        assertTrue("another operation's id does not settle a", files.phase(a) is FilePhase.AwaitingAck)
        files.timedOut(a, one.id)
        assertTrue(files.phase(a) is FilePhase.Unknown)
        assertTrue(files.phase(b) is FilePhase.AwaitingAck)
        val again = files.retry(a)!!
        assertEquals(one.id, again.id)
        assertEquals(0, (files.phase(a) as FilePhase.Sending).progress)
    }

    @Test fun `a connection that ends makes in-flight sends unknown on that host only - unpair releases them`() {
        val budget = PreparedBudget(1_000)
        val files = SendingFiles(budget)
        val other = FileKey("other", "c")
        drafted(files, a, budget, 10)
        drafted(files, other, budget, 10)
        files.begin(a, "")
        files.begin(other, "")
        files.ended("host")
        assertTrue(files.phase(a) is FilePhase.Unknown)
        assertTrue(files.phase(other) is FilePhase.Sending)
        assertTrue(files.owns("a"))
        files.forgetHost("host")
        assertFalse(files.owns("a"))
        assertEquals(10L, budget.used())
    }

    @Test fun `the budget is shared by every conversation and never evicts unsent bytes`() {
        val budget = PreparedBudget(100)
        val files = SendingFiles(budget)
        drafted(files, a, budget, 60)
        val refused = runCatching { readBounded(ByteArrayInputStream(ByteArray(60)), budget) }.exceptionOrNull()
        assertTrue(refused is PrepareRefused)
        assertEquals(60L, budget.used())
        assertEquals(1, files.draft(a).size)
        files.remove(a, 0)
        assertEquals(0L, budget.used())
        assertEquals(60, readBounded(ByteArrayInputStream(ByteArray(60)), budget).size)
    }

    @Test fun `a preparation no longer wanted releases what it read`() {
        val budget = PreparedBudget(1_000)
        val files = SendingFiles(budget)
        val job = Job()
        files.preparing(a, job)
        files.forget(a)
        assertTrue(job.isCancelled)
        val read = SendFile("x.txt", "text/plain", readBounded(ByteArrayInputStream(ByteArray(40)), budget))
        assertFalse(files.prepared(a, job, listOf(read)))
        assertEquals(0L, budget.used())
    }

    @Test fun `a cancelled preparation stops reading and releases every reservation`() {
        val budget = PreparedBudget(1_000_000)
        val dir = createTempDir()
        val picks = (1..3).map { n -> File(dir, "n$n.py").apply { writeBytes(ByteArray(100_000)) } }.map(::providerPick)
        var reads = 0
        val stop = runCatching {
            prepareSendFiles(ApplicationProvider.getApplicationContext(), picks, FileSource.Files, budget) {
                if (++reads > 5) throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(stop is CancellationException)
        assertTrue("it stopped part way", reads == 6)
        assertEquals(0L, budget.used())
    }

    @Test fun `a failed preparation releases what it reserved, and a successful one holds exactly its bytes`() {
        val budget = PreparedBudget(250_000)
        val dir = createTempDir()
        val picks = listOf(File(dir, "a.pdf").apply { writeBytes(ByteArray(200_000) { 1 }) }, File(dir, "b.pdf").apply { writeBytes(ByteArray(100_000)) })
        val result = prepareSendFiles(ApplicationProvider.getApplicationContext(), picks.map(::providerPick), FileSource.Files, budget)
        assertEquals(listOf("a.pdf"), result.files.map { it.name })
        assertEquals(1, result.refused.size)
        assertTrue(result.refused.single().startsWith("b.pdf ("))
        assertEquals(200_000L, budget.used())
    }

    @Test fun `Files keep a PNG's and a GIF's exact bytes and names - provider aliases do not change the type`() {
        val budget = PreparedBudget(1_000_000)
        val dir = createTempDir()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + ByteArray(50) { it.toByte() }
        val gif = "GIF89a".toByteArray() + ByteArray(40) { (it * 3).toByte() }
        val picks = listOf(File(dir, "clear.png").apply { writeBytes(png) }, File(dir, "moving.gif").apply { writeBytes(gif) },
            File(dir, "tool.py").apply { writeText("print(1)\n") })
        val result = prepareSendFiles(ApplicationProvider.getApplicationContext(), picks.map(::providerPick), FileSource.Files, budget)
        assertEquals(listOf("clear.png", "moving.gif", "tool.py"), result.files.map { it.name })
        assertEquals(listOf("image/png", "image/gif", "text/plain"), result.files.map { it.mime })
        assertTrue(png.contentEquals(result.files[0].bytes))
        assertTrue(gif.contentEquals(result.files[1].bytes))
    }

    @Test fun `a GIF from Photos is refused rather than sent with its source details`() {
        val budget = PreparedBudget(1_000_000)
        val dir = createTempDir()
        val gif = File(dir, "IMG_1.jpg").apply { writeBytes("GIF89a".toByteArray() + ByteArray(40)) }
        val result = prepareSendFiles(ApplicationProvider.getApplicationContext(), listOf(providerPick(gif)), FileSource.Photos, budget)
        assertTrue(result.files.isEmpty())
        assertEquals("photo.jpg ($GIF_FROM_PHOTOS)", result.refused.single())
        assertEquals(0L, budget.used())
    }

    @Test fun `a file over ten million bytes is refused while reading and releases its reservation`() {
        val budget = PreparedBudget(80_000_000)
        val failure = runCatching { readBounded(ByteArrayInputStream(ByteArray(10_000_001)), budget) }.exceptionOrNull()
        assertTrue(failure is PrepareRefused)
        assertEquals(0L, budget.used())
    }

    @Test fun `parts are 45000 bytes from byte zero`() {
        val parts = fileParts("id", listOf(SendFile("a.txt", "text/plain", ByteArray(90_001)))).toList()
        assertEquals(listOf(0, 45_000, 90_000), parts.map { it.getInt("offset") })
    }

    @Test fun `types come from the extension, whatever the provider calls them`() {
        mapOf("a.py" to "text/plain", "a.sh" to "text/plain", "a.csv" to "text/csv", "a.log" to "text/plain",
            "a.toml" to "application/toml", "a.md" to "text/markdown", "A.PDF" to "application/pdf", "x.tar.gz" to null, ".pdf" to null, "a.html" to null)
            .forEach { (name, mime) -> assertEquals(name, mime, sendFileType(name)) }
    }

    /** enclos-protocol's canonical corpus (crates/enclos-protocol/tests/fixtures/incoming_names.json), byte-identical copy. */
    @Test fun `filenames follow the protocol's canonical corpus`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/incoming_names.json")) { "corpus missing" }.use { it.readBytes() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) }
        assertEquals("c35c16598abc61bb860d650fab8fbc9c11c76dcbf49933944def5124c524bfe4", digest)
        val corpus = JSONArray(String(text, Charsets.UTF_8))
        assertTrue(corpus.length() > 0)
        for (i in 0 until corpus.length()) {
            val case = corpus.getJSONObject(i)
            assertEquals("${case.getString("description")}: ${case.getString("name")}", case.getBoolean("accepted"), validSendFileName(case.getString("name")))
        }
    }

    @Test fun `a refused name is shown with its hidden characters written out`() {
        assertEquals("report.pdf", safeFileName("report.pdf"))
        assertEquals("⟨U+202E⟩fdp.exe", safeFileName("‮fdp.exe"))
        assertEquals("a file without a name", safeFileName(null))
    }
}
