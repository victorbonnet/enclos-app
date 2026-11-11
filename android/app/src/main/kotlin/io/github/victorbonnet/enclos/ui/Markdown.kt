package io.github.victorbonnet.enclos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.victorbonnet.enclos.Visible
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.net.URI
import org.commonmark.node.Text as TextNode

/**
 * The agents' markdown: CommonMark with GitHub's tables, strikethrough and
 * bare links, drawn by the app with the design's tokens. Nothing in a
 * message is fetched or run: images show as a link with their alt text,
 * HTML as its literal text, and only http, https and mailto links open.
 */
object Markdown {
    /**
     * The most columns and rows of a table that are drawn, the header row
     * included: each cell is composed and measured at once, and the table's
     * width must stay one Compose can measure (see [fitting]).
     */
    const val TABLE_COLUMNS = 12
    const val TABLE_ROWS = 50

    /**
     * The most pixels a hairline is measured across. Compose packs a size
     * in `Constraints` with 13 to 18 bits a side; 32,766 fits whichever
     * split it takes while the other side is no larger, so no table can
     * make it throw.
     */
    const val MOST_PX = 32_766

    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()

    fun parse(text: String): Node = parser.parse(text)

    /**
     * The words without the marks, on one line: for a preview. (CommonMark's
     * own text renderer keeps some, such as `/struck/` and quoted code.)
     */
    fun plain(text: String): String = buildString { words(parse(text)) }.replace(Regex("\\s+"), " ").trim()

    private fun StringBuilder.words(node: Node) {
        when (node) {
            is TextNode -> append(node.literal)
            is Code -> append(node.literal)
            is FencedCodeBlock -> append(node.literal).append(' ')
            is IndentedCodeBlock -> append(node.literal).append(' ')
            // Shown as its literal text, so previewed and searched as it too:
            // HTML keeps its text in `literal`, with no children.
            is HtmlBlock -> append(node.literal).append(' ')
            is HtmlInline -> append(node.literal)
            is SoftLineBreak, is HardLineBreak -> append(' ')
            else -> {
                var child = node.firstChild
                while (child != null) {
                    words(child)
                    child = child.next
                }
                // Blocks and cells end with a space, so their words do not run together.
                if (node !is Emphasis && node !is StrongEmphasis && node !is Strikethrough && node !is Link) append(' ')
            }
        }
    }

    /**
     * Whether [url] may be opened from a message: a web address with a host
     * and no user-info (`https://bank.example@evil.example` reads as the
     * bank), or a mail address; nothing else, whatever its case.
     */
    fun opens(url: String): Boolean = runCatching {
        val uri = URI(url)
        when (uri.scheme?.lowercase()) {
            "mailto" -> !uri.schemeSpecificPart.isNullOrBlank()
            "http", "https" -> {
                if (uri.isOpaque) return@runCatching false
                // URL's host accepts international names, which URI.host leaves null.
                val web = uri.toURL()
                !web.host.isNullOrEmpty() && web.userInfo == null && web.port in -1..65535
            }
            else -> false
        }
    }.getOrDefault(false)

    /** A table's rows, the header's first, each with whether it is the header and its cells. */
    fun rows(table: TableBlock): List<Pair<Boolean, List<TableCell>>> {
        val rows = mutableListOf<Pair<Boolean, List<TableCell>>>()
        var section = table.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = mutableListOf<TableCell>()
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) cells += cell
                        cell = cell.next
                    }
                    rows += (section is TableHead) to cells
                }
                row = row.next
            }
            section = section.next
        }
        return rows
    }

    /** Of a table of [columns] by [rows], the columns and rows drawn. */
    fun shown(columns: Int, rows: Int): Pair<Int, Int> = minOf(columns, TABLE_COLUMNS) to minOf(rows, TABLE_ROWS)

    /** [px] bounded to what Compose can measure, whatever a table's size. */
    fun fitting(px: Int): Int = px.coerceIn(0, MOST_PX)

    /** What a table drawn in part says of the rest, or null when it is drawn whole. */
    fun cutNote(columns: Int, rows: Int): String? {
        val (c, r) = shown(columns, rows)
        if (c == columns && r == rows) return null
        val parts = listOfNotNull(
            if (c < columns) "$c of $columns columns" else null,
            if (r < rows) "$r of $rows rows" else null,
        )
        return "Table too large: ${parts.joinToString(" and ")} shown"
    }
}

/** A message's markdown, as blocks: paragraphs, headings, lists, quotes, code and tables. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val document = remember(text) { Markdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blocks(document, color)
    }
}

/** The block children of [parent], in order. */
@Composable
private fun Blocks(parent: Node, color: Color) {
    var node = parent.firstChild
    while (node != null) {
        Block(node, color)
        node = node.next
    }
}

