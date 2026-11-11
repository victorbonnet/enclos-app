package io.github.victorbonnet.enclos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    var confirmAlways by rememberSaveable(question.host, question.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question.text, style = Type.name, color = p.text)
        // Whole and wrapped, never scrolled out of sight; hidden characters are written out.
        SelectionContainer {
            Box(Modifier.fillMaxWidth().clip(Shapes.control).background(p.panelDeep).padding(12.dp)) {
                Text(approval.shownCommand, style = Type.code, color = p.panelText)
            }
        }
        if (approval.outsideAscii) Text(OUTSIDE_ASCII_NOTICE, style = Type.small, color = p.text)
        SelectionContainer { Text(Visible.revealed(approval.reason), style = Type.body, color = p.text) }
        runtimeApprovalScopeNotes(approval).forEach { Text(it, style = Type.small, color = p.textMuted) }
        if (approval.smartDenied) {
            Text(if (approval.actions == listOf(RuntimeApprovalAction.Deny)) "Only Deny is available for this request." else "Only Allow once or Deny is available for this request.", style = Type.small, color = p.textMuted)
        }
        Text("Existing sandbox policy still applies. Execution is reported separately.", style = Type.small, color = p.textMuted)
        approval.actions.forEach { action ->
            ActionButton(action.label, modifier = Modifier.fillMaxWidth().border(1.dp, if (enabled) p.lineStrong else p.line, Shapes.control), enabled = enabled) {
                // A grant that persists is confirmed first; every other action is one explicit tap.
                if (action == RuntimeApprovalAction.Always) confirmAlways = true
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
        }
        client.errors[question.key]?.let { Text(it, style = Type.small, color = p.textMuted) }
    }
    if (confirmAlways) {
        ConfirmDialog("Always allow?", alwaysConfirmation(approval), "Always allow", onDismiss = { confirmAlways = false }, enabled = enabled) {
            confirmAlways = false
            client.submit(question, QuestionAnswer.Approval(RuntimeApprovalAction.Always))
        }
    }
}

internal const val OUTSIDE_ASCII_NOTICE = "This command holds characters outside ASCII, some of which look like others. Check each one."

internal const val REVOKE_ALWAYS = "Always allow saves the grant in the runtime until it is removed there (Hermes: the profile's command_allowlist in config.yaml)."

/** What each offered scope allows, naming the runtime's patterns a session or always grant covers. */
internal fun runtimeApprovalScopeNotes(approval: RuntimeApprovalDefinition): List<String> = buildList {
    if (RuntimeApprovalAction.Once in approval.actions) add("Allow once applies only to this command request.")
    if (approval.grantsPatterns) {
        add("A session or always grant covers every command matching: ${approval.patterns.joinToString("; ")}. Not only this one.")
    }
    if (RuntimeApprovalAction.Session in approval.actions) add("Allow for session lasts for this runtime session.")
    if (RuntimeApprovalAction.Always in approval.actions) add(REVOKE_ALWAYS)
}

/** The confirmation an Always allow asks for: how far the grant reaches, and where it is revoked. */
internal fun alwaysConfirmation(approval: RuntimeApprovalDefinition): String =
    "Every later command matching ${approval.patterns.joinToString("; ")} will run without asking, in every conversation using this runtime profile. $REVOKE_ALWAYS"

internal fun runtimeApprovalStatus(question: QuestionInfo): String? = when (question.state) {
    QuestionState.Pending -> null
    QuestionState.Answered -> (question.answer as? QuestionAnswer.Approval)?.action?.result ?: "Resolved in the runtime"
    QuestionState.Expired -> "Expired"
    QuestionState.Cancelled -> "Cancelled"
    QuestionState.Resolved, QuestionState.Skipped -> "Resolved in the runtime"
}
