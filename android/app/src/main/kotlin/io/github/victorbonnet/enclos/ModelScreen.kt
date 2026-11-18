package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/**
 * An agent's model (enclos `docs/protocol.md`, An agent's model): its
 * runtime's providers, those it cannot use yet disabled with what to do on
 * the host, then the chosen provider's models, and Save.
 */
@Composable
fun ModelScreen(model: AppModel, address: String, back: () -> Unit) {
    val client = model.models
    val p = palette
    val live = client.available(address)
    val options = client.options[address]
    val busy = address in client.pending
    var provider by rememberSaveable(address) { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable(address) { mutableStateOf<String?>(null) }
    var typed by rememberSaveable(address) { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(address, live) { if (live) client.refresh(address) }
    // Start from the agent's model once it is known.
    LaunchedEffect(options?.current) { if (provider == null) provider = options?.current?.provider }
    LaunchedEffect(address, provider, options?.revision) {
        val picked = provider ?: return@LaunchedEffect
        if (options?.providers?.any { it.id == picked } == true && client.models[client.modelsKey(address, picked)] == null) client.load(address, picked)
    }
    val target = (typed.trim().takeIf { it.isNotEmpty() } ?: chosen)
    val canSave = provider != null && target != null && client.canSet(address) &&
        options?.providers?.any { it.id == provider && it.usable } == true &&
        options.current != ModelSelection(provider, target)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("Model", style = Type.name, color = p.text, modifier = Modifier.weight(1f))
            ActionButton("Refresh", enabled = live && !busy, onClick = { client.refresh(address) })
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(address, style = Type.code, color = p.textFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            Text("The model this agent uses for all its conversations, as its runtime on the host keeps it.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp))
            options?.current?.let { Text("Now: ${it.label}", style = Type.label, color = p.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (!live) Text("Connect to this agent's host to read or change its model.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            else if (options != null && !options.fresh && !busy) Text("This is out of date. Refresh before changing the model.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            if (busy) Text(when (client.pending[address]?.action) { "set" -> "Saving…"; "models" -> "Loading models…"; else -> "Loading…" }, style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            client.errors[address]?.let { Text(it, style = Type.small, color = p.text, modifier = Modifier.padding(16.dp)) }
            client.notices[address]?.let { Text(it, style = Type.small, color = p.accent, modifier = Modifier.padding(16.dp)) }
            if (options != null) {
                Section("Provider") {
                    options.providers.forEach { row ->
                        val selected = row.id == provider
                        SettingRow(Modifier.clickable(enabled = row.usable, onClickLabel = "Choose ${row.label}") { if (!selected) { provider = row.id; chosen = null; typed = "" } }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.label, style = Type.name, color = if (row.usable) p.text else p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!row.usable) Text(row.setup ?: "Not set up for this agent on the host.", style = Type.small, color = p.textMuted)
                                }
                                if (selected) Glyph(Glyphs.check, p.accent, size = 18.dp)
                            }
                        }
                    }
                    if (options.providers.isEmpty()) SettingRow { Text("The runtime lists no provider.", style = Type.small, color = p.textMuted) }
                }
                provider?.let { picked ->
                    val listed = client.models[client.modelsKey(address, picked)]
                    Section("Model") {
                        listed?.models?.forEach { choice ->
                            val selected = typed.isBlank() && (choice.id == chosen || (chosen == null && options.current == ModelSelection(picked, choice.id)))
                            SettingRow(Modifier.clickable(onClickLabel = "Choose ${choice.label}") { chosen = choice.id; typed = "" }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(choice.label, style = Type.label, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (choice.label != choice.id) Text(choice.id, style = Type.code, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (selected) Glyph(Glyphs.check, p.accent, size = 18.dp)
                                }
                            }
                        }
                        if (listed != null && listed.models.isEmpty()) SettingRow { Text("The runtime lists no model for this provider.", style = Type.small, color = p.textMuted) }
                        if (listed?.limited == true) SettingRow { Text("This provider offers more models than are listed.", style = Type.small, color = p.textMuted) }
                        if (options.customModels) {
                            FormField(typed, { typed = it }, enabled = live, label = "Another model ID", singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                if (options.restarts) Text("Saving restarts this sandbox's runtime once the turns running now end, and every agent it serves with it.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
                ActionButton("Save", enabled = canSave, modifier = Modifier.padding(start = 8.dp), onClick = {
                    if (options.restarts) confirming = true else client.set(address, provider!!, target!!)
                })
            }
        }
    }
    if (confirming) ConfirmDialog("Restart the sandbox's agents?", "This runtime applies a new model only once it restarts. Every agent it serves restarts after the turns running now.", "Save and restart", onDismiss = { confirming = false }) {
        confirming = false
        provider?.let { picked -> target?.let { client.set(address, picked, it) } }
    }
}
