package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*
import org.json.JSONObject

data class PolicyValue(val value: String, val source: String, val locked: Boolean)
data class PolicyField(val key: String, val label: String, val multiple: Boolean, val editable: Boolean, val choices: List<String>, val values: List<PolicyValue>)
data class SandboxPolicy(val address: String, val version: Int, val base: String, val editable: Boolean, val reason: String?, val fields: List<PolicyField>) {
    val accepted get() = fields.sumOf { f -> f.values.count { it.source == "Accepted access" } }
    companion object {
        fun from(host: String, m: JSONObject): SandboxPolicy {
            val fields = m.getJSONArray("fields")
            return SandboxPolicy("$host/${m.getString("sandbox")}", m.getInt("version"), m.getString("base"), m.getBoolean("editable"), m.optString("reason").takeUnless { it.isBlank() || it == "null" }, (0 until fields.length()).map { i ->
                val f = fields.getJSONObject(i); val values = f.getJSONArray("values"); val choices = f.getJSONArray("choices")
                PolicyField(f.getString("key"), f.getString("label"), f.getBoolean("multiple"), f.getBoolean("editable"), (0 until choices.length()).map(choices::getString), (0 until values.length()).map { j ->
                    val v = values.getJSONObject(j); PolicyValue(v.getString("value"), v.getString("source"), v.getBoolean("locked"))
                })
            })
        }
    }
}
data class PolicyEdit(val field: String, val before: String?, val after: String?) {
    fun json() = JSONObject().put("field", field).put("before", before ?: JSONObject.NULL).put("after", after ?: JSONObject.NULL)
}
data class PolicyReview(val base: String, val revision: String, val edit: PolicyEdit, val changes: List<String>, val restart: Boolean) {
    companion object {
        fun from(m: JSONObject): PolicyReview {
            val c = m.getJSONObject("change"); val changes = m.getJSONArray("changes")
            return PolicyReview(m.getString("base"), m.getString("revision"), PolicyEdit(c.getString("field"), c.optString("before").takeUnless { c.isNull("before") }, c.optString("after").takeUnless { c.isNull("after") }), (0 until changes.length()).map(changes::getString), m.getBoolean("restart"))
        }
    }
}

@Composable
fun SandboxPolicySummary(model: AppModel, address: String, open: () -> Unit) {
    val live = model.connected(address.substringBefore('/'))
    LaunchedEffect(address, live) { if (live) model.loadPolicy(address) }
    val policy = model.sandboxPolicies[address]
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Manage sandbox policy", onClick = open).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SandboxMark(live, side = 32.dp)
        Column(Modifier.weight(1f)) {
            Text("Policy", style = Type.name, color = palette.text)
            Text(policy?.let { "${it.accepted} accepted access ${if (it.accepted == 1) "entry" else "entries"} · active policy v${it.version}" } ?: "View access, remove grants and edit defaults", style = Type.small, color = palette.textMuted)
        }
        Glyph(Glyphs.chevron, palette.textFaint, size = 16.dp)
    }
}

private fun policyValueLabel(field: String, value: String): String = when {
    field == "mode" && value == "public" -> "Public web and listed destinations"
    field == "mode" -> "Listed destinations only"
    field == "listen" && value == "true" -> "Allowed"
    field == "listen" -> "Blocked"
    else -> value
}

private data class EditingPolicy(val field: PolicyField, val value: PolicyValue?)

