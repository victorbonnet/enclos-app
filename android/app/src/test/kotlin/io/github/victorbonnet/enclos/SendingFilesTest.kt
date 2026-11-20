package io.github.victorbonnet.enclos

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SendingFilesTest {
    @Test fun a_preparation_survives_metadata_and_siblings_but_not_discard_or_host_reset() {
        val budget = PreparedBudget(1_000)
        val state = SendingFiles(budget)
        val c = Conversation("h", "a", "home.a")
        val key = FileKey(c.host, c.id)
        val job = Job()
        assertTrue(state.preparing(key, job))
        val renamed = c.copy()
        assertEquals("a conversation's metadata does not change whose files they are", key, FileKey(renamed.host, renamed.id))
        assertFalse("one preparation at a time", state.preparing(FileKey(renamed.host, renamed.id), Job()))
        val sibling = FileKey("h", "sibling")
        val siblingJob = Job()
        assertTrue(state.preparing(sibling, siblingJob))
        val elsewhere = FileKey("other-host", c.id)
        val elsewhereJob = Job()
        assertTrue(state.preparing(elsewhere, elsewhereJob))
        state.forget("sibling")
        assertTrue(siblingJob.isCancelled)
        assertEquals(FilePhase.Preparing(job), state.phase(key))
        assertFalse(job.isCancelled)
        state.forgetHost("h") // works even when AppModel's conversation inventory is empty
        assertTrue(job.isCancelled)
        assertEquals(FilePhase.Draft, state.phase(key))
        assertEquals("another host's preparation of the same conversation id stays", FilePhase.Preparing(elsewhereJob), state.phase(elsewhere))
        val replacement = Job()
        assertTrue(state.preparing(key, replacement))
        assertTrue(budget.reserve(1))
        assertFalse("old picker/preparation/camera completion", state.prepared(key, job, listOf(SendFile("old.txt", "text/plain", byteArrayOf(1)))))
        assertTrue(state.draft(key).isEmpty())
        state.forget(c.id)
        assertTrue(replacement.isCancelled)
        assertTrue(budget.reserve(1))
        assertFalse("discarded navigation", state.prepared(key, replacement, listOf(SendFile("late.txt", "text/plain", byteArrayOf(1)))))
        assertTrue(state.draft(key).isEmpty())
        assertEquals("refused completions release what they had reserved", 0L, budget.used())
    }

    @Test fun discard_invalidates_inflight_preparation_and_errors() {
        val state = SendingFiles(PreparedBudget(1_000))
        val c = FileKey("h", "c")
        val other = FileKey("h", "other")
        val token = Job()
        val unrelated = Job()
        assertTrue(state.preparing(other, unrelated))
        assertTrue(state.preparing(c, token))
        state.error(c, "Could not read file.")
        state.forget("c")
        assertEquals(FilePhase.Draft, state.phase(c))
        assertTrue(token.isCancelled)
        assertEquals(FilePhase.Preparing(unrelated), state.phase(other))
        assertFalse(unrelated.isCancelled)
        assertNull(state[c].error)
        state.error(other, "Camera unavailable.")
        state.clear()
        assertEquals(FilePhase.Draft, state.phase(other))
        assertTrue(unrelated.isCancelled)
        assertNull(state[other].error)
    }

    @Test fun both_capabilities_are_required_and_groups_never_send_files() {
        for (group in listOf(false, true)) for (bridge in listOf(false, true)) for (agent in listOf(false, true))
            assertEquals(!group && bridge && agent, fileSendAvailable(group, bridge, agent))
    }
    @Test fun validation_matches_declared_type_and_bounds() {
        assertTrue(validSendFile("a.PDF", "application/pdf", 10_000_000))
        for (size in listOf(0, 10_000_001)) assertFalse(validSendFile("a.pdf", "application/pdf", size))
        for (name in listOf("../a.pdf", ".pdf", " a.pdf", "a‮.pdf", "a͏.pdf", "a.pdf ", "a/b.pdf", "a\\b.pdf"))
            assertFalse(name, validSendFile(name, "application/pdf", 1))
        assertFalse(validSendFile("a.pdf", "text/plain", 1))
        assertFalse(validSendFile("a.html", "text/html", 1))
        sendFileTypes.forEach { (ext, mime) -> assertTrue(validSendFile("file.$ext", mime, 1)) }
    }
    @Test fun ordered_chunks_and_explicit_retry_keep_begin_id() {
        val budget = PreparedBudget(1_000_000)
        val state = SendingFiles(budget)
        val key = FileKey("h", "c")
        val files = listOf(SendFile("a.txt", "text/plain", ByteArray(90_001) { (it % 127).toByte() }), SendFile("b.txt", "text/plain", byteArrayOf(7)))
        state.drafted(key, *files.toTypedArray(), budget = budget)
        val send = state.begin(key, "hello")!!
        val id = send.id
        assertEquals("the draft's own files move to the operation", files, send.files)
        val parts = fileParts(id, send.files).toList()
        assertEquals(listOf(0, 45000, 90000, 0), parts.map { it.getInt("offset") })
        assertEquals(listOf(0, 0, 0, 1), parts.map { it.getInt("file") })
        assertEquals(4, parts.map { it.getString("id") }.toSet().size)
        assertTrue(parts.all { it.getString("message") == id && '=' !in it.getString("data") && fits(it) })
        assertEquals(45000, Base64.getUrlDecoder().decode(parts[0].getString("data")).size)
        assertEquals(4, partCount(send.files))
        state.transmitted(key, id)
        state.timedOut(key, id)
        val again = state.retry(key)!!
        assertEquals(id, again.id)
        assertEquals("hello", again.text)
        assertEquals(0, fileParts(again.id, again.files).first().getInt("offset"))
        // Once the original is discarded, the same words and files are a new message.
        state.interrupted(key, id)
        state.dismiss(key)
        state.drafted(key, SendFile("a.txt", "text/plain", ByteArray(90_001) { (it % 127).toByte() }), budget = budget)
        assertNotEquals(id, state.begin(key, "hello")!!.id)
    }
    @Test fun failed_send_retains_files_and_receipt_cleans_exact_retry() {
        val out = Outgoing()
        val key = FileKey("host", "c")
        val file = SendFile("a.txt", "text/plain", byteArrayOf(1))
        out.files.drafted(key, file)
        out.write("c", "hi")
        val send = out.files.begin(key, "hi")!!
        out.write("c", "") // the words belong to the operation now
        out.shown("c", "hi") // text alone is not a receipt for this upload
        assertTrue(out.files.phase(key) is FilePhase.Sending)
        assertTrue(out.files.owns("c"))
        out.ended("host", "lost")
        assertNull(out.unsent["c"])
        val unknown = out.files.phase(key) as FilePhase.Unknown
        assertArrayEquals(file.bytes, unknown.send.files.single().bytes)
        assertEquals(send.id, unknown.send.id)
        assertEquals("hi", unknown.send.text)
        assertSame(send, out.files.retry(key))
        assertFalse(out.files.acknowledged("other", send.id))
        assertTrue(out.files.phase(key) is FilePhase.Sending)
        assertTrue(out.files.acknowledged("host", send.id))
        assertEquals(FilePhase.Draft, out.files.phase(key))
        assertFalse(out.files.owns("c"))
        assertTrue(out.files.draft(key).isEmpty())
        out.files.drafted(key, file)
        out.forget(listOf("c"))
        assertTrue(out.files.draft(key).isEmpty())
        out.files.drafted(key, file)
        out.clear()
        assertTrue(out.files.draft(key).isEmpty())
    }

    @Test fun ack_only_confirmation_and_timeout_preserve_explicit_retry() {
        val out = Outgoing()
        val key = FileKey("h", "c")
        val f = SendFile("x.txt", "text/plain", byteArrayOf(1))
        out.files.drafted(key, f)
        val send = out.files.begin(key, "same")!!
        assertTrue(out.files.busy("h"))
        assertFalse(out.files.busy("unrelated"))
        out.files.transmitted(key, send.id)
        assertTrue(out.files.busy("h"))
        out.files.timedOut(key, send.id)
        assertFalse(out.files.busy("h"))
        assertNull(out.unsent["c"])
        val unknown = out.files.phase(key) as FilePhase.Unknown
        assertArrayEquals(f.bytes, unknown.send.files.single().bytes)
        assertEquals(send.id, unknown.send.id)
        assertTrue(out.files.acknowledged("h", send.id))
        assertEquals(FilePhase.Draft, out.files.phase(key))
        assertTrue(out.files.draft(key).isEmpty())
        assertNull(out.unsent["c"])
    }

    @Test fun refusal_preserves_payload_and_cleanup_invalidates_preparation() {
        val out = Outgoing()
        val key = FileKey("h", "c")
        val f = SendFile("x.txt", "text/plain", byteArrayOf(1))
        out.files.drafted(key, f)
        val send = out.files.begin(key, "")!!
        assertNull("a message with files is not a text message", out.refused(send.id, "plugin failed"))
        assertTrue(out.files.refused("h", send.id, "internal", "plugin failed"))
        val unknown = out.files.phase(key) as FilePhase.Unknown
        assertArrayEquals(f.bytes, unknown.send.files.single().bytes)
        assertEquals(send.id, unknown.send.id)
        assertFalse("no preparation while the original awaits retry or discard", out.files.preparing(key, Job()))
        out.forget(listOf("c"))
        assertFalse(out.files.owns("c"))
        assertFalse("no longer pending", out.files.acknowledged("h", send.id))
        val job = Job()
        assertTrue(out.files.preparing(key, job))
        out.forget(listOf("c"))
        assertTrue(job.isCancelled)
        assertTrue(PreparedBudget.process.reserve(1))
        assertFalse(out.files.prepared(key, job, listOf(SendFile("late.txt", "text/plain", byteArrayOf(1)))))
        assertTrue(out.files.draft(key).isEmpty())
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun photo_is_reencoded_and_downsampled() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File.createTempFile("source-photo", ".png", context.cacheDir)
        try {
            val source = android.graphics.Bitmap.createBitmap(3000, 4, android.graphics.Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { source.recycle() }
            val budget = PreparedBudget(80_000_000)
            val prepared = prepareSendFile(context, android.net.Uri.fromFile(file), FileSource.Photos, null, 0, 1, budget)
            assertEquals("image/jpeg", prepared.mime)
            assertEquals("photo.jpg", prepared.name)
            assertEquals(prepared.bytes.size.toLong(), budget.used())
            assertEquals(0xff, prepared.bytes[0].toInt() and 255)
            assertEquals(0xd8, prepared.bytes[1].toInt() and 255)
            assertFalse(prepared.bytes.toString(Charsets.ISO_8859_1).contains("Exif"))
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size, bounds)
            assertEquals("JPEG must decode at the target width", 2048, bounds.outWidth)
            assertEquals("JPEG must preserve the scaled aspect ratio", 2, bounds.outHeight)
        } finally { file.delete() }
    }
}
