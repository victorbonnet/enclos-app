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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import io.github.victorbonnet.enclos.ui.ConversationFilters
import io.github.victorbonnet.enclos.ui.Gap
import io.github.victorbonnet.enclos.ui.Glyph
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.Hairline
import io.github.victorbonnet.enclos.ui.IconButton
import io.github.victorbonnet.enclos.ui.Motion
import io.github.victorbonnet.enclos.ui.Shapes
import io.github.victorbonnet.enclos.ui.TaskMark
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.WorkingWave
import io.github.victorbonnet.enclos.ui.UsageChart
import io.github.victorbonnet.enclos.ui.UsageMeasureTabs
import io.github.victorbonnet.enclos.ui.UsageWindowTabs
import io.github.victorbonnet.enclos.ui.seriesColor
import kotlinx.coroutines.delay
import io.github.victorbonnet.enclos.ui.palette

/** The two views of the app's first screen. */
enum class View { Conversations, Agents }

/**
 * The app's first screen: at the top, the switch between the conversations
 * and the agents, then conversation filters and Settings; the
 * view, swiped sideways between the two; and over its end, the search and
 * compose button, which starts a conversation with an agent the person picks.
 * The Inbox filters ([unreadOnly], [archivedOnly]) are kept by the caller,
 * outside the routes, so a full screen opened over this one leaves them.
 */
