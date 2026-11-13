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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import io.github.victorbonnet.enclos.ui.ThemeChoice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.client.android.Intents
import io.github.victorbonnet.enclos.core.coreVersion
import io.github.victorbonnet.enclos.ui.AgentAvatar
import io.github.victorbonnet.enclos.ui.AgentInitial
import io.github.victorbonnet.enclos.ui.SandboxMark
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.ActionButton
import io.github.victorbonnet.enclos.ui.AppSwitch
import io.github.victorbonnet.enclos.ui.Bubble
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.ConnectionDot
import io.github.victorbonnet.enclos.ui.Corners
import io.github.victorbonnet.enclos.ui.DaySeparator
import io.github.victorbonnet.enclos.ui.Fab
import io.github.victorbonnet.enclos.ui.FormField
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
import io.github.victorbonnet.enclos.ui.WorkingWave
import io.github.victorbonnet.enclos.ui.palette
import kotlinx.coroutines.delay

/** The header of a message an agent sent on its own, such as a cron job's: the protocol does not say which job. */
private const val ON_ITS_OWN = "on its own"


// ---------- Settings: sections and rows ----------

/**
 * One section of Settings: its name, small and faint above a hairline,
 * then its rows, each ending with a hairline of its own.
 */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = Type.meta.copy(letterSpacing = 0.08.em),
            color = palette.textFaint,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp).semantics { heading() },
        )
        Hairline()
        content()
    }
}

/** A row of a section: its content padded as every row is, then a hairline. */
@Composable
internal fun SettingRow(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().then(modifier)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), content = content)
        Hairline()
    }
}

/**
 * The theme, in Settings: the system's light or dark (the default), or the
 * one the person chose, which the app then keeps; and the way back to the
 * system's.
 */
@Composable
private fun ThemeSetting(model: AppModel) {
    val p = palette
    val choice = model.theme.value
    SettingRow {
        Text("Theme", style = Type.name, color = p.text)
        Gap(10.dp)
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

/**
 * The app lock, in Settings: a switch beside what it does. Either way
 * asks the person to unlock first: on, so a lock that cannot be opened is
 * never set; off, so someone who picks the phone up in the minute after it
 * was put down cannot turn it off. It needs a screen lock on the phone,
 * and says so when there is none. [ask] shows the prompt.
 */
@Composable
private fun LockSetting(lock: LockState, ask: Ask) {
    val p = palette
    val context = androidx.compose.ui.platform.LocalContext.current
    val on = lock.on.value
    var said by remember { mutableStateOf<String?>(null) }
    val turn = { want: Boolean ->
        when {
            want == on -> Unit
            !want -> ask("Turn off the app lock") { ok, why ->
                if (ok) lock.set(false)
                said = if (ok) null else why ?: "Not turned off."
            }
            !AppLock.available(context) -> said = "Set a screen lock on this phone first: a PIN, a pattern or a password."
            else -> ask("Turn on the app lock") { ok, why ->
                if (ok) lock.set(true)
                said = if (ok) null else why ?: "Not turned on."
            }
        }
    }
    SettingRow(Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { turn(it) })) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("App lock", style = Type.name, color = p.text)
                Gap(4.dp)
                Text(
                    said ?: if (on) {
                        "Asks for your fingerprint, face or the phone's screen lock when the app opens, and when it comes back after a minute away."
                    } else if (lock.dropped.value) {
                        "Turned off: this phone no longer has a screen lock. Set one, then turn the lock on again."
                    } else {
                        "Ask for your fingerprint, face or the phone's screen lock when the app opens."
                    },
                    style = Type.small,
                    color = p.textMuted,
                )
            }
            // The row is the switch: this one only shows it.
            AppSwitch(checked = on, onCheckedChange = null)
        }
    }
}

/**
 * All the app shows while it is locked: the mark, what to do, and Unlock,
 * which asks again (the prompt shows on its own as the app starts or comes
 * back). Nothing else of the app is composed meanwhile, and Back leaves
 * the app.
 */
