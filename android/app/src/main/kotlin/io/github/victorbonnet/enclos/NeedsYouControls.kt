package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

@Composable
fun NeedsYouRow(item: OpenItem, label: String, online: Boolean, photo: PhotoKey? = null, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Review request from $label", onClick = open)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AgentInitial(label, false, side = 32.dp,
            photo = photo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label + if (item.expired) " · Expired" else "", style = Type.name, color = palette.accent)
            Text(item.summary, style = Type.body, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.host + if (online) "" else " · Offline, cached request", style = Type.small, color = palette.textMuted)
        }
        Glyph(Glyphs.chevron, palette.textFaint, size = 16.dp)
    }
}

/** A reference to the original card, opened with its conversation, never a new message. */
@Composable
fun FocusedRequest(model: AppModel, item: OpenItem) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Needs you", style = Type.label, color = palette.accent)
        when (item.kind) {
            "question", "runtime_approval" -> {
                val question = model.questions.questions[QuestionKey(item.host, item.id)]
                if (question == null) Text(if (model.connected(item.host)) "Native controls are unavailable for this request. Review the original message below or use the runtime to answer it." else "Connect to this host to load the request.", style = Type.body, color = palette.textMuted)
                else if (question.approval != null) RuntimeApprovalCard(model.questions, question)
                else QuestionCard(model.questions, question) { model.draftQuestionReply(question, it) }
            }
            "held_task" -> {
                val card = model.heldTasks.cards.values.firstOrNull { it.host == item.host && it.agent == item.agent && it.task == item.id }
                if (card == null) Text(if (model.connected(item.host)) "The task’s controls are unavailable. Review its original message below." else "Connect to this host to load the request.", style = Type.body, color = palette.textMuted)
                else HeldTaskCard(model.heldTasks, card)
                val conversation = model.conversations.firstOrNull { it.id == item.conversation && it.host == item.host }
                if (conversation != null) {
                    val history = model.lines[conversation.id].orEmpty()
                    history.lastOrNull { offeredReplies(it, history).isNotEmpty() }?.let { line ->
                        SuggestedReplyButtons(model, conversation, line, history)
                    }
                }
            }
        }
        TextLink("Back to latest messages") { model.focusedRequest = null }
    }
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
