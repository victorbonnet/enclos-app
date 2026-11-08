package io.github.victorbonnet.enclos

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.victorbonnet.enclos.ui.AgentInitial
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.Gap
import io.github.victorbonnet.enclos.ui.Glyph
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.Hairline
import io.github.victorbonnet.enclos.ui.IconButton
import io.github.victorbonnet.enclos.ui.Motion
import io.github.victorbonnet.enclos.ui.Shapes
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.palette

/** The two views of the app's first screen. */
enum class View { Conversations, Agents }

/**
 * The app's first screen: at the top, the switch between the conversations
 * and the agents, then unread only (on conversations) and Settings; the
 * view; and over its end, the search for it and the compose button, which
 * starts a conversation with an agent the person picks.
 */
@Composable
fun HomeShell(
    model: AppModel,
    view: View,
    onView: (View) -> Unit,
    openChat: (String) -> Unit,
    openAgent: (Agent) -> Unit,
    settings: () -> Unit,
    compose: () -> Unit,
    pair: () -> Unit,
) {
    val p = palette
    var query by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewSwitch(view, onView)
            Spacer(Modifier.weight(1f))
            if (view == View.Conversations && model.paired.isNotEmpty()) {
                IconButton(
                    if (unreadOnly) Glyphs.unreadOn else Glyphs.unreadOff,
                    if (unreadOnly) "Show all conversations" else "Show unread only",
                    { unreadOnly = !unreadOnly },
                    tint = if (unreadOnly) p.accent else p.textMuted,
                )
            }
            IconButton(Glyphs.settings, "Settings", settings, tint = p.textMuted)
        }
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                view,
                transitionSpec = { fadeIn(Motion.enter(Motion.MEDIUM - 50)) togetherWith fadeOut(Motion.exit(100)) },
                label = "view",
            ) { shown ->
                Box(Modifier.fillMaxSize().background(p.bg)) {
                    when (shown) {
                        View.Conversations -> InboxScreen(model, query, unreadOnly, openChat, pair)
                        View.Agents -> AgentsScreen(model, query, openAgent, pair)
                    }
                }
            }
            if (model.paired.isNotEmpty()) {
                BottomBar(
                    query,
                    { query = it },
                    if (view == View.Conversations) "Search conversations" else "Search agents",
                    compose,
                    Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * The switch between the two views: a pill with the conversations' bubble
 * and the agents' glyph, the one shown on `surface` in `text`, the other
 * in `text-muted`.
 */
@Composable
private fun ViewSwitch(view: View, onView: (View) -> Unit) {
    val p = palette
    Row(
        Modifier.clip(Shapes.pill).border(1.dp, p.line, Shapes.pill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(View.Conversations to Glyphs.inbox, View.Agents to Glyphs.agents).forEach { (option, icon) ->
            val on = option == view
            Box(
                Modifier.size(width = 52.dp, height = 40.dp).clip(Shapes.pill)
                    .background(if (on) p.surface else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onView(option) }
                    .semantics { contentDescription = if (option == View.Conversations) "Conversations" else "Agents" },
                contentAlignment = Alignment.Center,
            ) { Glyph(icon, if (on) p.text else p.textMuted, size = 22.dp) }
        }
    }
}

/**
 * Over the end of the view, on a fade to `bg`: the search, a pill on
 * `surface`, and the compose button.
 */
@Composable
private fun BottomBar(query: String, onQuery: (String) -> Unit, placeholder: String, compose: () -> Unit, modifier: Modifier) {
    val p = palette
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, p.bg, p.bg)))
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = Type.message.copy(color = p.text),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            decorationBox = { field ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(Shapes.pill).background(p.surface)
                        .border(1.dp, p.line, Shapes.pill).padding(start = 18.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Glyph(Glyphs.search, p.textMuted, size = 20.dp)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(placeholder, style = Type.message, color = p.textFaint, maxLines = 1)
                        field()
                    }
                    AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(Motion.enter(Motion.SHORT)), exit = fadeOut(Motion.exit())) {
                        IconButton(Glyphs.clear, "Clear the search", { onQuery("") }, tint = p.textMuted)
                    }
                }
            },
        )
        Box(
            Modifier.size(52.dp).clip(Shapes.pill).background(p.buttonBg)
                .clickable(onClickLabel = "New conversation", role = Role.Button, onClick = compose)
                .semantics { contentDescription = "New conversation" },
            contentAlignment = Alignment.Center,
        ) { Glyph(Glyphs.compose, p.buttonText, size = 22.dp) }
    }
}