@Composable
fun LockScreen(lock: LockState, unlock: () -> Unit, leave: () -> Unit) {
    val p = palette
    androidx.activity.compose.BackHandler(onBack = leave)
    Box(
        Modifier.fillMaxSize().background(p.bg).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Corners(p.accent, Modifier.size(72.dp), stroke = 3.dp)
                Box(Modifier.size(14.dp).background(p.accent))
            }
            Gap(24.dp)
            Text("Enclos is locked", style = Type.heading, color = p.text)
            Gap(8.dp)
            Text(
                lock.said.value ?: "Unlock with your fingerprint, your face or the phone's screen lock.",
                style = Type.small,
                color = p.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Gap(24.dp)
            PrimaryButton("Unlock", onClick = unlock)
        }
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
fun InboxScreen(
    model: AppModel,
    query: String,
    unreadOnly: Boolean,
    archivedOnly: Boolean,
    open: (String) -> Unit,
    start: (Agent) -> Unit,
    openAgent: (Agent) -> Unit,
    openRoutines: (Agent) -> Unit,
    pair: () -> Unit,
    openApproval: (String) -> Unit,
) {
    // "5 min ago" stays true while the screen shows.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val sorted = inboxConversations(model.conversations.filter(model::listed), archivedOnly)
        .sortedByDescending { c -> model.lines[c.id]?.lastOrNull()?.time ?: 0L }
    val filter = if (unreadOnly) InboxFilter.Unread else InboxFilter.All
    // What search reads, taken here on the main thread: lists that are
    // replaced, never changed, when a message arrives.
    val said = sorted.associate { it.id to model.lines[it.id].orEmpty() }
    val unread = sorted.associate { it.id to model.unread(it.id) }
    // The agents' names as shown (display names too), found by search.
    val names = sorted.associate { c ->
        c.id to if (c.group) c.members.joinToString(" ") { model.agentLabel("${c.host}/$it") } else model.agentLabel("${c.host}/${c.agent}")
    }
    val listed = remember(sorted, said, unread, filter, archivedOnly) {
        search(sorted, { said[it].orEmpty() }, { unread[it] ?: 0 }, "", filter, archived = archivedOnly, agentNames = { names[it.id].orEmpty() })
    }
    // A query reads every message: searched off the main thread, each
    // message folded once (model.folds); the last result stays meanwhile.
    var searched by remember { mutableStateOf<List<Found>?>(null) }
    LaunchedEffect(sorted, said, unread, names, query, filter, archivedOnly) {
        searched = if (query.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                search(sorted, { said[it].orEmpty() }, { unread[it] ?: 0 }, query, filter, model.folds::of, archived = archivedOnly, agentNames = { names[it.id].orEmpty() })
            }
        }
    }
    val found = if (query.isBlank()) listed else searched ?: listed
    val requests = model.approvals.values.filter { a -> !archivedOnly && a.attention && (query.isBlank() || "${a.title} ${a.sandbox} ${a.host}".contains(query, ignoreCase = true)) }.sortedByDescending { it.time }
    val narrowed = archivedOnly || query.isNotBlank() || filter != InboxFilter.All
    // Groups have their own section; the agents' trees hold one-to-one conversations.
    val groupsFound = found.filter { it.conversation.group }
    val agents = byAgent(sorted.filter { !it.group }, found.filter { !it.conversation.group }, model.unfolded.toSet(), model::unread, narrowed)
    // A section folded away stays so, except under a search: that shows all it finds.
    val shown = { s: InboxSection -> query.isNotBlank() || s.name !in model.folded }
    val unreadIn = { group: Boolean -> sorted.filter { it.group == group }.sumOf { model.unread(it.id) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                model.paired.isEmpty() -> Empty(
                    "No host yet.",
                    "On a host, run enclos app serve, then enclos app pair, and scan its code.",
                    "Pair a host →",
                    pair,
                )
                sorted.isEmpty() && requests.isEmpty() -> Empty(
                    if (archivedOnly) "No archived conversation." else "No active conversation yet.",
                    if (archivedOnly) "Archived conversations stay here until you unarchive them." else "Start one below, or find archived conversations with the archive filter above.",
                )
                else -> {
                    LazyColumn(Modifier.weight(1f)) {
                        if (archivedOnly) item(key = "archive-view") {
                            Text("Archived conversations", style = Type.meta, color = palette.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                        if (requests.isNotEmpty()) {
                            val requestsShown = shown(InboxSection.Requests)
                            item(key = "policy-requests") {
                                FoldHeading("Sandbox requests", requestsShown, if (requestsShown) 0 else requests.size, query.isBlank()) { model.fold(InboxSection.Requests.name) }
                            }
                            if (requestsShown) {
                                items(requests, key = { "policy:${it.key}" }) { a ->
                                    Arriving { PolicyRequestRow(a, agentLabel = model::agentLabel) { openApproval(a.key) } }
                                }
                                item(key = "policy-gap") { Gap(8.dp) }
                            }
                        }
                        val groupsShown = shown(InboxSection.Groups)
                        val agentsShown = shown(InboxSection.Agents)
                        if (groupsFound.isNotEmpty()) {
                            item(key = "groups") {
                                FoldHeading("Groups", groupsShown, if (groupsShown) 0 else unreadIn(true), query.isBlank()) { model.fold(InboxSection.Groups.name) }
                            }
                            if (groupsShown) {
                                items(groupsFound, key = { "group:${it.conversation.id}" }) { f ->
                                    Arriving { GroupRow(model, f.conversation, f.match, query, now) { open(f.conversation.id) } }
                                }
                                item(key = "groups-gap") { Gap(6.dp) }
                            }
                        }
                        if (agents.isNotEmpty()) {
                            item(key = "agents") {
                                FoldHeading("Agents", agentsShown, if (agentsShown) 0 else unreadIn(false), query.isBlank()) { model.fold(InboxSection.Agents.name) }
                            }
                        }
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
                        if (agentsShown) agents.forEach { entry ->
                            val expanded = entry.address in model.unfolded
                            val rows = entry.under
                            // Searching shows everything found: no "more" then.
                            val hidden = entry.others - rows.size
                            val more = !narrowed && (hidden > 0 || (expanded && entry.others > RECENT))
                            val toggle: () -> Unit = { if (expanded) model.unfolded.remove(entry.address) else model.unfolded.add(entry.address) }
                            val agent = model.agents.firstOrNull { it.address == entry.address }
                            item(key = "agent:${entry.address}") {
                                Arriving {
                                    AgentHead(
                                        model,
                                        entry,
                                        query,
                                        now,
                                        archivedOnly,
                                        branches = rows.isNotEmpty() || more,
                                        menu = AgentMenu(
                                            start = agent?.let { { start(it) } },
                                            all = if (more) toggle else null,
                                            expanded = expanded,
                                            about = agent?.let { { openAgent(it) } },
                                            routines = agent?.takeIf { it.offersRoutines }?.let { { openRoutines(it) } },
                                        ),
                                        onOpen = entry.home?.let { home -> { open(home.id) } } ?: if (more) toggle else null,
                                    )
                                }
                            }
                            items(rows.size, key = { rows[it].conversation.id }) { i ->
                                val f = rows[i]
                                Arriving {
                                    TreeRow(model, f.conversation, f.match, query, now, last = i == rows.lastIndex && !more) { open(f.conversation.id) }
                                }
                            }
                            if (more) {
                                item(key = "more:${entry.address}") {
                                    Arriving {
                                        MoreBranch(if (expanded) "Show less" else "$hidden more ${if (hidden == 1) "conversation" else "conversations"}", expanded, toggle)
                                    }
                                }
                            }
                            item(key = "gap:${entry.address}") { Gap(14.dp) }
                        }
                        // Room for the search and compose bar over the list's end.
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * A group in the conversations view: its members' avatars, its name, the
 * wave while a member answers or how long ago its last message was, and
 * a chevron; under the name, its last message with who said it (or the
 * message a search found), and its unread count.
 */
@Composable
private fun GroupRow(model: AppModel, c: Conversation, match: Line?, query: String, now: Long, onClick: () -> Unit) {
    val p = palette
    val handles = c.members.map { it.substringAfterLast('/') }
    val all = model.lines[c.id].orEmpty()
    val last = match ?: (all.lastOrNull { !it.status } ?: all.lastOrNull())
    val unread = model.unread(c.id)
    val working = model.groups[c.id]?.state == "working"
    ConversationActions(model, c, onClick) { action ->
        Row(
            Modifier.fillMaxWidth().then(action).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MemberStack(handles, side = 32.dp, label = { model.agentLabel("${c.host}/${c.agent}/$it") }) { PhotoKey(c.host, "${c.agent}/$it") }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.title ?: c.members.joinToString(", ") { model.agentLabel("${c.host}/$it") }, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when {
                        working -> WorkingWave(square = 4.dp, lift = 3.dp)
                        last != null -> Text(Times.ago(last.time, now), style = Type.small, color = if (unread > 0) p.accent else p.textFaint)
                    }
                    Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val text = when {
                        last == null -> "${handles.size} agents: ${handles.joinToString(" ") { "@$it" }}"
                        last.from != "agent" -> "You: ${last.text}"
                        else -> (last.agent?.let { "${model.agentLabel(it)}: " } ?: "") + remember(last.text) { words(last) }
                    }
                    Text(
                        if (match != null) highlighted(text, query, p.accent) else AnnotatedString(text),
                        style = Type.small,
                        color = if (unread > 0) p.text else p.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread > 0) UnreadBadge(unread)
                }
            }
        }
    }
}

/** Where the tree's trunk runs, under the agent's initial (16dp in, 32dp wide), and where its branches end. */
private val TRUNK = 32.dp
private val BRANCH = 18.dp

/** Draws the tree beside a conversation: the trunk down (to the middle on the [last] one) and a branch to it. */
private fun Modifier.branch(color: Color, last: Boolean) = drawBehind {
    val x = TRUNK.toPx()
    val mid = size.height / 2
    val w = 1.dp.toPx()
    drawLine(color, Offset(x, 0f), Offset(x, if (last) mid else size.height), w)
    drawLine(color, Offset(x, mid), Offset(x + BRANCH.toPx(), mid), w)
}

/**
 * An agent in the inbox: its initial (pulsing while it works), its name,
 * then working (the wave) or how long ago it last said something, and a
 * chevron; under the name, its Home's last message on one line (or, while
 * it works, what it does), and Home's unread count. Tapping it opens its
 * Home; a long press opens its [menu]. With conversations under it, the
 * tree's trunk starts below the initial.
 */
@Composable
private fun AgentHead(model: AppModel, entry: AgentEntry, query: String, now: Long, archived: Boolean, branches: Boolean, menu: AgentMenu, onOpen: (() -> Unit)?) {
    val p = palette
    val name = model.agentLabel(entry.address)
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val working = model.workingOn(entry.address).filter { it.archived == archived }
    val home = entry.home
    val homeLines = home?.let { model.lines[it.id] }.orEmpty()
    val last = homeLines.lastOrNull { !it.status } ?: homeLines.lastOrNull()
    val unread = home?.let { model.unread(it.id) } ?: 0
    val newest = model.lastAt(entry.address, archived)
    val line = p.lineStrong
    val hasMenu = home != null || menu.start != null || menu.all != null || menu.about != null || menu.routines != null
    val showMenu: () -> Unit = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }
    val actions = when {
        onOpen != null -> Modifier.combinedClickable(
            onClickLabel = if (home != null) "Open $name's Home" else "Show more or fewer conversations",
            onLongClickLabel = if (hasMenu) "More for $name" else null,
            onLongClick = if (hasMenu) showMenu else null,
            onClick = onOpen,
        )
        hasMenu -> Modifier.pointerInput(entry.address) { detectTapGestures(onLongPress = { showMenu() }) }
            .semantics { heading(); onLongClick("More for $name") { showMenu(); true } }
        else -> Modifier.semantics { heading() }
    }
    Row(
        Modifier.fillMaxWidth()
            .then(actions)
            .then(
                if (branches) {
                    Modifier.drawBehind {
                        // The trunk, from under the initial down into the first conversation.
                        val x = TRUNK.toPx()
                        drawLine(line, Offset(x, (12.dp + 32.dp + 4.dp).toPx()), Offset(x, size.height), 1.dp.toPx())
                    }
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AgentInitial(name, working.isNotEmpty(), side = 32.dp, photo = entry.photo)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (working.isNotEmpty()) {
                    WorkingWave(square = 4.dp, lift = 3.dp)
                    Text("working", style = Type.small, color = p.accent)
                } else if (newest != null) {
                    Text(Times.ago(newest, now), style = Type.small, color = if (unread > 0) p.accent else p.textFaint)
                }
                if (onOpen != null) Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val at = working.firstOrNull()
                val shown = entry.homeMatch ?: last
                val text = when {
                    at != null && entry.homeMatch == null -> model.doing(at.id) ?: "working"
                    shown == null -> if (home != null) "Cron jobs and the agent's notices come here." else ""
                    shown.from == "agent" -> remember(shown.text) { words(shown) }
                    else -> "You: ${shown.text}"
                }
                Text(
                    if (entry.homeMatch != null) highlighted(text, query, p.accent) else AnnotatedString(text),
                    style = Type.small,
                    color = if (unread > 0) p.text else p.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) UnreadBadge(unread)
            }
            // Under the name, as the row's own options; here, it takes no room in the row.
            DropdownMenu(menuOpen, { menuOpen = false }, offset = DpOffset((-8).dp, (-4).dp), modifier = Modifier.background(p.bg)) {
                val pick = { action: () -> Unit ->
                    menuOpen = false
                    action()
                }
                menu.start?.let { MenuRow(Glyphs.compose, "New conversation") { pick(it) } }
                menu.all?.let { MenuRow(Glyphs.inbox, if (menu.expanded) "Fewer conversations" else "All conversations") { pick(it) } }
                menu.routines?.let { MenuRow(Glyphs.clock, "Routines") { pick(it) } }
                home?.let { ArchiveMenuItem(model, it) { menuOpen = false } }
                menu.about?.let {
                    Hairline()
                    MenuRow(Glyphs.agents, "About $name") { pick(it) }
                }
            }
            home?.let { ArchiveFeedback(model, it, Modifier.padding(top = 4.dp)) }
        }
    }
}

/**
 * What a long press on an agent in the inbox offers: a new conversation
 * with it ([start]; none while its host does not list it), all its
 * conversations or fewer ([all], when some are folded away), and its page
 * ([about]), and routines when the runtime supports them ([routines]).
 */
class AgentMenu(val start: (() -> Unit)?, val all: (() -> Unit)?, val expanded: Boolean, val about: (() -> Unit)?, val routines: (() -> Unit)? = null)

@Composable
private fun MenuRow(glyph: ImageVector, text: String, onClick: () -> Unit) {
    val p = palette
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Glyph(glyph, p.text, size = 20.dp) }
                Text(text, style = Type.name, color = p.text)
            }
        },
        onClick = onClick,
    )
}

