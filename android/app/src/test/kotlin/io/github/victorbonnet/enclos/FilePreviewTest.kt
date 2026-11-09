package io.github.victorbonnet.enclos

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
