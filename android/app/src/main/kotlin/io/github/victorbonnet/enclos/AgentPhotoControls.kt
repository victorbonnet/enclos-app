package io.github.victorbonnet.enclos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.github.victorbonnet.enclos.ui.AgentInitial
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.palette

/** Tapping the avatar grants access only to the photo selected in the system picker. */
@Composable
fun EditableAgentPhoto(model: AppModel, agent: Agent, working: Boolean) {
    val images = model.agentImages
    val allowed = agent.canEditAvatar && model.connected(agent.host) && images.busy[agent.photoKey] == null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) images.setPhoto(agent.photoKey, uri)
    }
    Box(Modifier.clickable(enabled = allowed, role = Role.Button, onClickLabel = "Change ${agent.label}'s photo") {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }.semantics { contentDescription = "${agent.label}'s photo" }) {
        AgentInitial(agent.label, working, side = 64.dp, photo = agent.photoKey)
    }
}

@Composable
fun AgentPhotoControls(model: AppModel, agent: Agent) {
    val images = model.agentImages
    val busy = images.busy[agent.photoKey]
    val allowed = agent.canEditAvatar && model.connected(agent.host) && busy == null
    var removing by remember(agent.address) { mutableStateOf(false) }
    LaunchedEffect(agent.address, model.connected(agent.host)) {
        if (model.connected(agent.host)) images.refresh(agent.photoKey)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (images.hasPhoto(agent.photoKey)) TextButton(enabled = allowed, onClick = { removing = true }) { Text("Remove photo") }
        Text(busy ?: "Shared across all apps connected to this host.", style = Type.small, color = palette.textMuted)
        images.errors[agent.photoKey]?.let { Text(it, style = Type.small, color = palette.textMuted) }
        val hint = when {
            !model.connected(agent.host) -> "Connect to the host to update this photo."
            !agent.supportsAvatars -> "Update the host to enable shared agent photos."
            !agent.canEditAvatar -> "Only the host owner can change this shared photo."
            else -> null
        }
        hint?.let { Text(it, style = Type.small, color = palette.textFaint) }
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("Remove ${agent.label}'s photo?") },
        text = { Text("Connected apps will show the coloured initial again.") },
        confirmButton = { TextButton(onClick = { removing = false; images.removePhoto(agent.photoKey) }, enabled = allowed) { Text("Remove photo") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
    )
}