/** Home stays active; a Home archived by an older version can still be restored. */
@Composable
private fun ArchiveMenuItem(model: AppModel, c: Conversation, dismiss: () -> Unit) {
    if (c.home && !c.archived) return
    val p = palette
    val unavailable = model.archiveUnavailable(c)
    DropdownMenuItem(
        enabled = unavailable == null,
        leadingIcon = { Glyph(Glyphs.archive, if (unavailable == null) p.text else p.textFaint, size = 20.dp) },
        text = {
            Column {
                Text(if (c.archived) "Unarchive" else "Archive", style = Type.name, color = if (unavailable == null) p.text else p.textFaint)
                unavailable?.let { Text(it, style = Type.small, color = p.textFaint) }
                model.archiveError(c)?.let { Text(it, style = Type.small, color = p.textMuted) }
            }
        },
        onClick = { if (model.archive(c, !c.archived)) dismiss() },
    )
}

@Composable
private fun ArchiveFeedback(model: AppModel, c: Conversation, modifier: Modifier = Modifier) {
    val text = if (model.archiving(c)) "Saving archive state…" else model.archiveError(c)
    text?.let { Text(it, style = Type.small, color = palette.textMuted, modifier = modifier) }
}

/** A long press offers archive without changing what a normal tap opens. */
@Composable
private fun ConversationActions(model: AppModel, c: Conversation, open: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Column {
        Box {
            content(Modifier.combinedClickable(
                onClick = open,
                onLongClickLabel = "Conversation actions",
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            ))
            DropdownMenu(menu, { menu = false }, modifier = Modifier.background(palette.bg)) {
                ArchiveMenuItem(model, c) { menu = false }
            }
        }
        ArchiveFeedback(model, c, Modifier.padding(start = 60.dp, end = 16.dp, bottom = 8.dp))
    }
}

