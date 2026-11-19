package io.github.victorbonnet.enclos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import io.github.victorbonnet.enclos.UsageBar
import io.github.victorbonnet.enclos.UsageWindow
import io.github.victorbonnet.enclos.tokensText

/**
 * What a turn's model calls used, one quiet line on the agent's side: under its answer, or
 * under the working bubble while it works (docs/spec.md, Usage).
 */
@Composable
fun UsageNote(text: String, modifier: Modifier = Modifier) {
    val p = palette
    Row(
        modifier.padding(start = 4.dp).semantics(mergeDescendants = true) { contentDescription = "Usage: $text" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Glyph(Glyphs.usage, p.textFaint, size = 14.dp)
        Text(text, style = Type.small, color = p.textFaint)
    }
}

/** The windows a host's usage can be read over, one selected. */
@Composable
fun UsageWindowTabs(selected: UsageWindow, enabled: Boolean, onSelect: (UsageWindow) -> Unit) {
    val p = palette
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageWindow.entries.forEach { window ->
            val on = window == selected
            val shape = RoundedCornerShape(50)
            Text(
                window.label,
                style = Type.label,
                color = if (on) p.bg else p.textMuted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .selectable(selected = on, enabled = enabled, role = Role.Tab) { if (!on) onSelect(window) }
                    .background(if (on) p.accent else p.bg, shape)
                    .border(1.dp, if (on) p.accent else p.textFaint, shape)
                    .defaultMinSize(minHeight = 32.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Whether a sandbox's or an agent's chart shows its estimated cost or its tokens, one selected. */
@Composable
fun UsageMeasureTabs(cost: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val p = palette
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(true to "Cost", false to "Tokens").forEach { (measure, label) ->
            val on = measure == cost
            val shape = RoundedCornerShape(50)
            Text(
                label,
                style = Type.label,
                color = if (on) p.bg else p.textMuted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .selectable(selected = on, enabled = enabled, role = Role.Tab) { if (!on) onSelect(measure) }
                    .background(if (on) p.accent else p.bg, shape)
                    .border(1.dp, if (on) p.accent else p.textFaint, shape)
                    .defaultMinSize(minHeight = 32.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The categorical colors of the chart's agents, in their fixed slot order: the reference
 * palette of the dataviz method, its light steps validated against the light background
 * (#FAFAF8: every check passes, three hues under 3:1, so values are always also given as
 * text, in the rows) and its dark steps against the dark one (#0B1A14: all pass).
 */
private val LightSeries = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948).map { Color(it) }
private val DarkSeries = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF0CA30C, 0xFF9085E9, 0xFFE66767).map { Color(it) }

/** The color of chart slot [slot] in the current theme. */
@Composable
fun seriesColor(slot: Int): Color {
    val dark = palette.bg.luminance() < 0.5f
    return (if (dark) DarkSeries else LightSeries)[slot.mod(LightSeries.size)]
}

/** An agent's mark in the rows under the chart, which are its legend and its table. */
@Composable
fun SeriesDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

/**
 * The agents' tokens over time: one bar a bucket, stacked by agent in the chart's order
 * (heaviest at the base), each part in its agent's color with a 2 dp gap of the background
 * between parts and a rounded top. The scale's top and the first and last buckets are named
 * faintly; tapping a bar reads it out above the chart, in text colors; tapping it again
 * clears it. The rows under the chart give every value as text. A bar's values are tokens,
 * or, for a cost chart, millionths of a dollar: [amount] writes one, [unit] follows a total.
 */
@Composable
fun UsageChart(
    bars: List<UsageBar>,
    colors: Map<String, Color>,
    label: (String) -> String,
    bucketLabel: (Long) -> String,
    summary: String,
    modifier: Modifier = Modifier,
    amount: (Long) -> String = ::tokensText,
    unit: String = " tokens",
    /** Whether a bar read out names each of its parts: not for a chart of one sandbox or agent. */
    split: Boolean = true,
    /** What a bar read out says of its whole, by its start, when its value alone does not say it all (a cost some of whose tokens had no price). */
    barText: ((Long) -> String?)? = null,
) {
    val p = palette
    var picked by remember(bars) { mutableStateOf<Int?>(null) }
    val top = bars.maxOfOrNull { it.tokens }?.coerceAtLeast(1) ?: 1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val shown = picked?.let { bars.getOrNull(it) }
        Text(
            if (shown == null) "Tap a bar to read it." else buildString {
                append(bucketLabel(shown.at)).append(" · ").append(barText?.invoke(shown.at) ?: (amount(shown.tokens) + unit))
                if (split && shown.parts.isNotEmpty()) append(": ").append(shown.parts.joinToString(" · ") { (a, t) -> "${label(a)} ${amount(t)}" })
            },
            style = Type.small,
            color = if (shown == null) p.textFaint else p.text,
        )
        Text(amount(top), style = Type.code, color = p.textFaint)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                // A stack of many thin parts, each kept visible, never draws over the scale.
                .clipToBounds()
                .semantics { contentDescription = summary }
                .pointerInput(bars) {
                    detectTapGestures { at ->
                        val i = (at.x / (size.width.toFloat() / bars.size.coerceAtLeast(1))).toInt().coerceIn(0, bars.lastIndex.coerceAtLeast(0))
                        picked = if (picked == i) null else i
                    }
                },
        ) {
            val slot = size.width / bars.size.coerceAtLeast(1)
            val barWidth = (slot * 0.7f).coerceAtMost(28.dp.toPx())
            val gap = 2.dp.toPx()
            // Recessive guides: the base and the scale's top.
            drawLine(p.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            drawLine(p.line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            bars.forEachIndexed { i, bar ->
                val x = slot * i + (slot - barWidth) / 2
                var y = size.height
                bar.parts.forEachIndexed { k, (agent, tokens) ->
                    // At least a sliver above its gap, so an agent in the readout is in the bar.
                    val floor = 1.dp.toPx() + if (k > 0) gap else 0f
                    val h = (tokens.toFloat() / top * size.height).coerceAtLeast(floor)
                    val color = colors[agent] ?: p.textFaint
                    val last = k == bar.parts.lastIndex
                    drawPart(color, x, y - h, barWidth, h - if (k > 0) gap else 0f, rounded = last)
                    y -= h
                }
                if (picked == i) drawRect(p.lineStrong, Offset(x - gap, 0f), Size(barWidth + 2 * gap, size.height), alpha = 0.25f)
            }
        }
        if (bars.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(bucketLabel(bars.first().at), style = Type.code, color = p.textFaint)
                Text(bucketLabel(bars.last().at), style = Type.code, color = p.textFaint)
            }
        }
    }
}

/** One part of a bar, its top end rounded 4 dp when it is the bar's top. */
private fun DrawScope.drawPart(color: Color, x: Float, top: Float, width: Float, height: Float, rounded: Boolean) {
    if (height <= 0f) return
    if (!rounded) {
        drawRect(color, Offset(x, top), Size(width, height))
        return
    }
    val r = minOf(4.dp.toPx(), width / 2, height)
    val path = Path().apply {
        moveTo(x, top + height)
        lineTo(x, top + r)
        quadraticTo(x, top, x + r, top)
        lineTo(x + width - r, top)
        quadraticTo(x + width, top, x + width, top + r)
        lineTo(x + width, top + height)
        close()
    }
    drawPath(path, color)
}
