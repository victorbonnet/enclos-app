package io.github.victorbonnet.enclos

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import io.github.victorbonnet.enclos.core.decodeBytes
import io.github.victorbonnet.enclos.core.encodeBytes
import io.github.victorbonnet.enclos.core.generateNoiseKeypair
import io.github.victorbonnet.enclos.core.pairingProof
import io.github.victorbonnet.enclos.ui.ThemeChoice
import io.github.victorbonnet.enclos.ui.applyTheme
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** An agent of a host, as its bridge lists it: `name` is `<sandbox>/<agent>`. */
data class Agent(val host: String, val name: String, val runtime: String, val capabilities: List<String>) {
    val sandbox: String get() = name.substringBefore('/')
    val agent: String get() = name.substringAfter('/')
    /** `host/sandbox/agent`, as the app writes an agent's address. */
    val address: String get() = "$host/$name"
}

/**
 * A tool the agent ran while it worked on an answer: [label] says what it
 * did; [done] once it ended, [ok] whether it worked.
 */
data class Step(
    val call: String,
    val name: String,
    val label: String?,
    val done: Boolean = false,
    val ok: Boolean = true,
    val durationMs: Long? = null,
)

/** A message of a conversation; [time] in seconds since the Unix epoch. */
data class Line(
    val seq: Long,
    val from: String,
    val text: String,
    val kind: String,
    val time: Long,
    val attachments: List<Attachment> = emptyList(),
    /** The tools the agent ran for this answer, which the bridge does not keep: this phone does. */
    val steps: List<Step> = emptyList(),
)

/** A file of a message, fetched from the bridge when it is shown. */
data class Attachment(val id: String, val mime: String, val name: String, val size: Long) {
    val image: Boolean get() = mime.startsWith("image/")
}

/** Where a file stands on this phone. */
sealed interface FileState {
    /** Being fetched: [done] of its bytes. */
    data class Loading(val done: Long, val size: Long) : FileState
    data class Ready(val file: java.io.File) : FileState
    data class Failed(val why: String) : FileState
}

/** A conversation: its host, agent (`<sandbox>/<agent>`) and id. */
data class Conversation(
    val host: String,
    val agent: String,
    val id: String,
    /** Its name: the agent's, from what was said, or the person's. */
    val title: String? = null,
    /** Who named it: `agent` or `person`. */
    val titledBy: String? = null,
)

/**
 * A conversation's name as the bridge sent it, ready to show: without the
 * characters that change how text reads unseen (an agent names it from what
 * was said), and none when nothing visible is left.
 */
fun shownTitle(sent: String?): String? = sent?.let { Visible.stripped(it).trim() }?.ifEmpty { null }

/**
 * Names [conversation] in [conversations] (an empty [title] clears its
 * name) once [send] passed the name to its bridge. When it could not (not
 * connected), nothing changes: a name never looks saved, then comes back
 * as it was at the next sync. Returns whether it was sent.
 */
fun renamed(conversations: MutableList<Conversation>, conversation: Conversation, title: String, send: (String) -> Boolean): Boolean {
    val name = title.trim()
    if (!send(name)) return false
    val at = conversations.indexOfFirst { it.id == conversation.id }
    if (at >= 0) {
        conversations[at] = conversations[at].copy(
            title = name.ifEmpty { null },
            titledBy = if (name.isEmpty()) null else "person",
        )
    }
    return true
}

/** A pairing link naming [old]'s name with another bridge: replaces [old] only once the person confirms. */
data class Replacing(val link: ReadLink, val old: Host)

/** The pairing in progress: what the link said, and how far it went. */
data class Pairing(
    val host: String,
    /** The bridge's address on the tailnet, without its scheme. */
    val address: String,
    /** When the link stops working, in seconds since the Unix epoch. */
    val expires: Long,
    val keyMade: Boolean = false,
    val paired: Boolean = false,
    /** Why it stopped before being paired: refused on the host, or the connection ended. */
    val stopped: String? = null,
)