/**
 * A section's heading in the conversations view (or a host's, in the
 * agents view), which folds it away or back: what it leads with ([lead],
 * such as a host's connection), its name, the unread count of what it
 * folds away, and a chevron
 * pointing down while open; no line under it. Under a search ([foldable]
 * false) it only names the section.
 */
@Composable
internal fun FoldHeading(
    text: String,
    open: Boolean,
    unread: Int,
    foldable: Boolean,
    lead: (@Composable () -> Unit)? = null,
    toggle: () -> Unit,
) {
    val p = palette
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.change(Motion.MEDIUM), label = "fold")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (foldable) {
                        Modifier.clickable(onClickLabel = if (open) "Fold $text away" else "Show $text", role = Role.Button, onClick = toggle)
                    } else {
                        Modifier
                    },
                )
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                .semantics(mergeDescendants = true) {
                    heading()
                    stateDescription = if (open) "Shown" else "Folded"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lead?.invoke()
            Text(text, style = Type.meta.copy(letterSpacing = 0.04.em), color = p.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            if (unread > 0) UnreadBadge(unread)
            if (foldable) Box(Modifier.rotate(turn)) { Glyph(Glyphs.chevron, p.textMuted, size = 16.dp) }
        }
    }
}

/**
 * A conversation on the agent's tree, on one line: a small square in
 * `accent` while it has something unread, its name ("No name yet" while it
 * has none), the wave while the agent works on it or how long ago its last
 * message was, and a chevron. Found by a message, that message shows under
 * it, its words in `accent`.
 */
