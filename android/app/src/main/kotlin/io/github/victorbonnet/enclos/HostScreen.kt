package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.ConnectionDot
import io.github.victorbonnet.enclos.ui.Gap
import io.github.victorbonnet.enclos.ui.Glyph
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.Hairline
import io.github.victorbonnet.enclos.ui.IconButton
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.UsageWindowTabs
import io.github.victorbonnet.enclos.ui.WorkingWave
import io.github.victorbonnet.enclos.ui.palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Host details, its agents' usage in your conversations, and its owner's audit, fetched only while this page is visible. */
@Composable
fun HostScreen(model: AppModel, name: String, back: () -> Unit, openSandbox: (String) -> Unit) {
    val p = palette
    val host = model.paired.firstOrNull { it.name == name }
    val live = model.connected(name)
    val available = model.canReadAudit(name)
    val audit = model.audit[name] ?: HostAudit()
    LaunchedEffect(name, available) { if (available) model.loadAudit(name) }
    val usageAvailable = model.canReadUsage(name)
    val usage = model.hostUsage[name] ?: HostUsage()
    LaunchedEffect(name, usageAvailable) { if (usageAvailable) model.loadUsage(name, usage.window) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text(name, style = Type.heading.copy(fontSize = 18.sp), color = p.text)
        }
        Hairline()
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConnectionDot(live)
                        Text(model.links[name].shown(), style = Type.small, color = p.textMuted)
                    }
                    host?.let { Text(it.url.removePrefix("https://"), style = Type.code, color = p.textFaint) }
                    if (!live && host != null) AuditAction("Reconnect") { model.reconnect(name) }
                    Text("Sandboxes", style = Type.name, color = p.text)
                }
            }
            items(model.agents.filter { it.host == name }.distinctBy { it.sandbox }, key = { "sandbox:${it.sandbox}" }) { sandbox ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { openSandbox("$name/${sandbox.sandbox}") }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sandbox.sandbox, style = Type.name, color = p.text)
                        Text(sandbox.runtime, style = Type.small, color = p.textMuted)
                    }
                    Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Usage", style = Type.name, color = p.text)
                        if (usageAvailable && usage.request == null) AuditAction("Refresh") { model.loadUsage(name, usage.window) }
                    }
                    Text("Tokens each agent used in your conversations, as its runtime counted them.", style = Type.small, color = p.textMuted)
                    UsageWindowTabs(usage.window, enabled = usageAvailable && usage.request == null) { model.loadUsage(name, it) }
                    when {
                        !live -> Text(if (usage.loaded) "Saved in memory. Reconnect to refresh." else "Connect to this host to see its usage.", style = Type.small, color = p.textMuted)
                        !usageAvailable -> Text("Usage is unavailable. Update the host's enclos.", style = Type.small, color = p.textMuted)
                    }
                    usage.error?.let { Text(it, style = Type.small, color = p.textMuted) }
                    if (usage.request != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            WorkingWave()
                            Text("Loading usage…", style = Type.small, color = p.textMuted)
                        }
                    }
                    if (usage.loaded && usage.agents.isEmpty()) Text("No usage in the last ${usage.window.label}.", style = Type.small, color = p.textMuted)
                }
            }
            items(usage.agents, key = { "usage:${it.agent}" }) { row -> AgentUsageRow(model.agentLabel("$name/${row.agent}"), row.agent, row.total) }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Audit", style = Type.name, color = p.text)
                        if (available && audit.request == null) AuditAction("Refresh") { model.loadAudit(name) }
                    }
                    Text("State-changing commands on this host, newest first.", style = Type.small, color = p.textMuted)
                    when {
                        !live -> Text(if (audit.loaded) "Saved in memory. Reconnect to refresh." else "Connect to this host to see its audit.", style = Type.small, color = p.textMuted)
                        !available -> Text("Audit viewing is unavailable. Update the host and connect as its owner.", style = Type.small, color = p.textMuted)
                    }
                    audit.error?.let { Text(it, style = Type.small, color = p.textMuted) }
                    if (audit.loaded && audit.entries.isEmpty()) Text("No audit entries yet.", style = Type.small, color = p.textMuted)
                }
            }
            items(audit.entries, key = { "audit:${it.seq}" }) { entry -> AuditRow(entry) }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (audit.request != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            WorkingWave()
                            Text("Loading audit…", style = Type.small, color = p.textMuted)
                        }
                    } else if (available && audit.nextBefore != null) {
                        AuditAction("Load older entries") { model.loadAudit(name, older = true) }
                    }
                    Gap(16.dp)
                }
            }
        }
    }
}

/** One agent's usage over the window: its name, its tokens, and how they split. */
@Composable
private fun AgentUsageRow(label: String, address: String, total: UsageTotal) {
    val p = palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.name, color = p.text)
                if (label != address) Text(address, style = Type.code, color = p.textFaint)
            }
            Text("${tokensText(total.tokens.total)} tokens", style = Type.label, color = p.text)
        }
        Text(usageLine(total), style = Type.small, color = p.textMuted)
    }
}

@Composable
private fun AuditAction(text: String, click: () -> Unit) {
    Text(text, style = Type.label, color = palette.accent,
        modifier = Modifier.clickable(role = Role.Button, onClick = click).padding(vertical = 12.dp))
}

@Composable
private fun AuditRow(entry: HostAuditEntry) {
    val p = palette
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOfNotNull(entry.command, entry.target).joinToString(" "), style = Type.code, color = p.text)
            Text("${auditTime(entry.time)} · ${entry.user} · ${entry.host}", style = Type.small, color = p.textMuted)
            val result = when (entry.result) {
                "ok" -> "Succeeded"
                "failed" -> "Failed"
                else -> "No outcome recorded"
            }
            Text(result, style = Type.label, color = if (entry.result == "ok") p.accent else p.text)
            entry.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small, color = p.textMuted) }
            entry.finished?.let { Text("Finished ${auditTime(it)}", style = Type.small, color = p.textFaint) }
        }
    }
    Hairline()
}

private fun auditTime(time: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(time))
