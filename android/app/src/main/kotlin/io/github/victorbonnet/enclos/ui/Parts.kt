package io.github.victorbonnet.enclos.ui

import io.github.victorbonnet.enclos.Visible

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import android.graphics.Bitmap
import io.github.victorbonnet.enclos.firstAgentCharacter
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.victorbonnet.enclos.PhotoKey

/** Keyed by host and agent kept apart, so names shared across hosts never share pictures. */
val LocalAgentPictures = staticCompositionLocalOf<Map<PhotoKey, Bitmap>> { emptyMap() }

/** A line of `line`: lists and bars are separated by hairlines, never shadows. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(palette.line))
}

/** One of the design system's glyphs, tinted. */
@Composable
fun Glyph(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** A 48dp tap target around a glyph. */
@Composable
fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = palette.text) {
    Box(
        Modifier.size(48.dp).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, tint) }
}

/**
 * The Home base mark's four open corners, stretched over the whole box:
 * each arm a [arm] fraction of the side, drawn with a square end.
 */
@Composable
fun Corners(color: Color, modifier: Modifier = Modifier, stroke: Dp = 2.dp, arm: Float = 0.3f) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val h = s / 2
        val ax = size.width * arm
        val ay = size.height * arm
        val r = size.width - h
        val b = size.height - h
        val path = Path().apply {
            moveTo(ax, h); lineTo(h, h); lineTo(h, ay)
            moveTo(r - (ax - h), h); lineTo(r, h); lineTo(r, ay)
            moveTo(h, b - (ay - h)); lineTo(h, b); lineTo(ax, b)
            moveTo(r, b - (ay - h)); lineTo(r, b); lineTo(r - (ax - h), b)
        }
        drawPath(path, color, style = Stroke(width = s))
    }
}

/**
 * An agent: the first letter of its name inside the mark's corners, in
 * `accent` while its host is connected, `line-strong` otherwise. While it
 * is [working], a copy of the corners swells out of them and fades, every
 * 1.4 s; with animations off, only the corners' `accent` says so.
 */
@Composable
fun AgentAvatar(name: String, live: Boolean, small: Boolean = false, working: Boolean = false, photo: PhotoKey? = null) {
    val side = if (small) 32.dp else 44.dp
    val picture = photo?.let { LocalAgentPictures.current[it] }
    val stroke = if (small) 2.dp else 2.5.dp
    Box(
        Modifier.size(side).then(if (working) Modifier.semantics { contentDescription = "$name is working" } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (working && !animationsOff()) {
            val pulse = rememberInfiniteTransition(label = "active")
            val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")
            Corners(
                palette.accent,
                Modifier.size(side).graphicsLayer {
                    // Read here, the pulse redraws the corners without recomposing them.
                    val grow = (t / 0.7f).coerceAtMost(1f)
                    scaleX = 1f + 0.4f * grow
                    scaleY = 1f + 0.4f * grow
                    alpha = 0.7f * (1f - grow)
                },
                stroke = stroke,
            )
        }
        // The corners say whether the host is connected, photo or not.
        Corners(if (live || working) palette.accent else palette.lineStrong, Modifier.size(side), stroke = stroke)
        if (picture != null) {
            Image(
                picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(side - stroke * 4).clip(CircleShape),
            )
        } else Text(
            firstAgentCharacter(name).lowercase(),
            style = Type.code.copy(fontSize = if (small) 12.sp else 15.sp, fontWeight = FontWeight.SemiBold),
            color = palette.text,
        )
    }
}

/**
 * A sandbox: the Enclos mark, its corners around a filled square, in
 * `accent` while its host is connected, `line-strong` otherwise, unless tinted.
 */
@Composable
fun SandboxMark(live: Boolean, side: Dp = 32.dp, tint: Color? = null) {
    val color = tint ?: if (live) palette.accent else palette.lineStrong
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        Corners(color, Modifier.size(side), stroke = 2.dp)
        Box(Modifier.size(side * 0.22f).background(color))
    }
}

/**
 * An agent's two colours: a hue from its name, so each agent keeps its own,
 * deep enough for white letters in light and dark alike.
 */
private fun agentColors(name: String): Pair<Color, Color> {
    val hue = ((name.hashCode() % 360) + 360) % 360f
    return Color.hsl(hue, 0.42f, 0.46f) to Color.hsl((hue + 40f) % 360f, 0.48f, 0.32f)
}

