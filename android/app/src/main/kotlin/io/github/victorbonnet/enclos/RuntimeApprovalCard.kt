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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** What every runtime approval card says first, in the app's words. */
const val RUNTIME_APPROVAL_FRAME = "The runtime asks to run a command"

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
    // The list opens on its newest item, so a tall card shows its buttons first: allowing waits
    // until the start of the command has been on screen.
    var startSeen by remember(question.host, question.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The runtime wrote what follows; this heading is the app's.
        Text(RUNTIME_APPROVAL_FRAME, style = Type.name, color = p.text)
        SelectionContainer { Text(Visible.revealed(question.text), style = Type.body, color = p.text) }
        // Whole and wrapped, never scrolled out of sight; hidden characters and runs of blanks are written out.
        SelectionContainer {
            Box(
                Modifier.fillMaxWidth().clip(Shapes.control).background(p.panelDeep)
                    .onGloballyPositioned { box ->
                        val bounds = box.boundsInWindow()
                        if (!startSeen && bounds.height > 0f && bounds.top <= box.positionInWindow().y + 1f) startSeen = true
                    }
                    .padding(12.dp),
            ) {
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
        if (!terminal) commandLengthNote(approval, startSeen)?.let { Text(it, style = Type.small, color = p.text) }
        approval.actions.forEach { action ->
            val ready = enabled && actionReady(action, startSeen)
            ActionButton(action.label, modifier = Modifier.fillMaxWidth().border(1.dp, if (ready) p.lineStrong else p.line, Shapes.control), enabled = ready) {
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

internal const val OUTSIDE_ASCII_NOTICE = "This command holds characters outside ASCII, some of which look like others. Check each one."

internal const val REVOKE_ALWAYS = "Always allow saves the grant in the runtime until it is removed there (Hermes: the profile's command_allowlist in config.yaml)."

/** A command longer than this many lines, as shown, says next to the buttons where it starts. */
internal const val LONG_COMMAND_LINES = 4

/** A command longer than this many characters, as shown, says so too: wrapped, it runs over several lines. */
internal const val LONG_COMMAND_CHARACTERS = 240

/** Deny is always ready; an allow waits until the start of the command has been on screen. */
internal fun actionReady(action: RuntimeApprovalAction, startSeen: Boolean): Boolean =
    action == RuntimeApprovalAction.Deny || startSeen

/**
 * What the app says next to the buttons: for a long command, how long it is and that it
 * starts above, so its last lines are not taken for all of it; and, until the start of the
 * command has been on screen, that only Deny is available. None for a short command in view.
 */
internal fun commandLengthNote(approval: RuntimeApprovalDefinition, startSeen: Boolean): String? {
    val shown = approval.shownCommand
    val lines = approval.shownLines
    val long = lines > LONG_COMMAND_LINES || shown.length > LONG_COMMAND_CHARACTERS
    val size = "This command is ${if (lines == 1) "one line" else "$lines lines"} (${shown.length} characters) and starts above."
    return when {
        !startSeen && long -> "$size Scroll up to its start to read it all; until then only Deny is available."
        !startSeen -> "Scroll up to the start of the command to allow it; until then only Deny is available."
        long -> "$size Read it all before you answer."
        else -> null
    }
}

/** What each offered scope allows, naming the runtime's patterns a session or always grant covers. */
internal fun runtimeApprovalScopeNotes(approval: RuntimeApprovalDefinition): List<String> = buildList {
    if (RuntimeApprovalAction.Once in approval.actions) add("Allow once applies only to this command request.")
    if (approval.grantsPatterns) {
        add("A session or always grant covers every command matching: ${approval.patterns.joinToString("; ")}. Not only this one.")
    }
    if (RuntimeApprovalAction.Session in approval.actions) add("Allow for session lasts for this runtime session.")
    if (RuntimeApprovalAction.Always in approval.actions) add(REVOKE_ALWAYS)
}

/**
 * The confirmation a grant beyond this one command asks for (Session and Always): how far it
 * reaches, and for Always where it is revoked. None for Once and Deny, which are one tap.
 */
internal fun grantConfirmation(approval: RuntimeApprovalDefinition, action: RuntimeApprovalAction): String? = when (action) {
    RuntimeApprovalAction.Session ->
        "Every later command matching ${approval.patterns.joinToString("; ")} will run without asking, until this runtime session ends."
    RuntimeApprovalAction.Always -> alwaysConfirmation(approval)
    RuntimeApprovalAction.Once, RuntimeApprovalAction.Deny -> null
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
