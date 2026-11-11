package io.github.victorbonnet.enclos

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import io.github.victorbonnet.enclos.ui.ThemeChoice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.client.android.Intents
import io.github.victorbonnet.enclos.core.coreVersion
import io.github.victorbonnet.enclos.core.parsePairLink
import io.github.victorbonnet.enclos.ui.AgentAvatar
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.Bubble
import io.github.victorbonnet.enclos.ui.CapabilityChip
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.ConnectionDot
import io.github.victorbonnet.enclos.ui.Corners
import io.github.victorbonnet.enclos.ui.DaySeparator
import io.github.victorbonnet.enclos.ui.Fab
import io.github.victorbonnet.enclos.ui.Gap
import io.github.victorbonnet.enclos.ui.Glyph
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.Hairline
import io.github.victorbonnet.enclos.ui.IconButton
import io.github.victorbonnet.enclos.ui.LargeTitle
import io.github.victorbonnet.enclos.ui.Markdown
import io.github.victorbonnet.enclos.ui.PrimaryButton
import io.github.victorbonnet.enclos.ui.Shapes
import io.github.victorbonnet.enclos.ui.StatusLine
import io.github.victorbonnet.enclos.ui.statusText
import io.github.victorbonnet.enclos.ui.SystemLine
import io.github.victorbonnet.enclos.ui.TextLink
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.Motion
import io.github.victorbonnet.enclos.ui.UnreadBadge
import io.github.victorbonnet.enclos.ui.Files
import io.github.victorbonnet.enclos.ui.ToolSteps
import io.github.victorbonnet.enclos.ui.WorkingBubble
import io.github.victorbonnet.enclos.ui.palette
import kotlinx.coroutines.delay

/** The header of a message an agent sent on its own, such as a cron job's: the protocol does not say which job. */
private const val ON_ITS_OWN = "on its own"

/** What a runtime's channel can do, from the capabilities its plugin announced; none before it was reached. */
private fun channel(agent: Agent): Pair<String, Boolean>? = when {
    agent.capabilities.isEmpty() -> null
    "notify" in agent.capabilities -> "full channel" to true
    else -> "request/reply" to false
}

// ---------- Theme ----------

/**
 * The theme, in Status: the system's light or dark (the default), or the
 * one the person chose, which the app then keeps; and the way back to the
 * system's.
 */
@Composable
private fun ThemeSetting(model: AppModel) {
    val p = palette
    val choice = model.theme.value
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Theme", style = Type.name, color = p.text)
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice.entries.forEach { option ->
                Choice(option.name, option == choice) { model.chooseTheme(option) }
            }
        }
        Gap(8.dp)
        Text(
            when (choice) {
                ThemeChoice.System -> "Follows the system's light or dark setting."
                else -> "${choice.name} on this phone, whatever the system's setting."
            },
            style = Type.small,
            color = p.textMuted,
        )
    }
}

// ---------- Inbox ----------