/**
 * An agent: a round picture of its own two colours, a diagonal blend, with
 * the first letter of its name in white. While it is [working], a ring in
 * `accent` swells out of it and fades, every 1.4 s; with animations off,
 * the ring stays.
 */
@Composable
fun AgentInitial(name: String, working: Boolean = false, side: Dp = 28.dp, photo: PhotoKey? = null) {
    val p = palette
    // A display-name edit changes the letter, not the agent's established colours.
    val identity = photo?.agent?.substringAfterLast('/') ?: name
    val (from, to) = remember(identity) { agentColors(identity) }
    Box(
        Modifier.size(side).then(if (working) Modifier.semantics { contentDescription = "$name is working" } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (working) {
            if (animationsOff()) {
                Box(Modifier.size(side + 6.dp).border(1.5.dp, p.accent, CircleShape))
            } else {
                val pulse = rememberInfiniteTransition(label = "active")
                val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")
                Box(
                    Modifier.size(side).graphicsLayer {
                        // Read here, the pulse redraws the ring without recomposing it.
                        val grow = (t / 0.7f).coerceAtMost(1f)
                        scaleX = 1f + 0.45f * grow
                        scaleY = 1f + 0.45f * grow
                        alpha = 0.8f * (1f - grow)
                    }.border(2.dp, p.accent, CircleShape),
                )
            }
        }
        Box(
            Modifier.size(side).clip(CircleShape).background(Brush.linearGradient(listOf(from, to))),
            contentAlignment = Alignment.Center,
        ) {
            val picture = photo?.let { LocalAgentPictures.current[it] }
            if (picture != null) Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(side))
            else Text(
                firstAgentCharacter(name).uppercase(),
                style = Type.name.copy(fontSize = (side.value * 0.44f).coerceAtLeast(11f).sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }
    }
}

/** An unread count: a pill in `accent`. */
@Composable
fun UnreadBadge(count: Int) {
    Box(
        Modifier.defaultMinSize(minWidth = 22.dp).height(22.dp).clip(Shapes.pill).background(palette.accent)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = "$count unread" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", style = Type.code.copy(fontWeight = FontWeight.SemiBold), color = palette.bg)
    }
}


/** A host's connection: a filled square while connected, an outline otherwise. */
@Composable
fun ConnectionDot(on: Boolean) {
    val m = Modifier.size(8.dp)
    Box(if (on) m.background(palette.accent) else m.border(1.5.dp, palette.textFaint))
}

/** A top app bar: 64dp, the screen's actions at its ends. */
@Composable
fun AppBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * A tab's title (`title-large`), at the top of the screen with no bar above
 * it, so the content starts high; the tab's one [action], if any, at its end.
 */
@Composable
fun LargeTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 12.dp).defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.titleLarge, color = palette.text, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** The extended floating action button: the screen's one primary action. */
@Composable
fun Fab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.height(56.dp).clip(Shapes.bubble).background(palette.buttonBg)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(icon, palette.buttonText)
        Text(text, style = Type.label, color = palette.buttonText)
    }
}

/** The three destinations of the navigation bar. */
enum class Tab(val label: String) { Inbox("Inbox"), Agents("Agents"), Settings("Settings") }

/**
 * The navigation bar: the active destination is enclosed, its icon inside
 * the logo's four open corners in `accent`, in place of Material's pill.
 */
@Composable
fun NavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(palette.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(80.dp).padding(top = 12.dp)) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                // The whole column takes the tap, with no press shown: the
                // corners closing in on the tab chosen are its answer, and
                // they open out of the one left.
                val enclosed by animateFloatAsState(if (on) 1f else 0f, Motion.change(Motion.MEDIUM), label = "enclosed")
                val tint by animateColorAsState(if (on) palette.accent else palette.textMuted, Motion.change(), label = "tint")
                val label by animateColorAsState(if (on) palette.text else palette.textMuted, Motion.change(), label = "label")
                Column(
                    Modifier.weight(1f).clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.width(56.dp).height(32.dp), contentAlignment = Alignment.Center) {
                        Corners(
                            palette.accent,
                            Modifier.width(56.dp).height(32.dp).graphicsLayer {
                                alpha = enclosed
                                val scale = 1.25f - 0.25f * enclosed
                                scaleX = scale
                                scaleY = scale
                            },
                            stroke = 2.5.dp,
                            arm = 0.21f,
                        )
                        val icon = when (tab) {
                            Tab.Inbox -> Glyphs.inbox
                            Tab.Agents -> Glyphs.agents
                            Tab.Settings -> Glyphs.settings
                        }
                        Glyph(icon, tint)
                    }
                    Text(tab.label, style = Type.label.copy(fontSize = 12.sp), color = label)
                }
            }
        }
    }
}