@Composable
private fun TreeRow(model: AppModel, c: Conversation, match: Line?, query: String, now: Long, last: Boolean, onClick: () -> Unit) {
    val p = palette
    val unread = model.unread(c.id)
    val latest = model.lines[c.id]?.lastOrNull()
    ConversationActions(model, c, onClick) { action ->
        Column(
            Modifier.fillMaxWidth().then(action).branch(p.lineStrong, last)
                .padding(start = 60.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unread > 0) Box(Modifier.size(7.dp).background(p.accent).semantics { contentDescription = "$unread unread" })
                Text(
                    c.title?.let { highlighted(it, query, p.accent) } ?: AnnotatedString("No name yet"),
                    style = Type.name.copy(fontSize = 15.sp, fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (c.title != null) p.text else p.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    model.working(c.id) -> WorkingWave(square = 4.dp, lift = 3.dp)
                    latest != null -> Text(Times.ago(latest.time, now), style = Type.small, color = if (unread > 0) p.accent else p.textFaint, maxLines = 1)
                }
                Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
            }
            if (match != null) Preview(match, true, query, false, "", model.statusSource(c), Modifier.padding(top = 2.dp))
        }
    }
}

/** The tree's last branch when more is folded away: "2 more conversations", or Show less, and a chevron. */
@Composable
private fun MoreBranch(text: String, expanded: Boolean, onClick: () -> Unit) {
    val p = palette
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = text, role = Role.Button, onClick = onClick).branch(p.lineStrong, last = true)
            .padding(start = 60.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, style = Type.small, color = p.textMuted)
        Box(Modifier.rotate(if (expanded) -90f else 0f)) { Glyph(Glyphs.chevron, p.textMuted, size = 14.dp) }
    }
}