/** Every conversation with every agent, on every host, newest first; one with no message yet is not listed. */
@Composable
fun InboxScreen(model: AppModel, open: (String) -> Unit, newConversation: () -> Unit, pair: () -> Unit) {
    val sorted = model.conversations.filter(model::listed)
        .sortedByDescending { c -> model.lines[c.id]?.lastOrNull()?.time ?: 0L }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf<InboxFilter>(InboxFilter.All) }
    val found = search(sorted, { model.lines[it].orEmpty() }, model::unread, query, filter)
    // An agent's Home first, the rest newest first: pinned in the list and in its agent's group.
    val homeFirst = { list: List<Found> -> list.sortedBy { !it.conversation.home } }
    val grouped = model.grouped.value
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Grouping by agent sits on the title's line, in `accent` while on.
            LargeTitle("Inbox", action = if (sorted.isEmpty()) null else {
                {
                    IconButton(
                        Glyphs.group,
                        if (grouped) "Show newest first" else "Group by agent",
                        { model.group(!grouped) },
                        tint = if (grouped) palette.accent else palette.textMuted,
                    )
                }
            })
            when {
                model.paired.isEmpty() -> Empty(
                    "No host yet.",
                    "On a host, run enclos app serve, then enclos app pair, and scan its code.",
                    "Pair a host →",
                    pair,
                )
                sorted.isEmpty() -> Empty("No conversation yet.", "Start one with an agent.", "Agents →", newConversation)
                else -> {
                    SearchField(query, { query = it })
                    Filters(sorted, filter) { filter = it }
                    LazyColumn(Modifier.weight(1f)) {
                        item { Hairline() }
                        if (found.isEmpty()) {
                            item {
                                Text(
                                    if (filter == InboxFilter.Unread && query.isBlank()) "Nothing unread." else "No conversation matches.",
                                    style = Type.small,
                                    color = palette.textMuted,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        if (grouped) {
                            // Each agent under its header, the most recently active first;
                            // a header folds its conversations away.
                            byAgent(found).forEach { group ->
                                val shut = group.address in model.folded
                                item(key = "agent:${group.address}") {
                                    Arriving {
                                        AgentHeader(model, group, shut) {
                                            if (shut) model.folded.remove(group.address) else model.folded.add(group.address)
                                        }
                                    }
                                }
                                if (!shut) {
                                    items(homeFirst(group.found), key = { it.conversation.id }) { f ->
                                        Arriving { ConversationRow(model, f.conversation, f.match, query, grouped = true) { open(f.conversation.id) } }
                                    }
                                }
                            }
                        } else {
                            // A conversation that moves to the top slides there.
                            items(homeFirst(found), key = { it.conversation.id }) { f ->
                                Arriving { ConversationRow(model, f.conversation, f.match, query) { open(f.conversation.id) } }
                            }
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
        if (model.paired.isNotEmpty()) {
            Fab("New conversation", Glyphs.compose, newConversation, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}

@Composable
private fun ColumnScope.Empty(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
        Hairline()
        Gap(24.dp)
        Text(title, style = Type.name, color = palette.text)
        Gap(4.dp)
        Text(text, style = Type.small, color = palette.textMuted)
        TextLink(action, onAction)
    }
}

/** The inbox's search: agents, names and what was said. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val p = palette
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = Type.message.copy(color = p.text),
        cursorBrush = SolidColor(p.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
            .semantics { contentDescription = "Search conversations" },
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(Shapes.control).background(p.surface).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph(Glyphs.search, p.textFaint, size = 20.dp)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search conversations", style = Type.message, color = p.textFaint)
                    field()
                }
                AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(Motion.enter(Motion.SHORT)), exit = fadeOut(Motion.exit())) {
                    IconButton(Glyphs.clear, "Clear the search", { onQuery("") }, tint = p.textMuted)
                }
            }
        },
    )
}

/** All, Unread, and each agent when there are several: one at a time. */
@Composable
private fun Filters(conversations: List<Conversation>, chosen: InboxFilter, onChoose: (InboxFilter) -> Unit) {
    val agents = conversations.map { "${it.host}/${it.agent}" }.distinct().sorted()
    val labels = agentLabels(agents)
    val options = buildList {
        add(InboxFilter.All to "All")
        add(InboxFilter.Unread to "Unread")
        if (agents.size > 1) agents.forEach { a -> add(InboxFilter.OfAgent(a) to labels.getValue(a)) }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (option, label) -> Choice(label, option == chosen) { onChoose(option) } }
    }
}

/** A choice among a few, outlined in `accent` when chosen (filters, the theme). */
@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    val p = palette
    val edge by animateColorAsState(if (on) p.accent else p.lineStrong, Motion.change(), label = "edge")
    val text by animateColorAsState(if (on) p.accent else p.textMuted, Motion.change(), label = "text")
    Box(
        Modifier.height(44.dp).clip(Shapes.control).border(1.dp, edge, Shapes.control)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.label, color = text, maxLines = 1) }
}

/** [text] with the words of [query] in `accent`, whatever their case. */
private fun highlighted(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val lower = text.lowercase()
    query.lowercase().split(' ').filter { it.isNotBlank() }.forEach { word ->
        var at = lower.indexOf(word)
        while (at >= 0) {
            addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), at, at + word.length)
            at = lower.indexOf(word, at + word.length)
        }
    }
}

/**
 * A conversation in the inbox: the agent, the conversation's name (or its
 * address while it has none), the time, the last message or the one the
 * search found, and the unread count.
 */
@Composable
private fun ConversationRow(model: AppModel, c: Conversation, match: Line?, query: String, grouped: Boolean = false, onClick: () -> Unit) {
    val p = palette
    // The last message, not a status line (gateway online) when there is anything else.
    val all = model.lines[c.id].orEmpty()
    val last = all.lastOrNull { !it.status } ?: all.lastOrNull()
    val unread = model.unread(c.id)
    val agent = c.agent.substringAfter('/')
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // Under its agent's header, a row starts where the header's name does, without the avatar.
        val start = if (grouped) 74.dp else 16.dp
        Row(Modifier.fillMaxWidth().padding(start = start, end = 16.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!grouped) AgentAvatar(agent, live = model.connected(c.host))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (grouped) {
                        // The agent is in the header: the conversation's name leads.
                        Text(
                            c.title?.let { highlighted(it, query, p.accent) } ?: AnnotatedString("No name yet"),
                            style = Type.name,
                            color = if (c.title != null) p.text else p.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(agent, style = Type.name, color = p.text, maxLines = 1)
                        // Its name, as the agent or the person gave it; the address until then.
                        val title = c.title
                        if (title != null) {
                            Text(
                                highlighted(title, query, p.accent),
                                style = Type.small,
                                color = p.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(bottom = 1.dp),
                            )
                        } else {
                            Text(
                                "${c.host}/${c.agent}",
                                style = Type.code,
                                color = p.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(bottom = 2.dp),
                            )
                        }
                    }
                    if (last != null) {
                        Text(Times.inList(last.time), style = Type.code, color = if (unread > 0) p.accent else p.textFaint, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                if (last?.kind == "notify") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Glyph(Glyphs.clock, p.accent, size = 14.dp)
                        Text(ON_ITS_OWN, style = Type.meta, color = p.accent)
                    }
                }
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The message the search found, else the last; the words, without markdown.
                    val shown = match ?: last
                    val words = when {
                        shown == null && c.home -> "Cron jobs and the agent's notices come here."
                        shown == null -> "No message yet"
                        shown.status -> statusText(model.statusSource(c), shown.text)
                        shown.from == "agent" -> remember(shown.text) { words(shown) }
                        else -> "You: ${shown.text}"
                    }
                    Text(
                        if (match != null) highlighted(words, query, p.accent) else AnnotatedString(words),
                        style = Type.small,
                        color = if (unread > 0) p.text else p.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedVisibility(
                        visible = unread > 0,
                        enter = fadeIn(Motion.enter(Motion.SHORT)) + scaleIn(Motion.enter(Motion.SHORT), initialScale = 0.6f),
                        exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.6f),
                    ) { UnreadBadge(unread.coerceAtLeast(1)) }
                }
            }
        }
        Hairline()
    }
}