/** A day between messages, in lowercase mono: `today`, `yesterday`, `mon 3 nov`. */
@Composable
fun DaySeparator(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.meta, color = palette.textFaint)
    }
}

/**
 * The runtime about itself (gateway online, shutting down), not the agent
 * speaking: a quiet centred line, small and faint, with its time, as a day
 * separator is, never a bubble. Its kind and text come from the sandbox, so
 * it is [statusText]: one line, nothing hidden, and the runtime's name
 * before it, never to be read as the app's or the bridge's own word.
 */
@Composable
fun StatusLine(source: String, text: String, time: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(
            "${statusText(source, text)} · $time",
            style = Type.meta,
            color = palette.textFaint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A status line as shown: [source] (the runtime that sent it), then its
 * [text] on one line, without the characters that would reorder or hide
 * part of it.
 */
fun statusText(source: String, text: String): String {
    val line = Visible.stripped(text.replace(Regex("\\s+"), " ")).trim()
    return "${Visible.stripped(source).trim()}: $line"
}

/**
 * A message: the person's on `button-bg` at the right, the agent's on
 * `surface` with a hairline at the left; 16dp corners but the speaker's
 * bottom one, which stays square, the logo's corner. A message the agent
 * sent on its own (a cron job's, say) carries [header] above its text.
 */
@Composable
fun Bubble(
    text: String,
    time: String,
    outgoing: Boolean,
    header: String? = null,
    files: (@Composable () -> Unit)? = null,
    speaker: String? = null,
    speakerPhoto: PhotoKey? = null,
) {
    val p = palette
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        // In a group, who of its members said it: its avatar and name above.
        if (speaker != null && !outgoing) {
            Row(
                Modifier.padding(start = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentInitial(speaker, side = 20.dp, photo = speakerPhoto)
                Text(speaker, style = Type.meta.copy(fontWeight = FontWeight.SemiBold), color = p.textMuted)
            }
        }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = if (outgoing) Shapes.bubbleOut else Shapes.bubbleIn
        val base = Modifier.widthIn(max = 300.dp).clip(shape)
        val look = if (outgoing) base.background(p.buttonBg) else base.background(p.surface).border(BorderStroke(1.dp, p.line), shape)
        Column(look.padding(horizontal = 12.dp, vertical = 9.dp)) {
            if (header != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Glyph(Glyphs.clock, p.accent, size = 14.dp)
                    Text(header, style = Type.meta, color = p.accent)
                }
            }
            // Its files first (an image the agent made), then its words.
            files?.let {
                it()
                if (text.isNotBlank()) Gap(8.dp)
            }
            // The agent writes markdown, drawn; what the person typed shows as typed.
            if (outgoing) {
                Text(text, style = Type.message, color = p.buttonText)
            } else if (text.isNotBlank()) {
                SelectionContainer { MarkdownText(text, p.text) }
            }
            Text(
                time,
                style = Type.meta,
                color = if (outgoing) p.buttonText.copy(alpha = 0.8f) else p.textFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    }
}

/**
 * The agent is working: three squares in a bubble of the agent's shape,
 * rising and brightening one after the other, a wave that loops every
 * 900 ms. They stand still when the person turned animations off.
 */
@Composable
fun WorkingBubble(agent: String) {
    Box(
        Modifier.clip(Shapes.bubbleIn).background(palette.surface).border(BorderStroke(1.dp, palette.line), Shapes.bubbleIn)
            .padding(horizontal = 12.dp, vertical = 14.dp).semantics { contentDescription = "$agent is working" },
    ) { WorkingWave() }
}

/**
 * The working wave on its own: three [square]s in `accent`, rising by
 * [lift] and brightening one after the other every 900 ms; still with
 * animations off.
 */
@Composable
fun WorkingWave(square: Dp = 6.dp, lift: Dp = 4.dp) {
    val still = animationsOff()
    val wave = rememberInfiniteTransition(label = "working")
    val phase by wave.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "phase",
    )
    val up = with(LocalDensity.current) { lift.toPx() }
    Row(horizontalArrangement = Arrangement.spacedBy(square * 5 / 6), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier.size(square).graphicsLayer {
                    // Read here, the phase redraws the squares without recomposing them.
                    val bump = if (still) 0f else bump((phase - i * 0.18f + 1f) % 1f)
                    translationY = -up * bump
                    alpha = 0.4f + 0.6f * bump
                }.background(palette.accent),
            )
        }
    }
}

