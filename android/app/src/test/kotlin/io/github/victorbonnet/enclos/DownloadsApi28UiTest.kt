package io.github.victorbonnet.enclos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.victorbonnet.enclos.ui.ArtefactViewer
import io.github.victorbonnet.enclos.ui.FileViewer
import io.github.victorbonnet.enclos.ui.LocalDownloadsOffered
import io.github.victorbonnet.enclos.ui.OtherFile
import io.github.victorbonnet.enclos.ui.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * AC8: where Download is not offered (Android 9, API 28), no placement draws it
 * (file card, document viewer, image viewer, HTML artefact reader) while Share
 * stays. The platform predicate is covered in [DownloadsTest]; here the screens
 * are composed with [LocalDownloadsOffered] set to what the predicate returns on
 * API 28 (the API 28 Robolectric runtime itself could not be fetched offline).
 * Each placement is also composed with it on, so "absent" is not an empty page.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadsApi28UiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun offered(on: Boolean, content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDownloadsOffered provides on, content = content)
    }

    private fun assertDownload(on: Boolean) {
        if (on) compose.onNodeWithText("Download").assertExists() else compose.onNodeWithText("Download").assertDoesNotExist()
    }

    private fun card(on: Boolean) {
        val file = File.createTempFile("api28-card", ".apk").apply { writeBytes("apk".toByteArray()) }
        try {
            val apk = Attachment("att-1", "application/vnd.android.package-archive", "build.apk", file.length())
            offered(on) { OtherFile(apk, FileState.Ready(file), itself = true, retry = {}, open = {}) }
            compose.onNodeWithText("build.apk").assertExists()
            assertDownload(on)
        } finally {
            file.delete()
        }
    }

    private fun document(on: Boolean) {
        val file = File.createTempFile("api28-doc", ".apk").apply { writeBytes("apk".toByteArray()) }
        try {
            val apk = Attachment("att-2", "application/vnd.android.package-archive", "build.apk", file.length())
            offered(on) { FileViewer(apk, file) {} }
            compose.onNodeWithText("Share").assertExists()
            assertDownload(on)
        } finally {
            file.delete()
        }
    }

    private fun image(on: Boolean) {
        val file = File.createTempFile("api28-img", ".png").apply { writeBytes(byteArrayOf()) }
        try {
            val img = Attachment("att-3", "image/png", "shot.png", 10)
            offered(on) { Viewer(img, file) {} }
            compose.onNodeWithText("Share").assertExists()
            assertDownload(on)
        } finally {
            file.delete()
        }
    }

    private fun artefact(on: Boolean) {
        val missing = File("nonexistent-api28-artefact.html")
        val html = Attachment("att-4", "text/html", "report.html", 42)
        offered(on) { ArtefactViewer(html, missing) {} }
        compose.onNodeWithText("Share").assertExists()
        assertDownload(on)
    }

    @Test fun file_card_draws_no_download_where_not_offered() = card(false)

    @Test fun file_card_draws_download_where_offered() = card(true)

    @Test fun document_viewer_draws_no_download_where_not_offered() = document(false)

    @Test fun document_viewer_draws_download_where_offered() = document(true)

    @Test fun image_viewer_draws_no_download_where_not_offered() = image(false)

    @Test fun image_viewer_draws_download_where_offered() = image(true)

    @Test fun artefact_viewer_draws_no_download_where_not_offered() = artefact(false)

    @Test fun artefact_viewer_draws_download_where_offered() = artefact(true)
}