/**
 * An agent's header in the grouped inbox, on `surface`: its avatar, name and
 * address, how many of its conversations show, their unread total, and a
 * chevron, down while open; tapping it folds them away or opens them.
 */
@Composable
private fun AgentHeader(model: AppModel, group: Group, shut: Boolean, onToggle: () -> Unit) {
    val p = palette
    val host = group.address.substringBefore('/')
    val name = group.address.substringAfterLast('/')
    val unread = group.found.sumOf { model.unread(it.conversation.id) }
    val turn by animateFloatAsState(if (shut) 0f else 90f, Motion.change(), label = "chevron")
    val count = group.found.size
    Column(
        Modifier.fillMaxWidth().background(p.surface)
            .clickable(onClickLabel = if (shut) "Show $name's conversations" else "Fold $name's conversations", onClick = onToggle)
            .semantics { heading() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AgentAvatar(name, live = model.connected(host))
            Column(Modifier.weight(1f)) {
                Text(name, style = Type.name, color = p.text, maxLines = 1)
                // The count first: a long address is what gets cut.
                Text(
                    "$count ${if (count == 1) "conversation" else "conversations"} · ${group.address}",
                    style = Type.code,
                    color = p.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                visible = unread > 0,
                enter = fadeIn(Motion.enter(Motion.SHORT)) + scaleIn(Motion.enter(Motion.SHORT), initialScale = 0.6f),
                exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.6f),
            ) { UnreadBadge(unread.coerceAtLeast(1)) }
            Box(Modifier.rotate(turn)) { Glyph(Glyphs.chevron, p.textMuted, size = 20.dp) }
        }
        Hairline()
    }
}

