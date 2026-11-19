package io.github.victorbonnet.enclos

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** The sheet's header line for a held task: its agent and, when another agent does the work, whose task it is. */
internal fun heldTaskHeader(agentLabel: String, card: HeldTask): String =
    card.assignee?.let { "$agentLabel · $it's task" } ?: agentLabel

/** What the sheet says is blocked: the agent's words, else the runtime's reason (drawn on two lines). */
internal fun heldTaskBlocked(card: HeldTask): String = card.ask?.blocked?.takeIf { it.isNotBlank() } ?: card.reason

/**
 * A held task in a Needs you sheet, from a bridge that takes the person's answer (enclos ADR
 * 0073): the decision, what is blocked, then the answers first, one button per choice of the
 * agent's ask (the recommended one first, marked) and a typed answer. A card no agent wrote offers
 * **Ask the agent** and, where the runtime allows it, Resume. Every other part is under a folded
 * **Details**: the task's id and title, the runtime's reason, then [details] (View task, View
 * conversation, Open task board, Dismiss reminder). A tap sends one `held_task.answer` at the
 * card's revision and waits for its result; nothing is sent again on its own. An answered card
 * says what the person answered.
 */
@Composable
fun HeldTaskAnswerPanel(client: HeldTaskClient, card: HeldTask, details: @Composable ColumnScope.() -> Unit) {
    val p = palette
    var open by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf(false) }
    var draft by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf("") }
    val ask = card.ask
    val answering = client.offersAnswer(card)
    val unavailable = client.answerUnavailable(card)
    val enabled = answering && unavailable == null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The agent wrote the decision and what is blocked; a card no agent wrote shows the runtime's title and reason.
        SelectionContainer { Text(ask?.decision ?: card.title, style = Type.heading, color = p.text) }
        if (card.state == HeldTaskState.Answered) {
            Text(answeredLine(card), style = Type.body, color = p.text)
        } else {
            val blocked = heldTaskBlocked(card)
            val fromAgent = ask?.blocked?.isNotBlank() == true
            SelectionContainer {
                Text(
                    "Blocked: $blocked", style = Type.body, color = p.text,
                    maxLines = if (fromAgent) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (answering) {
                if (ask != null) {
                    ask.shown().forEach { (index, label) ->
                        HeldChoiceButton(label, recommended = index == ask.recommended, enabled = enabled) {
                            client.answer(card, HeldTaskAnswer.Choice(index))
                        }
                    }
                } else {
                    PrimaryButton("Ask the agent", enabled = enabled) { client.answer(card, HeldTaskAnswer.Agent) }
                    if (client.offersResume(card)) {
                        RequestSecondaryButton("Resume task", enabled = client.canResume(card)) { client.resume(card) }
                    }
                }
                FormField(
                    draft, { draft = it },
                    label = "Something else…",
                    placeholder = "Write your answer",
                    enabled = enabled,
                    singleLine = false,
                    minLines = 2,
                )
                val typed = heldAnswerText(draft)
                if (draft.isNotBlank() && typed == null) {
                    Text("Remove tabs or hidden characters, or shorten the answer, to send it.", style = Type.small, color = p.textMuted)
                }
                RequestSecondaryButton("Send", enabled = enabled && typed != null) {
                    typed?.let { client.answer(card, HeldTaskAnswer.Text(it)) }
                }
                unavailable?.let { Text(it, style = Type.small, color = p.textMuted) }
            } else {
                Text(client.status(card), style = Type.small, color = p.textMuted)
            }
        }
        client.notes[card.key]?.let { Text(it, style = Type.small, color = p.textMuted) }
        TextLink(if (open) "Hide details" else "Details") { open = !open }
        if (open) {
            Text("Task ${card.task}", style = Type.small, color = p.textMuted)
            SelectionContainer { Text(card.title, style = Type.name, color = p.text) }
            SelectionContainer { Text(card.reason, style = Type.small, color = p.text) }
            // A card the agent wrote keeps Resume here, where the runtime allows it.
            if (ask != null && client.offersResume(card) && card.state == HeldTaskState.Held) {
                RequestSecondaryButton("Resume task", enabled = client.canResume(card)) { client.resume(card) }
                client.unavailable(card)?.let { Text(it, style = Type.small, color = p.textMuted) }
            }
            details()
        }
    }
}

/** One choice of the agent's ask: the whole line wraps; the recommended one is outlined in the accent colour and says so. */
@Composable
private fun HeldChoiceButton(label: String, recommended: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = palette
    Column(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(Shapes.control)
            .border(if (recommended) 2.dp else 1.dp, if (recommended && enabled) p.accent else p.line, Shapes.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (recommended) Text("Recommended", style = Type.small, color = if (enabled) p.accent else p.textFaint)
        Text(label, style = Type.label, color = if (enabled) p.text else p.textFaint)
    }
}