/** What the screens show, kept across the screens and updated on the main thread. */
class AppModel(application: Application) : AndroidViewModel(application) {
    private val hosts = Hosts(application)
    /** What the bridges sent, kept on the phone: shown at once when the app starts. */
    private val store = Store(application)
    /** Writes to [store], in order, off the main thread. */
    private val saving = Executors.newSingleThreadExecutor()
    private val reads = application.getSharedPreferences("reads", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    /** Where the pairing keys are made: StrongBox can take seconds. */
    private val work = Executors.newSingleThreadExecutor()
    /** By host: its connection while it is open or opening, none once it ended. */
    private val connections = mutableMapOf<String, BridgeConnection>()
    /** By host: failed attempts since it was last authenticated, for the backoff. */
    private val failures = mutableMapOf<String, Int>()
    /** Hosts whose connection ended on a message the app cannot read: Reconnect or the next foreground retries them. */
    private val unreadable = mutableSetOf<String>()
    /** Whether a screen of the app is visible: only then are ended connections retried. */
    private var foreground = false

    val paired = mutableStateListOf<Host>().apply { addAll(hosts.all()) }
    /** By host: connected, the reason it is not, or connecting. */
    val status = mutableStateMapOf<String, String>()
    /** By host: the last error its bridge sent, such as an agent it could not reach. */
    val said = mutableStateMapOf<String, String>()
    val agents = mutableStateListOf<Agent>()
    val conversations = mutableStateListOf<Conversation>()
    val lines = mutableStateMapOf<String, List<Line>>()

    /**
     * Whether what the phone kept for the first screens is read. Until then
     * the launch screen stays up (so the app never opens empty and fills
     * in) and no connection opens (so `sync` knows where the phone stopped).
     */
    var ready = false
        private set
    /** By host: the newest `seq` kept on the phone, which `sync` starts after. */
    private val kept = mutableMapOf<String, Long>()

    init {
        // Off the main thread, on the writes' own, before any write: first
        // what the first screens show, then every message, merged in.
        saving.execute {
            val recent = read { recent() }
            // The connections open next: their HTTP client is built here, not on the main thread.
            BridgeConnection.prepare()
            ui {
                if (recent != null) {
                    agents.addAll(recent.agents)
                    conversations.addAll(recent.conversations)
                    lines.putAll(recent.lines)
                    kept.putAll(recent.kept)
                }
                ready = true
                if (foreground) foreground()
            }
            val all = read { lines() } ?: return@execute
            ui {
                val known = conversations.mapTo(HashSet()) { it.id }
                all.forEach { (id, older) ->
                    if (id !in known) return@forEach
                    val now = lines[id].orEmpty()
                    val both = merged(older, now)
                    if (both !== now) lines[id] = both
                }
            }
        }
    }

    /** Reads what the phone kept; on a failure, the bridge's sync still brings what it has. */
    private fun <T> read(query: Store.() -> T): T? = try {
        store.query()
    } catch (e: android.database.SQLException) {
        android.util.Log.w("enclos", "could not read what the phone kept: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Keeps something on the phone. A conversation or the agents not kept
     * come again at the next sync, which sends them all; a message goes
     * through [keep] instead.
     */
    private fun save(write: Store.() -> Unit) = saving.execute {
        try {
            store.write()
        } catch (e: android.database.SQLException) {
            // Its type only: nothing of what was said goes to logcat.
            android.util.Log.w("enclos", "not kept on the phone: ${e.javaClass.simpleName}")
        }
    }

    /** Which messages may still be kept this run; used on [saving]'s thread only. */
    private val gaps = Gaps()

    /**
     * Keeps a message of [host]. `sync` starts after the newest message kept,
     * so one that could not be written (a full disk) must not be followed by
     * a later one: after a failed write, no later message of that host is
     * kept this run, and the next start syncs from before the one that failed.
     */
    private fun keep(host: String, conversation: String, line: Line) = saving.execute {
        if (!gaps.keeps(host, line.seq)) return@execute
        try {
            store.message(host, conversation, line)
        } catch (e: android.database.SQLException) {
            gaps.failed(host, line.seq)
            android.util.Log.w("enclos", "not kept on the phone: ${e.javaClass.simpleName}")
        }
    }
    /** By conversation: whether the agent said it is writing (`typing`). */
    val typing = mutableStateMapOf<String, Boolean>()
    /** By conversation: a message was sent and the agent has not answered yet. */
    private val awaiting = mutableStateMapOf<String, Boolean>()
    /** The messages behind [awaiting], by id: a refusal of one names it. */
    private val unanswered = Awaiting()
    /** By conversation: the tools the agent runs now, live from the bridge. */
    val steps = mutableStateMapOf<String, List<Step>>()
    /** By conversation: the last message read on this device, kept across launches. */
    private val lastRead = mutableStateMapOf<String, Long>().apply {
        reads.all.forEach { (id, seq) -> if (seq is Long) put(id, seq) }
    }
    val pairing = mutableStateOf<Pairing?>(null)
    /** Why the last link scanned or typed is not a pairing link. */
    val pairLinkError = mutableStateOf<String?>(null)

    private val settings = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Light or dark: the system's until the person chooses, then theirs, kept on this device. */
    val theme = mutableStateOf(ThemeChoice.read(settings.getString("theme", null)))

    fun chooseTheme(choice: ThemeChoice) {
        theme.value = choice
        // Written off the main thread (apply); Android writes pending preferences
        // before it stops an activity, and from Android 12 keeps the mode itself.
        settings.edit { if (choice == ThemeChoice.System) remove("theme") else putString("theme", choice.name) }
        applyTheme(getApplication(), choice)
    }

    /** Whether the inbox groups conversations by agent: the person's choice, kept on this device. */
    val grouped = mutableStateOf(settings.getBoolean("inbox.grouped", false))
    /** The agents (`host/sandbox/agent`) whose group the inbox shows folded, while the app runs. */
    val folded = mutableStateListOf<String>()

    fun group(on: Boolean) {
        grouped.value = on
        settings.edit(commit = true) { putBoolean("inbox.grouped", on) }
    }

    private fun ui(block: () -> Unit) = main.post(block)

    /**
     * A link that names a host already paired with another bridge, waiting
     * for the person to confirm the replacement (docs/spec.md, Pairing).
     */
    val replacing = mutableStateOf<Replacing?>(null)

    /** Whether [host] is connected and authenticated. */
    fun connected(host: String) = status[host]?.startsWith("connected") == true

    /**
     * Whether the agent is working on [conversation]: from the person's
     * message until the agent answers, and whenever its runtime says it is
     * writing, so there is something to see before the first `typing`.
     */
    fun working(conversation: String) =
        typing[conversation] == true || awaiting[conversation] == true || steps[conversation].orEmpty().any { !it.done }

    /** The agent's messages in [conversation] not read on this device yet. */
    fun unread(conversation: String): Int {
        val read = lastRead[conversation] ?: 0
        return lines[conversation].orEmpty().count { it.from == "agent" && it.seq > read }
    }

    /** Marks every message of [conversation] read. */
    fun markRead(conversation: String) {
        val last = lines[conversation]?.lastOrNull()?.seq ?: return
        if ((lastRead[conversation] ?: 0) >= last) return
        lastRead[conversation] = last
        reads.edit { putLong(conversation, last) }
    }

    /** Pairs with the host whose link was scanned or typed. */
    fun pair(link: String) {
        replacing.value = null
        openedLink.value = null
        // The enclos:// link, also from the https link to a bridge's pairing
        // page; read once, as the opened link's card read it: the same
        // checks, the same address.
        val read = readPairLink(pairLink(link)).getOrElse {
            pairLinkError.value = "Not an enclos pairing link. ${it.message}"
            return
        }
        val parsed = read.link
        pairLinkError.value = null
        // The link chooses the name it gives: a name already paired is
        // replaced only by its own bridge, or once the person confirms.
        when (val target = pairingTarget(paired, parsed.host, parsed.key)) {
            PairingTarget.New -> start(read, null)
            is PairingTarget.Again -> start(read, target.old)
            is PairingTarget.Other -> {
                pairing.value = null
                replacing.value = Replacing(read, target.old)
            }
        }
    }

    /** The person confirmed the replacement [replacing] asked about. */
    fun replace() {
        val r = replacing.value ?: return
        replacing.value = null
        start(r.link, r.old)
    }

    /** The person declined the replacement: [Replacing.old] stays paired. */
    fun keep() {
        val r = replacing.value ?: return
        replacing.value = null
        pairing.value = shown(r.link).copy(stopped = "Not paired: ${r.old.name} stays paired with ${shownAddress(r.old.url)}.")
    }

    /** The pairing [read] starts, as the pairing screen shows it. */
    private fun shown(read: ReadLink) = Pairing(read.link.host, read.address, read.link.expires.toLong())

    /**
     * Makes the keys, off the main thread (StrongBox can take seconds), then
     * connects and asks the bridge to pair; in place of [old] once accepted.
     * A pairing that ends otherwise (refused, expired, the connection lost)
     * deletes the key it made ([PairingAttempt.ended]).
     */
    private fun start(read: ReadLink, old: Host?) {
        val parsed = read.link
        val attempt = PairingAttempt(parsed.host, read.url, parsed.key, old, Keys, ::decodeBytes)
        pairing.value = shown(read)
        work.execute {
            val noise = try {
                attempt.makeDeviceKey()
                generateNoiseKeypair()
            } catch (e: Exception) {
                attempt.ended()
                ui { pairing.value = pairing.value?.copy(stopped = "Not paired: the device key could not be made (${e.message})") }
                return@execute
            }
            ui { pairing.value = pairing.value?.copy(keyMade = true) }
            lateinit var connection: BridgeConnection
            connection = BridgeConnection(
                url = read.url,
                bridgeKey = parsed.key,
                noisePrivate = { noise.privateKey },
                first = { session ->
                    message(
                        "pair.request",
                        "name" to Build.MODEL,
                        "device_key" to encodeBytes(Keys.devicePublicKey(attempt.alias)),
                        "proof" to encodeBytes(pairingProof(parsed.code, session.handshakeHash())),
                    )
                },
                // A field missing here ends the connection (BridgeConnection),
                // and with it the attempt.
                onMessage = { m ->
                    when (m.optString("type")) {
                        "pair.accepted" -> {
                            val host = attempt.host(m, noise.privateKey)
                            hosts.add(host)
                            attempt.kept()
                            ui { pairedWith(host, old) }
                            connection.close()
                        }
                        "pair.refused" -> {
                            ui { pairing.value = pairing.value?.copy(stopped = "Refused on ${parsed.host}: ${m.optString("reason")}") }
                            connection.close()
                        }
                    }
                },
                onClosed = { why ->
                    attempt.ended()
                    ui {
                        val now = pairing.value
                        if (now != null && !now.paired && now.stopped == null) pairing.value = now.copy(stopped = "Not paired: $why")
                    }
                },
            )
            connection.open()
        }
    }

    /** [host] was paired, in place of [old] if it replaced it. */
    private fun pairedWith(host: Host, old: Host?) {
        conversations.filter { it.host == host.name }.forEach {
            typing.remove(it.id)
            answered(it.id)
            steps.remove(it.id)
        }
        // Paired again, as another device: what the host sent before is forgotten.
        forget(host.name)
        paired.removeAll { it.name == host.name }
        paired.add(host)
        // Another bridge under the same name: nothing of the old one stays.
        if (old != null && !old.bridgeKey.contentEquals(host.bridgeKey)) {
            status.remove(host.name)
            said.remove(host.name)
        }
        failures.remove(host.name)
        pairing.value = pairing.value?.copy(paired = true)
        connect(host)
    }

    /**
     * A pairing link the system opened the app with (tapped in a browser, a
     * chat): shown on the pairing screen, which pairs only once the person
     * says so, since any page can hold such a link.
     */
    val openedLink = mutableStateOf<String?>(null)

    /**
     * The system opened the app with [link]; ignored while a pairing is under
     * way, and when it was handled before: the system hands a link again
     * when it restarts the app's task (after an update, from recents). The
     * links' hashes are kept, not the links and their one-time codes; the
     * last 20, as a link works for 5 minutes only.
     */
    fun opened(link: String) {
        val now = pairing.value
        val underWay = now != null && !now.paired && now.stopped == null
        val handled = settings.getString("pair.links.handled", "").orEmpty().split(' ').filter { it.isNotEmpty() }
        val kept = handledAfter(underWay, handled, linkHash(link)) ?: return
        settings.edit(commit = true) { putString("pair.links.handled", kept.joinToString(" ")) }
        pairing.value = null
        replacing.value = null
        pairLinkError.value = null
        openedLink.value = link
    }

    /** Forgets the pairing shown, to scan another code. */
    fun clearPairing() {
        pairing.value = null
        pairLinkError.value = null
        openedLink.value = null
    }

    /**
     * Opens the connection to [host]: `auth`, then its agents and every
     * message after the newest kept. [foreground] opens it, and [ended] again.
     */
    fun connect(host: Host) {
        connections.remove(host.name)?.close()
        // While retrying, why the last attempt ended stays on screen.
        if (status[host.name]?.startsWith("connected") != false) status[host.name] = "connecting"
        lateinit var connection: BridgeConnection
        connection = BridgeConnection(
            url = host.url,
            bridgeKey = host.bridgeKey,
            noisePrivate = { Keys.unwrap(host.noiseKeyWrapped) },
            first = { session ->
                message(
                    "auth",
                    "device" to host.deviceId,
                    "sig" to encodeBytes(Keys.sign(host.keyAlias, session.handshakeHash())),
                )
            },
            // A message the app cannot read ends this connection, not the app:
            // the two repositories release separately.
            onMessage = { m ->
                ui {
                    try {
                        received(host, m)
                    } catch (e: JSONException) {
                        // Not retried on its own: the next connection would get the same.
                        if (connections[host.name] === connection) unreadable += host.name
                        connection.fail("the bridge sent what the app cannot read: ${e.message}")
                    }
                }
            },
            // A connection [connect] replaced says nothing of the new one.
            onClosed = { why -> ui { if (connections[host.name] === connection) ended(host, why) } },
        )
        connections[host.name] = connection
        connection.open()
    }

    /**
     * [host]'s connection ended: says why, and tries again while the app is
     * in the foreground, waiting longer after each failure (2 s, 4 s, … 60 s).
     * The phone ends connections on its own, such as when it locks.
     */
    private fun ended(host: Host, why: String) {
        connections.remove(host.name)
        status[host.name] = why
        // Nothing will come over a closed connection: stop showing the agent at work.
        val ids = conversations.filter { it.host == host.name }.map { it.id }.toSet()
        typing.keys.filter { it in ids }.forEach { typing.remove(it) }
        ids.forEach { answered(it) }
        steps.keys.filter { it in ids }.forEach { steps.remove(it) }
        // Files being fetched from it stop; asked again, they go on from where they were.
        fetching.filterValues { it.host == host.name }.keys.forEach {
            fetching.remove(it)
            files[it] = FileState.Failed("the connection ended")
        }
        if (unreadable.remove(host.name) || !foreground) return
        val n = failures.getOrDefault(host.name, 0)
        failures[host.name] = n + 1
        main.postDelayed({ if (foreground && host.name !in connections) reopen(host.name) }, backoff(n))
    }

    /**
     * The app came to the foreground: opens the connection of every host
     * that has none, once what the phone kept is read ([ready]).
     */
    fun foreground() {
        foreground = true
        if (ready) paired.filter { it.name !in connections }.forEach { connect(it) }
    }

    /** The app left the foreground: its connections stay up while the phone lets them. */
    fun background() {
        foreground = false
    }

    private fun reopen(name: String) {
        paired.firstOrNull { it.name == name }?.let { connect(it) }
    }

    private fun received(host: Host, m: JSONObject) {
        // A conversation is known by its id alone: one another host already
        // has stays that host's, whatever this host's bridge sends about it.
        if (ownedByAnother(conversations, host.name, m.optString("conversation"))) {
            android.util.Log.w("enclos", "${m.optString("type")} for another host's conversation: ignored")
            return
        }
        when (m.optString("type")) {
            "authenticated" -> {
                failures.remove(host.name)
                status[host.name] = "connected as ${m.optString("person")}"
                connections[host.name]?.send(message("agents"))
                // What is on the phone is kept: only what is newer comes.
                connections[host.name]?.send(message("sync", "since" to since(host.name)))
            }
            "agents.list" -> {
                val list = agentsIn(host.name, m)
                agents.removeAll { it.host == host.name }
                agents.addAll(list)
                val listed = agents.filter { it.host == host.name }
                save { agents(host.name, listed) }
            }
            // A conversation and its name: at each sync (so an empty one is
            // known too), when one starts, and when its name changes.
            "conversation" -> {
                val id = m.getString("conversation")
                val known = Conversation(
                    host.name,
                    m.getString("agent"),
                    id,
                    shownTitle(m.optString("title")),
                    m.optString("titled_by").ifBlank { null },
                )
                val at = conversations.indexOfFirst { it.id == id }
                if (at < 0 || conversations[at] != known) {
                    if (at < 0) conversations.add(known) else conversations[at] = known
                    save { conversation(known) }
                }
            }
            "message" -> {
                val id = m.getString("conversation")
                var line = lineIn(m)
                if (conversations.none { it.id == id }) {
                    val started = Conversation(host.name, m.getString("agent"), id)
                    conversations.add(started)
                    save { conversation(started) }
                }
                if (line.from == "agent") {
                    val agent = m.getString("agent")
                    // Only a reply answers the person; any other kind (notify,
                    // and later ones) leaves the turn and its steps alone.
                    if (line.kind == "reply") {
                        // An answer ends the turn: the steps that led to it go with it, kept with it.
                        typing.remove(id)
                        answered(id)
                        steps.remove(id)?.let { line = line.copy(steps = it) }
                    } else if (agents.none { it.host == host.name && it.name == agent && "typing" in it.capabilities }) {
                        // A message on its own (a cron job's) while the agent
                        // works keeps the turn's steps and its working state;
                        // only a runtime that says nothing of its typing has
                        // no other sign that a turn ended.
                        answered(id)
                    }
                    // The bridge learns what an agent's channel can do once it
                    // reached it, which an agent's message shows: ask again.
                    if (agents.any { it.host == host.name && it.name == agent && it.capabilities.isEmpty() }) {
                        connections[host.name]?.send(message("agents"))
                    }
                }
                val now = lines[id].orEmpty()
                if (now.none { it.seq == line.seq }) {
                    lines[id] = (now + line).sortedBy { it.seq }
                    keep(host.name, id, line)
                }
            }
            "typing" -> {
                val id = m.getString("conversation")
                if (m.optBoolean("on")) {
                    typing[id] = true
                } else {
                    typing.remove(id)
                    answered(id)
                    // The turn ended: a step that never ended no longer runs.
                    steps[id]?.let { steps[id] = settled(it) }
                }
            }
            // A part of a file: written where it goes, then the next asked for.
            "attachment.data" -> received(host, m.getString("attachment"), m.getLong("offset"), m.getLong("size"), m.getString("data"))
            // A tool the agent starts, or ends: added, or marked done.
            "tool" -> {
                val id = m.getString("conversation")
                steps[id] = stepped(
                    steps[id].orEmpty(),
                    call = m.getString("call"),
                    phase = m.optString("phase"),
                    name = m.getString("name"),
                    label = m.optString("label").ifBlank { null },
                    ok = m.optBoolean("ok", true),
                    durationMs = if (m.has("duration_ms")) m.getLong("duration_ms") else null,
                )
            }
            "error" -> {
                // An error about a file asked for fails that file; else it is the bridge's word.
                val file = asked.remove(m.optString("of"))
                if (file != null) {
                    fetching.remove(file)
                    files[file] = FileState.Failed(m.optString("message"))
                } else {
                    said[host.name] = m.optString("message")
                    // The bridge refused a message of the person's (the agent is
                    // not reachable, say): nothing will answer it now.
                    unanswered.refused(m.optString("of"))?.let { stillAwaiting(it) }
                }
            }
        }
    }

    /** Starts a conversation with [agent]; returns its id. */
    fun newConversation(agent: Agent): String {
        val id = UUID.randomUUID().toString()
        conversations.add(Conversation(agent.host, agent.name, id))
        connections[agent.host]?.send(message("conversation.new", "conversation" to id, "agent" to agent.name))
        return id
    }

    // Files. Fetched from the bridge over the conversation's connection, a
    // part at a time, into the app's cache (never backed up), one folder per
    // attachment under the file's own name, which sharing shows.

    /** By attachment id: on the phone, being fetched, or failed; none until it is asked for. */
    val files = mutableStateMapOf<String, FileState>()
    /** A file being fetched: from [host], [at] bytes of it written so far. */
    private class Fetch(val host: String, val a: Attachment, val at: Long)

    /** By attachment id: the file being fetched. */
    private val fetching = mutableMapOf<String, Fetch>()
    /** By the id of the `attachment.get` sent for a file, the one part asked for at a time: its attachment. */
    private val asked = mutableMapOf<String, String>()
    /** Files are written here, one part after another, never on the main thread. */
    private val disk = Executors.newSingleThreadExecutor()

    // cache/attachments/<host>/<id>/<name>: the id checked (Attachments.validId), the host and name made plain.
    private fun folderOf(host: String, a: Attachment) =
        java.io.File(java.io.File(java.io.File(getApplication<Application>().cacheDir, "attachments"), Attachments.hostFolder(host)), a.id)
    private fun fileOf(host: String, a: Attachment) = java.io.File(folderOf(host, a), Attachments.fileName(a.name))
    private fun partOf(host: String, a: Attachment) = java.io.File(folderOf(host, a), ".part")

    /** Fetches [a] from [host]'s bridge, or finds it on the phone; again after a failure. */
    fun fetch(host: String, a: Attachment) {
        if (a.id in fetching) return
        files[a.id] = FileState.Loading(0, a.size)
        // What a broken fetch left is kept: it goes on from there.
        disk.execute {
            val done = fileOf(host, a)
            val found = try {
                if (done.isFile) done else null.also { folderOf(host, a).mkdirs() }
            } catch (e: Exception) {
                null
            }
            val have = partOf(host, a).length()
            ui {
                when {
                    found != null -> files[a.id] = FileState.Ready(found)
                    a.id in fetching -> {}
                    else -> {
                        files[a.id] = FileState.Loading(have, a.size)
                        fetching[a.id] = Fetch(host, a, have)
                        ask(host, a.id, have)
                    }
                }
            }
        }
    }

    private fun ask(host: String, id: String, offset: Long) {
        val get = message("attachment.get", "attachment" to id, "offset" to offset)
        asked.values.removeAll { it == id }
        asked[get.getString("id")] = id
        if (connections[host]?.send(get) != true) failed(id, "not connected")
    }

    /** The fetch of [id] stopped: a tap asks again, from what was written. */
    private fun failed(id: String, why: String) {
        fetching.remove(id)
        asked.values.removeAll { it == id }
        files[id] = FileState.Failed(why)
    }

    private fun received(host: Host, id: String, offset: Long, size: Long, data: String) {
        val f = fetching[id] ?: return
        val bytes = try {
            decodeBytes(data)
        } catch (e: Exception) {
            return failed(id, "a part of it could not be read")
        }
        when (val next = part(f.at, offset, bytes.size, size)) {
            Part.Stale -> return
            Part.Wrong -> return failed(id, "a part of it came out of order")
            is Part.Write -> {
                // Taken as written: a part repeated meanwhile is stale, not written twice.
                fetching[id] = Fetch(f.host, f.a, next.at)
                asked.values.removeAll { it == id }
                disk.execute {
                    val outcome = try {
                        val partial = partOf(f.host, f.a)
                        partial.appendBytes(bytes)
                        if (!next.complete) {
                            null
                        } else {
                            val done = fileOf(f.host, f.a)
                            if (partial.renameTo(done)) FileState.Ready(done) else FileState.Failed("cannot keep the file")
                        }
                    } catch (e: Exception) {
                        // Storage full, or the system cleared the cache meanwhile.
                        FileState.Failed("cannot write it on the phone: ${e.message}")
                    }
                    ui {
                        if (fetching[id]?.at != next.at) return@ui
                        when (outcome) {
                            null -> {
                                files[id] = FileState.Loading(next.at, size)
                                ask(host.name, id, next.at)
                            }
                            is FileState.Failed -> failed(id, outcome.why)
                            else -> {
                                fetching.remove(id)
                                files[id] = outcome
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Names [conversation] (an empty [title] clears its name, and the agent
     * may name it again): sent to the bridge, then shown. Returns false,
     * changing nothing, when its host is not connected.
     */
    fun rename(conversation: Conversation, title: String): Boolean =
        renamed(conversations, conversation, title) { name ->
            connected(conversation.host) &&
                connections[conversation.host]?.send(
                    message("conversation.rename", "conversation" to conversation.id, "title" to name),
                ) == true
        }

    /** Each message's folded words, for search, kept while the app runs. */
    val folds = Folds()

    /** Sends [text] to [conversation]'s agent; why it was not sent, or `null` once it was. */
    fun send(conversation: Conversation, text: String): String? {
        said.remove(conversation.host)
        val out = message("message.send", "conversation" to conversation.id, "text" to text)
        if (!fits(out)) {
            return "Too long to send: ${text.toByteArray(Charsets.UTF_8).size / 1000} KB, and at most about 63 KB go at once."
        }
        val sent = connections[conversation.host]?.send(out) == true
        if (!sent) return "Not sent: not connected to ${conversation.host}. Your message is still here."
        unanswered.sent(out.getString("id"), conversation.id, SystemClock.elapsedRealtime())
        awaiting[conversation.id] = true
        // A new turn: steps left by one that ended with no answer are not its own.
        steps.remove(conversation.id)
        main.postDelayed({ stillAwaiting(conversation.id) }, unanswered.limit)
        return null
    }

    /** Stops showing the agent at work on [conversation] once no message of it waits. */
    private fun stillAwaiting(conversation: String) {
        if (!unanswered.waiting(conversation, SystemClock.elapsedRealtime())) awaiting.remove(conversation)
    }

    /** The agent answered in [conversation], its turn ended, or nothing more will come. */
    private fun answered(conversation: String) {
        unanswered.answered(conversation)
        awaiting.remove(conversation)
    }

    fun reconnect(name: String) {
        failures.remove(name)
        reopen(name)
    }

    /** The newest message from [host] on the phone, which `sync` starts after. */
    private fun since(host: String): Long {
        val ids = conversations.filter { it.host == host }.map { it.id }.toSet()
        val shown = ids.maxOfOrNull { lines[it]?.lastOrNull()?.seq ?: 0 } ?: 0
        return maxOf(shown, kept[host] ?: 0)
    }

    /** Forgets what [host] sent, on screen and on the phone. */
    private fun forget(host: String) {
        val ids = conversations.filter { it.host == host }.map { it.id }.toSet()
        conversations.removeAll { it.host == host }
        ids.forEach { lines.remove(it) }
        agents.removeAll { it.host == host }
        kept.remove(host)
        saving.execute { gaps.clear(host) }
        save { forget(host) }
    }

    override fun onCleared() {
        connections.values.forEach { it.close() }
        work.shutdown()
        disk.shutdown()
        // What is queued is still written.
        saving.shutdown()
    }
}

/**
 * [older], every message the phone kept of a conversation, merged with
 * [now], what is shown: the newest of [older], then what came since, which
 * is newer than all of it. [now] itself when it already holds them all.
 */
internal fun merged(older: List<Line>, now: List<Line>): List<Line> {
    if (older.isEmpty()) return now
    if (now.size >= older.size && now.firstOrNull()?.seq == older.first().seq) return now
    val last = older.last().seq
    return older + now.filter { it.seq > last }
}

/** Whether [id] is a conversation of a host other than [host]. */
internal fun ownedByAnother(conversations: List<Conversation>, host: String, id: String) =
    id.isNotEmpty() && conversations.any { it.id == id && it.host != host }

/**
 * By host, the lowest `seq` whose write failed this run. A later message of
 * that host is not kept, so the newest message kept, which the next start's
 * `sync` starts after, stays below the one missing.
 */
internal class Gaps {
    private val lowest = mutableMapOf<String, Long>()

    fun keeps(host: String, seq: Long) = lowest[host]?.let { seq < it } ?: true

    fun failed(host: String, seq: Long) {
        lowest[host] = minOf(seq, lowest[host] ?: seq)
    }

    /** [host] was forgotten: what it sends next starts from nothing. */
    fun clear(host: String) {
        lowest.remove(host)
    }
}

/** The agents an `agents.list` from [host] names; throws [JSONException] when a field is missing. */
fun agentsIn(host: String, m: JSONObject): List<Agent> {
    val list = m.getJSONArray("agents")
    return (0 until list.length()).map { i ->
        val a = list.getJSONObject(i)
        val capabilities = a.optJSONArray("capabilities")
        Agent(
            host,
            a.getString("agent"),
            a.optString("runtime"),
            (0 until (capabilities?.length() ?: 0)).map { capabilities!!.getString(it) },
        )
    }
}

/** The line a `message` holds; throws [JSONException] when a field is missing. */
fun lineIn(m: JSONObject): Line {
    val files = m.optJSONArray("attachments")
    // An id names a folder on the phone: only the bridge's form is kept.
    val attached = (0 until (files?.length() ?: 0)).map {
        val a = files!!.getJSONObject(it)
        // The name as the agent wrote it, without what would make
        // it read as another (`report` U+202E `fdp.html`).
        val name = Visible.stripped(a.optString("name")).trim().ifEmpty { "file" }
        Attachment(a.getString("id"), a.optString("mime"), name, a.optLong("size"))
    }.filter { Attachments.validId(it.id) }
    return Line(m.getLong("seq"), m.getString("from"), m.getString("text"), m.getString("kind"), m.optLong("time"), attached)
}