// ---------- Agents ----------

/** Hosts, their sandboxes and their agents, whatever the runtime; tap an agent to start a conversation. */
@Composable
fun AgentsScreen(model: AppModel, start: (Agent) -> Unit, pair: () -> Unit) {
    val p = palette
    Column(Modifier.fillMaxSize()) {
        // Pairing another host sits on the title's line: no bar above it.
        LargeTitle("Agents") { IconButton(Glyphs.qr, "Pair a host", pair) }
        if (model.paired.isEmpty()) {
            Empty("No host yet.", "On a host, run enclos app serve, then enclos app pair, and scan its code.", "Pair a host →", pair)
            return
        }
        LazyColumn(Modifier.weight(1f)) {
            model.paired.forEach { host ->
                val live = model.connected(host.name)
                item(key = "host-${host.name}") {
                    Column {
                        Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ConnectionDot(live)
                            Text(host.name, style = Type.code.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                            Text(
                                if (live) "connected · tailnet" else model.status[host.name] ?: "not connected",
                                style = Type.code,
                                color = p.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
                model.agents.filter { it.host == host.name }.groupBy { it.sandbox }.forEach { (sandbox, agents) ->
                    item(key = "sandbox-${host.name}-$sandbox") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 34.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(sandbox, style = Type.code.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                            Text(agents.first().runtime.replaceFirstChar { it.uppercase() }, style = Type.small, color = p.textMuted)
                            // Known once the bridge reached the agent: it fades in then.
                            val chip = channel(agents.first())
                            AnimatedVisibility(chip != null, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
                                chip?.let { (text, full) -> CapabilityChip(text, full) }
                            }
                        }
                    }
                    items(agents, key = { "agent-${it.address}" }) { agent ->
                        Row(
                            Modifier.fillMaxWidth().clickable(onClickLabel = "New conversation", onClick = { start(agent) })
                                .padding(start = 34.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AgentAvatar(agent.agent, live, small = true)
                            Column(Modifier.weight(1f)) {
                                Text(agent.agent, style = Type.name.copy(fontSize = 15.sp), color = p.text)
                                Text(agent.address, style = Type.code, color = p.textFaint)
                            }
                            Glyph(Glyphs.plus, p.accent, size = 20.dp)
                        }
                    }
                }
            }
            item { Gap(16.dp) }
        }
    }
}

// ---------- Status ----------

/**
 * The paired hosts and their connections, and what the phone keeps: forget a
 * host, clear the cache. Nothing here acts on a sandbox.
 */
@Composable
fun StatusScreen(model: AppModel, pair: () -> Unit) {
    val p = palette
    var forgetting by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    forgetting?.let { name ->
        val live = model.connected(name)
        ConfirmDialog(
            title = "Forget $name?",
            text = if (live) {
                "This phone is unpaired on $name, and its conversations and files here are deleted. They stay on the host. To use it again, pair again."
            } else {
                "Its conversations and files here are deleted. Not connected: $name keeps this phone listed until you run enclos app unpair there. To use it again, pair again."
            },
            confirm = "Forget",
            onDismiss = { forgetting = null },
        ) {
            model.forgetHost(name)
            forgetting = null
        }
    }
    if (clearing) {
        ConfirmDialog(
            title = "Clear the cache?",
            text = "The conversations and files kept on this phone are deleted, then synced again from each host. Pairings stay.",
            confirm = "Clear",
            onDismiss = { clearing = false },
        ) {
            model.clearCache()
            clearing = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Status")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Hairline()
            model.paired.forEach { host ->
                val live = model.connected(host.name)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConnectionDot(live)
                        Text(host.name, style = Type.code.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                    }
                    Gap(4.dp)
                    Text(model.status[host.name] ?: "not connected", style = Type.small, color = p.textMuted)
                    model.said[host.name]?.let { Text("The bridge said: $it", style = Type.small, color = p.textMuted) }
                    Text(host.url.removePrefix("https://"), style = Type.code, color = p.textFaint)
                    if (!live) TextLink("Reconnect →") { model.reconnect(host.name) }
                    TextLink("Forget this host") { forgetting = host.name }
                }
                Hairline()
            }
            ThemeSetting(model)
            Hairline()
            // What the phone keeps: synced again from the hosts when cleared.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("On this phone", style = Type.label, color = p.text)
                Gap(4.dp)
                val count = model.conversations.count(model::listed)
                Text(
                    "$count ${if (count == 1) "conversation" else "conversations"} and the files shown in them, kept to open at once.",
                    style = Type.small,
                    color = p.textMuted,
                )
                if (model.keptUnreadable.value) {
                    Text(
                        "The conversations kept here could not be read (a newer version of the app may have written them): they show from the hosts only, and nothing more is kept until the app that wrote them is installed again.",
                        style = Type.small,
                        color = p.textMuted,
                    )
                }
                TextLink("Clear cache") { clearing = true }
            }
            Hairline()
            Column(Modifier.padding(horizontal = 16.dp)) {
                TextLink("Pair a host →", pair)
                Text("core ${coreVersion()}", style = Type.code, color = p.textFaint)
            }
        }
    }
}

// ---------- Conversation ----------

/** One conversation with one agent: messages both ways, and what the agent sent on its own. */
@Composable
fun ConversationScreen(model: AppModel, id: String, back: () -> Unit) {
    val p = palette
    val conversation = model.conversations.firstOrNull { it.id == id } ?: return
    val lines = model.lines[id].orEmpty()
    val agent = conversation.agent.substringAfter('/')
    val live = model.connected(conversation.host)
    val working = model.working(id)
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, working) {
        model.markRead(id)
        // The list is anchored at the newest: follow what arrives, unless
        // the person scrolled up to read.
        if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0)
    }
    var renaming by remember { mutableStateOf(false) }
    // Which answers' steps are open.
    val opened = remember { mutableStateMapOf<Long, Boolean>() }
    if (renaming) {
        RenameDialog(conversation.title, onDismiss = { renaming = false }) {
            model.rename(conversation, it)
            renaming = false
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            AgentAvatar(agent, live, small = true)
            // Its name, and the agent under it; tap to rename.
            Column(Modifier.weight(1f).clickable(onClickLabel = "Rename the conversation") { renaming = true }) {
                Text(
                    conversation.title ?: agent,
                    style = Type.heading.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (conversation.title != null) "$agent · ${conversation.host}/${conversation.agent}" else "${conversation.host}/${conversation.agent}",
                    style = Type.code,
                    color = p.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Hairline()
        // Newest first, drawn from the bottom up: the conversation stays
        // anchored at its last message when the keyboard opens or a message
        // arrives. What arrives fades in while the rest slides up.
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            model.said[conversation.host]?.let { item(key = "said") { Arriving { SystemLine("The bridge said: $it") } } }
            if (!live) item(key = "status") { Arriving { SystemLine(model.status[conversation.host] ?: "not connected") } }
            if (working) item(key = "working") { Arriving { WorkingBubble(agent) } }
            // The tools it runs now, above the working bubble.
            val running = model.steps[id].orEmpty()
            if (running.isNotEmpty()) item(key = "steps-live") { Arriving { ToolSteps(running, live = true, expanded = true) {} } }
            val runtime = model.statusSource(conversation)
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                item(key = line.seq) {
                    // The runtime about itself: a quiet line, not a bubble.
                    // One with files is drawn as a message, so they show.
                    if (line.status && line.attachments.isEmpty()) {
                        Arriving { StatusLine(runtime, line.text, Times.clock(line.time)) }
                        return@item
                    }
                    Arriving {
                        Bubble(
                            text = line.text,
                            time = Times.clock(line.time),
                            outgoing = line.from != "agent",
                            header = if (line.kind == "notify") ON_ITS_OWN else null,
                            files = line.attachments.takeIf { it.isNotEmpty() }?.let { attached ->
                                { Files(attached, model.files) { model.fetch(conversation.host, it) } }
                            },
                        )
                    }
                }
                // The steps that led to an answer, folded above it.
                line.steps.takeIf { it.isNotEmpty() }?.let { done ->
                    item(key = "steps-${line.seq}") {
                        Arriving {
                            ToolSteps(done, live = false, expanded = opened[line.seq] == true) {
                                opened[line.seq] = opened[line.seq] != true
                            }
                        }
                    }
                }
                val day = Times.day(line.time)
                if (i == 0 || Times.day(lines[i - 1].time) != day) item(key = "day-${line.seq}") { Arriving { DaySeparator(day) } }
            }
        }
        Composer(draft, { draft = it }, "Message $agent") {
            model.send(conversation, draft.trim())
            draft = ""
        }
    }
}

/**
 * Names the conversation: the person's name replaces the agent's, which
 * then names it no more; clearing it lets the agent name it again.
 */
@Composable
private fun RenameDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val p = palette
    var name by remember { mutableStateOf(current.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = Shapes.bubble,
        title = { Text("Name the conversation", style = Type.heading, color = p.text) },
        text = {
            Column {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    singleLine = true,
                    textStyle = Type.message.copy(color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { field ->
                        Box(
                            Modifier.fillMaxWidth().height(44.dp).clip(Shapes.control).background(p.bg)
                                .border(1.dp, p.lineStrong, Shapes.control).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (name.isEmpty()) Text("Weather in Lyon", style = Type.message, color = p.textFaint)
                            field()
                        }
                    },
                )
                Gap(8.dp)
                Text("The agent names a conversation until you do.", style = Type.small, color = p.textMuted)
            }
        },
        confirmButton = { DialogButton("Save", enabled = name.isNotBlank()) { onSave(name) } },
        dismissButton = {
            Row {
                if (current != null) DialogButton("Clear name") { onSave("") }
                DialogButton("Cancel", onClick = onDismiss)
            }
        },
    )
}

/** A question before something that cannot be undone: [confirm] does it, Cancel does not. */
@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = Shapes.bubble,
        title = { Text(title, style = Type.heading, color = p.text) },
        text = { Text(text, style = Type.small, color = p.textMuted) },
        confirmButton = { DialogButton(confirm, onClick = onConfirm) },
        dismissButton = { DialogButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun DialogButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(Shapes.control).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.label, color = if (enabled) palette.accent else palette.textFaint) }
}