@Composable
fun HomeShell(
    model: AppModel,
    views: PagerState,
    unreadOnly: Boolean,
    archivedOnly: Boolean,
    onUnreadOnly: (Boolean) -> Unit,
    onArchivedOnly: (Boolean) -> Unit,
    onView: (View) -> Unit,
    openChat: (String) -> Unit,
    openAgent: (String) -> Unit,
    openRoutines: (Agent) -> Unit,
    openSandbox: (String) -> Unit,
    openApproval: (String) -> Unit,
    start: (Agent) -> Unit,
    settings: () -> Unit,
    compose: () -> Unit,
    pair: () -> Unit,
    createSandbox: (String) -> Unit,
    openHost: (String) -> Unit,
) {
    val p = palette
    // The view settled on: the top bar's actions and the search's label
    // change once a swipe ends, not half way through it.
    val view = View.entries[views.settledPage]
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The switch follows the finger: the view a swipe would settle on.
            ViewSwitch(View.entries[views.targetPage], model.attentionItems().size, onView)
            Spacer(Modifier.weight(1f))
            if (view == View.Conversations && model.paired.isNotEmpty()) {
                ConversationFilters(
                    unreadOnly = unreadOnly,
                    archivedOnly = archivedOnly,
                    onUnreadChange = onUnreadOnly,
                    onArchivedChange = onArchivedOnly,
                )
            }
            IconButton(Glyphs.settings, "Settings", settings, tint = p.textMuted)
        }
        Box(Modifier.weight(1f)) {
            HorizontalPager(state = views, modifier = Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize().background(p.bg)) {
                    when (View.entries[page]) {
                        View.Conversations -> InboxScreen(model, query, unreadOnly, archivedOnly, openChat, start, openAgent, openRoutines, pair, openApproval)
                        View.Agents -> AgentsScreen(model, query, openAgent, openSandbox, pair, createSandbox, openHost)
                    }
                }
            }
            if (model.paired.isNotEmpty()) {
                BottomBar(
                    query,
                    { query = it },
                    if (view == View.Conversations) {
                        if (archivedOnly) "Search archived conversations" else "Search conversations"
                    } else "Search sandboxes and agents",
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
private fun ViewSwitch(view: View, waiting: Int, onView: (View) -> Unit) {
    val p = palette
    Row(
        Modifier.clip(Shapes.pill).border(1.dp, p.line, Shapes.pill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(View.Conversations to Glyphs.inbox, View.Agents to Glyphs.agents).forEach { (option, icon) ->
            val on = option == view
            Box(
                Modifier.widthIn(min = 52.dp).heightIn(min = 48.dp).clip(Shapes.pill)
                    .background(if (on) p.surface else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onView(option) }
                    .semantics { contentDescription = if (option == View.Conversations) "Conversations" + if (waiting > 0) ", $waiting needs you" else "" else "Agents" },
                contentAlignment = Alignment.Center,
            ) { Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Glyph(icon, if (on) p.text else p.textMuted, size = 22.dp)
                if (option == View.Conversations && waiting > 0) Text(if (waiting > 99) "99+" else waiting.toString(), style = Type.small, color = p.accent)
            } }
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
    "activity" -> "says what it is doing"
    "tasks" -> "reports its tasks"
    "task_details" -> "shares its tasks' details"
    "model" -> "lets its model be chosen"
    else -> null
}

/**
 * An agent's page: its initial, name and address, and what its runtime
 * says it is doing; its conversations (Home, a new one, how many); its
 * tasks, when its runtime reports them; what runs it (runtime, sandbox,
 * host and connection, what it can do); its commands and shared photo
 * controls.
 */
@Composable
fun AgentScreen(model: AppModel, address: String, back: () -> Unit, openChat: (String) -> Unit, start: (Agent) -> Unit, openRoutines: () -> Unit, openModel: () -> Unit) {
    val p = palette
    val agent = model.agents.firstOrNull { it.address == address }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text(agent?.label ?: address, style = Type.heading.copy(fontSize = 18.sp, lineHeight = 22.sp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Hairline()
        val key = AgentKey.of(address)
        // Closes once this phone forgot it: nothing of it is left to show.
        val forgotten = model.forgetting.forgotten[key] == true
        LaunchedEffect(forgotten) {
            if (forgotten) {
                model.forgetting.closed(key)
                back()
            }
        }
        if (agent == null) {
            // Whether it was removed, as the host says now.
            LaunchedEffect(key) { model.forgetting.refresh(key.host) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    if (model.forgetting.isRemoved(key)) "This agent was removed from its host: nothing of it is left there."
                    else "This agent is no longer listed by its host.",
                    style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp),
                )
                model.forgetting.status(key)?.let {
                    Text(it, style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp))
                }
                ForgetAgentSection(model, key, address.substringAfterLast('/'))
            }
            return@Column
        }
        val activity = model.activityOf(agent.address)
        val working = model.workingOn(agent).isNotEmpty() || activity != null
        val conversations = model.conversations.filter { it.host == agent.host && it.agent == agent.name }
        val home = conversations.firstOrNull { it.home }
        val others = conversations.count { !it.home && !it.archived && model.listed(it) }
        val commands = model.commands[agent.address].orEmpty()
        var allCommands by remember { mutableStateOf(false) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EditableAgentPhoto(model, agent, working)
                Gap(14.dp)
                Text(agent.label, style = Type.heading, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(agent.address, style = Type.code, color = p.textFaint)
                activity?.label?.let { doing ->
                    // Working on a task the phone keeps: tapping opens its sheet.
                    val task = model.activeTask(agent.address)
                    val open = if (task != null) Modifier.clickable(onClickLabel = "Open the task") { model.taskDetails.show(task.key) } else Modifier
                    Row(Modifier.padding(top = 6.dp).then(open), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkingWave(square = 4.dp, lift = 3.dp)
                        Text(doing, style = Type.small, color = p.accent)
                    }
                }
            }
            AgentNameControls(model, agent)
            AgentPhotoControls(model, agent)
            Section("Conversations") {
                if (model.leftOut(agent)) {
                    SettingRow {
                        Text(
                            "This agent's Home is not shown here: another paired host has an agent named ${agent.name} too, and this phone keeps the first one's.",
                            style = Type.small, color = p.textMuted,
                        )
                    }
                }
                if (home != null) {
                    SettingRow(Modifier.clickable(onClickLabel = "Open Home") { openChat(home.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (home.archived) "Home · Archived" else "Home", style = Type.name, color = p.text)
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
            AgentUsageSection(model, agent)
            ScopedUsageSection(model, agent.host, agent.sandbox, agent.agent, agent.label)
            if (model.offersTasks(agent.address)) AgentTasks(model, agent)
            if (agent.offersRoutines) {
                Section("Routines") {
                    SettingRow(Modifier.clickable(onClickLabel = "Manage routines", onClick = openRoutines)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Glyph(Glyphs.clock, p.accent, size = 20.dp)
                            Column(Modifier.weight(1f)) {
                                Text("Routines", style = Type.name, color = p.text)
                                Text("Scheduled work this agent runs on its host.", style = Type.small, color = p.textMuted)
                            }
                            Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                        }
                    }
                }
            }
            if (agent.offersModel) {
                Section("Model") {
                    SettingRow(Modifier.clickable(onClickLabel = "Choose its model", onClick = openModel)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(model.models.options[agent.address]?.current?.label ?: "Model", style = Type.name, color = p.text)
                                Text("The model provider and model it uses for all its conversations.", style = Type.small, color = p.textMuted)
                            }
                            Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                        }
                    }
                }
            }
            Section("Runs on") {
                Info("Runtime", agent.runtime.replaceFirstChar { it.uppercase() })
                Info("Sandbox", agent.sandbox)
                Info("Host", "${agent.host} · ${if (model.connected(agent.host)) "connected" else model.links[agent.host].shown()}")
                // Choosing the model is the host's person's alone.
                val does = agent.capabilities.filter { it != "model" || agent.offersModel }.mapNotNull(::can)
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
            // Still listed by a bridge started before the agent's removal.
            ForgetAgentSection(model, key, agent.label)
            Text(
                "The shared photo is stored on this agent's host. Other agent settings are managed there.",
                style = Type.small,
                color = p.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}

/**
 * What the agent used over the last 24 hours, on its page: its tokens, how they split, and
 * what is counted (its calls outside the app for the host's person, when its plugin reports
 * them). Asked again while the page shows.
 */
@Composable
private fun AgentUsageSection(model: AppModel, agent: Agent) {
    val p = palette
    RecentUsageRefresh(model, listOf(agent.host))
    val recent = model.recentUsage[agent.host]
    val row = recent?.of(agent.name)
    Section("Usage · last 24 hours") {
        SettingRow {
            when {
                row != null -> {
                    val total = row.total
                    Text(
                        if (total.tokens.counted) "${tokensText(total.tokens.total)} tokens" else if (total.calls == 1L) "1 call" else "${total.calls} calls",
                        style = Type.name, color = p.text,
                    )
                    if (total.calls > 0) Text(usageLine(total), style = Type.small, color = p.textMuted)
                    row.cost?.let { Text("Estimated cost: ${costText(it)}", style = Type.small, color = p.textMuted) }
                    Text(
                        when {
                            !recent.outside -> "In your conversations in the app."
                            row.outside -> "All its calls: your conversations in the app, its routines and its other chats."
                            else -> "In the app only: its calls outside the app are not reported."
                        },
                        style = Type.small, color = p.textFaint,
                    )
                }
                !model.connected(agent.host) -> Text("Connect to this agent's host to see its usage.", style = Type.small, color = p.textMuted)
                !model.canReadUsage(agent.host) -> Text("Usage is unavailable. Update the host's enclos.", style = Type.small, color = p.textMuted)
                recent?.at != null && recent.cut -> Text("Not in the host's report, which lists $MAX_REPORT_AGENTS agents at most. See Host details.", style = Type.small, color = p.textMuted)
                recent?.error != null -> Text(recent.error, style = Type.small, color = p.textMuted)
                else -> Text("Loading usage…", style = Type.small, color = p.textMuted)
            }
        }
    }
}

/**
 * What a sandbox, or one agent of it, used and cost over time, on its page (docs/spec.md, Usage:
 * Cost): the window's tabs, cost or tokens, the window's total, and a bar a bucket, from a host
 * that estimates cost. Asked when the page opens and from its tabs; cost or tokens only redraws.
 */
@Composable
private fun ScopedUsageSection(model: AppModel, host: String, sandbox: String, agent: String?, label: String) {
    val p = palette
    val key = listOfNotNull(host, sandbox, agent).joinToString("/")
    val available = model.canReadCost(host)
    val usage = model.scopedUsage[key] ?: HostUsage()
    var cost by rememberSaveable(key) { mutableStateOf(true) }
    LaunchedEffect(key, available) { if (available) model.loadScopedUsage(host, sandbox, agent, usage.window) }
    Section("Cost and tokens") {
        SettingRow {
            when {
                !model.connected(host) -> Text(if (usage.loaded) "Saved in memory. Reconnect to refresh." else "Connect to this host to see its cost over time.", style = Type.small, color = p.textMuted)
                !available -> Text("Cost over time is unavailable. Update the host's enclos.", style = Type.small, color = p.textMuted)
            }
            if (available || usage.loaded) {
                UsageWindowTabs(usage.selected, enabled = available && usage.request == null) { model.loadScopedUsage(host, sandbox, agent, it) }
                Gap(8.dp)
                UsageMeasureTabs(cost, enabled = true) { cost = it }
            }
            usage.error?.let { Text(it, style = Type.small, color = p.textMuted) }
            if (usage.request != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkingWave()
                    Text("Loading usage…", style = Type.small, color = p.textMuted)
                }
            }
            if (usage.rowsShown) {
                // The scope's own total: its agent's row, or its sandbox's.
                val total = if (agent != null) usage.agents.firstOrNull { it.agent == "$sandbox/$agent" }?.let { it.total to it.cost }
                    else usage.sandboxes.firstOrNull { it.sandbox == sandbox }?.let { it.total to it.cost }
                if (total == null || (total.first.calls == 0L && !total.first.tokens.counted)) {
                    Text("No usage in the last ${usage.window.label}.", style = Type.small, color = p.textMuted)
                } else {
                    val (used, spent) = total
                    Text(
                        buildString {
                            spent?.let { append(costText(it)).append(" · ") }
                            append(if (used.tokens.counted) "${tokensText(used.tokens.total)} tokens" else "${used.calls} calls")
                            append(" over the last ").append(usage.window.label)
                        },
                        style = Type.name,
                        color = p.text,
                    )
                    // Drawn from when the report was asked and the offset it was asked with.
                    val bars = remember(usage, cost) { sumBars(usage.sum, key, cost, usage.window, usage.at, usage.offset) }
                    if (bars.any { it.tokens > 0 }) {
                        val bucketLabel = { at: Long -> usageBucketLabel(at, usage.window, usage.offset) }
                        UsageChart(
                            bars,
                            mapOf(key to seriesColor(0)),
                            label = { label },
                            bucketLabel = bucketLabel,
                            summary = sumSummary(bars, usage.window, cost, bucketLabel),
                            amount = if (cost) ::dollarsText else ::tokensText,
                            unit = if (cost) "" else " tokens",
                            split = false,
                        )
                    } else if (cost) {
                        Text("Nothing priced in dollars in this window.", style = Type.small, color = p.textMuted)
                    }
                    spent?.let { c ->
                        if (c.unpriced > 0) Text("Some tokens are of a model the host has no price for: the cost is at least this.", style = Type.small, color = p.textFaint)
                        if (c.included > 0) Text("Tokens on a subscription count against its limits, not in dollars.", style = Type.small, color = p.textFaint)
                    }
                    Text(
                        (if (usage.outside) "All its calls the host counts" else "Your conversations in the app") +
                            ". Cost is the host's estimate from its price list: a guide, not a bill.",
                        style = Type.small,
                        color = p.textFaint,
                    )
                }
            }
        }
    }
}

/** How many ended tasks an agent's page lists before Show all. */
private const val ENDED_SHOWN = 5

/**
 * An agent's tasks, on its page: those still going (queued, running,
 * blocked) first, then those that ended, the five most recent before Show
 * all. Each its mark, its title on one line, where it is and who asked for
 * it, and its summary once ended; each opens its [task sheet][TaskSheet].
 * Every text is the sandbox's, drawn as plain text.
 */
@Composable
private fun AgentTasks(model: AppModel, agent: Agent) {
    val p = palette
    val tasks = model.tasksOf(agent.address)
    val current = tasks.filter { !it.state.ended }
    val ended = tasks.filter { it.state.ended }
    var allEnded by remember { mutableStateOf(false) }
    // "running 12 min" stays true while the page shows.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    Section(if (current.isEmpty()) "Tasks" else "Tasks · ${current.size} going") {
        if (tasks.isEmpty()) {
            SettingRow {
                Text("No tasks. A task is work its runtime tracks outside a conversation; only the host's person sees them.", style = Type.small, color = p.textMuted)
            }
        }
        (current + if (allEnded) ended else ended.take(ENDED_SHOWN)).forEach { task ->
            SettingRow(Modifier.clickable(onClickLabel = "Open the task") { model.taskDetails.show(task.key) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { TaskMark(task.state) }
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val by = task.askedBy?.let { " · asked by ${model.agentLabel("${task.host}/$it")}" }.orEmpty()
                        Text(taskStatus(task, now) + by, style = Type.small, color = if (task.state.ended) p.textMuted else p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        task.summary?.takeIf { task.state.ended }?.let {
                            Text(it, style = Type.small, color = p.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                }
            }
        }
        if (ended.size > ENDED_SHOWN) {
            SettingRow(Modifier.clickable { allEnded = !allEnded }) {
                Text(if (allEnded) "Show fewer" else "Show all ${ended.size} ended", style = Type.label, color = p.accent)
            }
        }
    }
}

// ---------- A sandbox's page ----------

/**
 * A sandbox's page (`host/sandbox`): its mark (in `accent` while its host
 * is connected), name, runtime and host; its agents, as the agents view
 * shows them, each opening its page; its groups, each opening; what runs
 * it; runtime installation, its active policy and accepted access, with an editor
 * for the host person; and where its sessions show on the host.
 */
@Composable
fun SandboxScreen(model: AppModel, address: String, back: () -> Unit, openAgent: (Agent) -> Unit, openChat: (String) -> Unit, openPolicy: () -> Unit) {
    val p = palette
    val host = address.substringBefore('/')
    val sandbox = address.substringAfter('/')
    val agents = model.agents.filter { it.host == host && it.sandbox == sandbox }
    val listed = model.sandboxesOf(host).firstOrNull { it.name == sandbox }
    val operation = model.provisioning[address]
    val now = remember { System.currentTimeMillis() / 1000 }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text(sandbox, style = Type.heading.copy(fontSize = 18.sp, lineHeight = 22.sp), color = p.text, maxLines = 1)
        }
        Hairline()
        if (listed == null && operation == null) {
            Text("This sandbox is no longer listed by its host.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val live = model.connected(host)
        val runtime = (listed?.runtime ?: agents.firstOrNull()?.runtime)?.replaceFirstChar { it.uppercase() }
        val groups = model.conversations
            .filter { it.group && !it.archived && it.host == host && it.members.first().substringBefore('/') == sandbox }
            .sortedByDescending { model.lines[it.id]?.lastOrNull()?.time ?: 0L }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SandboxMark(live, side = 56.dp)
                Gap(14.dp)
                Text(sandbox, style = Type.heading, color = p.text)
                Text("${runtime ?: "No runtime installed"} · $host", style = Type.code, color = p.textFaint)
            }
            if (runtime == null) {
                Gap(24.dp)
                RuntimeInstallation(model, host, sandbox)
            } else if (operation != null) {
                Column(Modifier.padding(horizontal = 16.dp)) { ProvisioningStatus(operation) }
            }
            ListHeading("Agents · ${agents.size}")
            if (agents.isEmpty()) {
                Text(
                    if (runtime == null) "Install a runtime to begin setting up an agent." else "No agent listed yet. Complete the runtime's login and app channel setup on the host, then restart the bridge to list the agent.",
                    style = Type.small,
                    color = p.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            RecentUsageRefresh(model, listOf(host))
            agents.forEach { a -> AgentListRow(model, a, now) { openAgent(a) } }
            if (runtime != null) ScopedUsageSection(model, host, sandbox, null, sandbox)
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
                        MemberStack(handles, side = 32.dp, label = { model.agentLabel("$host/$sandbox/$it") }) { PhotoKey(host, "$sandbox/$it") }
                        Text(g.title ?: g.members.joinToString(", ") { model.agentLabel("$host/$it") }, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                    }
                }
            }
            // Only the host's person manages a sandbox policy: a person it was shared with sees no row.
            if (address !in model.notPolicyOwner) {
                ListHeading("Policy")
                SandboxPolicySummary(model, address, openPolicy)
            }
            ListHeading("Runs on")
            Info("Runtime", runtime ?: "Not installed")
            Info("Host", "$host · ${if (live) "connected" else model.links[host].shown()}")
            listed?.state?.let { Info("State", it) }
            Text(
                "Its sessions show on the host: enclos sandbox show $sandbox.",
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
            val ticked = members.any { it.address == a.address }
            val allowed = first == null || (a.host == first.host && a.sandbox == first.sandbox)
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = ticked, enabled = allowed || ticked, role = Role.Checkbox) {
                        if (ticked) members.removeAll { it.address == a.address } else if (members.size < 6) members.add(a)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AgentInitial(a.label, side = 32.dp, photo = a.photoKey)
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = Type.name, color = if (allowed || ticked) p.text else p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${a.agent} · ${a.sandbox} · ${a.host}", style = Type.code, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                val agent = chosen?.let { selected -> model.agents.firstOrNull { it.address == selected.address } }
                Row(
                    Modifier.clip(Shapes.pill).border(1.dp, p.line, Shapes.pill)
                        .clickable(onClickLabel = "Choose the agent", enabled = agents.size > 1) { picking = true }
                        .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (grouping) {
                        MemberStack(members.map { it.agent }.ifEmpty { listOf("?") }, side = 28.dp, label = { h -> members.firstOrNull { it.agent == h }?.let { model.agentLabel(it.address) } ?: h }, photoOf = members.firstOrNull()?.let { m -> { h: String -> PhotoKey(m.host, "${m.sandbox}/$h") } })
                    } else if (agent != null) {
                        AgentInitial(agent.label, side = 28.dp, photo = agent.photoKey)
                    }
                    Text(if (grouping) "New group" else agent?.label ?: "No agent", style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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
                                    AgentInitial(a.label, side = 24.dp, photo = a.photoKey)
                                    Column(Modifier.weight(1f, fill = false)) {
                                        Text(a.label, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("@${a.agent} · ${a.sandbox} · ${a.host}", style = Type.code, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (a.address == chosen?.address) Glyph(Glyphs.check, p.accent, size = 18.dp)
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
            val agent = chosen?.let { selected -> model.agents.firstOrNull { it.address == selected.address } }
            if (agent == null) {
                Text("No agent yet: pair a host, or wait for it to list its agents.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(24.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AgentInitial(agent.label, model.workingOn(agent).isNotEmpty() || model.activityOf(agent.address) != null, side = 64.dp, photo = agent.photoKey)
                    Gap(14.dp)
                    Text(agent.label, style = Type.heading, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            if (grouping) "Message the group, @ to address" else "Message ${agent?.let { model.agentLabel(it.address) } ?: ""}".trim(),
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

/**
 * Forget, for an agent its host confirmed removed (enclos ADR 0067,
 * proposed): its Home and the person's direct conversations with it are
 * deleted, from the host and the person's paired phones. Nothing unless
 * the host lists it in `agents.removed.list`, which only a host that can
 * forget sends, to its own person, or a forget of it is pending or failed.
 */
@Composable
internal fun ForgetAgentSection(model: AppModel, key: AgentKey, label: String) {
    if (!model.forgetting.offers(key)) return
    val p = palette
    Section("Removed agent") {
        SettingRow {
            Text(
                "Its Home and your conversations with it stay until you forget it. Group conversations it was in stay; delete each on its own.",
                style = Type.small, color = p.textMuted,
            )
        }
        ForgetAgentAction(model, key, label)
    }
}

/** One confirmation and action, shared by agent and host pages. */
@Composable
internal fun ForgetAgentAction(model: AppModel, key: AgentKey, label: String) {
    if (!model.forgetting.offers(key)) return
    val p = palette
    var asking by remember(key) { mutableStateOf(false) }
    val unavailable = model.forgetting.unavailable(key)
    SettingRow(Modifier.clickable(enabled = unavailable == null, onClickLabel = "Forget $label") { asking = true }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(Glyphs.delete, if (unavailable == null) p.text else p.textFaint, size = 20.dp)
            Column {
                Text("Forget $label", style = Type.label, color = if (unavailable == null) p.text else p.textFaint)
                (if (key in model.forgetting.pending) "Forgetting agent…" else model.forgetting.errors[key] ?: unavailable)
                    ?.let { Text(it, style = Type.small, color = p.textMuted) }
            }
        }
    }
    if (asking) {
        ConfirmDialog(
            title = "Forget $label?",
            text = "This permanently deletes its Home and your conversations with it, their history and files, from the host and your paired phones. Group conversations it was in stay. An agent made again under this name starts with a new Home." +
                (unavailable ?: model.forgetting.errors[key])?.let { "\n\n$it" }.orEmpty(),
            confirm = "Forget",
            enabled = unavailable == null,
            onDismiss = { asking = false },
        ) { if (model.forgetting.forget(key)) asking = false }
    }
}
