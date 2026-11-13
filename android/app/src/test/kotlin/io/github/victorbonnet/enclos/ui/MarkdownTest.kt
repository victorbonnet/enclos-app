package io.github.victorbonnet.enclos.ui

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun html_previews_as_the_literal_text_it_is_shown_as() {
        assertEquals("<div>important</div>", Markdown.plain("<div>important</div>"))
        assertEquals("Press <kbd>Ctrl</kbd> now", Markdown.plain("Press <kbd>Ctrl</kbd> now"))
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

    @Test
    fun a_link_needs_a_host_and_no_user_info_whatever_its_case() {
        assertTrue(Markdown.opens("HTTPS://enclos.dev"))
        assertFalse(Markdown.opens("JAVASCRIPT:alert(1)"))
        assertFalse(Markdown.opens("Intent://scan/#Intent;end"))
        assertFalse(Markdown.opens("http:"))
        assertFalse(Markdown.opens("http:///path"))
        assertFalse(Markdown.opens("https:opaque"))
        assertFalse(Markdown.opens("https://bank.example@evil.example/login"))
        assertFalse(Markdown.opens("mailto:"))
        assertFalse(Markdown.opens("not a link"))
    }

    @Test
    fun an_image_whatever_its_destination_is_never_a_link_the_app_cannot_open() {
        val shown = inlines(first<Paragraph>(Markdown.parse("![the hosts](content://io.github.victorbonnet.enclos/hosts)")), LightPalette)
        assertEquals("the hosts ↗", shown.text)
        assertTrue(shown.getLinkAnnotations(0, shown.length).isEmpty())
        val web = inlines(first<Paragraph>(Markdown.parse("![chart](https://enclos.dev/c.png)")), LightPalette)
        assertEquals(1, web.getLinkAnnotations(0, web.length).size)
    }

    @Test
    fun a_link_text_drops_hidden_characters() {
        val shown = inlines(first<Paragraph>(Markdown.parse("[\u202Elmth.fdp](https://enclos.dev)")), LightPalette)
        assertEquals("lmth.fdp", shown.text)
        assertEquals(1, shown.getLinkAnnotations(0, shown.length).size)
    }

    @Test
    fun a_wide_table_is_drawn_in_part() {
        val cell = "x".repeat(35)
        val columns = 500
        val text = listOf(
            (1..columns).joinToString("|", "|", "|") { "h$it" },
            (1..columns).joinToString("|", "|", "|") { "---" },
            (1..columns).joinToString("|", "|", "|") { cell },
        ).joinToString("\n")
        val rows = Markdown.rows(first<TableBlock>(Markdown.parse(text)))
        assertEquals(2, rows.size)
        assertEquals(columns, rows[1].second.size)
        assertEquals(Markdown.TABLE_COLUMNS to 2, Markdown.shown(columns, rows.size))
        assertEquals("Table too large: 12 of 500 columns shown", Markdown.cutNote(columns, rows.size))
    }

    @Test
    fun a_long_table_is_drawn_in_part_and_a_small_one_whole() {
        assertEquals(Markdown.TABLE_COLUMNS to Markdown.TABLE_ROWS, Markdown.shown(100, 300))
        assertEquals("Table too large: 12 of 100 columns and 50 of 300 rows shown", Markdown.cutNote(100, 300))
        assertEquals(3 to 4, Markdown.shown(3, 4))
        assertNull(Markdown.cutNote(3, 4))
    }

    @Test
    fun a_table_width_always_fits_in_constraints() {
        // The widest drawn table: every column at 240dp, on a screen of
        // a density far above any phone's.
        val widest = Markdown.TABLE_COLUMNS * 240 * 6
        assertEquals(widest, Markdown.fitting(widest))
        assertEquals(Markdown.MOST_PX, Markdown.fitting(500 * 240 * 4))
        assertEquals(Markdown.MOST_PX, Markdown.fitting(Int.MAX_VALUE))
        assertEquals(0, Markdown.fitting(-1))
        // Below 2^15 - 1 a side fits whichever way Compose splits its bits.
        assertTrue(Markdown.MOST_PX < (1 shl 15) - 1)
    }

    private inline fun <reified T : Node> first(root: Node): T {
        var n: Node? = root.firstChild
        while (n != null) {
            if (n is T) return n
            n = n.firstChild ?: n.next
        }
        error("no ${T::class.simpleName}")
    }
}