/**
 * An item of a lazy list that fades in when it arrives, slides to its new
 * place when others arrive, and fades out when it goes.
 */
@Composable
private fun LazyItemScope.Arriving(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().animateItem(
            fadeInSpec = Motion.enter(),
            placementSpec = Motion.enter(),
            fadeOutSpec = Motion.exit(),
        ),
    ) { content() }
}

// ---------- Pairing ----------

/** Pairs the phone with one host: scan the code `enclos app pair` shows, then accept in the terminal. */
@Composable
fun PairScreen(model: AppModel, back: () -> Unit, done: () -> Unit) {
    val p = palette
    val pairing = model.pairing.value
    var typed by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { model.pair(it) }
    }
    val launch = {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("The code of enclos app pair")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                // A terminal with a dark theme draws the code light on
                // dark: read it either way round.
                .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN),
        )
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    // The countdown ticks while a pairing goes on, and while an opened link waits.
    val waiting = model.openedLink.value != null
    LaunchedEffect(pairing, waiting) {
        while ((pairing != null && !pairing.paired && pairing.stopped == null) || (pairing == null && waiting)) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("Pair a host", style = Type.heading, color = p.text, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // The viewfinder is the logo: the Home base corners frame the code.
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(300.dp).background(p.panelDeep)
                    .clickable(enabled = pairing == null, onClickLabel = "Scan the code", role = Role.Button, onClick = launch),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                    Corners(p.lime, Modifier.size(180.dp), stroke = 3.dp, arm = 0.22f)
                    Box(Modifier.size(17.dp).background(p.lime))
                }
                Text(
                    if (pairing == null) "Tap to scan the code from enclos app pair" else "Code read",
                    style = Type.code,
                    color = p.panelMuted,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Gap(16.dp)
                // Opened from a link: what it names, and nothing done until the person says so.
                val opened = model.openedLink.value
                if (pairing == null && opened != null) {
                    OpenedLink(opened, now, pair = { model.pair(opened) }, dismiss = { model.clearPairing() })
                    Gap(8.dp)
                }
                val host = pairing?.host ?: "the host"
                Step(
                    1,
                    if (pairing != null) StepState.Done else StepState.Now,
                    "Code read",
                    pairing?.let {
                        val left = (it.expires - now).coerceAtLeast(0)
                        "${it.host} · ${it.address} · " + if (left > 0) "expires in ${left / 60}:${"%02d".format(left % 60)}" else "expired"
                    } ?: "Scan the code enclos app pair shows",
                )
                Step(2, if (pairing?.keyMade == true) StepState.Done else StepState.Next, "Device key made", "Kept in this phone's Android Keystore")
                Step(
                    3,
                    when {
                        pairing?.paired == true -> StepState.Done
                        pairing?.keyMade == true && pairing.stopped == null -> StepState.Now
                        else -> StepState.Next
                    },
                    "Accept on $host",
                    if (pairing?.paired == true) "Paired." else "Your terminal asks:",
                )
                if (pairing != null && !pairing.paired && pairing.stopped == null) {
                    // The terminal's question, with what only the host knows left as a placeholder.
                    Text(
                        "${Build.MODEL} (tailscale login: <your login>) wants to pair. Accept? [y/N]",
                        style = Type.meta.copy(fontSize = 11.5.sp, lineHeight = 18.sp),
                        color = p.panelMuted,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().background(p.panelDeep).padding(12.dp),
                    )
                }
                pairing?.stopped?.let {
                    Gap(12.dp)
                    SystemLine(it)
                    TextLink("Scan another code →") { model.clearPairing() }
                }
                if (pairing?.paired == true) {
                    Gap(16.dp)
                    PrimaryButton("Done") {
                        model.clearPairing()
                        done()
                    }
                }
                if (pairing == null) {
                    if (!typed) {
                        TextLink("Type the code instead →") { typed = true }
                    } else {
                        Gap(12.dp)
                        BasicTextField(
                            value = link,
                            onValueChange = { link = it },
                            textStyle = Type.code.copy(color = p.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(p.accent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { field ->
                                Box(
                                    Modifier.fillMaxWidth().border(1.dp, p.lineStrong, Shapes.control).padding(12.dp)
                                        .semantics { contentDescription = "Pairing link" },
                                ) {
                                    if (link.isEmpty()) Text("https://…/pair#… or enclos://pair?…", style = Type.code.copy(fontSize = 13.sp), color = p.textFaint)
                                    field()
                                }
                            },
                        )
                        Gap(8.dp)
                        PrimaryButton("Pair", enabled = link.isNotBlank()) { model.pair(link) }
                    }
                    model.pairLinkError.value?.let { Gap(8.dp); Text(it, style = Type.small, color = p.textMuted) }
                }
                Gap(24.dp)
            }
        }
    }
}

