package io.github.victorbonnet.enclos

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The real MediaStore against the name and type rules of the security fixes: what the
 * platform stores is the name the app chose, whatever type the agent sent.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsNamingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "nm_${UUID.randomUUID()}_"
    private val rows = mutableListOf<Uri>()
    private var source: File? = null

    @Suppress("DEPRECATION") // Include pending rows on API 29 as well.
    @After fun remove_what_the_test_saved() {
        val leftovers = mutableListOf<Uri>()
        try {
            if (rows.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val store = MediaStoreDownloadStore(context.contentResolver)
                rows.forEach { uri ->
                    context.contentResolver.delete(MediaStore.setIncludePending(uri), null, null)
                    if (store.state(uri) != RowState.Gone) leftovers.add(uri)
                }
            }
        } finally {
            source?.let { file -> assertTrue("Could not delete $file", file.delete()) }
        }
        assertTrue("Rows not removed: $leftovers", leftovers.isEmpty())
    }

    private fun trackedStore(): DownloadStore {
        val mediaStore = MediaStoreDownloadStore(context.contentResolver)
        return object : DownloadStore by mediaStore {
            override fun begin(name: String, mediaType: String): Uri? =
                mediaStore.begin(name, mediaType)?.also { rows.add(it) }
        }
    }

    private fun save(name: String, mime: String): Pair<DownloadOutcome, Set<String>> {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val store = trackedStore()
        val file = File.createTempFile("naming-src-", ".bin", context.cacheDir)
        source = file
        file.writeBytes("naming-bytes".toByteArray())
        val outcome = saveToDownloads(store, name, mime, file)
        return outcome to store.taken()
    }

    @Test fun a_pdf_sent_with_an_apk_type_keeps_its_name() {
        val name = "${prefix}report.pdf"
        val (outcome, names) = save(name, "application/vnd.android.package-archive")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), name in names)
        assertTrue(names.toString(), names.none { it.startsWith("$name.") })
    }

    @Test fun an_apk_sent_with_an_image_type_keeps_its_name() {
        val name = "${prefix}img.apk"
        val (outcome, names) = save(name, "image/png")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), name in names)
        assertTrue(names.toString(), names.none { it.startsWith("$name.") })
    }

    @Test fun a_name_with_no_extension_is_not_given_one_by_the_agents_type() {
        val name = "${prefix}noext"
        val (outcome, names) = save(name, "application/pdf")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), name in names)
    }

    @Test fun a_padded_name_is_saved_with_one_space_and_its_extension_in_view() {
        val stem = "${prefix}pad"
        val (outcome, names) = save(stem + " ".repeat(30) + ".apk", "application/octet-stream")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), "$stem .apk" in names)
    }

    @Test fun a_leading_dot_is_not_a_hidden_file() {
        val name = "${prefix}profile"
        val (outcome, names) = save(" . .$name", "text/plain")
        assertEquals(DownloadOutcome.Saved, outcome)
        assertTrue(names.toString(), name in names)
        assertTrue(names.toString(), names.none { it.startsWith(".") && name in it })
    }

    @Test fun the_row_state_follows_the_row_and_the_folder_answers_for_a_saved_name() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val store = trackedStore()
        val name = "${prefix}state.bin"
        val uri = store.begin(name, "application/octet-stream")!!
        assertEquals(RowState.Pending, store.state(uri))
        store.output(uri)!!.use { it.write(1) }
        assertTrue(store.finish(uri))
        assertEquals(RowState.Done, store.state(uri))
        assertTrue(store.exists(name))
        store.discard(uri)
        assertEquals(RowState.Gone, store.state(uri))
    }
}
