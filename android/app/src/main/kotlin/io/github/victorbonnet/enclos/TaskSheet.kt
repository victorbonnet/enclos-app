package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.victorbonnet.enclos.ui.ActionButton
import io.github.victorbonnet.enclos.ui.AgentInitial
import io.github.victorbonnet.enclos.ui.Gap
import io.github.victorbonnet.enclos.ui.Glyph
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.Shapes
import io.github.victorbonnet.enclos.ui.TaskMark
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.palette
import kotlinx.coroutines.delay

/**
 * A task's sheet, over whatever screen opened it (a task row on an agent's
 * page, a task card in a conversation, an agent's `working on a task`):
 * its title, state, assignee and who asked for it; the conversation it was
 * asked in, when this phone holds it; then what its runtime holds of it,
 * fetched as the sheet opens ([TaskDetails]): its body, related work,
 * comments and history. Read only: no edit, no comment. Every text is the
 * sandbox's, drawn as plain text, never markdown or links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(model: AppModel, openChat: (String) -> Unit) {
    val details = model.taskDetails
    val open = details.open ?: return
    val p = palette
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = details::close, sheetState = sheet, containerColor = p.bg, shape = Shapes.bubble, tonalElevation = 0.dp) {
        // A related task opened in its place starts at its top.
        key(open) {
            val held = details.held
            val task = held?.let(::heldTaskSummary) ?: model.tasks[open]
            if (task == null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
                    Text("Task", style = Type.heading, color = p.text)
                    Gap(8.dp)
                    Text("Its host no longer keeps this task.", style = Type.small, color = p.textMuted)
                    ActionButton("Close", onClick = details::close)
                }
            } else {
                TaskSheetContent(model, task, openChat, held)
            }
        }
    }
}

@Composable
private fun TaskSheetContent(model: AppModel, task: AgentTask, openChat: (String) -> Unit, held: HeldTask? = null) {
    val p = palette
    val details = model.taskDetails
    val connected = model.connected(task.host)
    val shares = if (held != null) model.agents.any { it.host == held.host && it.name == held.agent && "held_task_details" in it.capabilities } && model.hostSupports(held.host, "held_task.get") else model.offersTaskDetails(task.assigneeAddress)
    val available = connected && shares
    LaunchedEffect(task.key, available) { if (available && details.detail == null) details.fetch() }
    // "running 12 min" and the times stay true while the sheet shows.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val detail = details.detail.takeIf { available }
    val name = { agent: String -> model.agentLabel("${task.host}/$agent") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // The title, where it is, who does it and who asked.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = Type.heading.copy(fontSize = 19.sp, lineHeight = 24.sp), color = p.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { TaskMark(task.state) }
                Text(if (held != null) heldTaskStatus(held) else taskStatus(task, now), style = Type.small, color = if (task.state.ended) p.textMuted else p.accent)
            }
            Text("Assigned to ${name(task.assignee)}", style = Type.small, color = p.textMuted)
            task.askedBy?.let { Text("Asked by ${name(it)}", style = Type.small, color = p.textMuted) }
            task.summary?.takeIf { task.state.ended }?.let { Text(it, style = Type.small, color = p.textMuted) }
        }
        val asked = task.conversation?.takeIf { id -> model.conversations.any { it.host == task.host && it.id == id } }
        if (asked != null) {
            Gap(8.dp)
            SettingRow(
                Modifier.clickable(onClickLabel = "Open the conversation it was asked in") {
                    details.close()
                    openChat(asked)
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open the conversation it was asked in", style = Type.label, color = p.accent, modifier = Modifier.weight(1f))
                    Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                }
            }
        }
        // Why there is no detail, or that it comes.
        val note = when {
            !connected -> "Details need the host connected."
            !shares -> "This agent's runtime doesn't share task details."
            details.error != null -> details.error
            detail == null -> "Loading details…"
            else -> null
        }
        if (note != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(note, style = Type.small, color = p.textMuted)
                if (available && details.error != null) ActionButton("Retry", enabled = !details.loading, onClick = { details.fetch() })
            }
        }
        if (detail != null) TaskDetailSections(model, task, detail, now, name)
    }
}

@Composable
private fun TaskDetailSections(model: AppModel, task: AgentTask, detail: TaskDetail, now: Long, name: (String) -> String) {
    val p = palette
    detail.body?.let {
        Text(it, style = Type.message, color = p.text, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
    }
    if (detail.related.isNotEmpty()) {
        Section("Related work") {
            detail.related.forEach { r ->
                val key = TaskKey(task.host, task.sandbox, r.id)
                val known = model.taskDetails.held == null && model.tasks[key] != null
                SettingRow(if (known) Modifier.clickable(onClickLabel = "Open the task") { model.taskDetails.show(key) } else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (r.relation == Relation.Parent) "↑" else "↓", style = Type.label, color = p.textFaint)
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = Type.name, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val line = listOfNotNull(
                                // A parent: this task waits for it; a child waits for this task.
                                r.relation.wire,
                                r.state?.wire,
                                r.assignee?.let(name),
                            ).joinToString(" · ")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                r.state?.let { Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { TaskMark(it) } }
                                Text(line, style = Type.small, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (known) Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                    }
                }
            }
        }
    }
    Section(if (detail.commentsTotal > 0) "Comments · ${detail.commentsTotal}" else "Comments") {
        if (detail.comments.isEmpty()) {
            SettingRow { Text("No comments.", style = Type.small, color = p.textMuted) }
        }
        if (detail.earlier > 0 && detail.comments.isNotEmpty()) {
            SettingRow {
                Text(
                    if (detail.earlier == 1) "1 earlier comment not shown" else "${detail.earlier} earlier comments not shown",
                    style = Type.small, color = p.textFaint,
                )
            }
        }
        detail.comments.forEach { c ->
            SettingRow {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val agent = c.agent
                    if (agent != null) AgentInitial(name(agent), side = 24.dp, photo = PhotoKey(task.host, agent))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // An agent of the host by its shown name; anyone else as the runtime names them, in mono.
                            if (agent != null) Text(name(agent), style = Type.label, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            else Text(c.author, style = Type.code, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text(Times.ago(c.time, now), style = Type.meta, color = p.textFaint, maxLines = 1)
                        }
                        Text(c.text, style = Type.small, color = p.text)
                    }
                }
            }
        }
    }
    if (detail.history.isNotEmpty()) {
        Section("History") {
            detail.history.forEach { e ->
                SettingRow {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(historyLine(e, name, now), style = Type.small, color = p.text, modifier = Modifier.weight(1f))
                        Text(Times.ago(e.time, now), style = Type.meta, color = p.textFaint, maxLines = 1)
                    }
                    e.text?.let { Text(it, style = Type.small, color = p.textMuted) }
                }
            }
        }
    }
}

/** Presentation only: read routing always uses HeldTask.agent and the complete task reference. */
internal fun heldTaskSummary(card: HeldTask): AgentTask = AgentTask(
    host = card.host, id = card.task, title = card.title,
    assignee = card.assignee?.let { if ('/' in it) AgentTask.agentName(it) else AgentTask.agentName("${card.agent.substringBefore('/')}/$it") } ?: card.agent,
    askedBy = card.agent, conversation = card.conversation, state = TaskState.Blocked,
    since = card.since, summary = null,
)
