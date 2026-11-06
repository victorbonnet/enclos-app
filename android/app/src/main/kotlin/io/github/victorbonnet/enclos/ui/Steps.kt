package io.github.victorbonnet.enclos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.Step

/**
 * What the agent did for an answer, one tool a line, in the manner of
 * ChatGPT's and Claude's steps: while it works ([live]), every step shows,
 * the running one marked by a pulsing square; once it answered, the steps
 * fold into one line above its message ("3 steps"), which opens them.
 */
@Composable
fun ToolSteps(steps: List<Step>, live: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val p = palette
    val open = live || expanded
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.change(), label = "chevron")
    Column(Modifier.widthIn(max = 320.dp)) {
        Row(
            Modifier.defaultMinSize(minHeight = 32.dp)
                .clickable(enabled = !live, onClickLabel = if (expanded) "Hide the steps" else "Show the steps", role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (open) "shown" else "hidden" }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Glyph(Glyphs.chevron, p.textFaint, size = 16.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
            val count = if (steps.size == 1) "1 step" else "${steps.size} steps"
            Text(if (live) "Working · $count" else count, style = Type.code, color = p.textMuted)
        }
        AnimatedVisibility(
            open,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit()),
        ) {
            Column(Modifier.padding(start = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { StepLine(it) }
            }
        }
    }
}

/** One tool: its mark, its name, what it did, and how long it took. */
@Composable
private fun StepLine(step: Step) {
    val p = palette
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { StepMark(step) }
        Text(step.name, style = Type.meta, color = if (step.ok) p.textMuted else p.textFaint, maxLines = 1)
        Text(
            step.label.orEmpty(),
            style = Type.small.copy(fontSize = Type.meta.fontSize, lineHeight = Type.meta.lineHeight),
            color = p.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        step.durationMs?.let { Text(seconds(it), style = Type.meta, color = p.textFaint) }
    }
}

/** Running: a square pulsing in `accent`; done: a check; failed or refused: a cross. */
@Composable
private fun StepMark(step: Step) {
    val p = palette
    when {
        !step.done -> {
            val still = animationsOff()
            val pulse = rememberInfiniteTransition(label = "running")
            val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "alpha")
            Box(Modifier.size(7.dp).graphicsLayer { this.alpha = if (still) 1f else alpha }.background(p.accent))
        }
        step.ok -> Glyph(Glyphs.check, p.accent, size = 14.dp)
        else -> Glyph(Glyphs.clear, p.textMuted, size = 14.dp)
    }
}

/** `0.4 s`, `12 s`. */
private fun seconds(ms: Long): String = if (ms < 10_000) "%.1f s".format(ms / 1000.0) else "${ms / 1000} s"
