package io.github.victorbonnet.enclos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/** A document provider as pickers hand out: its own display names and media types, read through the real resolver. */
class PreparationProvider : ContentProvider() {
    class Served(val file: File, val name: String?, val type: String?)
    companion object {
        const val AUTHORITY = "io.github.victorbonnet.enclos.test.documents"
        var accesses = 0
        val served = mutableMapOf<String, Served>()
        fun uri(key: String): Uri = Uri.parse("content://$AUTHORITY/document/$key")
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        accesses++
        val one = served.getValue(uri.lastPathSegment!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(one.name, one.file.length())) }
    }
    override fun getType(uri: Uri) = served[uri.lastPathSegment]?.type
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(served.getValue(uri.lastPathSegment!!).file, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/**
 * Preparation through a real ContentProvider and the app's ContentResolver
 * (JVM, Robolectric with native graphics; not a device's system picker).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderPreparationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File

    @Before fun provider() {
        dir = java.nio.file.Files.createTempDirectory("provider").toFile()
        Robolectric.buildContentProvider(PreparationProvider::class.java).create(PreparationProvider.AUTHORITY)
        PreparationProvider.served.clear()
    }

    @After fun clean() { PreparationProvider.served.clear(); dir.deleteRecursively() }

    private fun serve(key: String, bytes: ByteArray, name: String?, type: String?): Uri {
        val file = File(dir, key).apply { writeBytes(bytes) }
        PreparationProvider.served[key] = PreparationProvider.Served(file, name, type)
        return PreparationProvider.uri(key)
    }

    private fun prepare(picks: List<Uri>, source: FileSource, budget: PreparedBudget = PreparedBudget(80_000_000), images: Boolean = true) =
        prepareSendFiles(context, picks, source, budget, images = images)

    @Test fun documents_take_the_protocol_type_of_their_extension_whatever_the_provider_calls_them() {
        val cases = listOf(
            Triple("tool.py", "application/octet-stream", "text/plain"), Triple("run.sh", "application/x-sh", "text/plain"),
            Triple("table.csv", "text/comma-separated-values", "text/csv"), Triple("app.log", "application/octet-stream", "text/plain"),
            Triple("config.toml", "application/octet-stream", "application/toml"), Triple("README.md", "text/x-markdown", "text/markdown"),
            Triple("paper.pdf", "application/octet-stream", "application/pdf"), Triple("plain.txt", null, "text/plain"),
        )
        val budget = PreparedBudget(80_000_000)
        val picks = cases.mapIndexed { i, (name, alias, _) -> serve("doc$i", "content of $name\n".toByteArray(), name, alias) }
        val result = prepare(picks, FileSource.Files, budget)
        assertEquals(emptyList<String>(), result.refused)
        assertEquals(cases.map { it.first }, result.files.map { it.name })
        assertEquals(cases.map { it.third }, result.files.map { it.mime })
        result.files.forEachIndexed { i, f -> assertArrayEquals("content of ${cases[i].first}\n".toByteArray(), f.bytes) }
        assertEquals(result.files.sumOf { it.bytes.size }.toLong(), budget.used())
    }

    @Test fun each_refused_document_is_named_safely_and_the_others_are_kept() {
        val budget = PreparedBudget(80_000_000)
        val picks = listOf(
            serve("ok", "fine".toByteArray(), "fine.txt", "text/plain"),
            serve("exe", "MZ".toByteArray(), "setup.exe", "application/octet-stream"),
            serve("rtl", "x".toByteArray(), "invoice‮txt.exe", "text/plain"),
            serve("slash", "x".toByteArray(), "../secret.txt", "text/plain"),
            serve("space", "x".toByteArray(), "trailing.txt ", "text/plain"),
            serve("bare", "x".toByteArray(), ".pdf", "application/pdf"),
            serve("anon", "x".toByteArray(), null, "text/plain"),
            serve("empty", ByteArray(0), "empty.txt", "text/plain"),
        )
        val result = prepare(picks, FileSource.Files, budget)
        assertEquals(listOf("fine.txt"), result.files.map { it.name })
        assertEquals(7, result.refused.size)
        assertTrue(result.refused[0], result.refused[0].startsWith("setup.exe ("))
        assertTrue("hidden characters are written out", result.refused[1].startsWith("invoice⟨U+202E⟩txt.exe ("))
        assertTrue(result.refused[2], result.refused[2].startsWith("../secret.txt ("))
        assertTrue(result.refused[5], result.refused[5].startsWith("a file without a name ("))
        assertTrue(result.refused[6], result.refused[6].startsWith("empty.txt ("))
        result.refused.forEach { assertFalse("no provider exception text: $it", it.contains("Exception") || it.contains(dir.path)) }
        assertEquals("only the kept file is held", 4L, budget.used())
    }

    @Test fun files_keep_png_and_gif_bytes_names_transparency_and_frames_and_repeated_names_become_distinct() {
        val png = pngWithAlpha()
        val gif = animatedGif()
        val budget = PreparedBudget(80_000_000)
        val result = prepare(budget = budget, source = FileSource.Files, picks = listOf(
            serve("png", png, "clear.png", "application/octet-stream"),
            serve("gif", gif, "moving.gif", "image/gif"),
            serve("png2", png, "clear.png", "image/png"),
            serve("png3", png, "CLEAR.png", "image/png"),
        ))
        assertEquals(listOf("clear.png", "moving.gif", "clear-2.png", "CLEAR-3.png"), result.files.map { it.name })
        assertEquals("each renamed file is the only owner of its bytes", result.files.sumOf { it.bytes.size }.toLong(), budget.used())
        assertEquals(listOf("image/png", "image/gif", "image/png", "image/png"), result.files.map { it.mime })
        assertArrayEquals("a PNG goes exactly as it is", png, result.files[0].bytes)
        assertArrayEquals("a GIF's frames go exactly as they are", gif, result.files[1].bytes)
        assertArrayEquals(png, result.files[2].bytes)
    }

    @Test fun later_selections_never_repeat_a_draft_name_and_each_file_has_one_owner() {
        val budget = PreparedBudget(80_000_000)
        // Two photos, each selected alone, then a document named like them.
        val first = prepare(listOf(serve("c1", jpeg(), null, "image/jpeg")), FileSource.Photos, budget)
        val second = prepareSendFiles(context, listOf(serve("c2", jpeg(), null, "image/jpeg")), FileSource.Photos, budget,
            taken = first.files.map { it.name })
        val third = prepareSendFiles(context, listOf(serve("d", jpeg(), "Photo.jpg", "image/jpeg")), FileSource.Files, budget,
            taken = (first.files + second.files).map { it.name })
        val all = first.files + second.files + third.files
        assertEquals(listOf("photo.jpg", "photo-2.jpg", "Photo-3.jpg"), all.map { it.name })
        assertEquals("one byte array per file, no renamed copy over the same bytes", all.size, all.map { it.bytes }.toSet().size)
        assertEquals(all.sumOf { it.bytes.size }.toLong(), budget.used())
        // Each owner releases once: a repeat frees nothing more.
        all.forEach { it.releaseFrom(budget); it.releaseFrom(budget) }
        assertEquals(0L, budget.used())
    }

    @Test fun files_refuse_images_by_name_when_the_agent_takes_documents_only() {
        val result = prepare(listOf(
            serve("png", pngWithAlpha(), "clear.png", "image/png"),
            serve("pdf", "%PDF".toByteArray(), "paper.pdf", "application/pdf"),
        ), FileSource.Files, images = false)
        assertEquals(listOf("paper.pdf"), result.files.map { it.name })
        assertEquals(listOf("clear.png ($NO_IMAGES)"), result.refused)
    }

    @Test fun a_photo_png_keeps_its_transparency_even_when_the_provider_calls_it_generic() {
        val budget = PreparedBudget(80_000_000)
        val result = prepare(listOf(serve("photo", pngWithAlpha(), "sticker.bin", "application/octet-stream")), FileSource.Photos, budget)
        val photo = result.files.single()
        assertEquals("photo.png", photo.name)
        assertEquals("image/png", photo.mime)
        val decoded = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)
        try {
            assertEquals("a transparent pixel stays transparent", 0, Color.alpha(decoded.getPixel(0, 0)))
            assertEquals(255, Color.alpha(decoded.getPixel(3, 3)))
        } finally { decoded.recycle() }
        assertEquals(photo.bytes.size.toLong(), budget.used())
    }

    @Test fun photo_jpeg_and_png_are_written_again_without_their_location_or_other_metadata() {
        val marker = "GPS-48.8584N-2.2945E-Pixel"
        val jpeg = withExif(jpeg(), marker)
        val png = withTextChunk(pngWithAlpha(), marker)
        assertTrue("the sources carry the marker", String(jpeg, Charsets.ISO_8859_1).contains(marker))
        assertTrue(String(png, Charsets.ISO_8859_1).contains(marker))
        val result = prepare(listOf(serve("jpeg", jpeg, "IMG_1.jpg", "image/jpeg"), serve("png", png, "IMG_2.png", "image/png")), FileSource.Photos)
        assertEquals(listOf("photo.jpg", "photo.png"), result.files.map { it.name })
        for (f in result.files) {
            val text = String(f.bytes, Charsets.ISO_8859_1)
            assertFalse("${f.name}: no source metadata", text.contains(marker))
            assertFalse("${f.name}: no Exif block", text.contains("Exif"))
            assertFalse("${f.name}: no text chunk", text.contains("tEXt"))
        }
    }

    // A 4x4 PNG whose first pixel is transparent.
    @Test fun external_results_reject_private_files_and_own_authority_before_provider_access() {
        val privateFile = File(dir, "private.txt").apply { writeText("private") }
        val own = "${context.packageName}.files"
        Robolectric.buildContentProvider(PreparationProvider::class.java).create(own)
        PreparationProvider.served["secret"] = PreparationProvider.Served(privateFile, "private.txt", "text/plain")
        val picks = listOf(Uri.fromFile(privateFile), Uri.parse("content://$own/document/secret"))
        PreparationProvider.accesses = 0
        for (source in listOf(FileSource.Files, FileSource.Photos)) {
            val result = prepare(picks, source)
            assertTrue(result.files.isEmpty())
            assertEquals(2, result.refused.size)
            assertEquals(0, PreparationProvider.accesses)
            assertTrue(result.refused.none { it.contains("private") || it.contains(dir.path) })
        }
    }

    private fun pngWithAlpha(): ByteArray {
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.BLUE)
        b.setPixel(0, 0, Color.TRANSPARENT)
        return try { ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() } finally { b.recycle() }
    }

    private fun jpeg(): ByteArray {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.GREEN)
        return try { ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() } finally { b.recycle() }
    }

    /** [jpeg] with an APP1 Exif block (an empty big-endian TIFF directory, then [marker]) after its SOI. */
    private fun withExif(jpeg: ByteArray, marker: String): ByteArray {
        val tiff = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0) + marker.toByteArray()
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = body.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    /** [png] with a `tEXt` chunk holding [marker] after its IHDR. */
    private fun withTextChunk(png: ByteArray, marker: String): ByteArray {
        val data = "Comment".toByteArray() + byteArrayOf(0) + marker.toByteArray()
        val type = "tEXt".toByteArray()
        val crc = CRC32().apply { update(type); update(data) }.value
        fun int(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val chunk = int(data.size.toLong()) + type + data + int(crc)
        val afterHeader = 8 + 25 // signature, then IHDR (length, type, 13 bytes, CRC)
        return png.copyOfRange(0, afterHeader) + chunk + png.copyOfRange(afterHeader, png.size)
    }

    /** A two-frame GIF89a, 1x1, as an animation is stored (two image descriptors). */
    private fun animatedGif(): ByteArray {
        val header = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0) + byteArrayOf(0, 0, 0, -1, -1, -1)
        val loop = byteArrayOf(0x21, -1, 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0)
        fun frame(index: Byte) = byteArrayOf(0x21, -7, 4, 0, 10, 0, 0, 0) + byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0) + byteArrayOf(2, 2, 0x44, index, 0)
        return header + loop + frame(1) + frame(1) + byteArrayOf(0x3B)
    }
}

internal fun providerPick(file: File): Uri {
    org.robolectric.Robolectric.buildContentProvider(PreparationProvider::class.java).create(PreparationProvider.AUTHORITY)
    val key = java.util.UUID.randomUUID().toString()
    PreparationProvider.served[key] = PreparationProvider.Served(file, file.name, null)
    return PreparationProvider.uri(key)
}
