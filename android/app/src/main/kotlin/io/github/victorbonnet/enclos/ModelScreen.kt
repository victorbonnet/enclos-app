package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/**
 * An agent's model (enclos `docs/protocol.md`, An agent's model): its
 * runtime's model providers, those it cannot use yet disabled with what to
 * do on the host, then the picked model provider's models, and Save.
 * [hostConnected] tells an unreachable host from a runtime not offering
 * its model now.
 */
@Composable
fun ModelScreen(client: ModelClient, address: String, hostConnected: Boolean, back: () -> Unit) {
    val p = palette
    val live = client.available(address)
    val options = client.options[address]
    val busy = address in client.pending
    var provider by rememberSaveable(address) { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable(address) { mutableStateOf<String?>(null) }
    var typed by rememberSaveable(address) { mutableStateOf("") }
    var confirming by rememberSaveable(address) { mutableStateOf(false) }
    // The saves this page has seen confirmed: kept across rotation and the
    // app lock, so that only a new one clears what was picked.
    var seenSaves by rememberSaveable(address) { mutableIntStateOf(client.confirmed[address] ?: 0) }
    LaunchedEffect(address, live) { if (live) client.refresh(address) }
    // The picked model provider stays only while the runtime lists it as
    // usable; the agent's own starts picked when it is.
    LaunchedEffect(options?.current, options?.providers) {
        val providers = options?.providers ?: return@LaunchedEffect
        if (provider != null && providers.none { it.id == provider && it.usable }) { provider = null; chosen = null; typed = "" }
        if (provider == null) provider = options.current?.provider?.takeIf { current -> providers.any { it.id == current && it.usable } }
    }
    // A confirmed save: what was picked or typed is the model now.
    val saves = client.confirmed[address] ?: 0
    LaunchedEffect(saves) { if (saves != seenSaves) { seenSaves = saves; chosen = null; typed = "" } }
    // The picked model provider's models, loaded again once nothing else is
    // pending or the host is back, and after a refresh dropped them; after
    // a failure, only when the person asks (the row below, or Refresh), so
    // that a lasting one does not loop.
    val listError = provider?.let { client.modelErrors[client.modelsKey(address, it)] }
    LaunchedEffect(address, provider, options?.revision, busy, live, listError) {
        val picked = provider ?: return@LaunchedEffect
        if (live && !busy && listError == null && options?.providers?.any { it.id == picked } == true && client.models[client.modelsKey(address, picked)] == null) client.load(address, picked)
    }
    val target = (typed.trim().takeIf { it.isNotEmpty() } ?: chosen)
    val canSave = provider != null && target != null && client.canSet(address) &&
        options?.providers?.any { it.id == provider && it.usable } == true &&
        options.current != ModelSelection(provider, target)
    // The dialog asks about this save only while it can be sent.
    LaunchedEffect(canSave) { if (!canSave) confirming = false }
    val save: () -> Unit = {
        val picked = provider
        // Not sent: say so, unless the client already said why.
        if ((picked == null || target == null || client.set(address, picked, target) == null) && client.errors[address] == null) {
            client.errors[address] = "Not sent: the page changed meanwhile. Check it and save again."
        }
    }
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
            if (!hostConnected) Text("Connect to this agent's host to read or change its model.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            else if (!live) Text("This agent's runtime does not offer its model right now. Its channel may be reconnecting.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            else if (options != null && !options.fresh && !busy) Text("This is out of date. Refresh before changing the model.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            if (busy) Text(when (client.pending[address]?.action) { "set" -> "Saving…"; "models" -> "Loading models…"; else -> "Loading…" }, style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            client.errors[address]?.let { Text(it, style = Type.small, color = p.text, modifier = Modifier.padding(16.dp)) }
            client.notices[address]?.let { Text(it, style = Type.small, color = p.accent, modifier = Modifier.padding(16.dp)) }
            if (options != null && provider == null) {
                val current = options.current?.provider
                val why = when {
                    current != null && options.providers.none { it.id == current } -> "Its model provider, $current, is not among those the runtime lists. Choose one below."
                    current != null -> "Its model provider, $current, cannot be used yet. Choose one below, or set it up on the host."
                    else -> "Its settings name no model provider. Choose one below."
                }
                Text(why, style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (options != null) {
                Section("Model provider") {
                    options.providers.forEach { row ->
                        val selected = row.id == provider
                        SettingRow(Modifier.selectable(selected = selected, enabled = row.usable, role = Role.RadioButton) { if (!selected) { provider = row.id; chosen = null; typed = "" } }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.label, style = Type.name, color = if (row.usable) p.text else p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!row.usable) Text(row.setup ?: "Not set up for this agent on the host.", style = Type.small, color = p.textMuted)
                                }
                                if (selected) Glyph(Glyphs.check, p.accent, size = 18.dp)
                            }
                        }
                    }
                    if (options.providers.isEmpty()) SettingRow { Text("The runtime lists no model provider.", style = Type.small, color = p.textMuted) }
                }
                provider?.let { picked ->
                    val listed = client.models[client.modelsKey(address, picked)]
                    Section("Model") {
                        listed?.models?.forEach { choice ->
                            val selected = typed.isBlank() && (choice.id == chosen || (chosen == null && options.current == ModelSelection(picked, choice.id)))
                            SettingRow(Modifier.selectable(selected = selected, role = Role.RadioButton) { chosen = choice.id; typed = "" }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(choice.label, style = Type.label, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (choice.label != choice.id) Text(choice.id, style = Type.code, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (selected) Glyph(Glyphs.check, p.accent, size = 18.dp)
                                }
                            }
                        }
                        if (listed == null && !busy) {
                            when {
                                listError != null && listError.retry -> SettingRow(Modifier.clickable(enabled = live, onClickLabel = "Load its models again") { client.load(address, picked) }) {
                                    Text("${listError.text} Tap to try again.", style = Type.small, color = p.textMuted)
                                }
                                listError != null -> SettingRow { Text(listError.text, style = Type.small, color = p.textMuted) }
                                !live -> SettingRow { Text("Its models load once the host is connected.", style = Type.small, color = p.textMuted) }
                                else -> SettingRow(Modifier.clickable(onClickLabel = "Load its models") { client.load(address, picked) }) {
                                    Text("Load its models", style = Type.small, color = p.accent)
                                }
                            }
                        }
                        if (listed != null && listed.models.isEmpty()) SettingRow { Text("The runtime lists no model for this model provider.", style = Type.small, color = p.textMuted) }
                        if (listed?.limited == true) SettingRow { Text("This model provider offers more models than are listed.", style = Type.small, color = p.textMuted) }
                        if (options.customModels) {
                            FormField(typed, { typed = it }, enabled = live, label = "Another model ID", singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                if (options.restarts) Text("Saving restarts this sandbox's runtime, and every agent it serves with it.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
                ActionButton("Save", enabled = canSave, modifier = Modifier.padding(start = 8.dp), onClick = {
                    if (options.restarts) confirming = true else save()
                })
            }
        }
    }
    if (confirming) ConfirmDialog("Restart the sandbox's agents?", "This runtime applies a new model only once it restarts, and every agent it serves restarts with it.", "Save and restart", onDismiss = { confirming = false }) {
        confirming = false
        save()
    }
}
