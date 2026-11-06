package io.github.victorbonnet.enclos.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The design system's line glyphs (`en-i-*`): 2px strokes on square
 * geometry, to match the Home base mark. Drawn black and tinted where used.
 */
object Glyphs {
    private fun glyph(name: String, stroke: Float = 2f, strokes: List<String> = emptyList(), fills: List<String> = emptyList()) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            strokes.forEach {
                addPath(
                    addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            fills.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    /** A square path for an SVG `rect`. */
    private fun rect(x: Float, y: Float, w: Float, h: Float) = "M${x},${y}h${w}v${h}h${-w}z"

    val back = glyph("back", strokes = listOf("M20 12H5M11 6l-6 6 6 6"))
    val send = glyph("send", strokes = listOf("M4 12h15M13 6l6 6-6 6"))
    val plus = glyph("plus", strokes = listOf("M12 4v16M4 12h16"))
    val inbox = glyph("inbox", strokes = listOf("M4 5h16v12H11l-4 3v-3H4z"))
    val agents = glyph(
        "agents",
        strokes = listOf("M9 4H4v5M15 4h5v5M4 15v5h5M20 15v5h-5"),
        fills = listOf(rect(10f, 10f, 4f, 4f)),
    )
    val status = glyph("status", strokes = listOf(rect(3f, 4f, 18f, 12f), "M8 20h8M12 16v4"))
    val qr = glyph(
        "qr",
        strokes = listOf(rect(4f, 4f, 6f, 6f), rect(14f, 4f, 6f, 6f), rect(4f, 14f, 6f, 6f), "M14 14h2v2h-2zM18 18h2v2h-2zM18 14h2M14 18v2"),
    )
    val compose = glyph("compose", strokes = listOf("M12 4H4v16h16v-8", "M10 14v-3l9-9 3 3-9 9z"))
    val check = glyph("check", stroke = 2.4f, strokes = listOf("M4 12l5 5L20 6"))
    val clock = glyph("clock", strokes = listOf(rect(4f, 4f, 16f, 16f), "M12 8v4h4"))
    val search = glyph("search", strokes = listOf(rect(4f, 4f, 12f, 12f), "M15 15l5 5"))
    /** Pointing right: something that opens. */
    val chevron = glyph("chevron", strokes = listOf("M9 6l6 6-6 6"))
    /** Clear a field: an addition to the design's set. */
    val clear = glyph("clear", strokes = listOf("M6 6l12 12M18 6L6 18"))
    /**
     * Unread only: the inbox's bubble, open at its top right, where a
     * filled square marks what is unread. An addition to the design's set.
     */
    val unread = glyph(
        "unread",
        strokes = listOf("M13 5H4v12h3v3l4-3h9v-6"),
        fills = listOf(rect(16f, 3f, 6f, 6f)),
    )
}