@Composable
fun SandboxPolicyScreen(model: AppModel, address: String, back: () -> Unit) {
    val p = palette
    val live = model.connected(address.substringBefore('/'))
    LaunchedEffect(address, live) { if (live) model.loadPolicy(address) }
    val policy = model.sandboxPolicies[address]
    val busy = address in model.policyBusy
    val allowed = live && policy?.editable == true && model.policyFresh[address] == true && !busy
    var editing by remember(address) { mutableStateOf<EditingPolicy?>(null) }
    var value by remember { mutableStateOf("") }
    val expanded = remember(address) { mutableStateMapOf<String, Boolean>() }
    val review = model.policyReviews[address]
    LaunchedEffect(review) { if (review != null) editing = null }
    DisposableEffect(address) { onDispose { model.policyReviews.remove(address) } }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("${address.substringAfter('/')} policy", style = Type.name, color = p.text, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.loadPolicy(address) }, enabled = live && !busy) { Text("Refresh") }
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Access applies to every agent in this sandbox. Editing defaults changes this sandbox only.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            if (!live) Text("Connect to ${address.substringBefore('/')} to load or change its policy.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp))
            if (busy) Text("Waiting for the host…", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            model.policyErrors[address]?.let { Text(it, style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp)) }
            policy?.reason?.let { Text(it, style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp)) }
            policy?.fields?.forEach { field ->
                val shown = expanded[field.key] ?: (field.key in listOf("allow", "private", "write", "mode", "listen"))
                FoldHeading(field.label, shown, if (shown) 0 else field.values.size, true) { expanded[field.key] = !shown }
                if (shown) {
                    field.values.forEach { entry ->
                        val canEdit = allowed && field.editable && !entry.locked
                        Row(Modifier.fillMaxWidth().clickable(enabled = canEdit, onClickLabel = "Edit ${field.label}") { editing = EditingPolicy(field, entry); value = entry.value }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(policyValueLabel(field.key, entry.value), style = Type.name, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (entry.locked && field.key == "read_deny") "Protected default · cannot be removed" else entry.source, style = Type.small, color = p.textMuted)
                            }
                            if (field.editable && !entry.locked) Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                        }
                    }
                    if (field.values.isEmpty()) Text("None", style = Type.small, color = p.textFaint, modifier = Modifier.padding(horizontal = 16.dp))
                    if (field.editable && (field.multiple || field.values.isEmpty())) TextButton(onClick = { editing = EditingPolicy(field, null); value = "" }, enabled = allowed, modifier = Modifier.padding(start = 8.dp)) { Text(if (field.multiple) "Add entry" else "Set limit") }
                }
            }
        }
    }
    editing?.let { edit ->
        AlertDialog(
            onDismissRequest = { if (!busy) editing = null },
            title = { Text(edit.field.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (edit.field.choices.isEmpty()) OutlinedTextField(value, { value = it }, label = { Text(if (edit.field.multiple) "Path or destination" else "Value") }, singleLine = true)
                    else edit.field.choices.forEach { choice -> TextButton(onClick = { value = choice }) { Text((if (value == choice) "✓ " else "") + policyValueLabel(edit.field.key, choice)) } }
                    Text("The host will show exactly what changes before you save.", style = Type.small)
                    model.policyErrors[address]?.let { Text(it, style = Type.small) }
                }
            },
            confirmButton = { TextButton(enabled = allowed && value.isNotBlank() && value != edit.value?.value, onClick = { model.previewPolicy(address, PolicyEdit(edit.field.key, edit.value?.value, value.trim())) }) { Text("Review change") } },
            dismissButton = {
                Row {
                    if (edit.field.multiple && edit.value != null) TextButton(enabled = allowed, onClick = { model.previewPolicy(address, PolicyEdit(edit.field.key, edit.value.value, null)) }) { Text("Remove") }
                    TextButton(enabled = !busy, onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
    if (review != null) AlertDialog(
        onDismissRequest = { if (!busy) model.policyReviews.remove(address) },
        title = { Text("Review policy change") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                review.changes.forEach { Text(it, style = Type.small) }
                if (review.restart) Text("All running work in this sandbox will stop. Its services restart under the updated policy.", style = Type.small)
                Text("Every agent will learn the active policy after it is saved.", style = Type.small)
            }
        },
        confirmButton = { TextButton(enabled = allowed && review.base == policy.base, onClick = { model.applyPolicy(address, review) }) { Text(if (busy) "Saving…" else if (review.restart) "Save & restart" else "Save policy") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { model.policyReviews.remove(address) }) { Text("Cancel") } },
    )
}
