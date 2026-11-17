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
import io.github.victorbonnet.enclos.ui.SeriesDot
import io.github.victorbonnet.enclos.ui.UsageChart
import io.github.victorbonnet.enclos.ui.UsageWindowTabs
import io.github.victorbonnet.enclos.ui.seriesColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.remember
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
    LaunchedEffect(name, live) { if (live) model.forgetting.refresh(name) }
    val available = model.canReadAudit(name)
    val audit = model.audit[name] ?: HostAudit()
    LaunchedEffect(name, available) { if (available) model.loadAudit(name) }
    val usageAvailable = model.canReadUsage(name)
    val usage = model.hostUsage[name] ?: HostUsage()
    LaunchedEffect(name, usageAvailable) { if (usageAvailable) model.loadUsage(name, usage.window) }
    // Each charted agent's color, by its place among the host's agents: it keeps it across windows.
    // Only series with a token counted are drawn, and only those get a color.
    val charted = usage.series.filter { s -> s.points.any { (it.tokens ?: 0) > 0 } }.map { it.agent }
    val slots = usageSlots(model.agents.filter { it.host == name }.map { it.name }, charted)
    val colors: Map<String, Color> = slots.mapValues { (_, slot) -> seriesColor(slot) }
    val removed = model.forgetting.offered(name)
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
            items(removed, key = { "removed:${it.name}" }) { agent ->
                Column {
                    Text(agent.name, style = Type.code, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp))
                    ForgetAgentSection(model, agent, agent.name.substringAfterLast('/'))
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Usage", style = Type.name, color = p.text)
                        if (usageAvailable && usage.request == null) AuditAction("Refresh") { model.loadUsage(name, usage.window) }
                    }
                    Text("Tokens each agent used in your conversations, as its runtime counted them.", style = Type.small, color = p.textMuted)
                    UsageWindowTabs(usage.selected, enabled = usageAvailable && usage.request == null) { model.loadUsage(name, it) }
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
                    if (usage.rowsShown && usage.agents.isEmpty()) Text("No usage in the last ${usage.window.label}.", style = Type.small, color = p.textMuted)
                    if (usage.rowsShown && usage.agents.isNotEmpty()) {
                        Text(
                            if (usage.outside) "All of each agent's calls: your conversations in the app, its routines and its other chats."
                            else "Your conversations in the app.",
                            style = Type.small,
                            color = p.textMuted,
                        )
                    }
                    // Drawn from when the report was asked and the offset it was asked
                    // with, so the bars stay those of the rows, however long the page is
                    // open and wherever the phone has gone since.
                    val bars = remember(usage) { if (usage.rowsShown) usageBars(usage.series, usage.window, usage.at, usage.offset) else emptyList() }
                    if (bars.any { it.tokens > 0 }) {
                        val label = { agent: String -> model.agentLabel("$name/$agent") }
                        val bucketLabel = { at: Long -> usageBucketLabel(at, usage.window, usage.offset) }
                        UsageChart(bars, colors, label, bucketLabel, summary = usageSummary(bars, usage.window, label, bucketLabel))
                        usageOffChart(usage.series, bars).takeIf { it > 0 }?.let {
                            Text("$it of the host's buckets fall outside this chart; the rows count them.", style = Type.small, color = p.textMuted)
                        }
                    }
                }
            }
            items(if (usage.rowsShown) usage.agents else emptyList(), key = { "usage:${it.agent}" }) { row -> AgentUsageRow(model.agentLabel("$name/${row.agent}"), row.agent, row.total, colors[row.agent], appOnly = usage.outside && !row.outside) }
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
private fun AgentUsageRow(label: String, address: String, total: UsageTotal, color: Color?, appOnly: Boolean) {
    val p = palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Its color in the chart, which these rows are the legend of; the same room
            // for a row without, so the names line up.
            if (color != null) SeriesDot(color) else Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.name, color = p.text)
                // Two sandboxes may have agents of one name.
                Text(address, style = Type.code, color = p.textFaint)
            }
            Text(
                if (total.tokens.counted) "${tokensText(total.tokens.total)} tokens" else if (total.calls == 1L) "1 call" else "${total.calls} calls",
                style = Type.label,
                color = p.text,
            )
        }
        Text(usageLine(total), style = Type.small, color = p.textMuted)
        // Its plugin does not report its routines and other chats: the app's alone.
        if (appOnly) Text("In the app only: its calls outside the app are not reported.", style = Type.small, color = p.textFaint)
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
