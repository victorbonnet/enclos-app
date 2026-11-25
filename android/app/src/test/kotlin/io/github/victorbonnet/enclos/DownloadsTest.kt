package io.github.victorbonnet.enclos

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.victorbonnet.enclos.ui.LocalDownloadStore
import io.github.victorbonnet.enclos.ui.OtherFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class DownloadsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun sanitised_names_match_spec_corpus() {
        assertEquals("r_sum_.pdf", Attachments.fileName("résumé.pdf"))
        assertEquals("_.._x", Attachments.fileName("../../x"))
        assertEquals("file", Attachments.fileName(".."))
        assertEquals("file", Attachments.fileName(""))
        assertEquals("invoice_fdp.apk", Attachments.fileName("invoice\u202Efdp.apk"))
        val longName = Attachments.fileName("a".repeat(81) + ".pdf")
        assertEquals(80, longName.length)
        assertTrue(longName.endsWith(".pdf"))
    }

    @Test fun free_name_numbers_clashes_without_overwriting() {
        assertEquals("build.apk", Downloads.freeName("build.apk", emptySet()))
        assertEquals("build (1).apk", Downloads.freeName("build.apk", setOf("build.apk")))
        assertEquals("build (2).apk", Downloads.freeName("build.apk", setOf("build.apk", "build (1).apk")))
        assertEquals("README (1)", Downloads.freeName("README", setOf("README")))
        assertEquals("trailing. (1)", Downloads.freeName("trailing.", setOf("trailing.")))
    }

    @Test fun a_padded_name_does_not_hide_its_extension() {
        val padded = "report" + " ".repeat(50) + ".apk"
        assertEquals("report .apk", Downloads.savedName(padded))
        assertEquals("a b.pdf", Downloads.savedName("a" + " ".repeat(7) + "b.pdf"))
        // The name handed to the store is the collapsed one.
        val source = File.createTempFile("enclos-pad", ".bin").apply { writeBytes("x".toByteArray()) }
        try {
            val store = FakeDownloadStore()
            assertEquals(DownloadOutcome.Saved, saveToDownloads(store, padded, "application/octet-stream", source))
            assertEquals("report .apk", store.completed.values.single().name)
        } finally {
            source.delete()
        }
    }

    @Test fun a_leading_dot_never_makes_a_hidden_file() {
        assertEquals("profile", Downloads.savedName(".profile"))
        assertEquals("profile", Downloads.savedName(" . .profile"))
        assertEquals("profile", Downloads.savedName(". . . profile"))
        assertEquals("tar.gz", Downloads.savedName(".tar.gz"))
        assertEquals("file", Downloads.savedName(" . . "))
        val source = File.createTempFile("enclos-dot", ".bin").apply { writeBytes("x".toByteArray()) }
        try {
            val store = FakeDownloadStore()
            assertEquals(DownloadOutcome.Saved, saveToDownloads(store, " . .profile", "text/plain", source))
            assertEquals("profile", store.completed.values.single().name)
        } finally {
            source.delete()
        }
    }

    @Test fun the_agents_media_type_never_renames_the_saved_file() {
        val source = File.createTempFile("enclos-type", ".bin").apply { writeBytes("x".toByteArray()) }
        try {
            // A PDF name sent as an APK type: the type recorded is the PDF's own, so
            // MediaStore has no extension to add (it made report.pdf.apk).
            val pdf = FakeDownloadStore()
            saveToDownloads(pdf, "report.pdf", "application/vnd.android.package-archive", source)
            assertEquals("application/pdf", pdf.completed.values.single().mediaType)
            // And the other way round: an APK name sent as an image type.
            val apk = FakeDownloadStore()
            saveToDownloads(apk, "img.apk", "image/png", source)
            assertEquals("application/vnd.android.package-archive", apk.completed.values.single().mediaType)
            // No extension, or one nothing knows: the neutral type, never the agent's.
            val none = FakeDownloadStore()
            saveToDownloads(none, "notes", "application/pdf", source)
            assertEquals("application/octet-stream", none.completed.values.single().mediaType)
            val unknown = FakeDownloadStore()
            saveToDownloads(unknown, "data.zzqq", "text/html", source)
            assertEquals("application/octet-stream", unknown.completed.values.single().mediaType)
            // Upper-case extension, and a blank or malformed type from the agent.
            assertEquals("application/pdf", Downloads.mediaTypeFor("REPORT.PDF"))
            val blank = FakeDownloadStore()
            saveToDownloads(blank, "a.txt", "a/b\nc", source)
            assertEquals("text/plain", blank.completed.values.single().mediaType)
        } finally {
            source.delete()
        }
    }

    /** Waits for [condition] while running what the app posted to the main thread (the app-wide download scope runs there). */
    private fun untilMain(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 5_000) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    @Test fun a_name_another_app_owns_is_numbered_around_not_written_over() {
        val source = File.createTempFile("enclos-foreign", ".bin").apply { writeBytes("x".toByteArray()) }
        try {
            // taken() cannot see the other app's report.pdf; the folder still answers for it.
            val store = FakeDownloadStore(foreign = setOf("report.pdf"))
            assertEquals(DownloadOutcome.Saved, saveToDownloads(store, "report.pdf", "application/pdf", source))
            assertEquals("report (1).pdf", store.completed.values.single().name)
        } finally {
            source.delete()
        }
    }

    @Test fun a_refused_publish_tells_the_truth_about_what_is_left() {
        val source = File.createTempFile("enclos-publish", ".bin").apply { writeBytes("x".toByteArray()) }
        try {
            // The platform threw but finished the file itself and handed it on: it is there, so Saved.
            val gone = FakeDownloadStore(finishThrows = true, stateAfterFailure = RowState.Gone)
            assertEquals(DownloadOutcome.Saved, saveToDownloads(gone, "a.pdf", "application/pdf", source))
            assertTrue(gone.discarded.isEmpty())
            // It threw and the row is still ours (pending, or published but visible): removed, nothing left.
            for (left in listOf(RowState.Pending, RowState.Done)) {
                val store = FakeDownloadStore(finishThrows = true, stateAfterFailure = left)
                assertEquals(DownloadOutcome.Failed, saveToDownloads(store, "a.pdf", "application/pdf", source))
                assertEquals(1, store.discarded.size)
                assertTrue(store.completed.isEmpty())
            }
            // A plain refusal is a failure even though the row is not visible.
            val refused = FakeDownloadStore(failFinish = true, stateAfterFailure = RowState.Gone)
            assertEquals(DownloadOutcome.Failed, saveToDownloads(refused, "a.pdf", "application/pdf", source))
        } finally {
            source.delete()
        }
    }

    @Test fun a_cancel_landing_after_the_file_is_published_still_reports_saved() {
        val source = File.createTempFile("enclos-late", ".bin").apply { writeBytes("x".toByteArray()) }
        try {
            lateinit var job: kotlinx.coroutines.Job
            val store = FakeDownloadStore(onFinish = { job.cancel() })
            var outcome: DownloadOutcome? = null
            runBlocking {
                job = launch(kotlinx.coroutines.Dispatchers.Default) {
                    outcome = saveToDownloads(
                        ApplicationProvider.getApplicationContext(),
                        Attachment("att-late", "application/pdf", "a.pdf", 1),
                        source,
                        store,
                    )
                }
                job.join()
            }
            assertEquals(DownloadOutcome.Saved, outcome)
            assertEquals(1, store.completed.size)
        } finally {
            source.delete()
        }
    }

    @Test fun an_unreadable_source_leaves_no_row_and_no_open_stream() {
        val store = FakeDownloadStore()
        val dir = java.nio.file.Files.createTempDirectory("enclos-dir").toFile()
        val source = File(dir, "gone.bin").apply { writeBytes("x".toByteArray()) }
        try {
            source.setReadable(false)
            if (source.canRead()) return // running as a user that ignores permissions: nothing to show
            assertEquals(DownloadOutcome.Failed, saveToDownloads(store, "a.bin", "application/octet-stream", source))
            assertEquals(0, store.beginCalls)
            assertTrue(store.pending.isEmpty())
        } finally {
            source.delete()
            dir.delete()
        }
    }

    @Test fun a_save_goes_on_when_its_card_scrolls_away() {
        val source = File.createTempFile("enclos-scroll", ".bin").apply { writeBytes("payload".toByteArray()) }
        val gate = java.util.concurrent.CountDownLatch(1)
        val store = FakeDownloadStore(gate = gate)
        var shown by mutableStateOf(true)
        try {
            val attachment = Attachment("att-scroll", "application/octet-stream", "big.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    if (shown) OtherFile(a = attachment, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                }
            }
            compose.onNodeWithContentDescription("Download big.bin").performClick()
            untilMain { store.writing.count == 0L }
            shown = false
            compose.waitForIdle()
            gate.countDown()
            untilMain { ShadowToast.getTextOfLatestToast() != null }
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
            assertEquals(1, store.completed.size)
            assertTrue(store.discarded.isEmpty())
        } finally {
            gate.countDown()
            source.delete()
        }
    }

    @Test fun a_download_waiting_for_its_fetch_goes_on_when_its_card_scrolls_away() {
        val source = File.createTempFile("enclos-scroll-fetch", ".bin").apply { writeBytes("payload".toByteArray()) }
        val store = FakeDownloadStore()
        var state by mutableStateOf<FileState?>(null)
        var shown by mutableStateOf(true)
        var fetches = 0
        try {
            val attachment = Attachment("att-scroll-fetch", "application/octet-stream", "big.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    if (shown) {
                        OtherFile(
                            a = attachment,
                            state = state,
                            itself = false,
                            retry = { fetches++; state = FileState.Loading(0, source.length()) },
                            open = {},
                            stateOf = { state },
                        )
                    }
                }
            }
            compose.onNodeWithContentDescription("Download big.bin").performClick()
            compose.waitForIdle()
            assertEquals(1, fetches)
            shown = false
            compose.waitForIdle()
            state = FileState.Ready(source)
            untilMain { store.completed.isNotEmpty() }
            compose.waitForIdle()
            assertEquals(1, store.beginCalls)
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
        } finally {
            source.delete()
        }
    }

    @Test fun a_cleared_cache_file_is_fetched_again_before_it_is_saved() {
        val source = File.createTempFile("enclos-cleared", ".bin").apply { writeBytes("payload".toByteArray()) }
        val gone = File(source.parentFile, "cleared-${System.nanoTime()}.bin")
        val store = FakeDownloadStore()
        var state by mutableStateOf<FileState?>(FileState.Ready(gone))
        var fetches = 0
        try {
            val attachment = Attachment("att-cleared", "application/octet-stream", "big.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    // The fetch has not changed the state yet when the tap lands: the stale Ready is not the answer.
                    OtherFile(a = attachment, state = state, itself = false, retry = { fetches++ }, open = {}, stateOf = { state })
                }
            }
            compose.onNodeWithContentDescription("Download big.bin").performClick()
            compose.waitForIdle()
            assertEquals(1, fetches)
            assertEquals(0, store.beginCalls)
            state = FileState.Loading(0, source.length())
            compose.waitForIdle()
            state = FileState.Ready(source)
            untilMain { store.completed.isNotEmpty() }
            compose.waitForIdle()
            assertEquals(1, store.beginCalls)
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
        } finally {
            source.delete()
        }
    }

    @Test fun offered_is_false_on_api_28_and_true_from_api_29() {
        assertFalse(Downloads.offered(28))
        assertTrue(Downloads.offered(29))
        assertTrue(Downloads.offered(35))
    }

    @Test fun notices_match_exact_spec_text() {
        assertEquals("Saved to Downloads", downloadNotice(DownloadOutcome.Saved))
        assertEquals("The phone has no room to save this file. Nothing was saved.", downloadNotice(DownloadOutcome.NoRoom))
        assertEquals("The file could not be saved. Nothing was saved.", downloadNotice(DownloadOutcome.Failed))
        assertEquals("The file was cleared from the phone. Tap it to fetch it again.", downloadNotice(DownloadOutcome.Missing))
        assertEquals("Cancelled. Nothing was saved.", downloadNotice(DownloadOutcome.Cancelled))
        assertEquals("The host refused it. It may have changed on the host.", downloadFetchFailureNotice("not_allowed"))
        assertEquals("The host refused it. It may have changed on the host.", downloadFetchFailureNotice("file changed on host"))
        assertEquals("The transfer stopped. Nothing was saved.", downloadFetchFailureNotice("not connected"))
    }

    @Test fun saving_through_fake_store_shows_saved_to_downloads_toast() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("enclos-dl", ".apk").apply { writeBytes("apk-bytes".toByteArray()) }
        val store = FakeDownloadStore()
        try {
            val attachment = Attachment("att-1", "application/vnd.android.package-archive; charset=binary", "build.apk", source.length())
            val outcome = saveToDownloads(context, attachment, source, store)
            assertEquals(DownloadOutcome.Saved, outcome)
            downloadNotice(outcome)?.let { showDownloadNotice(context, it) }
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
            val saved = store.completed.values.single()
            assertEquals("build.apk", saved.name)
            assertEquals("application/vnd.android.package-archive", saved.mediaType)
            assertEquals(0, store.pending.size)
        } finally {
            source.delete()
        }
    }

    @Test fun other_file_download_button_saves_without_opening_viewer_and_changes_label() {
        val source = File.createTempFile("enclos-card", ".apk").apply { writeBytes("apk-payload".toByteArray()) }
        val store = FakeDownloadStore()
        var opened = 0
        try {
            val attachment = Attachment("att-card", "application/vnd.android.package-archive", "build.apk", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    OtherFile(
                        a = attachment,
                        state = FileState.Ready(source),
                        itself = true,
                        retry = {},
                        open = { opened++ },
                    )
                }
            }
            compose.onNodeWithContentDescription("Download build.apk").assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { store.completed.isNotEmpty() }
            compose.waitForIdle()
            assertEquals(0, opened)
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
            compose.onNodeWithText("Download again").assertIsDisplayed()
        } finally {
            source.delete()
        }
    }

    @Test fun file_card_not_yet_fetched_fetches_first_then_saves_once() {
        val source = File.createTempFile("enclos-fetch", ".apk").apply { writeBytes("apk-payload".toByteArray()) }
        val store = FakeDownloadStore()
        var fetches = 0
        var state by mutableStateOf<FileState?>(null)
        try {
            val attachment = Attachment("att-fetch", "application/vnd.android.package-archive", "build.apk", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    OtherFile(a = attachment, state = state, itself = false, retry = { fetches++ }, open = {}, stateOf = { state })
                }
            }
            compose.onNodeWithContentDescription("Download build.apk").performClick()
            compose.waitForIdle()
            assertEquals(1, fetches)
            assertEquals(0, store.beginCalls)
            state = FileState.Ready(source)
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 5_000) { store.completed.isNotEmpty() }
            compose.waitForIdle()
            assertEquals(1, store.beginCalls)
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
        } finally {
            source.delete()
        }
    }

    @Test fun file_card_fetch_that_fails_shows_the_notice_and_saves_nothing() {
        val store = FakeDownloadStore()
        var state by mutableStateOf<FileState?>(null)
        val attachment = Attachment("att-fail", "application/octet-stream", "big.bin", 10)
        compose.setContent {
            CompositionLocalProvider(LocalDownloadStore provides store) {
                OtherFile(a = attachment, state = state, itself = false, retry = {}, open = {}, stateOf = { state })
            }
        }
        compose.onNodeWithContentDescription("Download big.bin").performClick()
        compose.waitForIdle()
        state = FileState.Failed("not connected")
        compose.waitForIdle()
        assertEquals("The transfer stopped. Nothing was saved.", ShadowToast.getTextOfLatestToast())
        assertEquals(0, store.beginCalls)
    }

    @Test fun failure_and_cancel_paths_leave_no_pending_or_completed_row() {
        val source = File.createTempFile("enclos-fail", ".bin").apply { writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() }) }
        try {
            // 1. begin returns null
            val beginFail = FakeDownloadStore(failBegin = true)
            assertEquals(DownloadOutcome.Failed, saveToDownloads(beginFail, "a.bin", "application/octet-stream", source))
            assertTrue(beginFail.pending.isEmpty())
            assertTrue(beginFail.completed.isEmpty())

            // 2. output returns null
            val outputNull = FakeDownloadStore(failOutputNull = true)
            assertEquals(DownloadOutcome.Failed, saveToDownloads(outputNull, "a.bin", "application/octet-stream", source))
            assertTrue(outputNull.pending.isEmpty())
            assertTrue(outputNull.completed.isEmpty())
            assertEquals(1, outputNull.discarded.size)

            // 3. output stream throws IOException mid-copy
            val writeFail = FakeDownloadStore(throwAfterBytes = 32 * 1024, throwOnWrite = { IOException("disk error") })
            assertEquals(DownloadOutcome.Failed, saveToDownloads(writeFail, "a.bin", "application/octet-stream", source))
            assertTrue(writeFail.pending.isEmpty())
            assertTrue(writeFail.completed.isEmpty())
            assertEquals(1, writeFail.discarded.size)

            // 4. output stream throws ENOSPC -> NoRoom
            val noRoom = FakeDownloadStore(
                throwAfterBytes = 16 * 1024,
                throwOnWrite = { IOException("write failed", ErrnoException("write", OsConstants.ENOSPC)) },
            )
            assertEquals(DownloadOutcome.NoRoom, saveToDownloads(noRoom, "a.bin", "application/octet-stream", source))
            assertTrue(noRoom.pending.isEmpty())
            assertTrue(noRoom.completed.isEmpty())
            assertEquals(1, noRoom.discarded.size)

            // 5. finish returns false
            val finishFail = FakeDownloadStore(failFinish = true)
            assertEquals(DownloadOutcome.Failed, saveToDownloads(finishFail, "a.bin", "application/octet-stream", source))
            assertTrue(finishFail.pending.isEmpty())
            assertTrue(finishFail.completed.isEmpty())
            assertEquals(1, finishFail.discarded.size)

            // 6. cancellation via isActive mid-copy
            val cancelled = FakeDownloadStore()
            var checks = 0
            val cancelOutcome = saveToDownloads(cancelled, "a.bin", "application/octet-stream", source) {
                checks++ < 4
            }
            assertEquals(DownloadOutcome.Cancelled, cancelOutcome)
            assertTrue(cancelled.pending.isEmpty())
            assertTrue(cancelled.completed.isEmpty())
            assertEquals(1, cancelled.discarded.size)

            // 7. cache file missing -> writes nothing
            val missingStore = FakeDownloadStore()
            val missingFile = File(source.parentFile, "does-not-exist-${System.nanoTime()}.bin")
            assertEquals(DownloadOutcome.Missing, saveToDownloads(missingStore, "a.bin", "application/octet-stream", missingFile))
            assertEquals(0, missingStore.beginCalls)
            assertTrue(missingStore.pending.isEmpty())
            assertTrue(missingStore.completed.isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test fun twenty_five_mib_file_copies_byte_for_byte() {
        val totalBytes = 25 * 1024 * 1024
        val source = File.createTempFile("enclos-25m", ".bin")
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        try {
            val chunk = ByteArray(64 * 1024) { i -> ((i * 31 + 7) and 0xFF).toByte() }
            source.outputStream().use { out ->
                var remaining = totalBytes
                while (remaining > 0) {
                    val toWrite = minOf(remaining, chunk.size)
                    out.write(chunk, 0, toWrite)
                    sourceDigest.update(chunk, 0, toWrite)
                    remaining -= toWrite
                }
            }
            val store = FakeDownloadStore()
            val outcome = saveToDownloads(store, "big.bin", "", source)
            assertEquals(DownloadOutcome.Saved, outcome)
            val saved = store.completed.values.single()
            assertEquals("application/octet-stream", saved.mediaType)
            assertEquals(totalBytes.toLong(), saved.bytesWritten)
            assertArrayEquals(sourceDigest.digest(), saved.sha256())
        } finally {
            source.delete()
        }
    }

    private class FakeDownloadStore(
        private val existing: MutableSet<String> = mutableSetOf(),
        private val failBegin: Boolean = false,
        private val failOutputNull: Boolean = false,
        private val throwAfterBytes: Int = Int.MAX_VALUE,
        private val throwOnWrite: (() -> Throwable)? = null,
        private val failFinish: Boolean = false,
        /** Names another app owns: not in [taken], but the folder answers for them ([exists]). */
        private val foreign: Set<String> = emptySet(),
        /** [finish] throws instead of answering, as the platform did on a clash. */
        private val finishThrows: Boolean = false,
        /** What [state] says once [finish] failed. */
        private val stateAfterFailure: RowState = RowState.Pending,
        /** Runs inside [finish], before it answers (a cancel landing there). */
        private val onFinish: () -> Unit = {},
        /** A write waits here until released: a save in flight for as long as a test wants. */
        private val gate: java.util.concurrent.CountDownLatch? = null,
    ) : DownloadStore {
        /** Counted down by the first write that reaches [gate]. */
        val writing = java.util.concurrent.CountDownLatch(1)
        class Entry(val name: String, val mediaType: String) {
            private val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten: Long = 0
                private set

            fun record(b: ByteArray, off: Int, len: Int) {
                digest.update(b, off, len)
                bytesWritten += len
            }

            fun sha256(): ByteArray = digest.digest()
        }

        private var nextId = 1
        var beginCalls = 0
            private set
        val pending = mutableMapOf<Uri, Entry>()
        val completed = mutableMapOf<Uri, Entry>()
        val discarded = mutableListOf<Uri>()

        override fun taken(): Set<String> = existing + completed.values.map { it.name }

        override fun exists(name: String): Boolean = name in foreign || name in taken()

        override fun state(uri: Uri): RowState = stateAfterFailure

        override fun begin(name: String, mediaType: String): Uri? {
            beginCalls++
            if (failBegin) return null
            val uri = Uri.parse("content://downloads/fake/${nextId++}")
            pending[uri] = Entry(name, mediaType)
            return uri
        }

        override fun output(uri: Uri): OutputStream? {
            if (failOutputNull) return null
            val entry = pending[uri] ?: return null
            return object : OutputStream() {
                private var written = 0
                override fun write(b: Int) {
                    write(byteArrayOf(b.toByte()), 0, 1)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (gate != null) {
                        writing.countDown()
                        gate.await()
                    }
                    written += len
                    if (throwOnWrite != null && written >= throwAfterBytes) {
                        throw throwOnWrite.invoke()
                    }
                    entry.record(b, off, len)
                }
            }
        }

        override fun finish(uri: Uri): Boolean {
            onFinish()
            if (finishThrows) throw IllegalStateException("UNIQUE constraint failed: files._data")
            if (failFinish) return false
            val entry = pending.remove(uri) ?: return false
            completed[uri] = entry
            return true
        }

        override fun discard(uri: Uri) {
            pending.remove(uri)
            discarded.add(uri)
        }
    }
}
