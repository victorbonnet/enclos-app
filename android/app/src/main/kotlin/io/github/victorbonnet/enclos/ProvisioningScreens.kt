package io.github.victorbonnet.enclos

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.Gap
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.Hairline
import io.github.victorbonnet.enclos.ui.IconButton
import io.github.victorbonnet.enclos.ui.PrimaryButton
import io.github.victorbonnet.enclos.ui.Shapes
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.WorkingWave
import io.github.victorbonnet.enclos.ui.palette

/** Creation is scoped to one paired host; its bridge chooses the supported backend. */
@Composable
fun CreateSandboxScreen(model: AppModel, host: String, back: () -> Unit, created: (String) -> Unit) {
    val p = palette
    var name by rememberSaveable(host) { mutableStateOf("") }
    var template by rememberSaveable(host) { mutableStateOf("minimal") }
    var submitted by rememberSaveable(host) { mutableStateOf<String?>(null) }
    val operation = model.provisioning[host]
    val busy = operation?.busy == true
    val error = name.takeIf { it.isNotEmpty() }?.let { sandboxNameError(it.trim()) }
    val duplicate = model.sandboxesOf(host).any { it.name == name.trim() }
    LaunchedEffect(operation?.request, operation?.done) {
        if (operation?.done == true && operation.request == submitted) {
            created("$host/${operation.sandbox}")
            model.clearProvisioning(host)
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("Create sandbox", style = Type.heading.copy(fontSize = 18.sp), color = p.text)
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("On $host", style = Type.name, color = p.text)
            Gap(8.dp)
            Text("Create a sandbox with its own home and policy, then install a runtime in it.", style = Type.small, color = p.textMuted)
            Gap(24.dp)
            Text("Sandbox name", style = Type.label, color = p.text)
            Gap(8.dp)
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                enabled = !busy,
                singleLine = true,
                textStyle = Type.code.copy(color = p.text),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Sandbox name" },
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().height(52.dp).border(1.dp, p.lineStrong, Shapes.control).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                        if (name.isEmpty()) Text("my-sandbox", style = Type.code, color = p.textFaint)
                        field()
                    }
                },
            )
            Gap(8.dp)
            Text(error ?: if (duplicate) "A sandbox with this name already exists." else "2–41 characters: lowercase letters, numbers and hyphens; start with a letter.", style = Type.small, color = p.textMuted)
            Gap(24.dp)
            Text("Starting policy", style = Type.label, color = p.text)
            ProvisioningChoice("Minimal", "Only listed destinations and the runtime's hosts.", template == "minimal", !busy) { template = "minimal" }
            ProvisioningChoice("Assistant", "Allows the public web.", template == "assistant", !busy) { template = "assistant" }
            Gap(16.dp)
            if (!model.canManageSandboxes(host)) {
                Text(if (model.connected(host)) "This host does not offer sandbox creation to this device." else "Connect to $host to create a sandbox.", style = Type.small, color = p.textMuted)
                Gap(12.dp)
            }
            operation?.let { ProvisioningStatus(it) }
            Gap(16.dp)
            PrimaryButton("Create sandbox", enabled = model.canManageSandboxes(host) && !busy && name.isNotBlank() && error == null && !duplicate) {
                if (model.createSandbox(host, name.trim(), template)) submitted = model.provisioning[host]?.request
            }
        }
    }
}

/** Available runtimes come from this host, including whether this version supports installation. */
@Composable
internal fun RuntimeInstallation(model: AppModel, host: String, sandbox: String) {
    val p = palette
    val key = "$host/$sandbox"
    val operation = model.provisioning[key]
    val options = model.runtimes[host].orEmpty()
    var selected by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val runtime = options.firstOrNull { it.runtime == selected } ?: options.firstOrNull()
    val busy = operation?.busy == true
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Install a runtime", style = Type.name, color = p.text)
        Gap(8.dp)
        Text("The runtime's installer runs inside this sandbox. Installation can take several minutes. Login and channel setup continue on the host after installation.", style = Type.small, color = p.textMuted)
        if (model.canManageSandboxes(host)) {
            options.forEach { option ->
                ProvisioningChoice(option.name, option.description, option.runtime == runtime?.runtime, !busy) { selected = option.runtime }
            }
            if (options.isEmpty()) {
                Gap(12.dp)
                Text("No runtime is available from this host.", style = Type.small, color = p.textMuted)
            }
        } else {
            Gap(12.dp)
            Text(if (model.connected(host)) "This host does not offer runtime installation to this device." else "Connect to $host to install a runtime.", style = Type.small, color = p.textMuted)
        }
        operation?.let { ProvisioningStatus(it) }
        Gap(16.dp)
        PrimaryButton("Install ${runtime?.name ?: "runtime"}", enabled = model.canManageSandboxes(host) && runtime != null && !busy) {
            runtime?.let { model.installRuntime(host, sandbox, it.runtime) }
        }
        Gap(16.dp)
    }
}

@Composable
internal fun ProvisioningStatus(operation: SandboxOperation) {
    val p = palette
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (operation.busy) WorkingWave()
            Text(operation.error ?: operation.message, style = Type.small, color = if (operation.busy) p.accent else p.textMuted)
        }
        operation.login?.let { login ->
            Text("Continue login and setup on the host:", style = Type.small, color = p.textMuted)
            SelectionContainer { Text(login, style = Type.code, color = p.text) }
        }
    }
}

@Composable
private fun ProvisioningChoice(name: String, description: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    val p = palette
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = choose).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.textFaint))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.label, color = p.text)
            Text(description, style = Type.small, color = p.textMuted)
        }
    }
}
