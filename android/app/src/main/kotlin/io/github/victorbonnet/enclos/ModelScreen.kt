package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
 * do on the host, then the picked model provider's models. Save sits in
 * the top bar, and a choice not saved yet is named at the top, so that
 * picking a model is never taken for saving it.
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
    var query by rememberSaveable(address) { mutableStateOf("") }
    var filter by rememberSaveable(address) { mutableStateOf<ModelProviderFilter?>(null) }
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
    val providers = options?.providers.orEmpty()
    val providersView = providersView(providers, provider, query, filter)
    val readyCount = providersView.ready
    val needsSetupCount = providersView.total - providersView.ready
    val trimmedQuery = query.trim()
    val hasQuery = trimmedQuery.isNotEmpty()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("Model", style = Type.name, color = p.text, modifier = Modifier.weight(1f))
            ActionButton("Refresh", enabled = live && !busy, onClick = { client.refresh(address) })
            ActionButton("Save", enabled = canSave, onClick = { if (options?.restarts == true) confirming = true else save() })
        }
        Hairline()
        SearchField(
            query = query,
            onQuery = { query = it },
            label = "Search model providers and models",
            modifier = Modifier.padding(top = 12.dp),
        )
        Hairline()
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Choice(
                label = "Ready here · $readyCount",
                on = filter == ModelProviderFilter.ReadyHere,
                multiple = true,
                onClick = { filter = if (filter == ModelProviderFilter.ReadyHere) null else ModelProviderFilter.ReadyHere },
            )
            Choice(
                label = "Needs host setup · $needsSetupCount",
                on = filter == ModelProviderFilter.NeedsHostSetup,
                multiple = true,
                onClick = { filter = if (filter == ModelProviderFilter.NeedsHostSetup) null else ModelProviderFilter.NeedsHostSetup },
            )
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(address, style = Type.code, color = p.textFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            Text("The model this agent uses for all its conversations, as its runtime on the host keeps it.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp))
            options?.current?.let { Text("Now: ${it.label}", style = Type.label, color = p.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (canSave && target != null) Text("Not saved: ${ModelSelection(provider, target).label}. Tap Save to change the model.", style = Type.small, color = p.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (options?.restarts == true) Text("Saving restarts this sandbox's runtime, and every agent it serves with it.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
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
                val providerTitle = when (filter) {
                    null -> "Model provider"
                    ModelProviderFilter.ReadyHere -> "Model provider · Ready here"
                    ModelProviderFilter.NeedsHostSetup -> "Model provider · Needs host setup"
                }
                Section(providerTitle, count = providersView.count) {
                    providersView.rows.forEach { (row, kept) ->
                        val selected = row.id == provider
                        SettingRow(Modifier.selectable(selected = selected, enabled = row.usable, role = Role.RadioButton) { if (!selected) { provider = row.id; chosen = null; typed = "" } }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(highlighted(row.label, query, p.accent), style = Type.name, color = if (row.usable) p.text else p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (kept) Text("Kept: your pick.", style = Type.small, color = p.textMuted)
                                    if (!row.usable) Text(row.setup ?: "Not set up for this agent on the host.", style = Type.small, color = p.textMuted)
                                }
                                if (selected) Glyph(Glyphs.check, p.accent, size = 18.dp)
                            }
                        }
                    }
                    if (options.providers.isEmpty()) {
                        SettingRow { Text("The runtime lists no model provider.", style = Type.small, color = p.textMuted) }
                    } else if (providersView.matches.isEmpty() && providersView.narrowed) {
                        when {
                            hasQuery && filter == null -> SettingRow {
                                Text("No model provider matches \u201C$trimmedQuery\u201D.", style = Type.small, color = p.textMuted)
                            }
                            hasQuery && filter == ModelProviderFilter.ReadyHere -> SettingRow {
                                Text("No model provider matches \u201C$trimmedQuery\u201D with Ready here on.", style = Type.small, color = p.textMuted)
                                Text("$readyCount of the ${providersView.total} are ready to use here.", style = Type.small, color = p.textMuted)
                            }
                            hasQuery && filter == ModelProviderFilter.NeedsHostSetup -> SettingRow {
                                Text("No model provider matches \u201C$trimmedQuery\u201D with Needs host setup on.", style = Type.small, color = p.textMuted)
                                Text("$needsSetupCount of the ${providersView.total} need setup on the host.", style = Type.small, color = p.textMuted)
                            }
                            filter == ModelProviderFilter.ReadyHere -> SettingRow {
                                Text("No model provider is ready to use here.", style = Type.small, color = p.textMuted)
                                Text("Turn the filter off to see the $needsSetupCount that need setup on the host.", style = Type.small, color = p.textMuted)
                            }
                            filter == ModelProviderFilter.NeedsHostSetup -> SettingRow {
                                Text("No model provider needs host setup.", style = Type.small, color = p.textMuted)
                                Text("Turn the filter off to see the $readyCount that are ready to use here.", style = Type.small, color = p.textMuted)
                            }
                        }
                        if (hasQuery) {
                            val clearLabel = "Show all ${providersView.total} model providers"
                            SettingRow(Modifier.clickable(role = Role.Button, onClickLabel = clearLabel) { query = "" }) {
                                Text(clearLabel, style = Type.label, color = p.accent)
                            }
                        }
                    }
                }
                provider?.let { picked ->
                    val pickedProvider = options.providers.firstOrNull { it.id == picked }
                    val listed = client.models[client.modelsKey(address, picked)]
                    val loadingModels = client.pending[address]?.let { it.action == "models" && it.provider == picked } == true
                    val isChecked: (ModelChoice) -> Boolean = { choice ->
                        typed.isBlank() && (choice.id == chosen || (chosen == null && options.current == ModelSelection(picked, choice.id)))
                    }
                    val modelsView = listed?.let { modelsView(it.models, isChecked, query) }
                    val modelCount = when {
                        loadingModels -> "loading…"
                        listError != null || listed == null -> "not loaded"
                        else -> modelsView!!.count
                    }
                    Section("Model · ${pickedProvider?.label ?: picked}", count = modelCount) {
                        modelsView?.rows?.forEach { (choice, kept) ->
                            val selected = isChecked(choice)
                            SettingRow(Modifier.selectable(selected = selected, role = Role.RadioButton) { chosen = choice.id; typed = "" }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(highlighted(choice.label, query, p.accent), style = Type.label, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (choice.label != choice.id) Text(highlighted(choice.id, query, p.accent), style = Type.code, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (kept) Text("Kept: your pick.", style = Type.small, color = p.textMuted)
                                    }
                                    if (selected) Glyph(Glyphs.check, p.accent, size = 18.dp)
                                }
                            }
                        }
                        if (listed != null && listed.models.isNotEmpty() && modelsView != null && modelsView.matches.isEmpty() && hasQuery) {
                            SettingRow { Text("No model matches \u201C$trimmedQuery\u201D.", style = Type.small, color = p.textMuted) }
                            if (pickedProvider?.usable == true && options.customModels) {
                                val useLabel = "Use \u201C$trimmedQuery\u201D as a model ID"
                                SettingRow(Modifier.clickable(role = Role.Button, onClickLabel = useLabel) { typed = trimmedQuery }) {
                                    Text(useLabel, style = Type.label, color = p.accent)
                                }
                            }
                        }
                        if (listed == null && !busy) {
                            when {
                                listError != null && listError.tooLarge -> SettingRow {
                                    Text(listError.text, style = Type.small, color = p.textMuted)
                                    Text(
                                        if (options.customModels) "Search can only match models that load on the phone. Type the model's id below."
                                        else "Search can only match models that load on the phone. Choose another model provider, or set the model on the host.",
                                        style = Type.small,
                                        color = p.textMuted,
                                    )
                                }
                                listError != null && listError.retry -> SettingRow(Modifier.clickable(enabled = live, onClickLabel = "Load its models again") { client.load(address, picked) }) {
                                    Text("${listError.text} Tap to try again.", style = Type.small, color = p.textMuted)
                                    if (hasQuery) Text("Search matches its models once they load.", style = Type.small, color = p.textMuted)
                                }
                                listError != null -> SettingRow {
                                    Text(listError.text, style = Type.small, color = p.textMuted)
                                    if (hasQuery) Text("Search matches its models once they load.", style = Type.small, color = p.textMuted)
                                }
                                !live -> SettingRow { Text("Its models load once the host is connected.", style = Type.small, color = p.textMuted) }
                                else -> SettingRow(Modifier.clickable(onClickLabel = "Load its models") { client.load(address, picked) }) {
                                    Text("Load its models", style = Type.small, color = p.accent)
                                }
                            }
                        }
                        if (listed != null && listed.models.isEmpty()) SettingRow { Text("The runtime lists no model for this model provider.", style = Type.small, color = p.textMuted) }
                        if (listed?.limited == true) SettingRow {
                            Text("This model provider offers more models than are listed.", style = Type.small, color = p.textMuted)
                            if (hasQuery) Text("Search matches the listed ones.", style = Type.small, color = p.textMuted)
                        }
                        if (options.customModels) {
                            FormField(typed, { typed = it }, enabled = live, label = "Another model ID", singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
    if (confirming) ConfirmDialog("Restart the sandbox's agents?", "This runtime applies a new model only once it restarts, and every agent it serves restarts with it.", "Save and restart", onDismiss = { confirming = false }) {
        confirming = false
        save()
    }
}
