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
    /** A sandbox: a box under its lid, a handle on its front. An addition to the design's set. */
    val sandbox = glyph(
        "sandbox",
        strokes = listOf(rect(3f, 4f, 18f, 5f), "M5 9v11h14V9", "M10 13h4"),
    )
    /** Settings: two sliders, each with a square knob. An addition to the design's set. */
    val settings = glyph(
        "settings",
        strokes = listOf("M4 7h9M17 7h3M4 17h3M11 17h9", rect(13f, 5f, 4f, 4f), rect(7f, 15f, 4f, 4f)),
    )
    val qr = glyph(
        "qr",
        strokes = listOf(rect(4f, 4f, 6f, 6f), rect(14f, 4f, 6f, 6f), rect(4f, 14f, 6f, 6f), "M14 14h2v2h-2zM18 18h2v2h-2zM18 14h2M14 18v2"),
    )
    val compose = glyph("compose", strokes = listOf("M12 4H4v16h16v-8", "M10 14v-3l9-9 3 3-9 9z"))
    /** A stored conversation, with its original history kept. */
    val archive = glyph("archive", strokes = listOf(rect(3f, 4f, 18f, 4f), "M5 8v12h14V8", "M9 12h6"))
    val delete = glyph("delete", strokes = listOf("M4 6h16M9 6V3h6v3M6 6v15h12V6M10 10v7M14 10v7"))
    val more = glyph("more", fills = listOf(rect(3f, 10f, 4f, 4f), rect(10f, 10f, 4f, 4f), rect(17f, 10f, 4f, 4f)))
    val check = glyph("check", stroke = 2.4f, strokes = listOf("M4 12l5 5L20 6"))
    val clock = glyph("clock", strokes = listOf(rect(4f, 4f, 16f, 16f), "M12 8v4h4"))
    val search = glyph("search", strokes = listOf(rect(4f, 4f, 12f, 12f), "M15 15l5 5"))
    val filter = glyph("filter", strokes = listOf("M4 6h16M7 12h10M10 18h4"))
    /** Pointing right: something that opens. */
    val chevron = glyph("chevron", strokes = listOf("M9 6l6 6-6 6"))
    /** Clear a field: an addition to the design's set. */
    val clear = glyph("clear", strokes = listOf("M6 6l12 12M18 6L6 18"))
}
