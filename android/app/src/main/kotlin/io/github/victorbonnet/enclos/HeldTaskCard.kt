package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
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
 * A held task's card, in its agent's Home at the message that introduced it (or, with no such
 * message, below the history: [HeldTaskClient.unlinked]): the runtime's title, reason and last
 * error (folded when long), then the assignee, since when it is held and what resuming does,
 * then its state and, while held, Resume. The runtime's words are plain text, never markdown,
 * and arrive without hidden characters ([HeldTask.from]).
 */
@Composable
fun HeldTaskCard(client: HeldTaskClient, card: HeldTask) {
    val p = palette
    var reasonUnfolded by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf(false) }
    var unfolded by rememberSaveable(card.host, card.agent, card.task) { mutableStateOf(false) }
    val unavailable = client.unavailable(card)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The runtime wrote all that follows; this line is the app's.
        Text(if (card.resumable) HELD_TASK_FRAME else "Held task", style = Type.small, color = p.textMuted)
        SelectionContainer { Text(card.title, style = Type.name, color = p.text) }
        SelectionContainer { Text(card.reason, style = Type.body, color = p.text,
            maxLines = if (reasonUnfolded || (card.reason.length <= 180 && card.reason.count { it == '\n' } < 3)) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis) }
        if (card.reason.length > 180 || card.reason.count { it == '\n' } >= 3) {
            TextLink(if (reasonUnfolded) "Show less" else "Show details") { reasonUnfolded = !reasonUnfolded }
        }
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
            if (folds) Text(
                if (unfolded) "Show less" else "Show the whole error",
                style = Type.label, color = p.accent,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button) { unfolded = !unfolded }.padding(vertical = 12.dp),
            )
        }
        card.assignee?.let { Text("Assigned to $it", style = Type.small, color = p.textMuted) }
        Text("Held since ${Times.ago(card.since, System.currentTimeMillis() / 1000)}", style = Type.small, color = p.textMuted)
        if (card.resumable) {
            if (card.resume.isNotBlank()) Text("Resume: ${card.resume}", style = Type.small, color = p.textMuted)
        } else if (card.state == HeldTaskState.Held) {
            Text(card.resume.ifBlank { "This task cannot be resumed from the app." }, style = Type.small, color = p.textMuted)
        }
        Text(client.status(card), style = Type.body, color = p.text)
        if (client.offersResume(card)) {
            PrimaryButton("Resume", enabled = unavailable == null) { client.resume(card) }
            unavailable?.let { Text(it, style = Type.small, color = p.textMuted) }
        }
        client.notes[card.key]?.let { Text(it, style = Type.small, color = p.textMuted) }
    }
}
