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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question.text, style = Type.name, color = p.text)
        SelectionContainer {
            Box(Modifier.fillMaxWidth().clip(Shapes.control).background(p.panelDeep).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Text(approval.command, style = Type.code, color = p.panelText, softWrap = false)
            }
        }
        SelectionContainer { Text(approval.reason, style = Type.body, color = p.text) }
        if (RuntimeApprovalAction.Once in approval.actions) {
            Text("Allow once applies only to this command request.", style = Type.small, color = p.textMuted)
        }
        if (RuntimeApprovalAction.Session in approval.actions) {
            Text("Allow for session grants the runtime's command pattern for this session.", style = Type.small, color = p.textMuted)
        }
        if (RuntimeApprovalAction.Always in approval.actions) {
            Text("Always allow saves that pattern grant in this runtime.", style = Type.small, color = p.textMuted)
        }
        if (approval.smartDenied) {
            Text(if (approval.actions == listOf(RuntimeApprovalAction.Deny)) "Only Deny is available for this request." else "Only Allow once or Deny is available for this request.", style = Type.small, color = p.textMuted)
        }
        Text("Existing sandbox policy still applies. Execution is reported separately.", style = Type.small, color = p.textMuted)
        approval.actions.forEach { action ->
            ActionButton(action.label, modifier = Modifier.fillMaxWidth().border(1.dp, if (enabled) p.lineStrong else p.line, Shapes.control), enabled = enabled) {
                client.submit(question, QuestionAnswer.Approval(action))
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
}

internal fun runtimeApprovalStatus(question: QuestionInfo): String? = when (question.state) {
    QuestionState.Pending -> null
    QuestionState.Answered -> (question.answer as? QuestionAnswer.Approval)?.action?.result ?: "Resolved in the runtime"
    QuestionState.Expired -> "Expired"
    QuestionState.Cancelled -> "Cancelled"
    QuestionState.Resolved, QuestionState.Skipped -> "Resolved in the runtime"
}