@Composable
private fun ColumnScope.Empty(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
        Hairline()
        Gap(24.dp)
        Text(title, style = Type.name, color = palette.text)
        Gap(4.dp)
        Text(text, style = Type.small, color = palette.textMuted)
        if (action != null) TextLink(action, onAction)
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
internal fun Choice(label: String, on: Boolean, enabled: Boolean = true, multiple: Boolean = false, wrap: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = palette
    val edge by animateColorAsState(if (!enabled) p.line else if (on) p.accent else p.lineStrong, Motion.change(), label = "edge")
    val text by animateColorAsState(if (!enabled) p.textFaint else if (on) p.accent else p.textMuted, Motion.change(), label = "text")
    val select = if (multiple) Modifier.toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        else Modifier.selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Box(
        modifier.then(if (wrap) Modifier.defaultMinSize(minHeight = 44.dp) else Modifier.height(44.dp)).clip(Shapes.control).border(1.dp, edge, Shapes.control)
            .then(select)
            .padding(horizontal = 14.dp, vertical = if (wrap) 10.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.label, color = text, maxLines = if (wrap) Int.MAX_VALUE else 1) }
}

/** [text] with the words of [query] in `accent`, where search found them: its own rules (case, accents, spaces). */
internal fun highlighted(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    matches(text, query).forEach { range ->
        addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
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
                        Preview(match ?: last, match != null, query, unread > 0, "No message yet", model.statusSource(c), Modifier.weight(1f))
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
private fun Preview(shown: Line?, found: Boolean, query: String, unread: Boolean, none: String, statusFrom: String, modifier: Modifier) {
    val p = palette
    val words = when {
        shown == null -> none
        shown.status -> statusText(statusFrom, shown.text)
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
    val name = model.agentLabel(entry.address)
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
            AgentAvatar(name, live = model.connected(host), working = working.isNotEmpty(), photo = entry.photo)
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
                            home?.let(model::statusSource).orEmpty(),
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
 * Every agent, under its host's heading (its connection and name, which
 * folds the host away and back, kept across starts), then its sandbox's
 * (the Enclos mark, the sandbox, its runtime), each as the conversations view shows it, without
 * its conversations: its initial (pulsing while it works), its name, and
 * working or when it was last active. Tapping one opens its page; tapping
 * a heading, its sandbox's page (`host/sandbox`). [query] keeps those
 * whose address or runtime has every word of it.
 */
@Composable
fun AgentsScreen(model: AppModel, query: String, openAgent: (Agent) -> Unit, openSandbox: (String) -> Unit, pair: () -> Unit, openHost: (String) -> Unit) {
    // "5 min ago" stays true while the screen shows.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    if (model.paired.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Empty("No host yet.", "On a host, run enclos app serve, then enclos app pair, and scan its code.", "Pair a host →", pair)
        }
        return
    }
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    val shown = model.agents.filter { a -> words.all { it in fold("${a.label} ${a.address} ${a.runtime}") } }
    LazyColumn(Modifier.fillMaxSize()) {
        model.paired.forEach { host ->
            val live = model.connected(host.name)
            val its = shown.filter { it.host == host.name }
            // A search shows only the hosts with something found, unfolded.
            if (query.isNotBlank() && its.isEmpty()) return@forEach
            val key = model.hostFold(host.name)
            val open = query.isNotBlank() || key !in model.folded
            item(key = key) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        FoldHeading(host.name, open, 0, query.isBlank(), lead = { ConnectionDot(live) }) { model.fold(key) }
                    }
                    IconButton(Glyphs.chevron, "Open ${host.name}", { openHost(host.name) })
                }
            }
            if (!open) return@forEach
            val leftOut = model.leftOut(host.name)
            if (leftOut > 0) {
                item(key = "$key-clash") {
                    Text(
                        "$leftOut ${if (leftOut == 1) "conversation" else "conversations"} of this host not shown: another paired host has an agent with the same sandbox and name, whose conversations use the same ids.",
                        style = Type.small, color = palette.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (its.isEmpty()) {
                item(key = "$key-none") {
                    Text("No agent yet: the host lists them once connected.", style = Type.small, color = palette.textMuted, modifier = Modifier.padding(16.dp))
                }
            }
            its.groupBy { it.sandbox }.forEach { (sandbox, agents) ->
                item(key = "sandbox-${host.name}-$sandbox") {
                    Arriving {
                        val runtime = agents.first().runtime.replaceFirstChar { it.uppercase() }
                        ListHeading(sandbox, runtime, open = { openSandbox("${host.name}/$sandbox") }, top = 12.dp) { SandboxMark(live, side = 16.dp) }
                    }
                }
                items(agents, key = { "agent-${it.address}" }) { agent ->
                    Arriving { AgentListRow(model, agent, now) { openAgent(agent) } }
                }
            }
            item(key = "$key-gap") { Gap(8.dp) }
        }
        if (shown.isEmpty() && query.isNotBlank()) {
            item {
                Text("No agent matches.", style = Type.small, color = palette.textMuted, modifier = Modifier.padding(16.dp))
            }
        }
        // Room for the search and compose bar over the list's end.
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** An agent, as the conversations view shows it, without its conversations: tapping it opens its page. */
@Composable
internal fun AgentListRow(model: AppModel, agent: Agent, now: Long, onClick: () -> Unit) {
    val p = palette
    val working = model.workingOn(agent)
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${agent.label}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AgentInitial(agent.label, working.isNotEmpty(), side = 32.dp, photo = agent.photoKey)
        Column(Modifier.weight(1f)) {
            Text(agent.label, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (working.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkingWave(square = 4.dp, lift = 3.dp)
                    Text(model.doing(working.first().id) ?: "working", style = Type.small, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val last = model.lastActive(agent)
                Text(if (last != null) "active ${Times.ago(last, now)}" else "no message yet", style = Type.small, color = p.textMuted)
            }
        }
        Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
    }
}

/**
 * A heading of a list, small and faint with no line under it, with what
 * it leads with ([lead], such as a sandbox's mark) and a note at its end;
 * with [open], a chevron after the note, and tapping it opens what it
 * names. [top] is the room above it.
 */
@Composable
internal fun ListHeading(
    text: String,
    note: String? = null,
    open: (() -> Unit)? = null,
    top: androidx.compose.ui.unit.Dp = 28.dp,
    lead: (@Composable () -> Unit)? = null,
) {
    val p = palette
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (open != null) Modifier.clickable(onClickLabel = "Open $text", onClick = open) else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lead?.invoke()
            Text(text, style = Type.meta.copy(letterSpacing = 0.04.em), color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) {
                Text(
                    note,
                    style = Type.meta,
                    color = p.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            if (open != null) {
                if (note == null) Spacer(Modifier.weight(1f))
                Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
            }
        }
    }
}

// ---------- Settings ----------

/**
 * The app's settings, in sections: the paired hosts (each connection,
 * Reconnect, Forget, and pairing another), security (the app lock),
 * appearance (the theme), what is kept on this phone (Clear cache), and
 * the versions. Nothing here acts on a sandbox.
 */
@Composable
fun SettingsScreen(model: AppModel, pair: () -> Unit, ask: Ask, openHost: (String) -> Unit) {
    val p = palette
    var forgetting by remember { mutableStateOf<String?>(null) }
    // A host forgotten without being told: the dialog said it would be.
    var notTold by remember { mutableStateOf<String?>(null) }
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
            // The dialog's words were chosen when it opened: the connection
            // may have ended since. What happened is what counts.
            if (!model.forgetHost(name) && live) notTold = name
            forgetting = null
        }
    }
    notTold?.let { name ->
        AlertDialog(
            onDismissRequest = { notTold = null },
            containerColor = p.surface,
            shape = Shapes.bubble,
            title = { Text("$name was not told", style = Type.heading, color = p.text) },
            text = {
                Text(
                    "The connection ended before Forget. This phone is forgotten here, but $name keeps it listed until you run enclos app unpair there.",
                    style = Type.small,
                    color = p.textMuted,
                )
            },
            confirmButton = { DialogButton("OK", onClick = { notTold = null }) },
        )
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
        LargeTitle("Settings")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("Hosts") {
                model.paired.forEach { host ->
                    val live = model.connected(host.name)
                    SettingRow {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ConnectionDot(live)
                            Text(host.name, style = Type.code.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text,
                                modifier = Modifier.weight(1f).clickable(role = Role.Button) { openHost(host.name) }.padding(vertical = 10.dp))
                            IconButton(Glyphs.chevron, "Open ${host.name}", { openHost(host.name) })
                        }
                        Gap(6.dp)
                        Text(model.status[host.name] ?: "not connected", style = Type.small, color = p.textMuted)
                        model.said[host.name]?.let { Text("The bridge said: $it", style = Type.small, color = p.textMuted) }
                        Text(host.url.removePrefix("https://"), style = Type.code, color = p.textFaint)
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (!live) TextLink("Reconnect") { model.reconnect(host.name) }
                            TextLink("Forget this host") { forgetting = host.name }
                        }
                    }
                }
                // Another host: the row is the action.
                SettingRow(Modifier.clickable(onClickLabel = "Pair a host", onClick = pair)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(Glyphs.qr, p.accent, size = 20.dp)
                        Text("Pair a host", style = Type.label, color = p.accent)
                    }
                }
            }
            Section("Security") { LockSetting(model.lock, ask) }
            Section("Appearance") { ThemeSetting(model) }
            // What the phone keeps: synced again from the hosts when cleared.
            Section("On this phone") {
                SettingRow {
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
            }
            Section("About") {
                SettingRow {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val app = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                    Text("Enclos ${app.orEmpty()}".trim(), style = Type.name, color = p.text)
                    Gap(2.dp)
                    Text("core ${coreVersion()}", style = Type.code, color = p.textFaint)
                }
            }
            Gap(24.dp)
        }
    }
}

