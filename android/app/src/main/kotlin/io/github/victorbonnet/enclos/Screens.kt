package io.github.victorbonnet.enclos

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.client.android.Intents
import io.github.victorbonnet.enclos.core.coreVersion
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
import io.github.victorbonnet.enclos.ui.SystemLine
import io.github.victorbonnet.enclos.ui.TextLink
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.Motion
import io.github.victorbonnet.enclos.ui.UnreadBadge
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

/** Every conversation with every agent, on every host, newest first. */
@Composable
fun InboxScreen(model: AppModel, open: (String) -> Unit, newConversation: () -> Unit, pair: () -> Unit) {
    // A conversation with no message yet sorts first: it was just started.
    val sorted = model.conversations.sortedByDescending { c -> model.lines[c.id]?.lastOrNull()?.time ?: Long.MAX_VALUE }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf<InboxFilter>(InboxFilter.All) }
    // What search reads, taken here on the main thread: lists that are
    // replaced, never changed, when a message arrives.
    val said = sorted.associate { it.id to model.lines[it.id].orEmpty() }
    val unread = sorted.associate { it.id to model.unread(it.id) }
    val listed = remember(sorted, said, unread, filter) { search(sorted, { said[it].orEmpty() }, { unread[it] ?: 0 }, "", filter) }
    // A query reads every message: searched off the main thread, each
    // message folded once (model.folds); the last result stays meanwhile.
    var searched by remember { mutableStateOf<List<Found>?>(null) }
    LaunchedEffect(sorted, said, unread, query, filter) {
        searched = if (query.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                search(sorted, { said[it].orEmpty() }, { unread[it] ?: 0 }, query, filter, model.folds::of)
            }
        }
    }
    val found = if (query.isBlank()) listed else searched ?: listed
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LargeTitle("Inbox")
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
                        // A conversation that moves to the top slides there.
                        items(found, key = { it.conversation.id }) { f ->
                            Arriving { ConversationRow(model, f.conversation, f.match, query) { open(f.conversation.id) } }
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
    val names = agents.map { it.substringAfterLast('/') }
    val options = buildList {
        add(InboxFilter.All to "All")
        add(InboxFilter.Unread to "Unread")
        // An agent by its name, or by sandbox and name when two share one.
        if (agents.size > 1) agents.forEachIndexed { i, a ->
            add(InboxFilter.OfAgent(a) to if (names.count { it == names[i] } > 1) a.substringAfter('/') else names[i])
        }
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

/** [text] with the words of [query] in `accent`, where search found them: its own rules (case, accents, spaces). */
private fun highlighted(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    matches(text, query).forEach { range ->
        addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
    }
}

/**
 * A conversation in the inbox: the agent, the conversation's name (or its
 * address while it has none), the time, the last message or the one the
 * search found, and the unread count.
 */
@Composable
private fun ConversationRow(model: AppModel, c: Conversation, match: Line?, query: String, onClick: () -> Unit) {
    val p = palette
    val last = model.lines[c.id]?.lastOrNull()
    val unread = model.unread(c.id)
    val agent = c.agent.substringAfter('/')
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AgentAvatar(agent, live = model.connected(c.host))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        shown == null -> "No message yet"
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

/** A read-only view of the paired hosts and their connections. Nothing here acts on sandboxes. */
@Composable
fun StatusScreen(model: AppModel, pair: () -> Unit) {
    val p = palette
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
                }
                Hairline()
            }
            ThemeSetting(model)
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
    var unsent by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    // Whether the person is at the newest: the first item (the list is
    // reversed) at its very end. Read when a scroll ends, theirs or the
    // list's own, not when a message arrives, which shifts the first
    // visible item; within a long message, the offset says they scrolled up.
    var atNewest by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) atNewest = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= 4
        }
    }
    LaunchedEffect(lines.size, working) {
        model.markRead(id)
        // The list is anchored at the newest: follow what arrives, unless
        // the person scrolled up to read.
        if (atNewest) list.animateScrollToItem(0)
    }
    var renaming by remember { mutableStateOf(false) }
    // Which answers' steps are open.
    val opened = remember { mutableStateMapOf<Long, Boolean>() }
    if (renaming) {
        RenameDialog(conversation.title, live, conversation.host, onDismiss = { renaming = false }) {
            model.rename(conversation, it).also { sent -> if (sent) renaming = false }
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
            // The tools it runs now, above the working bubble; a turn that
            // ended with no answer shows none.
            val running = model.steps[id].orEmpty()
            if (working && running.isNotEmpty()) item(key = "steps-live") { Arriving { ToolSteps(running, live = true, expanded = true) {} } }
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                item(key = line.seq) {
                    Arriving {
                        Bubble(
                            text = line.text,
                            time = Times.clock(line.time),
                            outgoing = line.from != "agent",
                            header = if (line.kind == "notify") ON_ITS_OWN else null,
                        )
                    }
                }
                // The steps that led to an answer, folded above it.
                model.stepsOf[line.seq]?.let { done ->
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
        // Why the last message did not go: the draft stays until it does.
        unsent?.let { SystemLine(it, Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
        Composer(draft, { draft = it }, "Message $agent") {
            unsent = model.send(conversation, draft.trim())
            if (unsent == null) {
                draft = ""
                // What the person just sent shows, wherever they had scrolled.
                atNewest = true
            }
        }
    }
}

/**
 * Names the conversation: the person's name replaces the agent's, which
 * then names it no more; clearing it lets the agent name it again. The
 * bridge keeps the name, so it is saved only while [host] is connected
 * ([live]); [onSave] says whether it was.
 */
@Composable
private fun RenameDialog(current: String?, live: Boolean, host: String, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    val p = palette
    var name by remember { mutableStateOf(current.orEmpty()) }
    var refused by remember { mutableStateOf(false) }
    val save = { title: String -> refused = !onSave(title) }
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
                Text(
                    if (!live || refused) "Not connected to $host: the name can be saved once it is." else "The agent names a conversation until you do.",
                    style = Type.small,
                    color = p.textMuted,
                )
            }
        },
        confirmButton = { DialogButton("Save", enabled = live && name.isNotBlank()) { save(name) } },
        dismissButton = {
            Row {
                if (current != null) DialogButton("Clear name", enabled = live) { save("") }
                DialogButton("Cancel", onClick = onDismiss)
            }
        },
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
                // Portrait, as the app (the manifest)
                .setOrientationLocked(true)
                // A terminal with a dark theme draws the code light on
                // dark: read it either way round.
                .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN),
        )
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(pairing) {
        while (pairing != null && !pairing.paired && pairing.stopped == null) {
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
                Step(
                    2,
                    when {
                        pairing?.keyMade == true -> StepState.Done
                        // Made off the main thread: StrongBox can take seconds.
                        pairing != null && pairing.stopped == null -> StepState.Now
                        else -> StepState.Next
                    },
                    "Device key made",
                    "Kept in this phone's Android Keystore",
                )
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
                model.replacing.value?.let { Replace(it, model::replace, model::keep) }
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
                                    if (link.isEmpty()) Text("enclos://pair?…", style = Type.code.copy(fontSize = 13.sp), color = p.textFaint)
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
 * A link names a host already paired with another bridge: the person sees
 * both addresses and chooses. Nothing connects, and no key is made, before
 * Replace.
 */
@Composable
private fun Replace(r: Replacing, replace: () -> Unit, keep: () -> Unit) {
    val p = palette
    val name = r.old.name
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().border(1.dp, p.lineStrong, Shapes.control).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Replace $name?", style = Type.name, color = p.text)
        Text("This phone is already paired with $name, at ${shownAddress(r.old.url)}.", style = Type.small, color = p.textMuted)
        Text("This link pairs with another bridge under the same name, at ${shownAddress(r.link.url)}.", style = Type.small, color = p.textMuted)
        Text("Replacing ends the pairing with ${shownAddress(r.old.url)} on this phone, and what it shows of it.", style = Type.small, color = p.textMuted)
        PrimaryButton("Replace", onClick = replace)
        TextLink("Keep $name", keep)
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
