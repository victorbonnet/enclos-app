package io.github.victorbonnet.enclos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** The native runtime's exact waiting approval, with no inferred/default action. */
@Composable
fun RuntimeApprovalCard(client: QuestionClient, question: QuestionInfo) {
    val approval = question.approval ?: return
    val p = palette
    val unavailable = client.unavailable(question)
    val enabled = unavailable == null
    val terminal = question.state != QuestionState.Pending
    // Not saveable: a confirmation never comes back by itself when the card scrolls back or the app returns.
    var confirming by remember(question.host, question.id) { mutableStateOf<RuntimeApprovalAction?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question.text, style = Type.name, color = p.text)
        SelectionContainer {
            Box(Modifier.fillMaxWidth().clip(Shapes.control).background(p.panelDeep).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Text(approval.command, style = Type.code, color = p.panelText, softWrap = false)
            }
        }
        SelectionContainer { Text(approval.reason, style = Type.body, color = p.text) }
        runtimeApprovalScopeNotes(approval).forEach { Text(it, style = Type.small, color = p.textMuted) }
        // The runtime's own words, under the app's line that says whose they are.
        approval.scopes?.let { SelectionContainer { Text(it.shownDescription, style = Type.small, color = p.textMuted) } }
        if (approval.smartDenied) {
            Text(if (approval.actions == listOf(RuntimeApprovalAction.Deny)) "Only Deny is available for this request." else "Only Allow once or Deny is available for this request.", style = Type.small, color = p.textMuted)
        }
        Text("Existing sandbox policy still applies. Execution is reported separately.", style = Type.small, color = p.textMuted)
        approval.actions.forEach { action ->
            ActionButton(action.label, modifier = Modifier.fillMaxWidth().border(1.dp, if (enabled) p.lineStrong else p.line, Shapes.control), enabled = enabled) {
                // A grant beyond this one command is confirmed first; Once and Deny are one explicit tap.
                if (grantConfirmation(approval, action) != null) confirming = action
                else client.submit(question, QuestionAnswer.Approval(action))
            }
        }
        if (terminal) {
            Text(runtimeApprovalStatus(question).orEmpty(), style = Type.body, color = p.text)
            if (question.state == QuestionState.Resolved) {
                Text("The app did not receive the decision details.", style = Type.small, color = p.textMuted)
            }
            if (question.key in client.pending) {
                Text("Waiting for this decision's confirmation…", style = Type.small, color = p.textMuted)
            }
        } else {
            unavailable?.let { Text(it, style = Type.small, color = p.textMuted) }
            nativeAnswerNote(question, unavailable, question.key in client.pending)?.let {
                SelectionContainer { Text(it, style = Type.small, color = p.textMuted) }
            }
        }
        client.errors[question.key]?.let { Text(it, style = Type.small, color = p.textMuted) }
    }
    val action = confirming
    val text = action?.let { grantConfirmation(approval, it) }
    if (action != null && text != null) {
        ConfirmDialog("${action.label}?", text, action.label, onDismiss = { confirming = null }, enabled = enabled) {
            confirming = null
            client.submit(question, QuestionAnswer.Approval(action))
        }
    }
}

/** The app's line above a runtime's own description of its grants. */
internal const val SCOPES_FRAME = "The runtime describes these grants as follows:"

/** What each offered scope allows: for a scoped request the runtime's description follows; otherwise the patterns are named. */
internal fun runtimeApprovalScopeNotes(approval: RuntimeApprovalDefinition): List<String> = buildList {
    if (RuntimeApprovalAction.Once in approval.actions) add("Allow once applies only to this command request.")
    if (approval.scopes != null) {
        add(SCOPES_FRAME)
        return@buildList
    }
    if (!approval.grantsPatterns) return@buildList
    add("A session or always grant covers every command matching: ${approval.patterns.joinToString("; ")}. Not only this one.")
    if (RuntimeApprovalAction.Session in approval.actions) add("Allow for session lasts for this runtime session.")
    if (RuntimeApprovalAction.Always in approval.actions) add("Always allow saves the grant in this runtime.")
}

/**
 * The confirmation a grant beyond this one command asks for (Session and Always), or none
 * for Once and Deny. A scoped request repeats the runtime's own description of its grants,
 * never its patterns, which it need not send; a legacy request names its patterns.
 */
internal fun grantConfirmation(approval: RuntimeApprovalDefinition, action: RuntimeApprovalAction): String? {
    if (action == RuntimeApprovalAction.Once || action == RuntimeApprovalAction.Deny) return null
    approval.scopes?.let { return "${action.label} grants more than this command. $SCOPES_FRAME ${it.shownDescription}" }
    val patterns = approval.patterns.joinToString("; ")
    return if (action == RuntimeApprovalAction.Session) "Every later command matching $patterns will run without asking, until this runtime session ends."
    else "Every later command matching $patterns will run without asking, in every conversation using this runtime profile, until the grant is removed in the runtime."
}

/**
 * Where the card cannot be answered (offline, before sync, an unconfirmed outcome), the
 * runtime's own instructions for answering it there, marked as the runtime's. None while a
 * decision of this device's awaits its receipt, or for a legacy request, which has none.
 */
internal fun nativeAnswerNote(question: QuestionInfo, unavailable: String?, pending: Boolean): String? {
    val fallback = question.approval?.scopes?.fallback ?: return null
    if (question.state != QuestionState.Pending || unavailable == null || pending) return null
    return "The runtime says it can also be answered there: ${Visible.revealed(fallback)}"
}

internal fun runtimeApprovalStatus(question: QuestionInfo): String? = when (question.state) {
    QuestionState.Pending -> null
    QuestionState.Answered -> (question.answer as? QuestionAnswer.Approval)?.action?.result ?: "Resolved in the runtime"
    QuestionState.Expired -> "Expired"
    QuestionState.Cancelled -> "Cancelled"
    QuestionState.Resolved, QuestionState.Skipped -> "Resolved in the runtime"
}