@Composable
private fun Block(node: Node, color: Color) {
    val p = palette
    when (node) {
        is Paragraph -> Text(inlines(node, p), style = Type.message, color = color)
        is Heading -> Text(
            inlines(node, p),
            style = Type.message.copy(
                fontSize = when (node.level) {
                    1 -> 19.sp
                    2 -> 17.sp
                    else -> 15.sp
                },
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
        )
        is BulletList -> ListItems(node, color) { _ -> "•" }
        is OrderedList -> ListItems(node, color) { i -> "${(node.markerStartNumber ?: 1) + i}." }
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            // A quote: an accent bar as tall as the quote, its text muted.
            Box(Modifier.width(2.dp).fillMaxHeight().background(p.accent))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Blocks(node, p.textMuted) }
        }
        is FencedCodeBlock -> CodeBlock(node.literal)
        is IndentedCodeBlock -> CodeBlock(node.literal)
        is HtmlBlock -> Text(node.literal.trimEnd(), style = Type.message, color = color)
        is ThematicBreak -> Hairline()
        is TableBlock -> Table(node, color)
        else -> Blocks(node, color)
    }
}

/** A list: each item's marker in mono, then its blocks. */
@Composable
private fun ListItems(list: Node, color: Color, marker: (Int) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var item = list.firstChild
        var i = 0
        while (item != null) {
            if (item is ListItem) {
                Row {
                    Text(marker(i), style = Type.message.copy(fontFamily = Type.mono, fontSize = 13.sp), color = palette.textFaint, modifier = Modifier.widthIn(min = 18.dp).padding(end = 6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Blocks(item, color) }
                }
                i++
            }
            item = item.next
        }
    }
}

/** Code: mono on `panel-deep`, as the site's terminal panels; long lines scroll sideways. */
@Composable
private fun CodeBlock(code: String) {
    val p = palette
    Box(Modifier.fillMaxWidth().background(p.panelDeep).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(code.trimEnd('\n'), style = Type.code.copy(fontSize = 12.5.sp, lineHeight = 19.sp), color = p.panelText, softWrap = false)
    }
}

/**
 * A table: its columns as wide as their widest cell (up to 240dp, then
 * wrapped), the header row in bold, hairlines between rows, scrolling
 * sideways when wider than the bubble.
 */
@Composable
private fun Table(table: TableBlock, color: Color) {
    val all = Markdown.rows(table)
    val allColumns = all.maxOfOrNull { it.second.size } ?: return
    // A table an agent wrote may be as large as a message: draw a part of it.
    val (columns, shownRows) = Markdown.shown(allColumns, all.size)
    val rows = all.take(shownRows)
    val p = palette
    Markdown.cutNote(allColumns, all.size)?.let { Text(it, style = Type.small, color = p.textMuted) }
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                rows.forEach { (head, cells) ->
                    repeat(columns) { c ->
                        val cell = cells.getOrNull(c)
                        Text(
                            cell?.let { inlines(it, p) } ?: AnnotatedString(""),
                            style = Type.small.copy(fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal),
                            color = color,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                repeat(rows.size - 1) { Box(Modifier.background(p.line)) }
            },
        ) { measurables, _ ->
            val cellCount = rows.size * columns
            val most = 240.dp.roundToPx()
            val placed = measurables.take(cellCount).map { it.measure(Constraints(maxWidth = most)) }
            val widths = (0 until columns).map { c -> rows.indices.maxOf { r -> placed[r * columns + c].width } }
            val heights = rows.indices.map { r -> (0 until columns).maxOf { c -> placed[r * columns + c].height } }
            val total = widths.sum()
            val lines = measurables.drop(cellCount).map { it.measure(Constraints.fixed(Markdown.fitting(total), 1.dp.roundToPx())) }
            layout(total, heights.sum()) {
                var y = 0
                rows.indices.forEach { r ->
                    var x = 0
                    (0 until columns).forEach { c ->
                        placed[r * columns + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                    lines.getOrNull(r)?.place(0, y - lines[r].height)
                }
            }
        }
    }
}

/**
 * A paragraph's, heading's or cell's inline content: emphasis, code, links
 * and line breaks. A link's text drops hidden characters, so it cannot read
 * as another address.
 */
internal fun inlines(parent: Node, p: Palette): AnnotatedString = buildAnnotatedString {
    var inLink = false
    fun walk(node: Node) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is TextNode -> append(if (inLink) Visible.stripped(child.literal) else child.literal)
                is Code -> withStyle(SpanStyle(fontFamily = Type.mono, fontSize = 13.sp, color = p.accent)) { append(if (inLink) Visible.stripped(child.literal) else child.literal) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(child) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { walk(child) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(child) }
                // A single newline stays one: chat is written line by line.
                is SoftLineBreak, is HardLineBreak -> append("\n")
                is Link -> link(child.destination, p) {
                    inLink = true
                    walk(child)
                    inLink = false
                }
                // Never fetched: the alt text, linking to the image when it is on the web.
                is Image -> link(child.destination, p) {
                    val alt = Markdown.plain(buildString { var c = child.firstChild; while (c != null) { if (c is TextNode) append(c.literal); c = c.next } })
                    append(Visible.stripped(alt).ifEmpty { "image" })
                    append(" ↗")
                }
                is HtmlInline -> append(child.literal)
                else -> walk(child)
            }
            child = child.next
        }
    }
    walk(parent)
}

/** [content] as a link to [url] in `accent`, or as plain text when the link is not one the app opens. */
private fun AnnotatedString.Builder.link(url: String, p: Palette, content: AnnotatedString.Builder.() -> Unit) {
    if (!Markdown.opens(url)) return content()
    val style = TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline))
    withLink(LinkAnnotation.Url(url, style)) { content() }
}