// ---------- An agent's page ----------

/** What a capability lets the agent do, in the person's words; nothing for those the app does not show. */
private fun can(capability: String): String? = when (capability) {
    "typing" -> "says when it is working"
    "tools" -> "shows the tools it runs"
    "titles" -> "names conversations"
    "commands" -> "lists its commands"
    "attachments" -> "sends files"
    else -> null
}

/**
 * An agent's page: its initial, name and address; its conversations (Home,
 * a new one, how many); what runs it (runtime, sandbox, host and
 * connection, what it can do); and its commands. Read only: changing an
 * agent from the app comes later, for the host's own user.
 */
@Composable
fun AgentScreen(model: AppModel, address: String, back: () -> Unit, openChat: (String) -> Unit, start: (Agent) -> Unit) {
    val p = palette
    val agent = model.agents.firstOrNull { it.address == address }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text(agent?.agent ?: address, style = Type.heading.copy(fontSize = 18.sp, lineHeight = 22.sp), color = p.text, maxLines = 1)
        }
        Hairline()
        if (agent == null) {
            Text("This agent is no longer listed by its host.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val working = model.workingOn(agent).isNotEmpty()
        val conversations = model.conversations.filter { it.host == agent.host && it.agent == agent.name }
        val home = conversations.firstOrNull { it.home }
        val others = conversations.count { !it.home && model.listed(it) }
        val commands = model.commands[agent.address].orEmpty()
        var allCommands by remember { mutableStateOf(false) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AgentInitial(agent.agent, working, side = 64.dp)
                Gap(14.dp)
                Text(agent.agent, style = Type.heading, color = p.text)
                Text(agent.address, style = Type.code, color = p.textFaint)
            }
            Section("Conversations") {
                if (home != null) {
                    SettingRow(Modifier.clickable(onClickLabel = "Open Home") { openChat(home.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Home", style = Type.name, color = p.text)
                                Text("Cron jobs and the agent's notices come here.", style = Type.small, color = p.textMuted)
                            }
                            Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                        }
                    }
                }
                SettingRow(Modifier.clickable(onClickLabel = "New conversation") { start(agent) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(Glyphs.compose, p.accent, size = 20.dp)
                        Text("New conversation", style = Type.label, color = p.accent)
                    }
                }
                SettingRow {
                    Text("$others other ${if (others == 1) "conversation" else "conversations"}, in the conversations view.", style = Type.small, color = p.textMuted)
                }
            }
            Section("Runs on") {
                Info("Runtime", agent.runtime.replaceFirstChar { it.uppercase() })
                Info("Sandbox", agent.sandbox)
                Info("Host", "${agent.host} · ${if (model.connected(agent.host)) "connected" else model.status[agent.host] ?: "not connected"}")
                val does = agent.capabilities.mapNotNull(::can)
                Info("It", if (does.isEmpty()) "is known once its host reaches it" else does.joinToString(", "))
            }
            if (commands.isNotEmpty()) {
                Section("Commands · ${commands.size}") {
                    (if (allCommands) commands else commands.take(6)).forEach { c ->
                        SettingRow {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("/${c.name}", style = Type.code.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                                c.args?.let { Text(it, style = Type.code, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            if (c.description.isNotBlank()) Text(c.description, style = Type.small, color = p.textMuted)
                        }
                    }
                    if (commands.size > 6) {
                        SettingRow(Modifier.clickable { allCommands = !allCommands }) {
                            Text(if (allCommands) "Show fewer" else "Show all ${commands.size}", style = Type.label, color = p.accent)
                        }
                    }
                }
            }
            Text(
                "Changing an agent from the app comes later, for the host's own user.",
                style = Type.small,
                color = p.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}

/** A fact on an agent's page: its label, faint, then its value. */
@Composable
private fun Info(label: String, value: String) {
    val p = palette
    SettingRow {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = Type.small, color = p.textMuted, modifier = Modifier.width(80.dp))
            Text(value, style = Type.small, color = p.text, modifier = Modifier.weight(1f))
        }
    }
}

// ---------- A new conversation ----------

/**
 * A new conversation: the agent it is with, picked from a chip at the top
 * (the one most recently active first), its card in the middle, and the
 * field. The first message starts the conversation, which then opens.
 */
@Composable
fun NewChatScreen(model: AppModel, back: () -> Unit, opened: (String) -> Unit) {
    val p = palette
    val agents = model.agents.sortedByDescending { model.lastAt(it.address) ?: 0L }
    var chosen by remember { mutableStateOf(agents.firstOrNull()) }
    var text by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        AppBar {
            IconButton(Glyphs.clear, "Close", back)
            Box {
                val agent = chosen
                Row(
                    Modifier.clip(Shapes.pill).border(1.dp, p.line, Shapes.pill)
                        .clickable(onClickLabel = "Choose the agent", enabled = agents.size > 1) { picking = true }
                        .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (agent != null) AgentInitial(agent.agent, side = 28.dp)
                    Text(agent?.agent ?: "No agent", style = Type.name, color = p.text)
                    // Pointing down: a list opens.
                    if (agents.size > 1) Box(Modifier.size(16.dp).rotate(90f), contentAlignment = Alignment.Center) {
                        Glyph(Glyphs.chevron, p.textMuted, size = 16.dp)
                    }
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, modifier = Modifier.background(p.bg)) {
                    agents.forEach { a ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AgentInitial(a.agent, side = 24.dp)
                                    Column(Modifier.weight(1f, fill = false)) {
                                        Text(a.agent, style = Type.name, color = p.text)
                                        Text("${a.sandbox} · ${a.host}", style = Type.code, color = p.textFaint)
                                    }
                                    if (a == chosen) Glyph(Glyphs.check, p.accent, size = 18.dp)
                                }
                            },
                            onClick = {
                                chosen = a
                                picking = false
                            },
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val agent = chosen
            if (agent == null) {
                Text("No agent yet: pair a host, or wait for it to list its agents.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(24.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AgentInitial(agent.agent, model.workingOn(agent).isNotEmpty(), side = 64.dp)
                    Gap(14.dp)
                    Text(agent.agent, style = Type.heading, color = p.text)
                    Gap(4.dp)
                    Text("${agent.sandbox} · ${agent.runtime.replaceFirstChar { it.uppercase() }} · ${agent.host}", style = Type.small, color = p.textMuted)
                    val count = model.commands[agent.address]?.size ?: 0
                    if (count > 0) Text("$count commands: type / to see them", style = Type.small, color = p.textFaint)
                }
            }
        }
        val agent = chosen
        val live = agent != null && model.connected(agent.host)
        Composer(
            text,
            { text = it },
            "Message ${agent?.agent ?: ""}".trim(),
            canSend = live,
            notice = if (agent != null && !live) "Not connected to ${agent.host}: send once it's back." else null,
        ) {
            if (agent == null) return@Composer
            val id = model.newConversation(agent)
            val started = model.conversations.firstOrNull { it.id == id } ?: return@Composer
            if (model.send(started, text.trim())) opened(id)
        }
    }
}
