package io.github.victorbonnet.enclos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** Typed native questions only; readable history stays the fallback for older hosts. */
@Composable
fun QuestionCard(client: QuestionClient, question: QuestionInfo) {
    val p = palette
    var selected by rememberSaveable(question.host, question.id) { mutableStateOf(emptyList<Int>()) }
    var other by rememberSaveable(question.host, question.id) { mutableStateOf(false) }
    var text by rememberSaveable(question.host, question.id) { mutableStateOf("") }
    val selection = QuestionSelection(selected.toSet(), other, text)
    val change: (QuestionSelection) -> Unit = { selected = it.indices.toList(); other = it.other; text = it.text }
    val terminal = question.state != QuestionState.Pending
    val unavailable = client.unavailable(question)
    val enabled = unavailable == null
    val confirmed = question.answer
    val indices = if (terminal) (confirmed as? QuestionAnswer.Choices)?.indices.orEmpty().toSet() else selection.indices
    val custom = if (terminal) confirmed is QuestionAnswer.Text else other
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer { MarkdownText(question.text, p.text) }
        if (question.choices.isNotEmpty()) {
            Text(if (question.multiple) "Choose one or more" else "Choose one", style = Type.small, color = p.textMuted)
            question.choices.forEachIndexed { index, label ->
                Choice(label, index in indices, enabled = enabled, multiple = question.multiple, wrap = true, modifier = Modifier.fillMaxWidth()) {
                    change(selection.choose(index, question.multiple))
                }
            }
            Choice("Other", custom, enabled = enabled, wrap = true, modifier = Modifier.fillMaxWidth()) { change(selection.chooseOther()) }
        }
        if (question.choices.isEmpty() || custom) {
            FormField(
                if (terminal) (confirmed as? QuestionAnswer.Text)?.text.orEmpty() else text,
                { text = it },
                label = if (question.choices.isEmpty()) "Answer" else "Other answer",
                placeholder = "Write your answer",
                enabled = enabled,
                singleLine = false,
                minLines = 2,
            )
        }
        if (terminal) {
            Text(when (question.state) {
                QuestionState.Answered -> "Answered"
                QuestionState.Skipped -> "Skipped"
                QuestionState.Expired -> "Expired · ask the agent for a new question."
                QuestionState.Cancelled -> "Cancelled"
                QuestionState.Pending -> ""
            }, style = Type.small, color = p.textMuted)
            if (confirmed is QuestionAnswer.Choices) {
                Text("Selected: ${confirmed.indices.joinToString("; ") { question.choices[it] }}", style = Type.body, color = p.text)
            }
            if (question.key in client.pending) {
                Text("Waiting for this submission's confirmation…", style = Type.small, color = p.textMuted)
            }
        } else {
            val answer = selection.answer(question)
            PrimaryButton("Submit", enabled = enabled && answer != null) { answer?.let { client.submit(question, it) } }
            ActionButton("Skip", enabled = enabled) { client.submit(question, QuestionAnswer.Skip) }
            unavailable?.let { Text(it, style = Type.small, color = p.textMuted) }
        }
        client.errors[question.key]?.let { Text(it, style = Type.small, color = p.textMuted) }
    }
}
