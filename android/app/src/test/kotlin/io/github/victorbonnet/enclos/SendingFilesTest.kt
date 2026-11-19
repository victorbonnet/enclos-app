package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SendingFilesTest {
    @Test fun lifecycle_tokens_survive_metadata_and_siblings_but_not_discard_or_host_reset() {
        val state = SendingFiles()
        val c = Conversation("h", "a", "home.a")
        val token = state.selection(c.host, c.id)
        val renamed = c.copy()
        assertEquals(token, state.selection(renamed.host, renamed.id))
        val sibling = state.selection("h", "sibling")
        state.forget("sibling")
        assertTrue(state.owns(c.id, token))
        assertFalse(state.owns("sibling", sibling))
        state.forgetHost("h") // works even when AppModel's conversation inventory is empty
        assertFalse(state.owns(c.id, token))
        val replacement = state.selection("h", c.id)
        assertNotEquals(token, replacement)
        assertFalse("old picker/preparation/camera completion", state.owns(c.id, token))
        state.forget(c.id)
        assertFalse("discarded navigation", state.owns(c.id, replacement))
    }

    @Test fun discard_invalidates_inflight_preparation_and_errors() {
        val state = SendingFiles()
        val token = Any()
        val unrelated = Any()
        state.preparing["other"] = unrelated
        state.preparing["c"] = token
        state.errors["c"] = "Could not read file."
        val generation = state.selection("h", "c")
        state.forget("c")
        assertNull(state.preparing["c"])
        assertSame(unrelated, state.preparing["other"])
        assertNull(state.errors["c"])
        assertTrue(!state.owns("c", generation))
        state.preparing["other"] = Any()
        state.errors["other"] = "Camera unavailable."
        state.clear()
        assertTrue(state.preparing.isEmpty())
        assertTrue(state.errors.isEmpty())
    }

    @Test fun both_capabilities_are_required_and_groups_never_send_files() {
        for (group in listOf(false, true)) for (bridge in listOf(false, true)) for (agent in listOf(false, true))
            assertEquals(!group && bridge && agent, fileSendAvailable(group, bridge, agent))
    }
    @Test fun validation_matches_declared_type_and_bounds() {
        assertTrue(validSendFile("a.PDF", "application/pdf", 10_000_000))
        for (size in listOf(0, 10_000_001)) assertFalse(validSendFile("a.pdf", "application/pdf", size))
        for (name in listOf("../a.pdf", ".pdf", " a.pdf", "a\u202e.pdf", "a\u034f.pdf", "a.pdf ", "a/b.pdf", "a\\b.pdf"))
            assertFalse(name, validSendFile(name, "application/pdf", 1))
        assertFalse(validSendFile("a.pdf", "text/plain", 1))
        assertFalse(validSendFile("a.html", "text/html", 1))
        sendFileTypes.forEach { (ext, mime) -> assertTrue(validSendFile("file.$ext", mime, 1)) }
    }
    @Test fun ordered_chunks_and_explicit_retry_keep_begin_id() {
        val files = listOf(SendFile("a.txt", "text/plain", ByteArray(90_001) { (it % 127).toByte() }), SendFile("b.txt", "text/plain", byteArrayOf(7)))
        val state = SendingFiles()
        state.drafts["c"] = files
        val id = state.id("c", "hello")
        assertEquals(id, state.id("c", "hello"))
        val parts = fileParts(id, files).toList()
        assertEquals(listOf(0, 45000, 90000, 0), parts.map { it.getInt("offset") })
        assertEquals(listOf(0, 0, 0, 1), parts.map { it.getInt("file") })
        assertEquals(4, parts.map { it.getString("id") }.toSet().size)
        assertTrue(parts.all { it.getString("message") == id && '=' !in it.getString("data") && fits(it) })
        assertEquals(45000, Base64.getUrlDecoder().decode(parts[0].getString("data")).size)
        assertEquals(0, fileParts(id, files).first().getInt("offset"))
        state.remove("c", 0)
        assertNotEquals(id, state.id("c", "hello"))
    }
    @Test fun failed_send_retains_files_and_receipt_cleans_exact_retry() {
        val out = Outgoing()
        val file = SendFile("a.txt", "text/plain", byteArrayOf(1))
        out.files.drafts["c"] = listOf(file)
        val id = out.files.id("c", "hi")
        out.write("c", "hi")
        out.send("c", "host", "hi") { id }
        out.shown("c", "hi") // text alone is not a receipt for this upload
        assertTrue(out.waiting("c"))
        out.ended("host", "lost")
        assertNull(out.unsent["c"])
        assertArrayEquals(file.bytes, out.files.retries["c"]!!.files.single().bytes)
        assertEquals(id, out.files.retries["c"]?.id)
        out.send("c", "host", "hi", withFiles = true, preserveDraft = true) { id }
        out.acknowledged("other", id)
        assertTrue(out.waiting("c"))
        out.acknowledged("host", id)
        assertFalse(out.waiting("c"))
        assertNull(out.files.drafts["c"])
        out.files.drafts["c"] = listOf(file)
        out.forget(listOf("c"))
        assertNull(out.files.drafts["c"])
        out.files.drafts["c"] = listOf(file)
        out.clear()
        assertTrue(out.files.drafts.isEmpty())
    }

    @Test fun ack_only_confirmation_and_timeout_preserve_explicit_retry() {
        val out = Outgoing()
        val f = SendFile("x.txt", "text/plain", byteArrayOf(1))
        out.files.drafts["c"] = listOf(f)
        val id = out.files.id("c", "same")
        out.send("c", "h", "same") { id }
        assertTrue(out.waitingHost("h"))
        assertFalse(out.waitingHost("unrelated"))
        out.timedOut("c", id)
        assertFalse(out.waiting("c"))
        assertNull(out.unsent["c"])
        assertArrayEquals(f.bytes, out.files.retries["c"]!!.files.single().bytes)
        assertEquals(id, out.files.retries["c"]?.id)
        out.acknowledged("h", id)
        assertNull(out.files.drafts["c"])
        assertNull(out.unsent["c"])
    }

    @Test fun refusal_preserves_payload_and_cleanup_invalidates_preparation() {
        val out = Outgoing()
        val f = SendFile("x.txt", "text/plain", byteArrayOf(1))
        out.files.drafts["c"] = listOf(f)
        val id = out.files.id("c", "")
        out.send("c", "h", "") { id }
        assertEquals("c", out.refused(id, "plugin failed"))
        assertArrayEquals(f.bytes, out.files.retries["c"]!!.files.single().bytes)
        assertEquals(id, out.files.retries["c"]?.id)
        val generation = out.files.selection("h", "c")
        val camera = java.io.File.createTempFile("camera", ".jpg")
        out.files.cameras["c"] = camera
        out.forget(listOf("c"))
        assertFalse(camera.exists())
        assertTrue(!out.files.owns("c", generation))
        assertFalse(out.pending("c", id))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun photo_is_reencoded_and_downsampled() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File.createTempFile("source-photo", ".png", context.cacheDir)
        try {
            val source = android.graphics.Bitmap.createBitmap(3000, 4, android.graphics.Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { source.recycle() }
            val prepared = prepareSendFile(context, android.net.Uri.fromFile(file), true)
            assertEquals("image/jpeg", prepared.mime)
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