/**
 * A pairing link the app was opened with: the host and address it names and
 * when it expires, then Pair or Not now. Any page or message can hold such
 * a link, so nothing connects before Pair.
 */
@Composable
private fun OpenedLink(link: String, now: Long, pair: () -> Unit, dismiss: () -> Unit) {
    val p = palette
    val read = remember(link) { runCatching { parsePairLink(link) } }
    Column(Modifier.fillMaxWidth().border(1.dp, p.lineStrong, Shapes.control).padding(14.dp)) {
        val parsed = read.getOrNull()
        if (parsed == null) {
            Text("Not an enclos pairing link", style = Type.name, color = p.text)
            // The core's reason, without what the title already says.
            val why = read.exceptionOrNull()?.message.orEmpty().removePrefix("reason=").removePrefix("not an enclos pairing link: ")
            Text(why, style = Type.small, color = p.textMuted)
            TextLink("Dismiss", dismiss)
            return@Column
        }
        val left = (parsed.expires.toLong() - now).coerceAtLeast(0)
        Text("Pair with ${parsed.host}?", style = Type.name, color = p.text)
        Gap(2.dp)
        Text(
            parsed.url.removePrefix("https://") + " · " + if (left > 0) "expires in ${left / 60}:${"%02d".format(left % 60)}" else "expired",
            style = Type.code,
            color = p.textFaint,
        )
        Gap(8.dp)
        Text(
            "From a link you opened. Pair only with a host of yours: its agents will reach this phone. The host asks you to accept in its terminal too.",
            style = Type.small,
            color = p.textMuted,
        )
        Gap(12.dp)
        PrimaryButton("Pair", enabled = left > 0, onClick = pair)
        TextLink("Not now", dismiss)
    }
}

