package io.github.victorbonnet.enclos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.victorbonnet.enclos.ui.Glyph
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.Shapes
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.palette

/**
 * The sandbox page's pause block: heading, state line, one full-width action
 * button (min 48dp, grows with large text), and optional notice line.
 */
@Composable
internal fun SandboxPauseBlock(
    state: SandboxPauseBlockState,
    onAction: (PauseAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                heading()
                contentDescription = state.headingDescription
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Glyph(Glyphs.pause, p.textFaint, size = 14.dp)
            Text(
                state.heading,
                style = Type.meta.copy(letterSpacing = 0.04.em),
                color = p.textFaint,
            )
        }
        Text(
            state.stateLine,
            style = Type.small,
            color = p.textMuted,
        )
        state.button?.let { button ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(Shapes.control)
                    .background(if (button.enabled) p.buttonBg else p.surface)
                    .clickable(enabled = button.enabled, role = Role.Button) { onAction(button.action) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    button.label,
                    style = Type.label,
                    color = if (button.enabled) p.buttonText else p.textFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
        state.notice?.let { notice ->
            Text(
                notice,
                style = Type.small,
                color = p.textFaint,
            )
        }
    }
}

/**
 * Stateful sandbox pause block wired to [AppModel], including the confirmation
 * dialog for pause (resume asks nothing).
 */
@Composable
internal fun SandboxPauseSection(
    model: AppModel,
    host: String,
    sandbox: String,
    listed: Sandbox?,
    agentCount: Int,
) {
    val connected = model.connected(host)
    val inFlight = model.pause.inFlight[host]
    val thisPauseInFlight = inFlight?.sandbox == sandbox && inFlight.action == PauseAction.Pause
    val block = sandboxPauseBlock(
        host = host,
        sandbox = sandbox,
        connected = connected,
        hasBothCapabilities = model.pause.hasBothCapabilities(host),
        notAllowed = model.pause.notAllowed[host] == true,
        hostState = model.pause.states[host],
        listedState = listed?.state,
        hasRuntime = listed?.runtime != null || agentCount > 0,
        inFlight = inFlight,
        notice = model.pause.notices["$host/$sandbox"],
    ) ?: return

    var confirmingPause by remember(host, sandbox) { mutableStateOf(false) }
    var submittedPause by remember(host, sandbox) { mutableStateOf(false) }

    LaunchedEffect(thisPauseInFlight) {
        if (submittedPause && !thisPauseInFlight) {
            submittedPause = false
            confirmingPause = false
        }
    }

    SandboxPauseBlock(
        state = block,
        onAction = { action ->
            when (action) {
                PauseAction.Pause -> {
                    submittedPause = false
                    confirmingPause = true
                }
                PauseAction.Resume -> model.pause.resume(host, sandbox)
            }
        },
    )

    if (confirmingPause) {
        val sandboxPrefix = "$sandbox/"
        val waiting = model.attentionItems.filter { it.host == host && it.agent?.startsWith(sandboxPrefix) == true }
        val waitingQuestions = waiting.count { it.kind == "question" }
        val waitingHeldTasks = waiting.count { it.kind == "held_task" }
        val usageText = sandboxLastDayUsageText(model.recentUsage[host], sandbox)
        val body = pauseConfirmParagraphs(
            host = host,
            sandbox = sandbox,
            agentCount = agentCount,
            lastDayUsage = usageText,
            waitingQuestions = waitingQuestions,
            waitingHeldTasks = waitingHeldTasks,
        ).joinToString("\n\n")
        ConfirmDialog(
            title = "Pause every agent in $sandbox?",
            text = body,
            confirm = if (thisPauseInFlight) "Pausing…" else "Pause $sandbox",
            enabled = !thisPauseInFlight,
            onDismiss = {
                if (!thisPauseInFlight) confirmingPause = false
            },
            onConfirm = {
                if (model.pause.pause(host, sandbox)) {
                    submittedPause = true
                } else {
                    confirmingPause = false
                }
            },
        )
    }
}
