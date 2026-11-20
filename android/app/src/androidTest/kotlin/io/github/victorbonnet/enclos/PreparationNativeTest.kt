package io.github.victorbonnet.enclos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32

/**
 * Preparation on a real Android runtime: the platform's ImageDecoder, Bitmap
 * encoders and ContentResolver, through the app's own FileProvider (real
 * `content://` URIs, the type the provider gives by extension). The files
 * are synthetic; this is not the system Photos or Files picker, nor a camera.
 */
@RunWith(AndroidJUnit4::class)
class PreparationNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Under the provider's camera path, in a folder of this test's own, removed after.
    private val dir = File(cameraDir(context), "native-test-${UUID.randomUUID()}").apply { mkdirs() }

    @After fun clean() { dir.deleteRecursively() }

    private fun serve(name: String, bytes: ByteArray): Uri {
        val file = File(dir, name).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    @Test fun photos_drop_metadata_keep_png_transparency_whatever_the_type_and_refuse_a_gif() {
        val marker = "GPS-48.8584N-2.2945E-Pixel"
        val jpeg = withExif(jpeg(), marker)
        val png = withTextChunk(pngWithAlpha(), marker)
        assertTrue(String(jpeg, Charsets.ISO_8859_1).contains(marker))
        assertTrue(String(png, Charsets.ISO_8859_1).contains(marker))
        val budget = PreparedBudget(80_000_000)
        // "sticker.bin": the provider calls it application/octet-stream; its own bytes say PNG.
        val picks = listOf(serve("IMG_1.jpg", jpeg), serve("sticker.bin", png), serve("moving.gif", animatedGif()))
        assertEquals("application/octet-stream", context.contentResolver.getType(picks[1]))
        val result = prepareSendFiles(context, picks, FileSource.Photos, budget)

        assertEquals(listOf("IMG_1.jpg", "sticker.png"), result.files.map { it.name })
        assertEquals(listOf("image/jpeg", "image/png"), result.files.map { it.mime })
        assertEquals(listOf("moving.gif ($GIF_FROM_PHOTOS)"), result.refused)
        for (f in result.files) {
            val text = String(f.bytes, Charsets.ISO_8859_1)
            assertFalse("${f.name}: no source metadata", text.contains(marker))
            assertFalse("${f.name}: no Exif block", text.contains("Exif"))
            assertFalse("${f.name}: no text chunk", text.contains("tEXt"))
        }
        val decoded = BitmapFactory.decodeByteArray(result.files[1].bytes, 0, result.files[1].bytes.size)
        try {
            assertEquals("a transparent pixel stays transparent", 0, Color.alpha(decoded.getPixel(0, 0)))
            assertEquals(255, Color.alpha(decoded.getPixel(3, 3)))
        } finally { decoded.recycle() }
        assertEquals(result.files.sumOf { it.bytes.size }.toLong(), budget.used())
    }

    @Test fun files_keep_png_and_gif_exact_bytes_and_names_with_distinct_repeats() {
        val png = pngWithAlpha()
        val gif = animatedGif()
        val budget = PreparedBudget(80_000_000)
        val first = prepareSendFiles(context, listOf(serve("clear.png", png), serve("moving.gif", gif)), FileSource.Files, budget)
        val other = File(dir, "again").apply { mkdirs() }
        val again = File(other, "clear.png").apply { writeBytes(png) }
        val later = prepareSendFiles(context, listOf(FileProvider.getUriForFile(context, "${context.packageName}.files", again)),
            FileSource.Files, budget, taken = first.files.map { it.name })
        val all = first.files + later.files
        assertEquals(listOf("clear.png", "moving.gif", "clear-2.png"), all.map { it.name })
        assertEquals(listOf("image/png", "image/gif", "image/png"), all.map { it.mime })
        assertArrayEquals("a PNG goes exactly as it is", png, all[0].bytes)
        assertArrayEquals("a GIF's frames go exactly as they are", gif, all[1].bytes)
        assertArrayEquals(png, all[2].bytes)
        assertEquals(all.sumOf { it.bytes.size }.toLong(), budget.used())
        all.forEach { it.releaseFrom(budget); it.releaseFrom(budget) }
        assertEquals(0L, budget.used())
    }

    // A 4x4 PNG whose first pixel is transparent.
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