private enum class StepState { Done, Now, Next }

/** A pairing step: done in a filled `accent` square, current outlined in `accent`, next in `line-strong`. */
@Composable
private fun Step(n: Int, state: StepState, title: String, detail: String) {
    val p = palette
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A step fills in as it is done, rather than switching.
            val fill by animateColorAsState(if (state == StepState.Done) p.accent else p.accent.copy(alpha = 0f), Motion.change(Motion.MEDIUM), label = "fill")
            val edge by animateColorAsState(if (state == StepState.Next) p.lineStrong else p.accent, Motion.change(), label = "edge")
            val width by animateDpAsState(if (state == StepState.Now) 2.dp else 1.dp, Motion.change(), label = "width")
            Box(Modifier.size(24.dp).background(fill).border(width, edge), contentAlignment = Alignment.Center) {
                Crossfade(state == StepState.Done, animationSpec = Motion.change(), label = "mark") { done ->
                    if (done) {
                        Glyph(Glyphs.check, p.bg, size = 16.dp)
                    } else {
                        Text("$n", style = Type.code.copy(fontWeight = FontWeight.SemiBold), color = if (state == StepState.Now) p.accent else p.textMuted)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.name.copy(fontSize = 15.sp), color = p.text)
                Text(detail, style = Type.small, color = p.textMuted)
            }
        }
        Hairline()
    }
}

// ---------- Times ----------

/** Times as the screens write them, in the phone's zone. */
object Times {
    private val zone get() = java.time.ZoneId.systemDefault()
    private fun date(seconds: Long) = java.time.Instant.ofEpochSecond(seconds).atZone(zone)
    private val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    private val weekday = java.time.format.DateTimeFormatter.ofPattern("EEE")
    private val dayMonth = java.time.format.DateTimeFormatter.ofPattern("d MMM")
    private val full = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")

    /** `07:12`. */
    fun clock(seconds: Long): String = date(seconds).format(hm)

    /** In a list: the time today, the weekday this week, else the date. */
    fun inList(seconds: Long): String {
        val d = date(seconds).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        return when {
            d == today -> clock(seconds)
            d.isAfter(today.minusDays(7)) -> date(seconds).format(weekday)
            else -> date(seconds).format(dayMonth)
        }
    }

    /** Between messages, in lowercase: `today`, `yesterday`, `mon 3 nov`. */
    fun day(seconds: Long): String {
        val d = date(seconds).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        return when (d) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> date(seconds).format(full).lowercase()
        }
    }
}
