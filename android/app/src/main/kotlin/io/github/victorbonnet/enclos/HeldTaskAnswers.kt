package io.github.victorbonnet.enclos

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** The sheet's header line for a held task: its agent and, when another agent does the work, whose task it is. */
internal fun heldTaskHeader(agentLabel: String, card: HeldTask): String =
    card.assignee?.let { "$agentLabel · $it's task" } ?: agentLabel

/** What the sheet says is blocked: the agent's words, else the runtime's reason, in full. */
internal fun heldTaskBlocked(card: HeldTask): String = card.ask?.blocked?.takeIf { it.isNotBlank() } ?: card.reason

/** What a card no agent wrote says its one button does. */
internal const val ASK_FOR_CHOICES = "Ask the agent for choices"
internal const val ASK_FOR_CHOICES_HINT = "The agent looks into it and writes its question here, with choices to tap."

/**
 * A held task in a Needs you sheet, from a bridge that takes the person's answer (enclos ADR
 * 0073), laid out so the person decides without opening anything: the decision, what is blocked
 * in full, then the answers. Each choice of the agent's ask is one tap: the recommended one first,
 * filled and marked, the others outlined. A typed answer is folded behind **Answer in your own
 * words** (open at once when the ask has no choices, or a draft is kept). A card no agent wrote
 * shows the runtime's title and its whole reason, and offers **Ask the agent for choices** and,
 * where the runtime allows it, Resume. **Details** folds the rest: the task's id and title, the
 * runtime's reason unless it is shown above, then [details] (View task, View
 * conversation, Open task board, Dismiss reminder). A tap sends one `held_task.answer` at the
 * card's revision and waits for its result; nothing is sent again on its own. An answered card
 * says what the person answered.
 */
@Composable
fun HeldTaskAnswerPanel(client: HeldTaskClient, card: HeldTask, details: @Composable ColumnScope.() -> Unit) {
    val p = palette
    var open by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf(false) }
    var draft by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf("") }
    var writing by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf(false) }
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
            // Everything the person needs to decide, never cut: what is blocked is at most 400
            // characters, the runtime's reason at most 1,000.
            SelectionContainer { Text(heldTaskBlocked(card), style = Type.body, color = p.text) }
            if (answering) {
                if (ask != null) {
                    ask.shown().forEach { (index, label) ->
                        HeldChoiceButton(label, recommended = index == ask.recommended, enabled = enabled) {
                            client.answer(card, HeldTaskAnswer.Choice(index))
                        }
                    }
                } else {
                    PrimaryButton(ASK_FOR_CHOICES, enabled = enabled) { client.answer(card, HeldTaskAnswer.Agent) }
                    Text(ASK_FOR_CHOICES_HINT, style = Type.small, color = p.textMuted)
                    if (client.offersResume(card)) {
                        RequestSecondaryButton("Resume task", enabled = client.canResume(card)) { client.resume(card) }
                    }
                }
                if (writing || draft.isNotEmpty() || ask?.choices?.isEmpty() == true) {
                    FormField(
                        draft, { draft = it },
                        label = "Your answer",
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
                } else {
                    // The only way to type an answer: a full 48dp target that wraps at large text.
                    ActionButton("Answer in your own words", Modifier.fillMaxWidth(), wrap = true) { writing = true }
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
            // Unless it is shown above already: on a held card no agent wrote, or whose ask says nothing is blocked.
            if (card.state == HeldTaskState.Answered || heldTaskBlocked(card) != card.reason) {
                SelectionContainer { Text(card.reason, style = Type.small, color = p.text) }
            }
            // A card the agent wrote keeps Resume here, where the runtime allows it.
            if (ask != null && client.offersResume(card) && card.state == HeldTaskState.Held) {
                RequestSecondaryButton("Resume task", enabled = client.canResume(card)) { client.resume(card) }
                client.unavailable(card)?.let { Text(it, style = Type.small, color = p.textMuted) }
            }
            details()
        }
    }
}

/**
 * One choice of the agent's ask, one tap: the whole line wraps. The recommended one is filled
 * like the app's primary button and says so; the others are outlined.
 */
@Composable
private fun HeldChoiceButton(label: String, recommended: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = palette
    val text = when {
        !enabled -> p.textFaint
        recommended -> p.buttonText
        else -> p.text
    }
    Column(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(Shapes.control)
            .then(
                if (recommended) Modifier.background(if (enabled) p.buttonBg else p.surface)
                else Modifier.border(1.dp, if (enabled) p.lineStrong else p.line, Shapes.control),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (recommended) Text("Recommended", style = Type.small, color = text)
        Text(label, style = Type.label, color = text)
    }
}
