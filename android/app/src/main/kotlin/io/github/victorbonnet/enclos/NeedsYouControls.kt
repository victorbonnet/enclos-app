package io.github.victorbonnet.enclos

import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

@Composable
fun NeedsYouRow(item: OpenItem, label: String, online: Boolean, photo: PhotoKey? = null, now: Long = System.currentTimeMillis() / 1000, open: () -> Unit) {
    val p = palette
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Review request from $label", onClick = open)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AgentInitial(label, false, side = 32.dp, photo = photo)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label + if (item.expired) " · Expired" else "", style = Type.name, color = p.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Times.ago(item.since, now), style = Type.small, color = p.textFaint, maxLines = 1)
                Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
            }
            Text(
                "${item.host} · " + (if (online) "" else "Offline, cached request · ") + item.summary,
                style = Type.small, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A request has one review surface, separate from the conversation history. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NeedsYouSheet(model: AppModel, openChat: (String) -> Unit, openApproval: (String) -> Unit) {
    val opening = model.focusedRequest ?: return
    val item = model.attentionItems().firstOrNull { it.key == opening.key }
    if (item == null && model.needsYou.freshHosts[opening.host] == true) {
        LaunchedEffect(opening.key) { model.focusedRequest = null }
        return
    }
    val current = item ?: opening
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { model.focusedRequest = null }, sheetState = sheet,
        containerColor = palette.bg, shape = Shapes.bubble, tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Needs you", style = Type.label, color = palette.accent)
            Text(current.agent?.let { model.agentLabel("${current.host}/$it") } ?: "Policy request", style = Type.name, color = palette.text)
            when (current.kind) {
                "held_task" -> {
                    val card = model.heldTasks.cards.values.firstOrNull { it.host == current.host && it.agent == current.agent && it.task == current.id }
                    if (card == null) Text(current.summary, style = Type.small, color = palette.text)
                    else {
                        Text(card.title, style = Type.name, color = palette.text)
                        Text(card.reason, style = Type.small, color = palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (model.heldTasks.offersResume(card)) {
                            PrimaryButton("Resume task", enabled = model.heldTasks.canResume(card)) { model.heldTasks.resume(card) }
                            model.heldTasks.unavailable(card)?.let { Text(it, style = Type.small, color = palette.textMuted) }
                        } else if (card.state == HeldTaskState.Held && !card.resumable) {
                            val instruction = card.resume.ifBlank { "This task cannot be resumed from the app." }
                            Text(instruction, style = Type.small, color = palette.textMuted)
                            TaskBoardAction(model, card)
                        }
                        RequestSecondaryButton("View task") {
                            model.focusedRequest = null
                            model.taskDetails.showHeld(card)
                        }
                        model.heldTasks.notes[card.key]?.let { Text(it, style = Type.small, color = palette.textMuted) }
                    }
                }
                "question", "runtime_approval" -> {
                    val question = model.questions.questions[QuestionKey(current.host, current.id)]
                    if (question == null) {
                        Text(current.summary, style = Type.small, color = palette.text)
                        Text("Open the conversation to review this request.", style = Type.small, color = palette.textMuted)
                    } else if (question.approval != null) RuntimeApprovalCard(model.questions, question)
                    else QuestionCard(model.questions, question)
                }
                "policy_request" -> {
                    Text(current.summary, style = Type.small, color = palette.text)
                    PrimaryButton("Review request") {
                        model.focusedRequest = null
                        openApproval("${current.host}/${current.id}")
                    }
                }
            }
            val conversation = model.conversations.firstOrNull { it.host == current.host && it.id == current.conversation }
            if (conversation != null) RequestSecondaryButton("View conversation") {
                model.focusedRequest = null
                openChat(conversation.id)
            }
            RequestSecondaryButton(if (current.key in model.reminderDismissals.pending) "Dismissing…" else "Dismiss reminder", enabled = model.reminderDismissals.unavailable(current) == null) {
                model.reminderDismissals.dismiss(current)
            }
            Text("Dismiss hides this reminder. It leaves the request and work unchanged.", style = Type.small, color = palette.textMuted)
            model.reminderDismissals.unavailable(current)?.let { Text(it, style = Type.small, color = palette.textMuted) }
            model.reminderDismissals.errors[current.key]?.let { Text(it, style = Type.small, color = palette.textMuted) }
        }
    }
}

