package io.github.victorbonnet.enclos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.AgentTask
import io.github.victorbonnet.enclos.TaskState
import io.github.victorbonnet.enclos.taskLine
import io.github.victorbonnet.enclos.taskStatus

/**
 * A task asked for in this conversation, live: its mark, its assignee
 * ([assignee], as the app names it), its title and where it is (`running
 * 12 min`), then its summary once it ended. It is not a message: a framed
 * line among them, never a bubble. Every text is the sandbox's, drawn as
 * plain text, the title on one line.
 */
@Composable
fun TaskCard(task: AgentTask, assignee: String, now: Long) {
    val p = palette
    val line = taskLine(assignee, task, now)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(Shapes.control)
                .border(1.dp, p.lineStrong, Shapes.control).background(p.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clearAndSetSemantics { contentDescription = "Task: $line" + (task.summary?.takeIf { task.state.ended }?.let { ". $it" } ?: "") },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { TaskMark(task.state) }
                // The status stays whole: a long title gives way to it.
                Text(
                    "$assignee · ${task.title}",
                    style = Type.small,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("· ${taskStatus(task, now)}", style = Type.small, color = if (task.state.ended) p.textMuted else p.accent, maxLines = 1)
            }
            task.summary?.takeIf { task.state.ended }?.let {
                Text(
                    it,
                    style = Type.small,
                    color = p.textMuted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp),
                )
            }
        }
    }
}

/**
 * Where a task is, as a mark: running, a square pulsing in `accent`;
 * queued or blocked, a faint outlined square; done, a check; failed or
 * cancelled, a cross. Still with animations off.
 */
@Composable
fun TaskMark(state: TaskState) {
    val p = palette
    when (state) {
        TaskState.Running -> {
            val still = animationsOff()
            val pulse = rememberInfiniteTransition(label = "task")
            val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "alpha")
            Box(Modifier.size(7.dp).graphicsLayer { this.alpha = if (still) 1f else alpha }.background(p.accent))
        }
        TaskState.Queued, TaskState.Blocked -> Box(Modifier.size(7.dp).border(1.dp, p.textFaint))
        TaskState.Done -> Glyph(Glyphs.check, p.accent, size = 14.dp)
        TaskState.Failed, TaskState.Cancelled -> Glyph(Glyphs.clear, p.textMuted, size = 14.dp)
    }
}
