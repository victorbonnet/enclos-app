package io.github.victorbonnet.enclos

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real MediaStore against the name and type rules of the security fixes: what the
 * platform stores is the name the app chose, whatever type the agent sent.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsNamingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun source(): File = File(context.cacheDir, "naming-src").apply { writeBytes("naming-bytes".toByteArray()) }

    private fun save(name: String, mime: String): Pair<DownloadOutcome, Set<String>> {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val store = MediaStoreDownloadStore(context.contentResolver)
        val outcome = saveToDownloads(store, name, mime, source())
        return outcome to store.taken()
    }

    @Test fun a_pdf_sent_with_an_apk_type_keeps_its_name() {
        val (outcome, names) = save("nm_report.pdf", "application/vnd.android.package-archive")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), "nm_report.pdf" in names)
        assertTrue(names.toString(), names.none { it.startsWith("nm_report.pdf.") })
    }

    @Test fun an_apk_sent_with_an_image_type_keeps_its_name() {
        val (outcome, names) = save("nm_img.apk", "image/png")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), "nm_img.apk" in names)
        assertTrue(names.toString(), names.none { it.startsWith("nm_img.apk.") })
    }

    @Test fun a_name_with_no_extension_is_not_given_one_by_the_agents_type() {
        val (outcome, names) = save("nm_noext", "application/pdf")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), "nm_noext" in names)
    }

    @Test fun a_padded_name_is_saved_with_one_space_and_its_extension_in_view() {
        val (outcome, names) = save("nm_pad" + " ".repeat(50) + ".apk", "application/octet-stream")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), "nm_pad .apk" in names)
    }

    @Test fun a_leading_dot_is_not_a_hidden_file() {
        val (outcome, names) = save(" . .nm_profile", "text/plain")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), "nm_profile" in names)
        assertTrue(names.toString(), names.none { it.startsWith(".") && "nm_profile" in it })
    }

    @Test fun the_row_state_follows_the_row_and_the_folder_answers_for_a_saved_name() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val store = MediaStoreDownloadStore(context.contentResolver)
        val uri = store.begin("nm_state.bin", "application/octet-stream")!!
        assertEquals(RowState.Pending, store.state(uri))
        store.output(uri)!!.use { it.write(1) }
        assertTrue(store.finish(uri))
        assertEquals(RowState.Done, store.state(uri))
        assertTrue(store.exists("nm_state.bin"))
        store.discard(uri)
        assertEquals(RowState.Gone, store.state(uri))
    }
}
