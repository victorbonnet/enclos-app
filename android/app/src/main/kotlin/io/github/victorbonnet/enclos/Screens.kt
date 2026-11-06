package io.github.victorbonnet.enclos

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.produceState
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
import io.github.victorbonnet.enclos.ui.SystemLine
import io.github.victorbonnet.enclos.ui.TextLink
import io.github.victorbonnet.enclos.ui.Type
import io.github.victorbonnet.enclos.ui.Motion
import io.github.victorbonnet.enclos.ui.UnreadBadge
import io.github.victorbonnet.enclos.ui.Files
import io.github.victorbonnet.enclos.ui.ToolSteps
import io.github.victorbonnet.enclos.ui.WorkingBubble
import io.github.victorbonnet.enclos.ui.WorkingWave
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

/**
 * Every agent of every host, the one with the newest conversation first:
 * its row opens its Home; under it, its conversations with something
 * unread, or all of them once unfolded, newest first. One with no message
 * yet is not listed.
 */
@Composable
fun InboxScreen(model: AppModel, open: (String) -> Unit, newConversation: () -> Unit, pair: () -> Unit) {
    val sorted = model.conversations.filter(model::listed)
        .sortedByDescending { c -> model.lines[c.id]?.lastOrNull()?.time ?: 0L }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf<InboxFilter>(InboxFilter.All) }
    val found = search(sorted, { model.lines[it].orEmpty() }, model::unread, query, filter)
    val narrowed = query.isNotBlank() || filter != InboxFilter.All
    val agents = byAgent(sorted, found, model.unfolded.toSet(), model::unread, narrowed)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Unread only sits on the title's line: in `accent` while on.
            val unreadOnly = filter == InboxFilter.Unread
            LargeTitle("Inbox", action = if (model.paired.isEmpty() || sorted.isEmpty()) null else {
                {
                    IconButton(
                        if (unreadOnly) Glyphs.unreadOn else Glyphs.unreadOff,
                        if (unreadOnly) "Show all conversations" else "Show unread only",
                        { filter = if (unreadOnly) InboxFilter.All else InboxFilter.Unread },
                        tint = if (unreadOnly) palette.accent else palette.textMuted,
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
                        // An agent that moves to the top slides there, its conversations with it.
                        agents.forEach { entry ->
                            val unfolded = entry.address in model.unfolded
                            item(key = "agent:${entry.address}") {
                                Arriving {
                                    val fold = { if (unfolded) model.unfolded.remove(entry.address) else model.unfolded.add(entry.address) }
                                    // Its Home; with none, its conversations.
                                    AgentRow(model, entry, query) { entry.home?.let { open(it.id) } ?: fold() }
                                }
                            }
                            items(entry.under, key = { it.conversation.id }) { f ->
                                Arriving { ConversationRow(model, f.conversation, f.match, query) { open(f.conversation.id) } }
                            }
                            // A search or filter shows everything it found: nothing to fold.
                            val hidden = entry.others - entry.under.size
                            if (!narrowed && (unfolded || hidden > 0)) {
                                item(key = "more:${entry.address}") {
                                    Arriving {
                                        MoreRow(entry.address.substringAfterLast('/'), hidden, entry.under.size, unfolded) {
                                            if (unfolded) model.unfolded.remove(entry.address) else model.unfolded.add(entry.address)
                                        }
                                    }
                                }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
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
 * A conversation under its agent in the inbox, starting where the agent's
 * name does: its name ("No name yet" while it has none), the time, the
 * last message or the one the search found (or, while the agent works on
 * it, the wave and what it does), and the unread count.
 */
@Composable
private fun ConversationRow(model: AppModel, c: Conversation, match: Line?, query: String, onClick: () -> Unit) {
    val p = palette
    // The last message, not a status line (gateway online) when there is anything else.
    val all = model.lines[c.id].orEmpty()
    val last = all.lastOrNull { !it.status } ?: all.lastOrNull()
    val unread = model.unread(c.id)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(start = 74.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        c.title?.let { highlighted(it, query, p.accent) } ?: AnnotatedString("No name yet"),
                        style = Type.name,
                        color = if (c.title != null) p.text else p.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (last != null) {
                        Text(Times.inList(last.time), style = Type.code, color = if (unread > 0) p.accent else p.textFaint, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                if (last?.kind == "notify" && match == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Glyph(Glyphs.clock, p.accent, size = 14.dp)
                        Text(ON_ITS_OWN, style = Type.meta, color = p.accent)
                    }
                }
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (match == null && model.working(c.id)) {
                        Doing(model.doing(c.id) ?: "working", Modifier.weight(1f))
                    } else {
                        // The message the search found, else the last; the words, without markdown.
                        Preview(match ?: last, match != null, query, unread > 0, "No message yet", Modifier.weight(1f))
                    }
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

/** A message in a list: its words without markdown on two lines at most, the person's prefixed "You:"; [found] words in `accent`. */
@Composable
private fun Preview(shown: Line?, found: Boolean, query: String, unread: Boolean, none: String, modifier: Modifier) {
    val p = palette
    val words = when {
        shown == null -> none
        shown.from == "agent" -> remember(shown.text) { words(shown) }
        else -> "You: ${shown.text}"
    }
    Text(
        if (found) highlighted(words, query, p.accent) else AnnotatedString(words),
        style = Type.small,
        color = if (unread) p.text else p.textMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The agent at work, in a list: the wave, then what it does, in `accent`. */
@Composable
private fun Doing(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkingWave(square = 4.dp, lift = 3.dp)
        Text(text, style = Type.small, color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An agent in the inbox: its avatar (pulsing while it works), its name,
 * the time of its newest message, its Home's last message (or the one a
 * search found) or, while it works, the wave and what it does and where,
 * and its Home's unread count. Tapping it opens its Home; its other
 * conversations unfold from the [MoreRow] that ends its group.
 */
@Composable
private fun AgentRow(model: AppModel, entry: AgentEntry, query: String, onOpen: () -> Unit) {
    val p = palette
    val host = entry.address.substringBefore('/')
    val name = entry.address.substringAfterLast('/')
    val home = entry.home
    val all = home?.let { model.lines[it.id] }.orEmpty()
    val last = all.lastOrNull { !it.status } ?: all.lastOrNull()
    val unread = home?.let { model.unread(it.id) } ?: 0
    val working = model.workingOn(entry.address)
    val newest = model.lastAt(entry.address)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (home != null) "Open $name's Home" else null, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AgentAvatar(name, live = model.connected(host), working = working.isNotEmpty())
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (newest != null) {
                        Text(Times.inList(newest), style = Type.code, color = if (unread > 0) p.accent else p.textFaint, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val at = working.firstOrNull()
                    if (at != null && entry.homeMatch == null) {
                        val where = when {
                            working.size > 1 -> " · in ${working.size} conversations"
                            at.home -> ""
                            else -> " · in ${at.title ?: "a new conversation"}"
                        }
                        Doing((model.doing(at.id) ?: "working") + where, Modifier.weight(1f))
                    } else {
                        Preview(
                            entry.homeMatch ?: last,
                            entry.homeMatch != null,
                            query,
                            unread > 0,
                            if (home != null) "Cron jobs and the agent's notices come here." else "${entry.others} conversations",
                            Modifier.weight(1f),
                        )
                    }
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
 * The end of an agent's group, starting where its conversations do, in
 * `accent`: what is folded away and a chevron pointing down (`17
 * conversations`, `15 more conversations` when some show already), or
 * `Show less` and the chevron pointing up once unfolded. Tapping it unfolds
 * or folds them.
 */
@Composable
private fun MoreRow(name: String, hidden: Int, shown: Int, unfolded: Boolean, onClick: () -> Unit) {
    val p = palette
    val turn by animateFloatAsState(if (unfolded) -90f else 90f, Motion.change(), label = "chevron")
    val text = when {
        unfolded -> "Show less"
        shown > 0 -> "$hidden more ${if (hidden == 1) "conversation" else "conversations"}"
        else -> "$hidden ${if (hidden == 1) "conversation" else "conversations"}"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(
                    onClickLabel = if (unfolded) "Fold $name's conversations" else "Show $name's conversations",
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(start = 74.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text, style = Type.label, color = p.accent)
            Box(Modifier.rotate(turn)) { Glyph(Glyphs.chevron, p.accent, size = 18.dp) }
        }
        Hairline()
    }
}

// ---------- Agents ----------

/**
 * The agents at work first, each with what it does and where (tap one to
 * open that conversation); then hosts, their sandboxes and their agents,
 * whatever the runtime, each working or with when it was last active; tap
 * an agent to start a conversation.
 */
@Composable
fun AgentsScreen(model: AppModel, start: (Agent) -> Unit, open: (String) -> Unit, pair: () -> Unit) {
    val p = palette
    // "5 min ago" stays true while the screen shows.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val busy = model.agents.mapNotNull { a -> model.workingOn(a).takeIf { it.isNotEmpty() }?.let { a to it } }
    Column(Modifier.fillMaxSize()) {
        // Pairing another host sits on the title's line: no bar above it.
        LargeTitle("Agents") { IconButton(Glyphs.qr, "Pair a host", pair) }
        if (model.paired.isEmpty()) {
            Empty("No host yet.", "On a host, run enclos app serve, then enclos app pair, and scan its code.", "Pair a host →", pair)
            return
        }
        LazyColumn(Modifier.weight(1f)) {
            if (busy.isNotEmpty()) {
                item(key = "working") {
                    Arriving {
                        Column {
                            Hairline()
                            Text(
                                "Working now · ${busy.size}",
                                style = Type.label,
                                color = p.text,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                            )
                        }
                    }
                }
                items(busy, key = { "working-${it.first.address}" }) { (agent, on) ->
                    Arriving { WorkingRow(model, agent, on) { open(on.first().id) } }
                }
                item(key = "working-end") { Arriving { Gap(8.dp) } }
            }
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
                        val working = busy.any { it.first == agent }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClickLabel = "New conversation", onClick = { start(agent) })
                                .padding(start = 34.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AgentAvatar(agent.agent, live, small = true, working = working)
                            Column(Modifier.weight(1f)) {
                                Text(agent.agent, style = Type.name.copy(fontSize = 15.sp), color = p.text)
                                // Working, or when it last sent a message; its address before its first.
                                Crossfade(working, animationSpec = Motion.change(), label = "activity") { on ->
                                    if (on) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("working", style = Type.small, color = p.accent)
                                            WorkingWave(square = 4.dp, lift = 3.dp)
                                        }
                                    } else {
                                        val last = model.lastActive(agent)
                                        if (last != null) {
                                            Text("active ${Times.ago(last, now)}", style = Type.small, color = p.textMuted)
                                        } else {
                                            Text(agent.address, style = Type.code, color = p.textFaint)
                                        }
                                    }
                                }
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

/**
 * An agent at work, in Agents: its avatar pulsing, its name and where it
 * works (the conversation, or how many), the wave and the tool it runs.
 * Tapping it opens the conversation with the newest message.
 */
@Composable
private fun WorkingRow(model: AppModel, agent: Agent, on: List<Conversation>, onClick: () -> Unit) {
    val p = palette
    val first = on.first()
    val where = if (on.size > 1) {
        "in ${on.size} conversations"
    } else {
        "in ${first.title ?: if (first.home) "Home" else "a new conversation"}"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open the conversation", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AgentAvatar(agent.agent, model.connected(agent.host), working = true)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(agent.agent, style = Type.name, color = p.text, maxLines = 1)
                Text(
                    where,
                    style = Type.small,
                    color = p.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(bottom = 1.dp),
                )
            }
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkingWave(square = 4.dp, lift = 3.dp)
                Text(
                    model.doing(first.id) ?: "working",
                    style = Type.small,
                    color = p.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Glyph(Glyphs.chevron, p.textMuted, size = 20.dp)
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
    // What the person writes, kept when they leave and come back.
    val draft = model.unsent[id].orEmpty()
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
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                item(key = line.seq) {
                    // The runtime about itself: a quiet line, not a bubble.
                    if (line.status) {
                        Arriving { StatusLine(line.text, Times.clock(line.time)) }
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
        // The agent's commands that match what is typed after `/`; picking one puts it in the field.
        val known = model.commands["${conversation.host}/${conversation.agent}"].orEmpty()
        val offered = remember(known, draft) { suggest(known, draft) }
        CommandSuggestions(offered, draft.drop(1)) { model.unsent[id] = picked(it) }
        // Not connected: write, but nothing goes until the host is back.
        Composer(
            draft,
            { if (it.isEmpty()) model.unsent.remove(id) else model.unsent[id] = it },
            "Message $agent",
            canSend = live,
            notice = if (live) null else "Not connected to ${conversation.host}: send once it's back.",
        ) {
            // Cleared once it went; else it stays, to send again.
            model.send(conversation, draft.trim())
        }
    }
}

/**
 * The agent's commands to pick from as the person types `/`, over the
 * field, on `surface`: each `/name` in mono with the part [typed] in
 * `accent`, what follows it faint, and what it does on one line. At most
 * about four and a half rows show; the rest scroll. It opens and closes
 * with the list.
 */
@Composable
private fun CommandSuggestions(offered: List<AgentCommand>, typed: String, onPick: (AgentCommand) -> Unit) {
    val p = palette
    // Kept while it closes, so the rows do not vanish before it does.
    var shown by remember { mutableStateOf(offered) }
    if (offered.isNotEmpty()) shown = offered
    AnimatedVisibility(
        offered.isNotEmpty(),
        enter = fadeIn(Motion.enter(Motion.SHORT)) + expandVertically(Motion.enter(Motion.SHORT), expandFrom = Alignment.Bottom),
        exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Bottom),
    ) {
        Column(Modifier.fillMaxWidth().background(p.surface)) {
            Hairline()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(shown, key = { it.name }) { c ->
                    Column(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Use /${c.name}") { onPick(c) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                highlighted("/${c.name}", typed, p.accent),
                                style = Type.code.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                color = p.text,
                                maxLines = 1,
                            )
                            c.args?.let {
                                Text(it, style = Type.code, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (c.description.isNotBlank()) {
                            Text(c.description, style = Type.small, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
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

    /**
     * How long ago [seconds] was at [now], in the largest whole unit:
     * `just now`, `5 min ago`, `3 hours ago`, `yesterday`, `2 days ago`,
     * `3 weeks ago`, `4 months ago`, `2 years ago`.
     */
    fun ago(seconds: Long, now: Long): String {
        val s = (now - seconds).coerceAtLeast(0)
        fun n(count: Long, unit: String) = "$count $unit${if (count == 1L) "" else "s"} ago"
        val day = 86_400L
        return when {
            s < 60 -> "just now"
            s < 3_600 -> "${s / 60} min ago"
            s < day -> n(s / 3_600, "hour")
            s < 2 * day -> "yesterday"
            s < 7 * day -> n(s / day, "day")
            s < 30 * day -> n(s / (7 * day), "week")
            s < 365 * day -> n(s / (30 * day), "month")
            else -> n(s / (365 * day), "year")
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
