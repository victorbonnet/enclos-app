package io.github.victorbonnet.enclos

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtefactTest {
    @Test
    fun only_html_media_types_open_as_pages() {
        fun page(mime: String, name: String = "page.html") = Attachment("id", mime, name, 1).artefact
        assertTrue(page("text/html"))
        assertTrue(page("Text/HTML; charset=utf-8", "report"))
        assertFalse(page("text/plain"))
        assertFalse(page("application/octet-stream"))
        assertFalse(page("application/xhtml+xml"))
        assertFalse(page("image/svg+xml"))
    }

    @Test
    fun utf8_is_checked_through_a_small_buffer_across_its_whole_length() {
        // Many buffers' worth of multi-byte text, sequences straddling each boundary.
        val good = "東京 é ".repeat(5_000).toByteArray(Charsets.UTF_8)
        ArtefactDocument.checkUtf8(good, chunk = 7)
        ArtefactDocument.checkUtf8(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + good, chunk = 7)
        // A broken byte far past the first buffer, and a sequence cut at the end.
        val late = good.copyOf().also { it[it.size - 100] = 0xff.toByte() }
        assertThrows(CharacterCodingException::class.java) { ArtefactDocument.checkUtf8(late, chunk = 7) }
        val cut = good + byteArrayOf(0xe6.toByte(), 0x9d.toByte())
        assertThrows(CharacterCodingException::class.java) { ArtefactDocument.checkUtf8(cut, chunk = 7) }
    }

    @Test
    fun the_original_utf8_bytes_are_kept_and_bad_encoding_is_refused() {
        val file = File.createTempFile("artefact", ".html")
        try {
            val bytes = "<!doctype html><p>Été · 東京</p>".toByteArray(Charsets.UTF_8)
            file.writeBytes(bytes)
            assertArrayEquals(bytes, ArtefactDocument.read(file).bytes)
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(CharacterCodingException::class.java) { ArtefactDocument.read(file) }
        } finally { file.delete() }
    }

    @Test
    fun a_page_above_the_bridge_limit_is_refused_before_reading_it() {
        val file = File.createTempFile("artefact", ".html")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(ArtefactDocument.MAX_BYTES.toLong() + 1) }
            assertThrows(IllegalArgumentException::class.java) { ArtefactDocument.read(file) }
        } finally { file.delete() }
    }

    @Test
    fun each_page_has_its_own_address_and_only_its_anchors_are_navigation() {
        val page = ArtefactDocument(byteArrayOf())
        val other = ArtefactDocument(byteArrayOf())
        assertTrue(page.isAnchor("${page.url}#section"))
        assertFalse(page.isAnchor(page.url))
        assertFalse(page.isAnchor("${other.url}#section"))
        assertFalse(page.isAnchor("https://example.com/#section"))
        assertFalse(page.isAnchor("javascript:alert(1)"))
        assertFalse(page.isAnchor("file:///data/data/app/secret"))
        assertFalse(page.isAnchor("content://app/secret"))
        assertFalse(page.isAnchor("intent://example"))
        assertTrue(page.isDocument(page.url, true, "GET"))
        assertFalse(page.isDocument(page.url, false, "GET"))
        assertFalse(page.isDocument(page.url, true, "POST"))
        assertFalse(page.isDocument(other.url, true, "GET"))
    }
}
