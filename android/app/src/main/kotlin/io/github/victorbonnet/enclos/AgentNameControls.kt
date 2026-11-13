package io.github.victorbonnet.enclos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** Editing a shared name never changes its stable address or mention handle. */
@Composable
fun AgentNameControls(model: AppModel, agent: Agent) {
    val client = model.agentNames
    var editing by remember(agent.address) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ActionButton("Rename", enabled = client.editable(agent.address) && agent.address !in client.pending, onClick = { editing = true })
        val hint = when {
            !model.connected(agent.host) -> "Connect to ${agent.host} to change this shared name."
            !agent.supportsNames -> "Update the host to enable shared agent names."
            !agent.canEditName -> "Only the host owner can change this shared name."
            else -> null
        }
        hint?.let { Text(it, style = Type.small, color = palette.textFaint) }
        if (!editing) client.errors[agent.address]?.let { Text(it, style = Type.small, color = palette.textMuted) }
    }
    if (editing) AgentNameEditor(model, agent, close = { editing = false })
}

@Composable
private fun AgentNameEditor(model: AppModel, agent: Agent, close: () -> Unit) {
    val p = palette
    val client = model.agentNames
    val address = agent.address
    var opening by remember(address) { mutableStateOf<SharedAgentName?>(null) }
    var name by remember(address) { mutableStateOf("") }
    var submitted by remember(address) { mutableStateOf<String?>(null) }
    val busy = address in client.pending
    LaunchedEffect(address) { client.load(address) }
    LaunchedEffect(client.fresh[address], busy) {
        if (opening == null && !busy && client.fresh[address] == true) {
            client.current(address)?.let { opening = it; name = it.displayName.orEmpty() }
        }
    }
    LaunchedEffect(submitted, client.receipts[address]) {
        if (submitted != null && client.receipts[address] == submitted) close()
    }
    val original = opening
    val stale = original != null && client.current(address)?.revision != original.revision
    val enabled = original != null && client.editable(address) && client.fresh[address] == true && !busy && !stale
    val normalized = runCatching { normalizeAgentName(name) }.getOrNull()
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        containerColor = p.surface,
        shape = Shapes.bubble,
        title = { Text("Rename agent", style = Type.heading, color = p.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !model.connected(agent.host) -> Text("Connect to ${agent.host} to change this shared name.", style = Type.small, color = p.textMuted)
                    !agent.supportsNames -> Text("Update the host to enable shared agent names.", style = Type.small, color = p.textMuted)
                    !agent.canEditName -> Text("Only the host owner can change this shared name.", style = Type.small, color = p.textMuted)
                }
                if (original != null) {
                    FormField(name, { name = it }, label = "Display name", placeholder = agent.agent, enabled = enabled)
                    Text("Shared across apps connected to ${agent.host}.", style = Type.small, color = p.textMuted)
                    Text("@${agent.agent} remains its handle.", style = Type.code, color = p.textFaint)
                    if (name.isNotEmpty()) agentNameProblem(name)?.let { Text(it, style = Type.small, color = p.textMuted) }
                } else if (busy) Text("Loading the current name…", style = Type.small, color = p.textMuted)
                if (stale) Text("The name changed. Close and reopen Rename to load its current name. Your draft is still here.", style = Type.small, color = p.textMuted)
                client.errors[address]?.let { Text(it, style = Type.small, color = p.textMuted) }
                if (original == null && !busy) ActionButton("Load name again", enabled = client.editable(address), onClick = { client.load(address) })
                if (busy && submitted != null) Text("Saving the shared name…", style = Type.small, color = p.textMuted)
            }
        },
        confirmButton = { ActionButton("Save", enabled = enabled && normalized != null && normalized != original.displayName) {
            original?.let { submitted = client.save(address, it, name) }
        } },
        dismissButton = {
            Row {
                if (original?.displayName != null) ActionButton("Use original name", enabled = enabled) { submitted = client.save(address, original, null) }
                ActionButton("Cancel", enabled = !busy, onClick = close)
            }
        },
    )
}