/** 0 → 1 → 0 over the first half of the cycle, then rest: one square's rise. */
private fun bump(t: Float): Float = if (t < 0.5f) sin(PI.toFloat() * t / 0.5f) else 0f

/** A line about the conversation that is not a message, such as why the host is not connected. */
@Composable
fun SystemLine(text: String, modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxWidth().then(modifier), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = Type.meta.copy(fontSize = 11.5.sp, lineHeight = 17.sp),
            color = palette.textMuted,
            modifier = Modifier.widthIn(max = 320.dp).clip(Shapes.control)
                .border(1.dp, palette.lineStrong, Shapes.control).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * The message field and its send button. While it cannot send ([canSend]
 * false, such as while the host is not connected), the field still takes
 * text, the button stays off, and [notice] above them says why.
 */
@Composable
fun Composer(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    canSend: Boolean = true,
    notice: String? = null,
    onSend: () -> Unit,
) {
    val p = palette
    Column(Modifier.fillMaxWidth().background(p.bg)) {
        Hairline()
        AnimatedVisibility(notice != null, enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()), exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit())) {
            // Kept while it leaves, so the text does not vanish before the line closes.
            var shown by remember { mutableStateOf(notice.orEmpty()) }
            if (notice != null) shown = notice
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionDot(false)
                Text(shown, style = Type.small, color = p.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Its own cursor: text put in from outside (a command picked) leaves it at the end.
            var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != value) onValue(it.text)
                },
                textStyle = Type.message.copy(color = p.text),
                cursorBrush = SolidColor(p.accent),
                maxLines = 5,
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Box(
                        Modifier.defaultMinSize(minHeight = 44.dp).clip(Shapes.control).background(p.bg)
                            .border(1.dp, p.lineStrong, Shapes.control).padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) Text(placeholder, style = Type.message, color = p.textFaint)
                        field()
                    }
                },
            )
            val enabled = value.isNotBlank() && canSend
            val ground by animateColorAsState(if (enabled) p.buttonBg else p.surface, Motion.change(), label = "send")
            val arrow by animateColorAsState(if (enabled) p.buttonText else p.textFaint, Motion.change(), label = "arrow")
            Box(
                Modifier.size(44.dp).clip(Shapes.control).background(ground)
                    .clickable(enabled = enabled, onClickLabel = "Send", role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = "Send" },
                contentAlignment = Alignment.Center,
            ) { Glyph(Glyphs.send, arrow) }
        }
    }
}

/** A text link in `accent`, with its arrow. */
@Composable
fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = Type.label,
        color = palette.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** A form field with the same outline, cursor and type as the conversation name. */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = Type.message,
) {
    val p = palette
    Column(Modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = Type.small, color = p.textMuted)
            Gap(8.dp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            textStyle = textStyle.copy(color = if (enabled) p.text else p.textFaint),
            cursorBrush = SolidColor(p.accent),
            modifier = modifier.fillMaxWidth().then(if (label == null) Modifier else Modifier.semantics { contentDescription = label }),
            decorationBox = { field ->
                Box(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).clip(Shapes.control).background(p.bg)
                        .border(1.dp, if (enabled) p.lineStrong else p.line, Shapes.control).padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = textStyle, color = p.textFaint)
                    field()
                }
            },
        )
    }
}

/** A secondary action with the app's 44dp target and accent type. */
@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        // 48dp: the platform's minimum touch target.
        modifier.defaultMinSize(minHeight = 48.dp).clip(Shapes.control).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.label, color = if (enabled) palette.accent else palette.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A switch in the same palette as the app lock, including disabled states. */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = palette
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.bg,
            checkedTrackColor = p.accent,
            checkedBorderColor = p.accent,
            uncheckedThumbColor = p.textMuted,
            uncheckedTrackColor = p.surface,
            uncheckedBorderColor = p.lineStrong,
            disabledCheckedThumbColor = p.surface,
            disabledCheckedTrackColor = p.accent.copy(alpha = 0.38f),
            disabledCheckedBorderColor = p.accent.copy(alpha = 0.38f),
            disabledUncheckedThumbColor = p.textFaint.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = p.surface,
            disabledUncheckedBorderColor = p.line,
        ),
    )
}

/** A full-width primary button on `button-bg`. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) palette.buttonBg else palette.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.label, color = if (enabled) palette.buttonText else palette.textFaint) }
}

/** Vertical space. */
@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))