// ---------- Conversation ----------

/** One conversation with one agent: messages both ways, and what the agent sent on its own. */
@Composable
fun ConversationScreen(model: AppModel, id: String, back: () -> Unit) {
    val p = palette
    val conversation = model.conversations.firstOrNull { it.id == id }
    if (conversation == null) {
        // A group the bridge would not start: gone, saying why.
        model.refusedGroups[id]?.let { GroupRefused(it, back) }
        return
    }
    val lines = model.lines[id].orEmpty()
    val scope = "${conversation.host}/${conversation.agent}"
    val agent = if (conversation.group) conversation.members.joinToString(", ") { model.agentLabel("${conversation.host}/$it") } else model.agentLabel(scope)
    val live = model.connected(conversation.host)
    // A group's members by handle, and where its discussion is.
    val handles = conversation.members.map { it.substringAfterLast('/') }
    val status = model.groups[id]
    val turnOf = status?.turns?.firstOrNull { it.second == "working" }?.first?.substringAfterLast('/')?.let { h ->
        val name = model.agentLabel("$scope/$h")
        if (name == h) "@$h" else "$name (@$h)"
    }
    val working = if (conversation.group) status?.state == "working" else model.working(id)
    // What the person writes, kept when they leave and come back.
    val draft = model.unsent[id].orEmpty()
    // Why the last message could not go (too long, say): the draft stays.
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
    var options by remember { mutableStateOf(false) }
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
            if (conversation.group) MemberStack(handles, side = 30.dp, label = { model.agentLabel("$scope/$it") }) { PhotoKey(conversation.host, "${conversation.agent}/$it") } else AgentAvatar(agent, live, small = true, photo = PhotoKey(conversation.host, conversation.agent))
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
                    when {
                        conversation.group -> handles.joinToString(" ") { "@$it" } + " · ${conversation.host}/${conversation.agent}"
                        conversation.title != null -> "$agent · ${conversation.host}/${conversation.agent}"
                        else -> "${conversation.host}/${conversation.agent}"
                    },
                    style = Type.code,
                    color = p.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(Glyphs.more, "Conversation actions", { options = true })
                DropdownMenu(options, { options = false }, modifier = Modifier.background(p.bg)) {
                    MenuRow(Glyphs.compose, "Rename") { options = false; renaming = true }
                    ArchiveMenuItem(model, conversation) { options = false }
                }
            }
        }
        Hairline()
        if (conversation.archived) SystemLine("Archived · kept out of Active conversations.")
        if (model.archiving(conversation)) SystemLine("Saving archive state…")
        model.archiveError(conversation)?.let { SystemLine(it) }
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
            if (working) item(key = "working") { Arriving { WorkingBubble(turnOf ?: agent) } }
            // The tools it runs now, above the working bubble; a turn that
            // ended with no answer shows none.
            val running = model.steps[id].orEmpty()
            if (working && running.isNotEmpty()) item(key = "steps-live") { Arriving { ToolSteps(running, live = true, expanded = true) {} } }
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
                        val question = if (line.from == "agent") model.questions.current(conversation, line.question)?.takeIf { it.matchesMessageKind(line.kind) } else null
                        Bubble(
                            text = line.text,
                            time = Times.clock(line.time),
                            outgoing = line.from != "agent",
                            header = if (line.kind == "notify" && !conversation.group) ON_ITS_OWN else null,
                            speaker = line.agent?.let(model::agentLabel),
                            // `host/sandbox/agent`, its host known here: the rest is the member.
                            speakerPhoto = line.agent?.let { PhotoKey(conversation.host, it.removePrefix("${conversation.host}/")) },
                            files = line.attachments.takeIf { it.isNotEmpty() }?.let { attached ->
                                { Files(attached, model.files) { model.fetch(conversation.host, it) } }
                            },
                            content = question?.let { known -> {
                                if (known.approval != null) RuntimeApprovalCard(model.questions, known) else QuestionCard(model.questions, known)
                            } },
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
        if (conversation.group) {
            // Members to address, as the person types `@`.
            val labels = handles.associateWith { model.agentLabel("$scope/$it") }
            val members = remember(handles, draft, labels) { mentionsFor(handles, draft) { labels[it] ?: it } }
            MentionSuggestions(members, { PhotoKey(conversation.host, "${conversation.agent}/$it") }, label = { labels[it] ?: it }) { model.unsent[id] = mentioned(draft, it) }
            if (status != null) GroupStrip(status, turnOf) { model.stopGroup(conversation) }
        } else {
            // The agent's commands that match what is typed after `/`; picking one puts it in the field.
            val known = model.commands["${conversation.host}/${conversation.agent}"].orEmpty()
            val offered = remember(known, draft) { suggest(known, draft) }
            CommandSuggestions(offered, draft.drop(1)) { model.unsent[id] = picked(it) }
        }
        // Not connected: write, but nothing goes until the host is back. A
        // message the bridge did not get is back in the field, saying so; one
        // that could not go (too long) stays, saying why.
        val notSent = model.outgoing.notSent[id]?.let { "Not sent: $it." }
        val away = if (live) null else "Not connected to ${conversation.host}: send once it's back."
        Composer(
            draft,
            {
                unsent = null
                model.outgoing.write(id, it)
            },
            if (conversation.group) "Message the group, @ to address" else "Message $agent",
            canSend = live,
            notice = listOfNotNull(unsent, notSent, away).joinToString(" ").ifEmpty { null },
        ) {
            // Cleared once handed to the connection; else it stays, to send again.
            unsent = model.send(conversation, draft.trim())
            // What the person just sent shows, wherever they had scrolled.
            if (unsent == null) atNewest = true
        }
    }
}

