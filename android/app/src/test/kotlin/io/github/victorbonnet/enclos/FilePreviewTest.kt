package io.github.victorbonnet.enclos

import android.content.Intent
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewTest {
    @Test fun the_declared_media_type_selects_the_reader_independently_of_the_name() {
        val pdf = Attachment("id", "Application/PDF; version=1.7", "resume", 1)
        assertTrue(pdf.pdf)
        assertFalse(pdf.image)
        assertFalse(pdf.text)
        val text = Attachment("id", "text/plain; charset=utf-8", "resume.pdf", 1)
        assertFalse(text.pdf)
        assertTrue(text.text)
        assertFalse(Attachment("id", "application/octet-stream", "resume.pdf", 1).pdf)
        assertFalse(Attachment("id", "text/html", "page.txt", 1).text)
        assertTrue(Attachment("id", "application/json", "report", 1).text)
    }

    @Test fun utf8_text_is_literal_and_an_optional_bom_is_removed() {
        val file = File.createTempFile("preview", ".txt")
        try {
            val content = "# Été · 東京\n<script>alert('hello')</script>\n**plain text**"
            file.writeBytes("\uFEFF$content".toByteArray(Charsets.UTF_8))
            assertEquals(content, TextDocument.read(file))
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(CharacterCodingException::class.java) { TextDocument.read(file) }
        } finally { file.delete() }
    }

    @Test fun another_app_opens_only_listed_types_with_one_of_their_extensions() {
        assertEquals("application/pdf", viewIntentType("application/pdf", "report.pdf"))
        assertEquals("application/pdf", viewIntentType("Application/PDF; version=1.7", "REPORT.PDF"))
        assertEquals("text/plain", viewIntentType("text/plain", "notes.txt"))
        assertEquals("image/jpeg", viewIntentType("image/jpeg", "photo.jpg"))
        // Types another app would run or install are offered to Share only.
        listOf(
            "application/vnd.android.package-archive" to "app.apk",
            "application/xhtml+xml" to "page.xhtml",
            "text/html" to "page.html",
            "image/svg+xml" to "chart.svg",
            "application/xml" to "data.xml",
            "text/xml" to "data.xml",
            "multipart/related" to "page.mht",
            "application/octet-stream" to "blob.bin",
            "" to "file",
        ).forEach { (type, name) -> assertNull("$type $name", viewIntentType(type, name)) }
        // A listed type whose name another app would read as something else.
        assertNull(viewIntentType("text/plain", "page.xhtml"))
        assertNull(viewIntentType("application/pdf", "invoice.pdf.apk"))
        assertNull(viewIntentType("application/pdf", "report"))
    }

    @Test fun a_shown_name_drops_hidden_characters() {
        val override = Char(0x202E)
        val zeroWidth = Char(0x200B)
        val isolate = Char(0x2066)
        assertEquals("invoicefdp.apk", Attachment("id", "application/pdf", "invoice${override}fdp.apk", 1).shownName)
        assertEquals("a b.pdf", Attachment("id", "application/pdf", "a$zeroWidth b.pdf", 1).shownName)
        assertEquals("file", Attachment("id", "application/pdf", "$override$isolate", 1).shownName)
    }

    @Test fun text_blocks_are_bounded_and_put_together_give_the_text() {
        val lines = (1..3000).joinToString("\n") { "line $it" }
        val minified = "{" + "\"k\":1,".repeat(5000) + "}"
        val surrogates = "😀".repeat(5000)
        listOf(lines, minified, surrogates, "", "a\n\nb\n", "\n").forEach { text ->
            val blocks = TextDocument.blocks(text)
            assertEquals(text, blocks.joinToString(""))
            assertTrue(blocks.all { it.isNotEmpty() && it.length <= TextDocument.BLOCK_CHARS })
            assertTrue(blocks.none { Character.isHighSurrogate(it.last()) })
        }
        // Cut after a line break when one fits.
        assertTrue(TextDocument.blocks(lines).dropLast(1).all { it.endsWith("\n") })
        assertEquals(listOf("ab\n", "cd"), TextDocument.blocks("ab\ncd", max = 4))
        assertEquals(listOf("abcd", "ef"), TextDocument.blocks("abcdef", max = 4))
    }

    @Test fun only_images_the_platform_decodes_are_drawn_in_the_bubble() {
        listOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif", "image/bmp", "IMAGE/PNG; q=1").forEach {
            assertTrue(it, Attachment("id", it, "a", 1).image)
        }
        // Drawn as a file line instead, which opens the document viewer and its Share.
        listOf("image/svg+xml", "image/tiff", "image/avif", "image/x-icon", "image/", "application/pdf").forEach {
            assertFalse(it, Attachment("id", it, "a", 1).image)
        }
    }

    @Test fun a_long_cached_name_keeps_its_extension() {
        assertEquals("report.pdf", cachedFileName("report.pdf"))
        assertEquals("_x_.pdf", cachedFileName("..\u00E9x\u202E.pdf"))
        assertEquals("file", cachedFileName("..."))
        val long = cachedFileName("a".repeat(120) + ".pdf")
        assertEquals(80, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertEquals("application/pdf", viewIntentType("application/pdf", long))
        // No extension, or one too long to be one: cut as before.
        assertEquals("b".repeat(80), cachedFileName("b".repeat(120)))
        assertEquals(80, cachedFileName("c".repeat(60) + "." + "d".repeat(60)).length)
    }

    @Test fun a_handoff_names_the_action_and_type_and_grants_reading_only() {
        val pdf = Attachment("id", "application/pdf", "report.pdf", 1)
        val share = handoffOf(pdf, "report.pdf", sharing = true)!!
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("application/pdf", share.type)
        val open = handoffOf(pdf, "report.pdf", sharing = false)!!
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertEquals("application/pdf", open.type)
        for (handoff in listOf(share, open)) {
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, handoff.flags)
            assertEquals(0, handoff.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        }
        // Not opened elsewhere: an unlisted type, or a listed one under another extension; Share still is.
        assertNull(handoffOf(Attachment("id", "image/svg+xml", "chart.svg", 1), "chart.svg", sharing = false))
        assertNull(handoffOf(pdf, "report.pdf.apk", sharing = false))
        assertEquals("image/svg+xml", handoffOf(Attachment("id", "image/svg+xml", "chart.svg", 1), "chart.svg", sharing = true)?.type)
        assertEquals("application/octet-stream", handoffOf(Attachment("id", "", "blob", 1), "blob", sharing = true)?.type)
    }

    @Test fun a_block_going_on_with_a_cut_line_is_marked() {
        val blocks = TextDocument.blocks("abcdef\ngh", max = 4)
        assertEquals(listOf("abcd", "ef\n", "gh"), blocks)
        assertEquals(listOf(false, true, false), blocks.indices.map { TextDocument.continues(blocks, it) })
    }

    @Test fun text_preview_reads_only_up_to_its_limit() {
        val file = File.createTempFile("preview", ".txt")
        try {
            file.writeBytes(ByteArray(TextDocument.MAX_BYTES) { 'x'.code.toByte() })
            assertEquals(TextDocument.MAX_BYTES, TextDocument.read(file).length)
            RandomAccessFile(file, "rw").use { it.setLength(TextDocument.MAX_BYTES.toLong() + 1) }
            assertThrows(IllegalArgumentException::class.java) { TextDocument.read(file) }
        } finally { file.delete() }
    }
}
