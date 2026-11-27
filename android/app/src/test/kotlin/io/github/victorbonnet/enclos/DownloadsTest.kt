package io.github.victorbonnet.enclos

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.victorbonnet.enclos.ui.DownloadController
import io.github.victorbonnet.enclos.ui.DownloadVisit
import io.github.victorbonnet.enclos.ui.LocalDownloadStore
import io.github.victorbonnet.enclos.ui.LocalDownloadVisit
import io.github.victorbonnet.enclos.ui.OtherFile
import io.github.victorbonnet.enclos.ui.ProvideDownloadVisit
import io.github.victorbonnet.enclos.ui.Viewer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
// Native graphics: an undecodable image really fails to decode, as on a phone (the default fakes a bitmap).
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
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
            assertEquals(1, refused.discarded.size)
            assertTrue(refused.pending.isEmpty())
            assertTrue(refused.completed.isEmpty())
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
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
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
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
            compose.waitForIdle()
            assertEquals(1, store.beginCalls)
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
        } finally {
            source.delete()
        }
    }

    @Test fun a_cancel_landing_while_the_file_is_published_still_clears_saving_and_shows_the_notice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("enclos-late", ".bin").apply { writeBytes("payload".toByteArray()) }
        val job = kotlinx.coroutines.Job()
        // The cancel lands inside finish: the file is published, the notice and the state still to come.
        val store = FakeDownloadStore(onFinish = { job.cancel() })
        try {
            val attachment = Attachment("att-late", "application/octet-stream", "late.bin", source.length())
            val controller = io.github.victorbonnet.enclos.ui.DownloadController(
                attachment, context, kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default), store,
            )
            controller.start(source)
            assertTrue(controller.saving)
            untilMain { !controller.saving }
            assertTrue(controller.saved)
            assertEquals("Download again", controller.label)
            assertEquals(1, store.completed.size)
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
        } finally {
            job.cancel()
            source.delete()
        }
    }

    @Test fun cancellation_before_the_save_body_starts_releases_the_shared_lock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FakeDownloadStore()
        val attachment = Attachment("att-cancel-before-start", "application/octet-stream", "file.bin", 0)
        val parent = Job()
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.add(block)
            }
        }
        val controller = DownloadController(attachment, context, CoroutineScope(parent + dispatcher), store)
        val other = DownloadController(attachment, context, CoroutineScope(Dispatchers.Main), store)
        val idleThreads = mutableListOf<Thread>()
        controller.onIdle = { idleThreads.add(Thread.currentThread()) }

        controller.start(File(context.cacheDir, "cancelled-before-start.bin"))
        assertTrue(controller.saving)
        assertFalse(other.enabled)
        parent.cancel()
        queued.single().run()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(parent.isCompleted)
        assertFalse(controller.saving)
        assertTrue(other.enabled)
        assertEquals(0, store.beginCalls)
        assertEquals("Cancelled. Nothing was saved.", ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(Looper.getMainLooper().thread), idleThreads)
    }

    @Test fun an_unexpected_save_exception_shows_failure_notice_and_releases_the_shared_lock_on_main() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FakeDownloadStore()
        val attachment = Attachment("att-save-exception", "application/octet-stream", "file.bin", 0)
        val failure = IllegalStateException("unexpected source failure")
        val source = object : File(context.cacheDir, "exception.bin") {
            override fun isFile(): Boolean = throw failure
        }
        val reported = AtomicReference<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> reported.set(error) }
        val controller = DownloadController(attachment, context, CoroutineScope(Dispatchers.Default + handler), store)
        val other = DownloadController(attachment, context, CoroutineScope(Dispatchers.Main), store)
        val idleThreads = mutableListOf<Thread>()
        controller.onIdle = { idleThreads.add(Thread.currentThread()) }

        controller.start(source)
        assertFalse(other.enabled)
        untilMain { !controller.saving }

        assertNull(reported.get())
        assertFalse(controller.saving)
        assertTrue(other.enabled)
        assertEquals(0, store.beginCalls)
        assertEquals("The file could not be saved. Nothing was saved.", ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(Looper.getMainLooper().thread), idleThreads)
    }

    @Test fun a_synchronous_launch_failure_releases_the_shared_lock_on_main() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FakeDownloadStore()
        val attachment = Attachment("att-launch-failure", "application/octet-stream", "file.bin", 0)
        val failure = IllegalStateException("scope context unavailable")
        val scope = object : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = throw failure
        }
        val controller = DownloadController(attachment, context, scope, store)
        val other = DownloadController(attachment, context, CoroutineScope(Dispatchers.Main), store)
        val idleThreads = mutableListOf<Thread>()
        controller.onIdle = { idleThreads.add(Thread.currentThread()) }

        val thrown = assertThrows(IllegalStateException::class.java) {
            controller.start(File(context.cacheDir, "launch-failure.bin"))
        }

        assertSame(failure, thrown)
        assertFalse(controller.saving)
        assertTrue(other.enabled)
        assertEquals(0, store.beginCalls)
        assertEquals(listOf(Looper.getMainLooper().thread), idleThreads)
    }

    @Test fun cancellation_during_publish_preserves_handoff_and_discards_pending_row() {
        val source = File.createTempFile("enclos-publish-cancel", ".bin").apply { writeText("payload") }
        try {
            val gone = FakeDownloadStore(stateAfterFailure = RowState.Gone, onFinish = { throw kotlinx.coroutines.CancellationException() })
            assertEquals(DownloadOutcome.Saved, saveToDownloads(gone, "a.bin", "application/octet-stream", source))
            assertTrue(gone.discarded.isEmpty())
            val pending = FakeDownloadStore(stateAfterFailure = RowState.Pending, onFinish = { throw kotlinx.coroutines.CancellationException() })
            assertEquals(DownloadOutcome.Cancelled, saveToDownloads(pending, "a.bin", "application/octet-stream", source))
            assertEquals(1, pending.discarded.size)
            assertTrue(pending.pending.isEmpty())
            assertTrue(pending.completed.isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test fun saved_label_survives_lazy_list_scroll_off_and_back_within_same_visit() {
        val source = File.createTempFile("enclos-lazy-visit", ".bin").apply { writeBytes("payload".toByteArray()) }
        val store = FakeDownloadStore()
        val listState = LazyListState()
        try {
            val attachment = Attachment("att-lazy-visit", "application/octet-stream", "big.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    ProvideDownloadVisit("conv-scroll") {
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            item(key = "file") {
                                OtherFile(a = attachment, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                            }
                            items(40) { Box(Modifier.height(100.dp)) }
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("Download big.bin").performClick()
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
            compose.waitForIdle()
            compose.onNodeWithText("Download again").assertIsDisplayed()

            // Scroll completely away so the file card leaves composition.
            compose.runOnIdle { listState.requestScrollToItem(39) }
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithContentDescription("Download again big.bin").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithContentDescription("Download big.bin").fetchSemanticsNodes().isEmpty())

            // Scroll back within the same conversation visit: "Download again" survives.
            compose.runOnIdle { listState.requestScrollToItem(0) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Download again big.bin").assertIsDisplayed()
            compose.onNodeWithText("Download again").assertIsDisplayed()
        } finally {
            source.delete()
        }
    }

    @Test fun visit_end_releases_idle_controllers_and_new_visit_starts_fresh() {
        val source = File.createTempFile("enclos-visit-end", ".bin").apply { writeBytes("payload".toByteArray()) }
        val store = FakeDownloadStore()
        val firstVisit = DownloadVisit()
        var activeVisit by mutableStateOf<DownloadVisit?>(firstVisit)
        try {
            val attachment = Attachment("att-visit-end", "application/octet-stream", "doc.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    activeVisit?.let { visit ->
                        CompositionLocalProvider(LocalDownloadVisit provides visit) {
                            OtherFile(a = attachment, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("Download doc.bin").performClick()
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
            compose.waitForIdle()
            compose.onNodeWithText("Download again").assertIsDisplayed()
            assertEquals(1, firstVisit.size)

            // End the visit: idle controller is released immediately.
            compose.runOnIdle {
                activeVisit = null
                firstVisit.end()
            }
            compose.waitForIdle()
            assertEquals(0, firstVisit.size)

            // Re-entering the conversation starts a fresh visit with "Download".
            val secondVisit = DownloadVisit()
            activeVisit = secondVisit
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Download doc.bin").assertIsDisplayed()
            compose.onNodeWithText("Download").assertIsDisplayed()
            compose.runOnIdle { secondVisit.end() }
            assertEquals(0, secondVisit.size)
        } finally {
            source.delete()
        }
    }

    @Test fun visit_end_lets_active_save_finish_safely_then_releases_controller() {
        val source = File.createTempFile("enclos-visit-active", ".bin").apply { writeBytes("payload".toByteArray()) }
        val gate = java.util.concurrent.CountDownLatch(1)
        val store = FakeDownloadStore(gate = gate)
        val visit = DownloadVisit()
        var shown by mutableStateOf(true)
        try {
            val attachment = Attachment("att-visit-active", "application/octet-stream", "big.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store, LocalDownloadVisit provides visit) {
                    if (shown) OtherFile(a = attachment, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                }
            }
            compose.onNodeWithContentDescription("Download big.bin").performClick()
            untilMain { store.writing.count == 0L }
            assertEquals(1, visit.size)

            // Genuine visit end while a save is in flight: save continues safely.
            compose.runOnIdle {
                shown = false
                visit.end()
            }
            compose.waitForIdle()
            assertEquals(1, visit.size)
            assertTrue(store.completed.isEmpty())

            gate.countDown()
            untilMain { store.completed.isNotEmpty() && visit.size == 0 }
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
            assertTrue(store.discarded.isEmpty())
            assertEquals(0, visit.size)
        } finally {
            gate.countDown()
            source.delete()
        }
    }

    @Test fun concurrent_visits_do_not_conflate_saved_labels_or_cleanup() {
        val source = File.createTempFile("enclos-concurrent-visits", ".bin").apply { writeBytes("payload".toByteArray()) }
        val store = FakeDownloadStore()
        val visitA = DownloadVisit()
        val visitB = DownloadVisit()
        var showB by mutableStateOf(true)
        try {
            val attachmentA = Attachment("att-visit-a", "application/octet-stream", "alpha.bin", source.length())
            val attachmentB = Attachment("att-visit-b", "application/octet-stream", "beta.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    Column {
                        CompositionLocalProvider(LocalDownloadVisit provides visitA) {
                            OtherFile(a = attachmentA, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                        }
                        if (showB) {
                            CompositionLocalProvider(LocalDownloadVisit provides visitB) {
                                OtherFile(a = attachmentA, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                                OtherFile(a = attachmentB, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                            }
                        }
                    }
                }
            }
            // Save alpha.bin in visitA only.
            compose.onAllNodesWithContentDescription("Download alpha.bin")[0].performClick()
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
            compose.waitForIdle()

            // visitA shows "Download again", while visitB still shows "Download" for the same attachment.
            compose.onNodeWithContentDescription("Download again alpha.bin").assertIsDisplayed()
            compose.onNodeWithContentDescription("Download alpha.bin").assertIsDisplayed()
            assertEquals(1, visitA.size)
            assertEquals(2, visitB.size)

            // Ending visitB releases only visitB and leaves visitA's saved label intact.
            compose.runOnIdle {
                showB = false
                visitB.end()
            }
            compose.waitForIdle()
            assertEquals(0, visitB.size)
            assertEquals(1, visitA.size)
            compose.onNodeWithContentDescription("Download again alpha.bin").assertIsDisplayed()

            compose.runOnIdle { visitA.end() }
            assertEquals(0, visitA.size)
        } finally {
            source.delete()
        }
    }

    @Test fun abandoned_fetch_wait_is_cancelled_and_released_when_visit_ends() {
        val source = File.createTempFile("enclos-abandoned-wait", ".bin").apply { writeBytes("payload".toByteArray()) }
        val store = FakeDownloadStore()
        val visit = DownloadVisit()
        var state by mutableStateOf<FileState?>(null)
        var shown by mutableStateOf(true)
        var fetches = 0
        try {
            val attachment = Attachment("att-abandoned-wait", "application/octet-stream", "late.bin", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store, LocalDownloadVisit provides visit) {
                    if (shown) {
                        OtherFile(
                            a = attachment,
                            state = state,
                            itself = false,
                            retry = { fetches++ },
                            open = {},
                            stateOf = { state },
                        )
                    }
                }
            }
            compose.onNodeWithContentDescription("Download late.bin").performClick()
            compose.waitForIdle()
            assertEquals(1, fetches)
            assertEquals(1, visit.size)

            // Genuine visit end cancels the abandoned wait and releases the controller without a toast.
            compose.runOnIdle {
                shown = false
                visit.end()
            }
            compose.waitForIdle()
            assertEquals(0, visit.size)
            assertNull(ShadowToast.getTextOfLatestToast())

            // Even if the fetch settles later, the abandoned wait does not start a save.
            state = FileState.Ready(source)
            compose.waitForIdle()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, store.beginCalls)
            assertEquals(0, visit.size)
        } finally {
            source.delete()
        }
    }

    @Test fun start_when_fetched_immediate_completion_clears_wait_and_calls_on_idle_once() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("enclos-imm-wait", ".bin").apply { writeBytes("payload".toByteArray()) }
        try {
            // 1. Immediate failure on Main.immediate: wait clears and onIdle fires once.
            val failStore = FakeDownloadStore()
            val failAtt = Attachment("att-imm-fail", "application/octet-stream", "fail.bin", 10)
            var failState: FileState? = null
            val failController = DownloadController(failAtt, context, CoroutineScope(Dispatchers.Main.immediate), failStore)
            var failIdle = 0
            failController.onIdle = { failIdle++ }
            failController.startWhenFetched({ failState }) { failState = FileState.Failed("not connected") }
            assertTrue(failController.idle)
            assertEquals(1, failIdle)
            assertEquals("The transfer stopped. Nothing was saved.", ShadowToast.getTextOfLatestToast())

            // 2. Immediate ready on Main.immediate: wait hands off to save, onIdle fires once when save finishes.
            val readyStore = FakeDownloadStore()
            val readyAtt = Attachment("att-imm-ready", "application/octet-stream", "ready.bin", source.length())
            var readyState: FileState? = null
            val readyController = DownloadController(readyAtt, context, CoroutineScope(Dispatchers.Main.immediate), readyStore)
            var readyIdle = 0
            readyController.onIdle = { readyIdle++ }
            readyController.startWhenFetched({ readyState }) { readyState = FileState.Ready(source) }
            assertTrue(readyController.saving)
            assertEquals(0, readyIdle)
            untilMain { !readyController.saving }
            assertTrue(readyController.idle)
            assertTrue(readyController.saved)
            assertEquals(1, readyIdle)
            assertEquals(1, readyStore.completed.size)

            // 3. Immediate ready when busy elsewhere: tryStart returns false, wait clears and onIdle fires once.
            val gate = java.util.concurrent.CountDownLatch(1)
            val busyStore = FakeDownloadStore(gate = gate)
            val busyAtt = Attachment("att-imm-busy", "application/octet-stream", "busy.bin", source.length())
            val holder = DownloadController(busyAtt, context, CoroutineScope(Dispatchers.Main.immediate), busyStore)
            val waiter = DownloadController(busyAtt, context, CoroutineScope(Dispatchers.Main.immediate), busyStore)
            var busyState: FileState? = null
            var waiterIdle = 0
            waiter.onIdle = { waiterIdle++ }
            // Start wait first, then acquire lock synchronously inside fetch() before delivering Ready.
            waiter.startWhenFetched({ busyState }) {
                holder.start(source)
                busyState = FileState.Ready(source)
            }
            assertTrue(waiter.idle)
            assertEquals(1, waiterIdle)
            assertEquals(1, busyStore.beginCalls)
            gate.countDown()
            untilMain { !holder.saving }
        } finally {
            source.delete()
        }
    }

    @Test fun start_when_fetched_cancellation_and_replacement_call_on_idle_once() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FakeDownloadStore()
        val attachment = Attachment("att-wait-cancel", "application/octet-stream", "wait.bin", 10)

        // 1. Replacement does not fire onIdle for the replaced wait; cancelWait fires onIdle once.
        var state: FileState? = null
        val controller = DownloadController(attachment, context, CoroutineScope(Dispatchers.Main.immediate), store)
        var idleCount = 0
        controller.onIdle = { idleCount++ }
        controller.startWhenFetched({ state }) {}
        assertFalse(controller.idle)
        controller.startWhenFetched({ state }) {}
        assertFalse(controller.idle)
        assertEquals(0, idleCount)
        controller.cancelWait()
        assertTrue(controller.idle)
        assertEquals(1, idleCount)
        // Repeating cancelWait is a no-op.
        controller.cancelWait()
        assertEquals(1, idleCount)

        // 2. Pre-start scope cancellation of startWhenFetched clears waiting and calls onIdle once on Main.
        val parent = Job()
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.add(block)
            }
        }
        val queuedController = DownloadController(attachment, context, CoroutineScope(parent + dispatcher), store)
        val idleThreads = mutableListOf<Thread>()
        queuedController.onIdle = { idleThreads.add(Thread.currentThread()) }
        queuedController.startWhenFetched({ state }) {}
        assertFalse(queuedController.idle)
        parent.cancel()
        queued.single().run()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(queuedController.idle)
        assertEquals(listOf(Looper.getMainLooper().thread), idleThreads)
    }

    @Test fun shared_lock_serializes_card_and_viewer_in_both_directions_and_releases_on_cancel() {
        val source = File.createTempFile("enclos-shared-lock", ".png").apply { writeBytes(ByteArray(128 * 1024) { 1 }) }
        val gate1 = java.util.concurrent.CountDownLatch(1)
        val store1 = FakeDownloadStore(gate = gate1)
        val gate2 = java.util.concurrent.CountDownLatch(1)
        val store2 = FakeDownloadStore(gate = gate2)
        var store by mutableStateOf(store1)
        var showViewer by mutableStateOf(true)
        try {
            val attachment = Attachment("att-shared-lock", "image/png", "photo.png", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    ProvideDownloadVisit("conv-shared") {
                        OtherFile(a = attachment, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                        if (showViewer) Viewer(a = attachment, file = source) { showViewer = false }
                    }
                }
            }
            // Direction 1: Card save disables Viewer Download until card save finishes.
            compose.onAllNodesWithContentDescription("Download photo.png")[0].performClick()
            untilMain { store1.writing.count == 0L }
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("Download photo.png")[0].assertIsNotEnabled()
            compose.onAllNodesWithContentDescription("Download photo.png")[1].assertIsNotEnabled()
            gate1.countDown()
            untilMain {
                store1.completed.isNotEmpty() &&
                    ShadowToast.getTextOfLatestToast() == "Saved to Downloads" &&
                    compose.onAllNodesWithContentDescription("Download again photo.png").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Download again photo.png").assertIsEnabled()
            compose.onNodeWithContentDescription("Download photo.png").assertIsEnabled()

            // Direction 2: Viewer save disables Card Download; closing Viewer cancels save and re-enables Card.
            store = store2
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("Download photo.png")[1].performClick()
            untilMain { store2.writing.count == 0L }
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("Download photo.png")[0].assertIsNotEnabled()
            compose.onAllNodesWithContentDescription("Download photo.png")[1].assertIsNotEnabled()

            // Close the viewer to cancel its coroutine scope while the save is in flight.
            showViewer = false
            compose.waitForIdle()
            gate2.countDown()
            untilMain {
                store2.discarded.size == 1 &&
                    ShadowToast.getTextOfLatestToast() == "Cancelled. Nothing was saved." &&
                    compose.onAllNodesWithContentDescription("Download photo.png")
                        .fetchSemanticsNodes()
                        .singleOrNull()
                        ?.config
                        ?.getOrNull(SemanticsProperties.Disabled) == null
            }
            compose.waitForIdle()
            assertEquals("Cancelled. Nothing was saved.", ShadowToast.getTextOfLatestToast())
            assertEquals(1, store2.discarded.size)
            assertTrue(store2.pending.isEmpty())
            assertTrue(store2.completed.isEmpty())
            compose.onNodeWithContentDescription("Download photo.png").assertIsEnabled()
        } finally {
            gate1.countDown()
            gate2.countDown()
            source.delete()
        }
    }

    @Test fun other_file_and_viewer_download_semantics_have_no_duplicate_click_label() {
        val source = File.createTempFile("enclos-a11y", ".apk").apply { writeBytes("payload".toByteArray()) }
        val store = FakeDownloadStore()
        try {
            val doc = Attachment("att-a11y-doc", "application/vnd.android.package-archive", "build.apk", source.length())
            val img = Attachment("att-a11y-img", "image/png", "photo.png", source.length())
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    ProvideDownloadVisit("conv-a11y") {
                        OtherFile(a = doc, state = FileState.Ready(source), itself = true, retry = {}, open = {})
                        Viewer(a = img, file = source) {}
                    }
                }
            }
            val cardNode = compose.onNodeWithContentDescription("Download build.apk").fetchSemanticsNode()
            assertEquals(listOf("Download build.apk"), cardNode.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(Role.Button, cardNode.config.getOrNull(SemanticsProperties.Role))
            assertNull(cardNode.config.getOrNull(SemanticsActions.OnClick)?.label)

            val viewerNode = compose.onNodeWithContentDescription("Download photo.png").fetchSemanticsNode()
            assertEquals(listOf("Download photo.png"), viewerNode.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(Role.Button, viewerNode.config.getOrNull(SemanticsProperties.Role))
            assertNull(viewerNode.config.getOrNull(SemanticsActions.OnClick)?.label)
        } finally {
            source.delete()
        }
    }

    @Test fun an_undecodable_image_whose_file_was_cleared_is_fetched_again_and_then_saved() {
        val garbage = File.createTempFile("enclos-undecodable", ".png").apply { writeBytes("not an image".toByteArray()) }
        val fresh = File.createTempFile("enclos-undecodable-fresh", ".png").apply { writeBytes("still not an image".toByteArray()) }
        val store = FakeDownloadStore()
        val states = androidx.compose.runtime.mutableStateMapOf<String, FileState>()
        var fetches = 0
        try {
            val attachment = Attachment("att-undecodable", "image/png", "photo.png", garbage.length())
            states[attachment.id] = FileState.Ready(garbage)
            compose.setContent {
                CompositionLocalProvider(LocalDownloadStore provides store) {
                    io.github.victorbonnet.enclos.ui.Files(listOf(attachment), states) { fetches++ }
                }
            }
            // The image does not decode: its line, with a Download control, stands in for it.
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithContentDescription("Download photo.png").fetchSemanticsNodes().isNotEmpty()
            }
            // The system clears the cache while the line is shown, then Download is tapped.
            assertTrue(garbage.delete())
            compose.onNodeWithContentDescription("Download photo.png").performClick()
            compose.waitForIdle()
            assertEquals(1, fetches)
            assertEquals(0, store.beginCalls)
            states[attachment.id] = FileState.Loading(0, fresh.length())
            compose.waitForIdle()
            states[attachment.id] = FileState.Ready(fresh)
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
            compose.waitForIdle()
            assertEquals(1, store.beginCalls)
            assertEquals("Saved to Downloads", ShadowToast.getTextOfLatestToast())
        } finally {
            garbage.delete()
            fresh.delete()
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
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
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
            untilMain { store.completed.isNotEmpty() && ShadowToast.getTextOfLatestToast() != null }
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
