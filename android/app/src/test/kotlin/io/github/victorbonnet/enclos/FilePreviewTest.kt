package io.github.victorbonnet.enclos

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
