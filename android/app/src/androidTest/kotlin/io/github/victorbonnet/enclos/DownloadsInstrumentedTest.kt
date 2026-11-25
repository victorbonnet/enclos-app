package io.github.victorbonnet.enclos

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Real MediaStore.Downloads tests on an API 29+ emulator or device:
 * - AC2: PNG, PDF, APK, and 25 MiB files written byte-for-byte (SHA-256).
 * - AC3: second download of the same name writes `name (1).ext` leaving the first unchanged.
 * - AC5: a cancelled coroutine leaves no pending (`IS_PENDING = 1`) row in Downloads.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "enclos_dl_test_${System.currentTimeMillis()}_"
    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val uri = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$prefix%")
        resolver.query(uri, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val rowUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                runCatching { resolver.delete(rowUri, null, null) }
            }
        }
    }

    @Test
    fun writes_png_pdf_apk_and_25_mib_files_with_matching_sha256() = runBlocking {
        assumeTrue("MediaStore.Downloads requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val cases = listOf(
            Triple("${prefix}image.png", "image/png", ByteArray(4096) { (it * 7).toByte() }),
            Triple("${prefix}doc.pdf", "application/pdf", "%PDF-1.4\nhello\n%%EOF\n".toByteArray()),
            Triple("${prefix}build.apk", "application/vnd.android.package-archive", ByteArray(8192) { (it * 13).toByte() }),
        )
        for ((name, mime, bytes) in cases) {
            val file = makeTempFile(name, bytes)
            val outcome = saveToDownloads(context, Attachment("id-$name", mime, name, file.length()), file)
            assertEquals("save $name", DownloadOutcome.Saved, outcome)
            val savedUri = findDownloadUri(name)
            assertNotNull("row exists for $name", savedUri)
            assertArrayEquals("SHA-256 matches for $name", sha256(file), sha256(savedUri!!))
        }

        // 25 MiB file
        val bigName = "${prefix}large.bin"
        val bigFile = File(context.cacheDir, bigName).also { tempFiles.add(it) }
        val chunk = ByteArray(64 * 1024) { i -> ((i * 29 + 3) and 0xFF).toByte() }
        val total = 25 * 1024 * 1024
        bigFile.outputStream().use { out ->
            var left = total
            while (left > 0) {
                val n = minOf(left, chunk.size)
                out.write(chunk, 0, n)
                left -= n
            }
        }
        val bigOutcome = saveToDownloads(context, Attachment("id-big", "application/octet-stream", bigName, bigFile.length()), bigFile)
        assertEquals(DownloadOutcome.Saved, bigOutcome)
        val bigUri = findDownloadUri(bigName)
        assertNotNull("row exists for 25 MiB file", bigUri)
        assertArrayEquals("SHA-256 matches for 25 MiB file", sha256(bigFile), sha256(bigUri!!))
    }

    @Test
    fun second_download_of_same_name_writes_numbered_copy_leaving_first_unchanged() = runBlocking {
        assumeTrue("MediaStore.Downloads requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val name = "${prefix}clash.pdf"
        val numbered = "${prefix}clash (1).pdf"
        val firstBytes = "%PDF-1.4\nfirst\n%%EOF\n".toByteArray()
        val secondBytes = "%PDF-1.4\nsecond-version\n%%EOF\n".toByteArray()
        val firstFile = makeTempFile("first.pdf", firstBytes)
        val secondFile = makeTempFile("second.pdf", secondBytes)

        assertEquals(DownloadOutcome.Saved, saveToDownloads(context, Attachment("id-1", "application/pdf", name, firstFile.length()), firstFile))
        assertEquals(DownloadOutcome.Saved, saveToDownloads(context, Attachment("id-2", "application/pdf", name, secondFile.length()), secondFile))

        val firstUri = findDownloadUri(name)
        val secondUri = findDownloadUri(numbered)
        assertNotNull("first row exists", firstUri)
        assertNotNull("numbered row exists: $numbered", secondUri)
        assertArrayEquals("first row unchanged", sha256(firstFile), sha256(firstUri!!))
        assertArrayEquals("second row matches second bytes", sha256(secondFile), sha256(secondUri!!))
    }

    @Test
    fun cancelled_download_leaves_no_pending_row() = runBlocking {
        assumeTrue("MediaStore.Downloads requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val name = "${prefix}cancelled.bin"
        val bigFile = File(context.cacheDir, name).also { tempFiles.add(it) }
        val chunk = ByteArray(64 * 1024) { 0x5A }
        bigFile.outputStream().use { out ->
            repeat(25 * 16) { out.write(chunk) } // 25 MiB
        }

        val job = launch(Dispatchers.IO) {
            saveToDownloads(context, Attachment("id-cancel", "application/octet-stream", name, bigFile.length()), bigFile)
        }
        delay(5)
        job.cancelAndJoin()

        val resolver = context.contentResolver
        val pendingUri = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1"
        val args = arrayOf("${prefix}cancelled%")
        var pendingCount = 0
        resolver.query(pendingUri, projection, selection, args, null)?.use { cursor ->
            pendingCount = cursor.count
        }
        assertEquals("no pending row left after cancel", 0, pendingCount)
    }

    private fun makeTempFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply {
            writeBytes(bytes)
            tempFiles.add(this)
        }

    private fun findDownloadUri(displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND (${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
        val args = arrayOf(displayName, Environment.DIRECTORY_DOWNLOADS, "${Environment.DIRECTORY_DOWNLOADS}/")
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }

    private fun sha256(uri: Uri): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        val stream = context.contentResolver.openInputStream(uri)
        assertTrue("could open $uri", stream != null)
        stream!!.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}
