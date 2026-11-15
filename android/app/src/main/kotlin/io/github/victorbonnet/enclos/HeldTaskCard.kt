package io.github.victorbonnet.enclos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** What every held task's card says first, in the app's words. */
const val HELD_TASK_FRAME = "Held task · the runtime keeps it; resuming changes no access"

/**
 * A held task's card, in its agent's Home at the message that introduced it: the runtime's
 * title, reason, last error (folded when long), assignee and what resuming does, then since
 * when it is held, its state and, while held, Resume. The runtime's words are plain text,
 * never markdown, and arrive without hidden characters ([HeldTask.from]).
 */
@Composable
fun HeldTaskCard(client: HeldTaskClient, card: HeldTask) {
    val p = palette
    var unfolded by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf(false) }
    val unavailable = client.unavailable(card)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The runtime wrote all that follows; this line is the app's.
        Text(HELD_TASK_FRAME, style = Type.small, color = p.textMuted)
        SelectionContainer { Text(card.title, style = Type.name, color = p.text) }
        SelectionContainer { Text(card.reason, style = Type.body, color = p.text) }
        card.lastError?.let { error ->
            val folds = foldsError(error)
            Text("Last error", style = Type.small, color = p.textMuted)
            SelectionContainer {
                Box(Modifier.fillMaxWidth().clip(Shapes.control).background(p.panelDeep).padding(12.dp)) {
                    Text(
                        error,
                        style = Type.code,
                        color = p.panelText,
                        maxLines = if (folds && !unfolded) FOLDED_ERROR_LINES else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (folds) TextLink(if (unfolded) "Show less" else "Show the whole error") { unfolded = !unfolded }
        }
        card.assignee?.let { Text("Assigned to $it", style = Type.small, color = p.textMuted) }
        Text("Held since ${Times.ago(card.since, System.currentTimeMillis() / 1000)}", style = Type.small, color = p.textMuted)
        if (card.resume.isNotBlank()) Text("Resume: ${card.resume}", style = Type.small, color = p.textMuted)
        Text(heldTaskStatus(card, here = card.key in client.receipts), style = Type.body, color = p.text)
        if (client.offersResume(card)) {
            PrimaryButton("Resume", enabled = unavailable == null) { client.resume(card) }
            unavailable?.let { Text(it, style = Type.small, color = p.textMuted) }
        }
        client.notes[card.key]?.let { Text(it, style = Type.small, color = p.textMuted) }
    }
}