@Composable
private fun TaskBoardAction(model: AppModel, card: HeldTask) {
    val agent = AgentKey(card.host, card.agent)
    if (model.forgetting.isRemoved(agent) || agent in model.forgetting.forgotten) return
    val context = LocalContext.current
    val link = model.taskBoardLinks[agent]
    var editing by rememberSaveable(card.host, card.agent) { mutableStateOf(false) }
    PrimaryButton("Open task board") {
        if (link == null) editing = true else openWebLink(context, link.address)
    }
    if (link != null) {
        Text("Opens ${link.host}", style = Type.small, color = palette.textMuted)
        TextLink("Edit task board link") { editing = true }
    }
    if (!editing) return

    var draft by rememberSaveable(card.host, card.agent, link?.address) { mutableStateOf(link?.address.orEmpty()) }
    val destination = taskBoardDestination(draft)
    val p = palette
    AlertDialog(
        onDismissRequest = { editing = false },
        containerColor = p.surface,
        shape = Shapes.bubble,
        title = { Text("Task board link", style = Type.heading, color = p.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(draft, { draft = it }, label = "HTTPS address", placeholder = "https://")
                Text("Enter the board address for ${model.agentLabel("${card.host}/${card.agent}")} on ${card.host}.", style = Type.small, color = p.textMuted)
                if (draft.isNotBlank() && destination == null) {
                    Text("Enter a valid HTTPS address without a username or password.", style = Type.small, color = p.textMuted)
                }
                destination?.let { Text("Opens ${it.host}", style = Type.small, color = p.textMuted) }
            }
        },
        confirmButton = {
            ActionButton("Save and open", enabled = destination != null) {
                destination?.let {
                    model.taskBoardLinks.set(agent, it)
                    editing = false
                    openWebLink(context, it.address)
                }
            }
        },
        dismissButton = {
            Row {
                if (link != null) ActionButton("Remove link") {
                    model.taskBoardLinks.forget(agent)
                    editing = false
                }
                ActionButton("Cancel") { editing = false }
            }
        },
    )
}

@Composable
fun RequestSecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    ActionButton(text, modifier = Modifier.fillMaxWidth().border(1.dp, palette.line, Shapes.control), enabled = enabled, wrap = true, onClick = onClick)
}

@Composable
fun SuggestedReplyButtons(model: AppModel, conversation: Conversation, line: Line, history: List<Line>) {
    val replies = offeredReplies(line, history)
    if (replies.isEmpty()) return
    var error by remember(line.seq) { mutableStateOf<String?>(null) }
    val enabled = model.connected(conversation.host) && !model.outgoing.waiting(conversation.id)
    ReplyChoices(replies, enabled) { error = model.sendSuggested(conversation, line, it) }
    error?.let { Text(it, style = Type.small, color = palette.textMuted) }
}

@Composable
fun ReplyChoices(replies: List<String>, enabled: Boolean, send: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        replies.forEach { reply -> ActionButton(reply, modifier = Modifier.fillMaxWidth(), enabled = enabled, wrap = true) { send(reply) } }
    }
}

/** Consecutive runtime notices are grouped without hiding an agent's actual messages. */
fun statusRuns(lines: List<Line>): Map<Long, List<Line>> {
    val groups = mutableMapOf<Long, List<Line>>()
    var pending = mutableListOf<Line>()
    fun flush() {
        if (pending.isNotEmpty()) {
            groups[pending.last().seq] = pending.toList()
            pending = mutableListOf()
        }
    }
    for (line in lines) {
        if (line.status && line.attachments.isEmpty()) pending += line
        else flush()
    }
    flush()
    return groups
}
