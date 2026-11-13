package io.github.victorbonnet.enclos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import io.github.victorbonnet.enclos.ui.SandboxMark
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
 * view, swiped sideways between the two; and over its end, the search and
 * compose button, which starts a conversation with an agent the person picks.
 */
@Composable
fun HomeShell(
    model: AppModel,
    views: PagerState,
    onView: (View) -> Unit,
    openChat: (String) -> Unit,
    openAgent: (Agent) -> Unit,
    openSandbox: (String) -> Unit,
    openApproval: (String) -> Unit,
    start: (Agent) -> Unit,
    settings: () -> Unit,
    compose: () -> Unit,
    pair: () -> Unit,
) {
    val p = palette
    // The view settled on: the top bar's actions and the search's label
    // change once a swipe ends, not half way through it.
    val view = View.entries[views.settledPage]
    var query by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The switch follows the finger: the view a swipe would settle on.
            ViewSwitch(View.entries[views.targetPage], onView)
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
            HorizontalPager(state = views, modifier = Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize().background(p.bg)) {
                    when (View.entries[page]) {
                        View.Conversations -> InboxScreen(model, query, unreadOnly, openChat, start, openAgent, pair, openApproval)
                        View.Agents -> AgentsScreen(model, query, openAgent, openSandbox, pair)
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
                Info("Can", if (does.isEmpty()) "known once its host reaches it" else does.joinToString(", "))
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

// ---------- A sandbox's page ----------

/**
 * A sandbox's page (`host/sandbox`): its mark (in `accent` while its host
 * is connected), name, runtime and host; its agents, as the agents view
 * shows them, each opening its page; its groups, each opening; what runs
 * it; and where the rest (its state, policy and sessions) shows, on the
 * host. Read only: the bridge sends no more about a sandbox.
 */
@Composable
fun SandboxScreen(model: AppModel, address: String, back: () -> Unit, openAgent: (Agent) -> Unit, openChat: (String) -> Unit) {
    val p = palette
    val host = address.substringBefore('/')
    val sandbox = address.substringAfter('/')
    val agents = model.agents.filter { it.host == host && it.sandbox == sandbox }
    val now = remember { System.currentTimeMillis() / 1000 }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text(sandbox, style = Type.heading.copy(fontSize = 18.sp, lineHeight = 22.sp), color = p.text, maxLines = 1)
        }
        Hairline()
        if (agents.isEmpty()) {
            Text("This sandbox is no longer listed by its host.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val live = model.connected(host)
        val runtime = agents.first().runtime.replaceFirstChar { it.uppercase() }
        val groups = model.conversations
            .filter { it.group && it.host == host && it.members.first().substringBefore('/') == sandbox }
            .sortedByDescending { model.lines[it.id]?.lastOrNull()?.time ?: 0L }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SandboxMark(live, side = 56.dp)
                Gap(14.dp)
                Text(sandbox, style = Type.heading, color = p.text)
                Text("$runtime · $host", style = Type.code, color = p.textFaint)
            }
            ListHeading("Agents · ${agents.size}")
            agents.forEach { a -> AgentListRow(model, a, now) { openAgent(a) } }
            if (groups.isNotEmpty()) {
                ListHeading("Groups · ${groups.size}")
                groups.forEach { g ->
                    val handles = g.members.map { it.substringAfterLast('/') }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Open the group") { openChat(g.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MemberStack(handles, side = 32.dp)
                        Text(g.title ?: handles.joinToString(", "), style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                    }
                }
            }
            ListHeading("Runs on")
            Info("Runtime", runtime)
            Info("Host", "$host · ${if (live) "connected" else model.status[host] ?: "not connected"}")
            Text(
                "Its state, policy and sessions show on the host: enclos sandbox show $sandbox.",
                style = Type.small,
                color = p.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}

/**
 * Choosing a group's members: a name, then every agent, ticked or not;
 * once one is ticked, only the agents of its sandbox can be.
 */
@Composable
private fun GroupPicker(
    agents: List<Agent>,
    members: androidx.compose.runtime.snapshots.SnapshotStateList<Agent>,
    name: String,
    onName: (String) -> Unit,
    modifier: Modifier,
) {
    val p = palette
    val first = members.firstOrNull()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        BasicTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            textStyle = Type.name.copy(color = p.text),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            decorationBox = { field ->
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(Shapes.control).border(1.dp, p.lineStrong, Shapes.control).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("Group name (the agents' names if none)", style = Type.name.copy(fontWeight = FontWeight.Normal), color = p.textFaint)
                    field()
                }
            },
        )
        Text(
            "Two to six agents of one sandbox. In the group, @name addresses one, @all asks everyone, and a message naming no one invites them all.",
            style = Type.small,
            color = p.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Gap(12.dp)
        Hairline()
        agents.forEach { a ->
            val ticked = a in members
            val allowed = first == null || (a.host == first.host && a.sandbox == first.sandbox)
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = ticked, enabled = allowed || ticked, role = Role.Checkbox) {
                        if (ticked) members.remove(a) else if (members.size < 6) members.add(a)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AgentInitial(a.agent, side = 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(a.agent, style = Type.name, color = if (allowed || ticked) p.text else p.textFaint)
                    Text("${a.sandbox} · ${a.host}", style = Type.code, color = p.textFaint)
                }
                Box(
                    Modifier.size(22.dp).clip(Shapes.control).border(1.5.dp, if (ticked) p.accent else p.lineStrong, Shapes.control)
                        .background(if (ticked) p.accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { if (ticked) Glyph(Glyphs.check, p.bg, size = 16.dp) }
            }
            Hairline(Modifier.padding(start = 62.dp))
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

/** In place of a group the bridge would not start: back, and why. */
@Composable
fun GroupRefused(why: String, back: () -> Unit) {
    val p = palette
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar { IconButton(Glyphs.back, "Back", back) }
        Hairline()
        Text("The group was not started: $why", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
    }
}

/**
 * What a new conversation's screen made with an earlier tap whose message
 * did not go: the group (by its members and name) or the draft (by its
 * agent), reused by the next tap, so a failed send makes no second group on
 * the host, nor another draft on the phone.
 */
private data class Made(val key: String, val id: String)

/**
 * A new conversation: the agent it is with, picked from a chip at the top
 * (the one most recently active first), its card in the middle, and the
 * field. The first message starts the conversation, which then opens. The
 * chip's list also starts a group: two to six agents of one sandbox,
 * ticked, and a name if the person gives one.
 */
@Composable
fun NewChatScreen(model: AppModel, back: () -> Unit, opened: (String) -> Unit) {
    val p = palette
    val agents = model.agents.sortedByDescending { model.lastAt(it.address) ?: 0L }
    var chosen by remember { mutableStateOf(agents.firstOrNull()) }
    var text by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    // A group: the agents ticked, of one sandbox of one host.
    var grouping by remember { mutableStateOf(false) }
    val members = remember { androidx.compose.runtime.mutableStateListOf<Agent>() }
    var name by remember { mutableStateOf("") }
    var madeGroup by remember { mutableStateOf<Made?>(null) }
    var madeDraft by remember { mutableStateOf<Made?>(null) }
    // Left without a message sent: the draft goes (a group stays, as the bridge made it).
    val draftNow by androidx.compose.runtime.rememberUpdatedState(madeDraft)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { draftNow?.let { model.dropDraft(it.id) } } }
    val canGroup = agents.groupBy { it.host to it.sandbox }.values.any { it.size >= 2 }
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
                    if (grouping) {
                        MemberStack(members.map { it.agent }.ifEmpty { listOf("?") }, side = 28.dp)
                    } else if (agent != null) {
                        AgentInitial(agent.agent, side = 28.dp)
                    }
                    Text(if (grouping) "New group" else agent?.agent ?: "No agent", style = Type.name, color = p.text)
                    // Pointing down: a list opens.
                    if (agents.size > 1) Box(Modifier.size(16.dp).rotate(90f), contentAlignment = Alignment.Center) {
                        Glyph(Glyphs.chevron, p.textMuted, size = 16.dp)
                    }
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, modifier = Modifier.background(p.bg)) {
                    if (canGroup) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Glyph(Glyphs.inbox, p.text, size = 20.dp) }
                                    Text("New group", style = Type.name, color = p.text)
                                    if (grouping) Glyph(Glyphs.check, p.accent, size = 18.dp)
                                }
                            },
                            onClick = {
                                grouping = true
                                members.clear()
                                chosen?.let { members.add(it) }
                                picking = false
                            },
                        )
                        Hairline()
                    }
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
                                grouping = false
                                picking = false
                            },
                        )
                    }
                }
            }
        }
        if (grouping) {
            GroupPicker(agents, members, name, { name = it }, Modifier.weight(1f))
        } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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
        val agent = if (grouping) members.firstOrNull() else chosen
        val live = agent != null && model.connected(agent.host)
        val enough = !grouping || members.size in 2..6
        Composer(
            text,
            { text = it },
            if (grouping) "Message the group, @ to address" else "Message ${agent?.agent ?: ""}".trim(),
            canSend = live && enough,
            notice = when {
                agent != null && !live -> "Not connected to ${agent.host}: send once it's back."
                !enough -> "Tick two to six agents of one sandbox."
                grouping && madeGroup != null -> model.refusedGroups[madeGroup?.id]?.let { "The group was not started: $it" }
                else -> null
            },
        ) {
            if (agent == null || !model.connected(agent.host)) return@Composer
            if (grouping) {
                val key = members.joinToString(",") { it.address } + "|" + name.trim()
                // The group an earlier tap made, while it stands for the same members and name.
                val id = madeGroup?.takeIf { m -> m.key == key && model.conversations.any { it.id == m.id } }?.id
                    ?: model.newGroup(members.toList(), name)?.also { madeGroup = Made(key, it) }
                    ?: return@Composer
                val group = model.conversations.firstOrNull { it.id == id } ?: return@Composer
                if (model.send(group, text.trim()) == null) opened(id)
                return@Composer
            }
            // The draft an earlier tap made, while the agent is the same; another agent's goes.
            val id = madeDraft?.takeIf { m -> m.key == agent.address && model.conversations.any { it.id == m.id } }?.id
                ?: model.newConversation(agent).also { made ->
                    madeDraft?.let { model.dropDraft(it.id) }
                    madeDraft = Made(agent.address, made)
                }
            val started = model.conversations.firstOrNull { it.id == id } ?: return@Composer
            if (model.send(started, text.trim()) == null) opened(id)
        }
    }
}
