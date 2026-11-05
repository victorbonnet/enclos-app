package io.github.victorbonnet.enclos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun a_preview_keeps_the_words_and_drops_the_marks() {
        val reply = """
            # Format Test

            This is **bold**, *italic*, ~~struck~~ and `python -m venv`.

            - one
            - two

            | A | B |
            |---|---|
            | x | y |
        """.trimIndent()
        assertEquals(
            "Format Test This is bold, italic, struck and python -m venv. one two A B x y",
            Markdown.plain(reply),
        )
    }

    @Test
    fun a_preview_reads_line_breaks_as_spaces() {
        assertEquals("1 2 3", Markdown.plain("1\n2\n3"))
    }

    @Test
    fun a_link_shows_its_text() {
        assertEquals("Visit enclos", Markdown.plain("Visit [enclos](https://enclos.dev)"))
    }

    @Test
    fun only_web_and_mail_links_open() {
        assertTrue(Markdown.opens("https://enclos.dev"))
        assertTrue(Markdown.opens("http://example.com"))
        assertTrue(Markdown.opens("mailto:someone@example.com"))
        assertFalse(Markdown.opens("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(Markdown.opens("javascript:alert(1)"))
        assertFalse(Markdown.opens("file:///sdcard/secret"))
        assertFalse(Markdown.opens("content://io.github.victorbonnet.enclos/hosts"))
    }
}
