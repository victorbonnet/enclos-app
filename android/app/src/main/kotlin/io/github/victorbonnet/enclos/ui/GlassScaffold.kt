package io.github.victorbonnet.enclos.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Whether this phone can blur what is behind a bar: Android 12 (API 31) and later. */
val CanFrost: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private enum class Slot { Body, Top, Bottom }

/**
 * A screen whose content runs the full height, under its top and bottom bars.
 * Every bar is one material (`docs/spec.md`, §4 "The bars"): where the phone can
 * blur ([frost], Android 12 and later) the bar is the content behind it blurred
 * [Glass.radius] and tinted with `palette.glass`; before that it is `palette.veil`
 * with a [Glass.fade] fade into the content. No shadow, no hairline.
 *
 * [content] gets the room the bars take, **measured**, as padding to apply inside
 * its own scroll (`contentPadding` of a list, `padding` after `verticalScroll`), so
 * what is at rest never sits under a bar and what scrolls passes under it. The top
 * bar also takes the status bar's room, so the glass fills it; the bottom bar
 * stops at the navigation bar (the caller's own inset), where the page shows.
 *
 * A bar's controls draw only `text` and `textMuted` on it; anything fainter or
 * accented sits on its own fill.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    frost: Boolean = CanFrost,
    scrolling: () -> Boolean = { false },
    top: (@Composable () -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    // What the content drew, kept as a layer so a bar can draw the part behind it again, blurred.
    val seen = if (frost) rememberGraphicsLayer() else null
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bar = Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = height)
        val topBar = top?.let { subcompose(Slot.Top) { Bar(Edge.Top, frost, seen, scrolling, it) }.map { m -> m.measure(bar) } }.orEmpty()
        val bottomBar = bottom?.let { subcompose(Slot.Bottom) { Bar(Edge.Bottom, frost, seen, scrolling, it) }.map { m -> m.measure(bar) } }.orEmpty()
        val topHeight = topBar.maxOfOrNull { it.height } ?: 0
        val bottomHeight = bottomBar.maxOfOrNull { it.height } ?: 0
        // The veil's fade lies over the content beside the bar, so the content clears it too.
        val fade = if (frost) 0 else Glass.fade.roundToPx()
        val inset = PaddingValues(
            top = (if (topBar.isEmpty()) 0 else topHeight + fade).toDp(),
            bottom = (if (bottomBar.isEmpty()) 0 else bottomHeight + fade).toDp(),
        )
        val body = subcompose(Slot.Body) {
            Box(
                Modifier.fillMaxSize().then(if (seen != null) Modifier.drawWithContent {
                    seen.record { this@drawWithContent.drawContent() }
                    drawLayer(seen)
                } else Modifier),
            ) { content(inset) }
        }.map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) {
            body.forEach { it.place(0, 0) }
            topBar.forEach { it.place(0, 0) }
            bottomBar.forEach { it.place(0, height - bottomHeight) }
        }
    }
}

private enum class Edge { Top, Bottom }

@Composable
private fun Bar(edge: Edge, frost: Boolean, seen: GraphicsLayer?, scrolling: () -> Boolean, content: @Composable () -> Unit) {
    val p = palette
    val blurred = if (frost && seen != null) rememberGraphicsLayer() else null
    // The radius the layer's blur was last built for: the effect is built again only when it changes.
    val builtFor = remember { floatArrayOf(-1f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Cheaper while a list moves (spec M): the radius drops at once and comes back within 150 ms.
    val latest by rememberUpdatedState(scrolling)
    val moving by remember { derivedStateOf { latest() } }
    val radius by animateFloatAsState(
        with(density) { (if (moving) Glass.scrollRadius else Glass.radius).toPx() },
        if (moving) snap() else tween(150),
        label = "glass radius",
    )
    val fade = with(density) { Glass.fade.toPx() }
    Box(
        Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } } // a bar is a target: taps on its blank parts stop here
            .drawBehind {
                if (blurred != null && seen != null) {
                    // The content behind this bar, blurred, then the tint over it.
                    val originY = if (edge == Edge.Top) 0f else (seen.size.height - size.height)
                    if (builtFor[0] != radius) {
                        builtFor[0] = radius
                        blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    }
                    blurred.clip = true
                    blurred.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                        translate(top = -originY) { drawLayer(seen) }
                    }
                    drawLayer(blurred)
                    drawRect(p.glass)
                } else {
                    drawRect(p.veil)
                    val (y, brush) = if (edge == Edge.Top) {
                        size.height to Brush.verticalGradient(listOf(p.veil, Color.Transparent), startY = size.height, endY = size.height + fade)
                    } else {
                        -fade to Brush.verticalGradient(listOf(Color.Transparent, p.veil), startY = -fade, endY = 0f)
                    }
                    drawRect(brush, topLeft = Offset(0f, y), size = Size(size.width, fade))
                }
            }
            // Read in the order a person sees it: the top bar, then the content, then the bottom bar.
            .semantics { isTraversalGroup = true; traversalIndex = if (edge == Edge.Top) -1f else 1f }
            .then(if (edge == Edge.Top) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
    ) { content() }
}