/** A group's members' avatars, overlapping, at most three. */
@Composable
internal fun MemberStack(handles: List<String>, side: androidx.compose.ui.unit.Dp = 32.dp, label: (String) -> String = { it }, photoOf: ((String) -> PhotoKey)? = null) {
    val shown = handles.take(3)
    val step = side * 0.55f
    Box(Modifier.size(width = side + step * (shown.size - 1).coerceAtLeast(0), height = side)) {
        shown.forEachIndexed { i, h ->
            Box(
                Modifier.padding(start = step * i).size(side).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(palette.bg).padding(1.5.dp),
            ) { AgentInitial(label(h), side = side - 3.dp, photo = photoOf?.invoke(h)) }
        }
    }
}

/**
 * The members to address, over the field, as the person types `@` in a
 * group: each with its avatar and `@handle`, `@all` last; picking one puts
 * it in the field.
 */
@Composable
private fun MentionSuggestions(offered: List<String>, photoOf: (String) -> PhotoKey, label: (String) -> String, onPick: (String) -> Unit) {
    val p = palette
    AnimatedVisibility(
        offered.isNotEmpty(),
        enter = fadeIn(Motion.enter(Motion.SHORT)) + expandVertically(Motion.enter(Motion.SHORT), expandFrom = Alignment.Bottom),
        exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Bottom),
    ) {
        Column(Modifier.fillMaxWidth().background(p.surface)) {
            Hairline()
            offered.forEach { h ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Address @$h") { onPick(h) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (h == "all") {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Glyph(Glyphs.inbox, p.textMuted, size = 20.dp) }
                    } else {
                        AgentInitial(label(h), side = 26.dp, photo = photoOf(h))
                    }
                    val named = h != "all" && !namesReadAlike(label(h), h)
                    if (named) Text(label(h), style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("@$h", style = Type.code.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (named) Modifier.widthIn(max = 160.dp) else Modifier)
                    if (h == "all") Text("everyone answers", style = Type.small, color = p.textMuted)
                }
            }
        }
    }
}

/**
 * Where a group's discussion is, over the field: who works now (the wave)
 * and Stop; settled; bounded and why; stopped; failed.
 */
@Composable
private fun GroupStrip(status: GroupStatus, turnOf: String?, onStop: () -> Unit) {
    val p = palette
    val text = when (status.state) {
        "working" -> if (turnOf != null) "$turnOf is answering · round ${status.round + 1} of ${status.maxRounds}" else "Working"
        "settled" -> "Settled"
        "bounded" -> if (status.reason == "max_replies") "Paused: reply limit reached" else "Paused after ${status.maxRounds} rounds"
        "stopped" -> "Stopped"
        "failed" -> "Failed" + (status.reason?.let { ": $it" } ?: "")
        else -> return
    }
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status.state == "working") WorkingWave(square = 4.dp, lift = 3.dp)
            Text(
                text,
                style = Type.small,
                color = if (status.state == "working") p.accent else p.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (status.state == "working") TextLink("Stop", onStop) else Spacer(Modifier.height(44.dp))
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
                FormField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = "Weather in Lyon",
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

/** A question before something that cannot be undone: [confirm] does it, Cancel does not. */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, enabled: Boolean = true, dismissLabel: String = "Cancel", onConfirm: () -> Unit) {
    val p = palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = Shapes.bubble,
        title = { Text(title, style = Type.heading, color = p.text) },
        text = { Text(text, style = Type.small, color = p.textMuted) },
        confirmButton = { DialogButton(confirm, enabled = enabled, onClick = onConfirm) },
        dismissButton = { DialogButton(dismissLabel, onClick = onDismiss) },
    )
}

@Composable
private fun DialogButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    ActionButton(text, enabled = enabled, onClick = onClick)
}

/**
 * An item of a lazy list that fades in when it arrives, slides to its new
 * place when others arrive, and fades out when it goes.
 */
@Composable
internal fun LazyItemScope.Arriving(content: @Composable () -> Unit) {
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

/**
 * A pairing link the app was opened with: the host and address it names and
 * when it expires, then Pair or Not now. Any page or message can hold such
 * a link, so nothing connects before Pair.
 */
@Composable
private fun OpenedLink(link: String, now: Long, pair: () -> Unit, dismiss: () -> Unit) {
    val p = palette
    // Read as the pairing reads it: the same checks, the same address.
    val read = remember(link) { readPairLink(link) }
    Column(Modifier.fillMaxWidth().border(1.dp, p.lineStrong, Shapes.control).padding(14.dp)) {
        val ok = read.getOrNull()
        if (ok == null) {
            Text("Not an enclos pairing link", style = Type.name, color = p.text)
            // The app's own reason: never the link's text.
            Text(read.exceptionOrNull()?.message.orEmpty(), style = Type.small, color = p.textMuted)
            TextLink("Dismiss", dismiss)
            return@Column
        }
        val parsed = ok.link
        val left = (parsed.expires.toLong() - now).coerceAtLeast(0)
        Text("Pair with ${parsed.host}?", style = Type.name, color = p.text)
        Gap(2.dp)
        Text(
            ok.address + " · " + if (left > 0) "expires in ${left / 60}:${"%02d".format(left % 60)}" else "expired",
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
