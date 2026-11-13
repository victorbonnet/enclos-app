package io.github.victorbonnet.enclos

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
data class Agent(val host: String, val name: String, val runtime: String, val capabilities: List<String>, val avatarRevision: Long = 0, val avatarVersion: String? = null, val canEditAvatar: Boolean = false, val supportsAvatars: Boolean = false, val canManageRoutines: Boolean = false, val displayName: String? = null, val nameRevision: Long = 0, val canEditName: Boolean = false, val supportsNames: Boolean = false) {
    /** Routines are offered when the runtime has them and the host lets this person manage them: its own person only. */
    val offersRoutines: Boolean get() = canManageRoutines && "routines" in capabilities
    val sandbox: String get() = name.substringBefore('/')
    val agent: String get() = name.substringAfter('/')
    /** The shared human name; canonical identifiers never change when it does. */
    val label: String get() = displayName ?: agent
    /** `host/sandbox/agent`, as the app writes an agent's address. */
    val address: String get() = "$host/$name"
    /** Its photo's key: the host and the agent kept apart. */
    val photoKey: PhotoKey get() = PhotoKey(host, name)
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
    /** In a group, the member who said it (`host/sandbox/agent`); none otherwise. */
    val agent: String? = null,
)

/** A file of a message, fetched from the bridge when it is shown. */
data class Attachment(val id: String, val mime: String, val name: String, val size: Long) {
    val image: Boolean get() = mime.startsWith("image/")
    /** A static HTML page, opened separately from the message's markdown. */
    val artefact: Boolean get() = mime.substringBefore(';').trim().equals("text/html", ignoreCase = true)
}

/** Where a file stands on this phone. */
sealed interface FileState {
    /** Being fetched: [done] of its bytes. */
    data class Loading(val done: Long, val size: Long) : FileState
    data class Ready(val file: java.io.File) : FileState
    data class Failed(val why: String) : FileState
}

/** A section of the conversations view, which the person may fold away. */
enum class InboxSection { Requests, Groups, Agents }

/** A conversation: its host, agent (`<sandbox>/<agent>`) and id. */
data class Conversation(
    val host: String,
    val agent: String,
    val id: String,
    /** Its name: the agent's, from what was said, or the person's. */
    val title: String? = null,
    /** Who named it: `agent` or `person`. */
    val titledBy: String? = null,
    /**
     * The agent's Home, which its bridge keeps: where what has no
     * conversation of its own goes (cron jobs, the runtime's notices). Shown
     * first, never hidden as empty.
     */
    val home: Boolean = false,
    /**
     * A group's members (`sandbox/agent` each), in its order; none for a
     * conversation with one agent. A group's [agent] is its sandbox.
     */
    val members: List<String> = emptyList(),
    /** Kept out of the active inbox until its owner explicitly unarchives it. */
    val archived: Boolean = false,
    /** The bridge's order for metadata, independent of message sequence numbers. */
    val revision: Long = 0,
) {
    val group: Boolean get() = members.isNotEmpty()
}

/**
 * A delayed broadcast or sync must not overwrite newer metadata from the same host, and one
 * host's conversation never replaces another's that has the same id: [current] is the row the
 * phone keeps for [incoming]'s id, whatever its host.
 */
fun acceptsConversationMetadata(current: Conversation?, incoming: Conversation): Boolean =
    incoming.revision >= 0 && (current == null || (current.host == incoming.host && current.id == incoming.id && incoming.revision >= current.revision))

/**
 * Whether conversation [id] is kept for a host other than [host]. The phone keys conversations,
 * their messages and its cache by id alone, and a Home's id (`home.<sandbox>.<agent>`) is the
 * same on two hosts with the same sandbox and agent names: the second host's is refused rather
 * than mixed into the first's.
 */
fun heldByAnotherHost(conversations: List<Conversation>, host: String, id: String): Boolean =
    conversations.any { it.id == id && it.host != host }

/** The messages whose `conversation` names what this phone keeps by that id: the conversation, its lines, typing, steps, a group's state. */
val conversationScoped = setOf("conversation", "message", "group.state", "typing", "tool")

/**
 * Whether a message of [type] from [host] about conversation [id] is left out: it names
 * a conversation another host's has the id of, so it would land on that one. A policy
 * request that names a conversation is not about the conversation's own state, and stays.
 */
fun leftOutForClash(conversations: List<Conversation>, host: String, type: String, id: String?): Boolean =
    type in conversationScoped && id != null && heldByAnotherHost(conversations, host, id)

/** A dialog may hold an older row: naming changes only the current row's title fields. */
internal fun renameConversationLocally(conversations: MutableList<Conversation>, id: String, title: String) {
    val at = conversations.indexOfFirst { it.id == id }
    if (at >= 0) {
        conversations[at] = conversations[at].copy(
            title = title.ifEmpty { null },
            titledBy = if (title.isEmpty()) null else "person",
        )
    }
}

/**
 * Where a group's discussion is, as its bridge said: [state] `working`,
 * `settled`, `bounded`, `stopped` or `failed`; each member's turn
 * (`queued`, `working`, `replied`, `passed`, `failed`, `cancelled`).
 */
data class GroupStatus(
    val state: String,
    val round: Int,
    val maxRounds: Int,
    val turns: List<Pair<String, String?>>,
    val reason: String?,
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

/** A message the runtime sends about itself (gateway online, shutting down): a quiet line, never unread. */
val Line.status: Boolean get() = kind == "status"

/** The agent's messages among [lines] after [read]; a status line never counts. */
internal fun unreadAmong(lines: List<Line>, read: Long): Int = lines.count { it.from == "agent" && !it.status && it.seq > read }

/** Whether the inbox lists [conversation], whose messages are [lines]: once something was said in it, an agent's Home and a group always. */
internal fun listedWith(conversation: Conversation, lines: List<Line>): Boolean = conversation.home || conversation.group || lines.isNotEmpty()

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
    private val policyCache = application.getSharedPreferences("sandbox-policies", Context.MODE_PRIVATE)
    private val approvalCache = application.getSharedPreferences("policy-requests", Context.MODE_PRIVATE)
    val approvals = mutableStateMapOf<String, PolicyRequest>()
    val approvalsFresh = mutableStateMapOf<String, Boolean>()
    val decidingApprovals = mutableStateMapOf<String, String>() // request key -> message id
    val approvalErrors = mutableStateMapOf<String, String>()
    val agents = mutableStateListOf<Agent>()
    val agentNames = AgentNames(
        send = { host, command -> connections[host]?.send(command) == true },
        connected = ::connected,
        agent = { address -> agents.firstOrNull { it.address == address } },
        hostExists = { host -> paired.any { it.name == host } },
        changed = ::applyAgentName,
        later = { wait, action -> main.postDelayed({ action() }, wait) },
    )
    val routines = RoutineClient(
        send = { host, command -> connections[host]?.send(command) == true },
        connected = ::connected,
        supported = { address -> agents.any { it.address == address && it.offersRoutines } },
        later = { wait, action -> main.postDelayed({ action() }, wait) },
    )
    val agentImages = AgentImages(application,
        send = { host, command -> connected(host) && connections[host]?.send(command) == true },
        connected = ::connected,
        agent = { key -> agents.firstOrNull { it.host == key.host && it.name == key.agent } },
        hostExists = { host -> paired.any { it.name == host } },
    )
    val conversations = mutableStateListOf<Conversation>()
    val lines = mutableStateMapOf<String, List<Line>>()
    private data class ArchiveKey(val host: String, val conversation: String)
    private val archiveRequests = mutableMapOf<String, ArchiveKey>()
    private val archivePending = mutableStateMapOf<ArchiveKey, Boolean>()
    private val archiveProblems = mutableStateMapOf<ArchiveKey, String>()

    /**
     * Whether what the phone kept for the first screens is read. Until then
     * the launch screen stays up (so the app never opens empty and fills
     * in) and no connection opens (so `sync` knows where the phone stopped).
     */
    // Observable: a screen open on a conversation the phone does not hold
    // goes back once this is known (see MainActivity).
    var ready by mutableStateOf(false)
        private set
    /** By host: the newest `seq` kept on the phone, which `sync` starts after. */
    private val kept = mutableMapOf<String, Long>()

    init {
        // Off the main thread, on the writes' own, before any write: first
        // what the first screens show, then every message, merged in.
        saving.execute {
            // Signing keys no host uses (a pairing that ended, a host paired
            // again, a Forget the app was stopped in): deleted, as nothing
            // else ever would. No pairing is under way: the launch screen is up.
            try {
                Keys.deleteUnused(hosts.all().mapTo(HashSet()) { it.keyAlias })
            } catch (e: Exception) {
                // The Keystore's or the preferences' failure: tried again at the next start.
                android.util.Log.w("enclos", "unused keys not deleted: ${e.javaClass.simpleName}")
            }
            val cachedRequests = approvalCache.all.mapNotNull { (_, value) ->
                runCatching { val m = JSONObject(value as String); PolicyRequest.from(m.getString("host"), m) }.getOrNull()
            }.filter { a -> paired.any { it.name == a.host } }
            val cachedPolicies = policyCache.all.mapNotNull { (_, value) ->
                runCatching { val m = JSONObject(value as String); SandboxPolicy.from(m.getString("host"), m) }.getOrNull()
            }.filter { p -> paired.any { it.name == p.address.substringBefore('/') } }
            val recent = read { recent() }
            // The connections open next: their HTTP client is built here, not on the main thread.
            BridgeConnection.prepare()
            ui {
                cachedRequests.forEach { approvals[it.key] = it }
                cachedPolicies.forEach { sandboxPolicies[it.address] = it }
                if (recent != null) {
                    agents.addAll(recent.agents.map(agentNames::listed))
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
        // Said in Settings: the app runs from the bridges alone.
        ui { keptUnreadable.value = true }
        null
    }

    /**
     * Whether what the phone kept could not be read, such as a database a
     * newer build of the app wrote (its version cannot go back).
     */
    val keptUnreadable = mutableStateOf(false)

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
    /**
     * By agent (`host/sandbox/agent`): the commands the person may type to
     * its runtime, as its bridge last sent them; kept while the host is
     * connected (the bridge sends them again after `agents`).
     */
    val commands = mutableStateMapOf<String, List<AgentCommand>>()
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

    /** The app lock: on or off, kept on this device, and whether the app is locked now ([LockState]). */
    val lock = LockState(
        on = settings.getBoolean("lock", false),
        screenLock = { AppLock.available(getApplication()) },
        clock = android.os.SystemClock::elapsedRealtime,
        // Written at once (commit), not later: a choice must survive the app being stopped right after.
        keep = { on -> settings.edit(commit = true) { putBoolean("lock", on) } },
    )

    /** The agents (`host/sandbox/agent`) whose conversations the inbox shows unfolded, while the app runs. */
    val unfolded = mutableStateListOf<String>()

    /**
     * What the person folded away, kept across starts: the inbox's sections
     * (an [InboxSection]'s name) and the agents view's hosts ([hostFold]).
     */
    val folded = mutableStateListOf<String>().apply { addAll(settings.getStringSet("folded", null).orEmpty()) }

    /** Folds [key] away, or unfolds it. */
    fun fold(key: String) {
        if (!folded.remove(key)) folded.add(key)
        settings.edit { putStringSet("folded", folded.toSet()) }
    }

    /** A host's key in [folded]. */
    fun hostFold(host: String) = "host:$host"

    init {
        // The inbox is always by agent now: the earlier choice to group it goes.
        if (settings.contains("inbox.grouped")) settings.edit { remove("inbox.grouped") }    }

    private fun ui(block: () -> Unit) = main.post(block)

    /**
     * A link that names a host already paired with another bridge, waiting
     * for the person to confirm the replacement (docs/spec.md, Pairing).
     */
    val replacing = mutableStateOf<Replacing?>(null)

    /** Whether [host] is connected and authenticated. */
    fun connected(host: String) = status[host]?.startsWith("connected") == true

    /** Resolve current names at render time, including previously cached conversation history. */
    fun agentLabel(address: String): String = resolveAgentLabel(agents, address)

    private fun applyAgentName(value: SharedAgentName) {
        val index = agents.indexOfFirst { it.address == value.address }
        if (index >= 0 && value.revision >= agents[index].nameRevision) {
            agents[index] = agents[index].copy(displayName = value.displayName, nameRevision = value.revision)
        }
        save { agentName(value) }
    }

    /**
     * Whether the agent is working on [conversation]: from the person's
     * message until the agent answers, and whenever its runtime says it is
     * writing, so there is something to see before the first `typing`.
     */
    fun working(conversation: String) =
        typing[conversation] == true || awaiting[conversation] == true || steps[conversation].orEmpty().any { !it.done }

    /** The conversations of the agent at [address] (`host/sandbox/agent`). */
    private fun conversationsOf(address: String) = conversations.filter { "${it.host}/${it.agent}" == address }
    private fun conversationsOf(agent: Agent) = conversationsOf(agent.address)

    /** The conversations [agent] is working on now, the one with the newest message first. */
    fun workingOn(agent: Agent): List<Conversation> = workingOn(agent.address)

    /** The conversations the agent at [address] (`host/sandbox/agent`) is working on now, the one with the newest message first. */
    fun workingOn(address: String): List<Conversation> =
        conversationsOf(address).filter { working(it.id) }.sortedByDescending { lines[it.id]?.lastOrNull()?.time ?: 0L }

    /** The time of the newest message in any conversation of the agent at [address]; null before the first. */
    fun lastAt(address: String, archived: Boolean? = null): Long? =
        conversationsOf(address).filter { archived == null || it.archived == archived }
            .mapNotNull { c -> lines[c.id]?.lastOrNull()?.time?.takeIf { it > 0 } }.maxOrNull()

    /** What the agent is doing in [conversation] now: the tool it runs, if it said which. */
    fun doing(conversation: String): String? =
        steps[conversation].orEmpty().lastOrNull { !it.done }?.let { it.label ?: it.name }

    /**
     * When [agent] last sent a message in any of its conversations (the
     * runtime's notices aside), in seconds since the Unix epoch; null before
     * its first one this phone knows of.
     */
    fun lastActive(agent: Agent): Long? = conversationsOf(agent)
        .mapNotNull { c -> lines[c.id]?.lastOrNull { it.from == "agent" && !it.status }?.time?.takeIf { it > 0 } }
        .maxOrNull()

    /** The agent's messages in [conversation] not read on this device yet. */
    fun unread(conversation: String): Int {
        return unreadAmong(lines[conversation].orEmpty(), lastRead[conversation] ?: 0)
    }

    /** Marks every message of [conversation] read. */
    fun markRead(conversation: String) {
        // Locked, nobody has seen it: nothing of the app is composed then, and this holds should a caller forget.
        if (lock.locked.value) return
        val last = lines[conversation]?.lastOrNull()?.seq ?: return
        if ((lastRead[conversation] ?: 0) >= last) return
        read(conversation, last)
        // The bridge keeps it for the person's other devices and the next
        // sync; offline, it is sent when the bridge's is seen behind.
        val host = conversations.firstOrNull { it.id == conversation }?.host ?: return
        connections[host]?.send(message("conversation.read", "conversation" to conversation, "seq" to last))
    }

    /** By conversation: how far the bridge says it was read, past the newest message here so far. */
    private val unsettledRead = mutableMapOf<String, Long>()

    /** [conversation] is read up to [seq] (here or on another device): kept on this one. */
    private fun read(conversation: String, seq: Long) {
        lastRead[conversation] = seq
        reads.edit { putLong(conversation, seq) }
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
        agentNames.ended(host.name)
        routines.ended(host.name)
        // A connection replaced says nothing more ([ended] is not called): what went over it may be lost.
        connections.remove(host.name)?.let {
            it.close()
            outgoing.ended(host.name, "the connection was opened again")
        }
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
            // the two repositories release separately. A connection [connect]
            // replaced, or that Forget or Clear cache closed, says nothing
            // more: closing is asynchronous, and a late message would refill
            // what was just deleted, or a forgotten host.
            onMessage = { m ->
                ui {
                    if (connections[host.name] !== connection) return@ui
                    try {
                        received(host, m)
                    } catch (e: JSONException) {
                        // Not retried on its own: the next connection would get the same.
                        unreadable += host.name
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
        clearArchiveRequests(host.name, "The connection ended before the archive state was confirmed. Reconnect to check it.")
        routines.ended(host.name)
        agentNames.ended(host.name)
        agentImages.ended(host.name)
        approvalsFresh[host.name] = false
        policyConnectionEnded(host.name)
        decidingApprovals.keys.filter { it.startsWith("${host.name}/") }.forEach { decidingApprovals.remove(it) }
        status[host.name] = why
        // What the bridge did not show back may not have reached it: back in its field.
        outgoing.ended(host.name, "the connection ended")
        // Nothing will come over a closed connection: stop showing the agent at work.
        val ids = conversations.filter { it.host == host.name }.map { it.id }.toSet()
        typing.keys.filter { it in ids }.forEach { typing.remove(it) }
        ids.forEach { answered(it) }
        steps.keys.filter { it in ids }.forEach { steps.remove(it) }
        // Its agents' commands come again with the next connection.
        commands.keys.filter { it.startsWith("${host.name}/") }.forEach { commands.remove(it) }
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
        // Once for every message about a conversation: another host's with the same id keeps it.
        val about = m.optString("conversation").ifBlank { null }
        if (about != null && leftOutForClash(conversations, host.name, m.optString("type"), about)) {
            return refusedClash(host.name, about, m.optString("agent").ifBlank { null })
        }
        if (m.optString("type") in setOf("agent.name", "error") && agentNames.received(host.name, m)) return
        if (m.optString("type") in setOf("routine.response", "error") && routines.received(host.name, m)) return
        when (m.optString("type")) {
            "sandbox.policy", "sandbox.policy.preview" -> {
                val of = m.optString("of")
                val address = "${host.name}/${m.getString("sandbox")}"
                if (of.isNotEmpty() && policyRequests[of] != address) return
                if (of.isNotEmpty()) { policyRequests.remove(of); policyBusy.remove(address) }
                if (m.getString("type") == "sandbox.policy") {
                    val policy = SandboxPolicy.from(host.name, m)
                    sandboxPolicies[address] = policy
                    val cached = JSONObject(m.toString()).put("host", host.name).toString()
                    saving.execute { policyCache.edit { putString(address, cached) } }
                    policyFresh[address] = true
                    policyReviews.remove(address)
                } else {
                    val review = PolicyReview.from(m)
                    if (review.base == sandboxPolicies[address]?.base) policyReviews[address] = review
                    else { policyFresh[address] = false; policyErrors[address] = "The policy changed. Refresh and review it again." }
                }
            }
            "approval" -> {
                val request = PolicyRequest.parse(host.name, m) ?: run {
                    android.util.Log.w("enclos", "a malformed sandbox policy request was dropped")
                    return
                }
                approvals[request.key] = request
                decidingApprovals.remove(request.key)
                approvalErrors.remove(request.key)
                val json = JSONObject(m.toString()).put("host", host.name).toString()
                saving.execute { approvalCache.edit { putString(request.key, json) } }
            }
            "synced" -> { approvalsFresh[host.name] = true }
            "authenticated" -> {
                approvalsFresh[host.name] = false
                // A connection that replaced another without ending it: what the old one said is not fresh.
                policyConnectionEnded(host.name)
                approvals.keys.filter { approvals[it]?.host == host.name }.forEach { approvals.remove(it) }
                saving.execute { approvalCache.edit { approvalCache.all.keys.filter { it.startsWith("${host.name}/") }.forEach { remove(it) } } }
                failures.remove(host.name)
                status[host.name] = "connected as ${m.optString("person")}"
                connections[host.name]?.send(message("agents"))
                // What is on the phone is kept: only what is newer comes.
                connections[host.name]?.send(message("sync", "since" to since(host.name)))
            }
            "agent.avatar" -> agentImages.received(host.name, m)
            "agents.list" -> {
                val list = agentsIn(host.name, m)
                agents.removeAll { it.host == host.name }
                agents.addAll(list.map { agentNames.listed(it) })
                val listed = agents.filter { it.host == host.name }
                agentImages.listed(host.name, listed)
                save { agents(host.name, listed) }
            }
            // A conversation and its name: at each sync (so an empty one is
            // known too), when one starts, and when its name changes.
            "conversation" -> {
                val id = m.getString("conversation")
                // Started on the bridge: no longer a draft.
                drafts -= id
                val known = conversationOf(host.name, m)
                // A group started here: the bridge made it.
                startingGroups.values.remove(id)
                val at = conversations.indexOfFirst { it.id == id }
                if (!acceptsConversationMetadata(conversations.getOrNull(at), known)) return
                if (at < 0 || conversations[at] != known) {
                    if (at < 0) conversations.add(known) else conversations[at] = known
                    save { conversation(known) }
                }
                val key = ArchiveKey(host.name, id)
                archiveProblems.remove(key)
                // How far the person read it, on any of their devices: what
                // is before is not unread here. Read further here (offline,
                // say): the bridge is told. Past the newest message here (a
                // sync sends the conversations before their messages): kept
                // until the messages came, never past the newest of them.
                val bridgeRead = if (m.has("read")) m.getLong("read") else 0L
                val newest = lines[id]?.lastOrNull()?.seq ?: 0L
                when (val step = readStep(lastRead[id] ?: 0L, bridgeRead, newest)) {
                    is ReadStep.Take -> {
                        unsettledRead.remove(id)
                        read(id, step.seq)
                    }
                    is ReadStep.Wait -> unsettledRead[id] = step.seq
                    is ReadStep.Tell -> connections[host.name]?.send(message("conversation.read", "conversation" to id, "seq" to step.seq))
                    ReadStep.Same -> Unit
                }
            }
            // The sync's messages came: a read position still past them is
            // taken up to the newest, as the bridge itself never goes further.
            "synced" -> {
                conversations.filter { it.host == host.name && it.id in unsettledRead }.forEach { c ->
                    val newest = lines[c.id]?.lastOrNull()?.seq ?: 0L
                    val seq = settledRead(lastRead[c.id] ?: 0L, unsettledRead.remove(c.id) ?: 0L, newest)
                    if (seq != null) read(c.id, seq)
                }
            }
            "message" -> {
                val id = m.getString("conversation")
                // In a group, who of its members said it; read before anything
                // changes, as a message the app cannot read ends the connection.
                var line = lineOf(host.name, m, group = conversations.firstOrNull { it.id == id }?.group == true)
                // The bridge sends a conversation before its messages, at a
                // sync and when one starts, so this is for an older bridge:
                // the conversation is then taken as one with a single agent,
                // and in a group its speakers would be lost.
                if (conversations.none { it.id == id }) {
                    val started = Conversation(host.name, m.getString("agent"), id)
                    conversations.add(started)
                    save { conversation(started) }
                }
                // The person's own, as the bridge stored it: sent.
                if (line.from != "agent") outgoing.shown(id, line.text)
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
                // The message the bridge said was read up to has come.
                val unsettled = unsettledRead[id]
                if (unsettled != null && line.seq >= unsettled) {
                    unsettledRead.remove(id)
                    if (unsettled > (lastRead[id] ?: 0L)) read(id, unsettled)
                }
            }
            // Where a group's discussion is.
            "group.state" -> groups[m.getString("conversation")] = groupStatusOf(host.name, m)
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
            // An agent's commands, the whole list: replaces the one before.
            "commands" -> {
                val list = m.optJSONArray("commands")
                commands["${host.name}/${m.getString("agent")}"] = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val c = list!!.optJSONObject(i) ?: return@mapNotNull null
                    val name = c.optString("name").ifBlank { return@mapNotNull null }
                    AgentCommand(name, c.optString("description"), c.optString("args").ifBlank { null })
                }.distinctBy { it.name } // The bridge sends each name once; the app does not count on it.
            }
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
            "ack" -> {
                val request = m.optString("of")
                val archive = archiveRequests[request]?.takeIf { it.host == host.name }
                if (archive != null) {
                    // The bridge sends canonical metadata directly before this ack.
                    // It was applied, or ignored because a newer revision already won.
                    // The receipt finishes saving; it never supplies the archive flag.
                    archiveRequests.remove(request)
                    archivePending.remove(archive)
                }
            }
            "error" -> {
                val request = m.optString("of")
                val archive = archiveRequests[request]?.takeIf { it.host == host.name }
                if (archive != null) {
                    archiveRequests.remove(request)
                    archivePending.remove(archive)
                    archiveProblems[archive] = m.optString("message").ifBlank { "The archive state could not be changed." }
                    return
                }
                if (agentImages.failure(m.optString("of"), m.optString("message"))) return
                val policyAddress = policyRequests.remove(m.optString("of"))
                if (policyAddress != null) {
                    policyBusy.remove(policyAddress)
                    // A person the host shared an agent with: the sandbox page drops its Policy row.
                    if (policyIsOwnerOnly(m.optString("message"))) notPolicyOwner.add(policyAddress)
                    policyErrors[policyAddress] = m.optString("message")
                    policyReviews.remove(policyAddress)
                    return
                }
                val approval = decidingApprovals.entries.firstOrNull { it.value == m.optString("of") }?.key
                if (approval != null) {
                    decidingApprovals.remove(approval)
                    approvalErrors[approval] = m.optString("message")
                }
                // An error about a file asked for fails that file; else it is the bridge's word.
                val file = asked.remove(m.optString("of"))
                if (file != null) {
                    fetching.remove(file)
                    files[file] = FileState.Failed(m.optString("message"))
                } else {
                    val of = m.optString("of")
                    // A group the bridge would not start: gone from the phone, saying why.
                    val group = startingGroups.remove(of)
                    if (group != null) {
                        refused(group, m.optString("message").ifBlank { "the bridge refused it" })
                    } else {
                        // A message it refused goes back in its field, saying why; any other is the bridge's word.
                        if (outgoing.refused(of, m.optString("message").ifBlank { "the bridge refused it" }) == null) {
                            said[host.name] = m.optString("message")
                        }
                        // The bridge refused a message of the person's (the agent is
                        // not reachable, say): nothing will answer it now.
                        unanswered.refused(of)?.let { stillAwaiting(it) }
                    }
                }
            }
        }
    }

    /**
     * Conversations started on this phone with no message sent yet: drafts,
     * which the bridge learns of with their first message, so a conversation
     * opened and left leaves nothing behind.
     */
    private val drafts = mutableSetOf<String>()

    /** Starts a conversation with [agent], a draft until its first message; returns its id. */
    fun newConversation(agent: Agent): String {
        val id = UUID.randomUUID().toString()
        conversations.add(Conversation(agent.host, agent.name, id))
        drafts += id
        return id
    }

    /** The screen of [conversation] was left: a draft with nothing sent goes, as if never started. */
    fun left(conversation: String) {
        if (conversation !in drafts || !lines[conversation].isNullOrEmpty()) return
        drafts -= conversation
        conversations.removeAll { it.id == conversation }
    }

    /** Whether the inbox lists [conversation]: once something was said in it, an agent's Home and a group always. */
    fun listed(conversation: Conversation) = listedWith(conversation, lines[conversation.id].orEmpty())

    /** Who a status line of [conversation] is from: its agent's runtime, else the agent. */
    fun statusSource(conversation: Conversation): String =
        agents.firstOrNull { it.host == conversation.host && it.name == conversation.agent }?.runtime?.ifBlank { null }
            ?: conversation.agent.substringAfter('/')

    /**
     * Starts a group of [members] (two to six agents of one sandbox of
     * one host) named [title] (their names when blank); returns its id.
     * The bridge confirms it with the group's conversation.
     */
    fun newGroup(members: List<Agent>, title: String): String? {
        val host = members.firstOrNull()?.host ?: return null
        if (!connected(host)) return null
        val connection = connections[host] ?: return null
        val id = UUID.randomUUID().toString()
        val names = members.map { it.name }
        val message = message("group.new", "conversation" to id, "members" to org.json.JSONArray(names))
        if (title.isNotBlank()) message.put("title", title.trim())
        if (!connection.send(message)) return null
        startingGroups[message.getString("id")] = id
        // Shown at once, as the bridge will send it: its members `sandbox/agent`, as the bridge's are.
        conversations.add(
            Conversation(host, names.first().substringBefore('/'), id, title.trim().ifBlank { members.joinToString(", ") { it.agent } }, "person",
                members = names),
        )
        return id
    }

    /** By the id of a `group.new` sent: its group, until the bridge sends the group back, or refuses it. */
    private val startingGroups = mutableMapOf<String, String>()

    /** By group: why the bridge would not start it; said where it was started. */
    val refusedGroups = mutableStateMapOf<String, String>()

    /** The bridge would not start [group]: it leaves the phone, and [why] is kept to say so. */
    private fun refused(group: String, why: String) {
        conversations.removeAll { it.id == group }
        groups.remove(group)
        lines.remove(group)
        answered(group)
        refusedGroups[group] = why
    }

    /** Drops [id] if it is still a draft (no message sent): a new conversation's screen left, or its agent changed. */
    fun dropDraft(id: String) {
        if (!drafts.remove(id)) return
        conversations.removeAll { it.id == id }
    }

    val sandboxPolicies = mutableStateMapOf<String, SandboxPolicy>()
    val policyBusy = mutableStateMapOf<String, String>()
    val policyFresh = mutableStateMapOf<String, Boolean>()
    val policyErrors = mutableStateMapOf<String, String>()
    val policyReviews = mutableStateMapOf<String, PolicyReview>()
    private val policyRequests = mutableMapOf<String, String>()
    /** Sandboxes whose host said this person is not its own: only the host's person manages a policy. */
    val notPolicyOwner = mutableStateListOf<String>()

    /** [host]'s connection ended or was replaced: its policies are no longer fresh, and no answer will come to what was asked. */
    private fun policyConnectionEnded(host: String) {
        policyFresh.keys.filter { it.startsWith("$host/") }.forEach { policyFresh[it] = false; policyReviews.remove(it) }
        policyRequests.filterValues { it.startsWith("$host/") }.keys.toList().forEach { id ->
            val address = policyRequests.remove(id)
            if (address != null) { policyBusy.remove(address); policyErrors[address] = "The connection ended. Refresh the policy to check the outcome before retrying." }
        }
    }

    private fun policyCommand(address: String, command: JSONObject) {
        val host = address.substringBefore('/')
        if (!connected(host) || address in policyBusy) return
        val id = command.getString("id")
        policyBusy[address] = id
        policyRequests[id] = address
        policyErrors.remove(address)
        main.postDelayed({
            if (policyBusy[address] == id) {
                policyBusy.remove(address); policyRequests.remove(id); policyFresh[address] = false
                policyReviews.remove(address)
                policyErrors[address] = "The host has not confirmed the outcome. Refresh the policy before trying again."
            }
        }, 60_000)
        if (connections[host]?.send(command) != true) {
            policyBusy.remove(address); policyRequests.remove(id)
            policyErrors[address] = "The change was not sent. Reconnect and refresh the policy."
        }
    }
    fun loadPolicy(address: String) {
        if (address in policyBusy) return
        policyFresh[address] = false
        policyReviews.remove(address)
        policyCommand(address, message("sandbox.policy.get", "sandbox" to address.substringAfter('/')))
    }
    fun previewPolicy(address: String, edit: PolicyEdit) {
        val policy = sandboxPolicies[address] ?: return
        if (!policyEditable(true, policy, policyFresh[address] == true, false) || (edit.after != null && Visible.hasHidden(edit.after))) return
        policyCommand(address, message("sandbox.policy.preview", "sandbox" to address.substringAfter('/'), "base" to policy.base, "change" to edit.json()))
    }
    fun applyPolicy(address: String, review: PolicyReview) {
        if (!policyReviewSavable(review, sandboxPolicies[address], true, policyFresh[address] == true, false)) return
        policyCommand(address, message("sandbox.policy.apply", "sandbox" to address.substringAfter('/'), "base" to review.base, "revision" to review.revision, "change" to review.edit.json()))
    }
    private fun forgetPolicy(host: String) {
        saving.execute { policyCache.edit { policyCache.all.keys.filter { it.startsWith("$host/") }.forEach { remove(it) } } }
        val prefix = "$host/"
        notPolicyOwner.removeAll { it.startsWith(prefix) }
        sandboxPolicies.keys.filter { it.startsWith(prefix) }.forEach { sandboxPolicies.remove(it) }
        policyFresh.keys.filter { it.startsWith(prefix) }.forEach { policyFresh.remove(it) }
        policyBusy.keys.filter { it.startsWith(prefix) }.forEach { policyBusy.remove(it) }
        policyErrors.keys.filter { it.startsWith(prefix) }.forEach { policyErrors.remove(it) }
        policyReviews.keys.filter { it.startsWith(prefix) }.forEach { policyReviews.remove(it) }
        policyRequests.entries.removeAll { it.value.startsWith(prefix) }
    }

    /** No offline queue or optimistic approval: the host confirms the saved decision. */
    fun decideApproval(request: PolicyRequest, approve: Boolean) {
        if (!request.pending || !connected(request.host) || approvalsFresh[request.host] != true || request.key in decidingApprovals || (approve && !request.available)) return
        val command = message("approval.decide", "request" to request.id, "revision" to request.revision, "approve" to approve)
        if (connections[request.host]?.send(command) == true) {
            decidingApprovals[request.key] = command.getString("id")
            approvalErrors.remove(request.key)
        } else approvalErrors[request.key] = "The decision was not sent. Reconnect and try again."
    }

    /** Stops group [conversation]'s discussion; what was said stays. */
    fun stopGroup(conversation: Conversation) {
        connections[conversation.host]?.send(message("group.stop", "conversation" to conversation.id))
    }

    /** By group: where its discussion is, as its bridge said last. */
    val groups = mutableStateMapOf<String, GroupStatus>()

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

    /** The folder of [host]'s attachment [id], to delete; none for an id that is not one folder's name. */
    private fun attachmentFolder(host: String, id: String) =
        if (attachmentFolderName(id)) java.io.File(java.io.File(java.io.File(getApplication<Application>().cacheDir, "attachments"), Attachments.hostFolder(host)), id) else null

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
            // A draft's name stays here: the bridge does not know the
            // conversation yet, and is given the name with its first message.
            conversation.id in drafts ||
                connected(conversation.host) &&
                connections[conversation.host]?.send(
                    message("conversation.rename", "conversation" to conversation.id, "title" to name),
                ) == true
        }

    /** What the person writes and sends, by conversation, until the bridge has it ([Outgoing]). */
    val outgoing = Outgoing()

    /** By conversation: what the person wrote and has not sent yet, kept while the app runs. */
    val unsent get() = outgoing.unsent

    /** Each message's folded words, for search, kept while the app runs. */
    val folds = Folds()

    /**
     * Sends [text] in [conversation]; why it did not leave the field, or
     * `null` once it did. It goes only while the host is connected: the
     * screen does not offer it otherwise. Should the connection end, or the
     * bridge refuse it, before the bridge shows it back, it comes back to the
     * field ([Outgoing]).
     */
    fun send(conversation: Conversation, text: String): String? {
        said.remove(conversation.host)
        // A draft becomes a conversation on the bridge with its first message
        // ([firstSent]); it stays a draft until the bridge sends it back, so a
        // `conversation.new` lost with the connection goes again.
        val draft = conversation.id in drafts
        val named = conversations.firstOrNull { it.id == conversation.id } ?: conversation
        val frames = firstSent(named, draft, text).map { message(it.type, *it.fields.toTypedArray()) }
        val out = frames.last()
        if (!fits(out)) {
            return "Too long to send: ${text.toByteArray(Charsets.UTF_8).size / 1000} KB, and at most about 63 KB go at once."
        }
        val sent = outgoing.send(conversation.id, conversation.host, text) transmit@{
            if (!connected(conversation.host)) return@transmit null
            val connection = connections[conversation.host] ?: return@transmit null
            for ((i, frame) in frames.withIndex()) {
                // Not started on the bridge, or the message not handed over: it stays in the field.
                if (!connection.send(frame) && (i == 0 || frame === out)) return@transmit null
            }
            out.getString("id")
        }
        if (!sent) return "Not sent: not connected to ${conversation.host}. Your message is still here."
        unanswered.sent(out.getString("id"), conversation.id, SystemClock.elapsedRealtime())
        awaiting[conversation.id] = true
        // A new turn: steps left by one that ended with no answer are not its own.
        steps.remove(conversation.id)
        main.postDelayed({ stillAwaiting(conversation.id) }, unanswered.limit)
        return null
    }

    /** What prevents a server-confirmed archive change; nothing is queued offline. */
    fun archiveUnavailable(conversation: Conversation): String? = when {
        archivePending.containsKey(ArchiveKey(conversation.host, conversation.id)) -> "Saving archive state…"
        conversation.id in drafts -> "Send the first message before archiving."
        !connected(conversation.host) -> "Connect to ${conversation.host} to change archive state."
        else -> null
    }

    fun archiving(conversation: Conversation) = archivePending.containsKey(ArchiveKey(conversation.host, conversation.id))
    fun archiveError(conversation: Conversation) = archiveProblems[ArchiveKey(conversation.host, conversation.id)]

    /** The bridge owns archive state; only its conversation echo updates the phone. */
    fun archive(conversation: Conversation, archived: Boolean): Boolean {
        val key = ArchiveKey(conversation.host, conversation.id)
        if (archiveUnavailable(conversation) != null) return false
        val connection = connections[conversation.host] ?: return false
        val command = message("conversation.archive", "conversation" to conversation.id, "archived" to archived)
        archiveProblems.remove(key)
        if (!connection.send(command)) {
            archiveProblems[key] = "Not saved: the connection ended. Reconnect to check the archive state."
            return false
        }
        val id = command.getString("id")
        archiveRequests[id] = key
        archivePending[key] = archived
        // As for policy and photo requests: no answer in a minute, and the person may try again.
        main.postDelayed({
            if (archiveRequests[id] == key) {
                archiveRequests.remove(id)
                archivePending.remove(key)
                archiveProblems[key] = "The host has not confirmed the archive state. Reconnect and check it before trying again."
            }
        }, 60_000)
        return true
    }

    /**
     * Conversations of a host left out because another host's has the same id, by host:
     * the person's other host keeps them, this phone shows the first one it was given.
     */
    val clashes = mutableStateMapOf<String, Set<String>>()

    /** By host: the agents (`sandbox/agent`) whose conversation was left out for that reason. */
    private val clashAgents = mutableStateMapOf<String, Set<String>>()

    /** How many of [host]'s conversations are left out because another host's has the same id. */
    fun leftOut(host: String): Int = clashes[host]?.size ?: 0

    /** Whether a conversation of [agent] (its Home, most often) is left out because another host's has the same id. */
    fun leftOut(agent: Agent): Boolean = agent.name in clashAgents[agent.host].orEmpty()

    private fun refusedClash(host: String, id: String, agent: String?) {
        if (agent != null && agent !in clashAgents[host].orEmpty()) clashAgents[host] = clashAgents[host].orEmpty() + agent
        if (id in clashes[host].orEmpty()) return
        clashes[host] = clashes[host].orEmpty() + id
        // No host name in the log: the screens say which.
        android.util.Log.w("enclos", "a conversation has the id of another host's and is left out")
    }

    private fun clearArchiveRequests(host: String, problem: String? = null) {
        val keys = archivePending.keys.filter { it.host == host }
        keys.forEach { key ->
            archivePending.remove(key)
            if (problem != null) archiveProblems[key] = problem
        }
        archiveRequests.entries.removeAll { it.value.host == host }
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

    /**
     * Forgets host [name] on this phone: tells its bridge to unpair this
     * device when connected (`device.forget`; else it stays listed there
     * until `enclos app unpair`), then deletes the device's keys for it and
     * all the phone kept from it: agents, conversations, messages, files,
     * how far they were read. Returns whether the bridge was told.
     */
    fun forgetHost(name: String): Boolean {
        val host = paired.firstOrNull { it.name == name } ?: return false
        // Sent before the close, which OkHttp queues after it.
        val told = connections[name]?.send(message("device.forget")) == true
        connections.remove(name)?.close()
        failures.remove(name)
        hosts.remove(name)
        // The Keystore off the main thread; should it fail, the next start's sweep deletes the key.
        saving.execute {
            try {
                Keys.deleteDeviceKey(host.keyAlias)
            } catch (e: Exception) {
                android.util.Log.w("enclos", "the forgotten host's key was not deleted: ${e.javaClass.simpleName}")
            }
        }
        paired.removeAll { it.name == name }
        status.remove(name)
        said.remove(name)
        unfolded.removeAll { it.startsWith("$name/") }
        commands.keys.filter { it.startsWith("$name/") }.forEach { commands.remove(it) }
        // Whether its heading was folded: nothing left to fold, and a host paired again under that name starts open.
        if (folded.remove(hostFold(name))) settings.edit { putStringSet("folded", folded.toSet()) }
        val ids = conversations.filter { it.host == name }.map { it.id }.toSet()
        val attached = ids.flatMap { lines[it].orEmpty() }.flatMap { it.attachments }
        unreadable.remove(name)
        outgoing.forget(ids)
        drafts -= ids
        ids.forEach {
            groups.remove(it)
            typing.remove(it)
            answered(it)
            steps.remove(it)
            lastRead.remove(it)
        }
        reads.edit { ids.forEach { remove(it) } }
        attached.forEach {
            fetching.remove(it.id)
            files.remove(it.id)
        }
        // The files, off the main thread, after what is queued: those of the
        // messages shown, and those of messages kept but not read yet (the
        // second pass of the read may not have ended, or failed), before the
        // store forgets them ([forget] queues that next).
        saving.execute {
            val kept = try {
                store.attachmentIds(name)
            } catch (e: android.database.SQLException) {
                emptyList()
            }
            (attached.map { it.id } + kept).toSet().forEach { id -> attachmentFolder(name, id)?.deleteRecursively() }
        }
        forget(name)
        return told
    }

    /**
     * Clears what this phone keeps (conversations, messages, agents,
     * downloaded files) and syncs everything again from each bridge.
     * Pairings stay, and so does how far each conversation was read.
     */
    fun clearCache() {
        paired.forEach { agentNames.forget(it.name) }
        paired.forEach { routines.forget(it.name) }
        archiveRequests.clear(); archivePending.clear(); archiveProblems.clear(); clashes.clear(); clashAgents.clear()
        agentImages.clear(paired.map { it.name })
        sandboxPolicies.clear(); policyBusy.clear(); policyFresh.clear(); policyErrors.clear(); policyReviews.clear(); policyRequests.clear(); notPolicyOwner.clear()
        saving.execute { policyCache.edit { clear() } }
        approvals.clear()
        approvalsFresh.clear()
        decidingApprovals.clear()
        approvalErrors.clear()
        saving.execute { approvalCache.edit { clear() } }
        conversations.clear()
        // What was written goes too: its conversations are gone, and drafts do not come back.
        outgoing.clear()
        drafts.clear()
        groups.clear()
        lines.clear()
        agents.clear()
        kept.clear()
        steps.clear()
        fetching.clear()
        asked.clear()
        files.clear()
        commands.clear()
        save { clear() }
        saving.execute { java.io.File(getApplication<Application>().cacheDir, "attachments").deleteRecursively() }
        // Each connection again, its sync from the start.
        if (foreground && ready) paired.forEach { connect(it) }
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
        clashes.remove(host)
        clashAgents.remove(host)
        clearArchiveRequests(host)
        archiveProblems.keys.filter { it.host == host }.forEach { archiveProblems.remove(it) }
        agentImages.forget(host)
        agentNames.forget(host)
        routines.forget(host)
        forgetPolicy(host)
        val ids = conversations.filter { it.host == host }.map { it.id }.toSet()
        conversations.removeAll { it.host == host }
        ids.forEach { lines.remove(it) }
        ids.forEach { unsettledRead.remove(it) }
        agents.removeAll { it.host == host }
        approvals.keys.filter { approvals[it]?.host == host }.forEach { approvals.remove(it); decidingApprovals.remove(it); approvalErrors.remove(it) }
        approvalsFresh.remove(host)
        saving.execute { approvalCache.edit { approvalCache.all.keys.filter { it.startsWith("$host/") }.forEach { remove(it) } } }
        kept.remove(host)
        saving.execute { gaps.clear(host) }
        save { forget(host) }
    }

    override fun onCleared() {
        agentImages.close()
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
            avatarRevision = a.optJSONObject("avatar")?.optLong("revision") ?: 0,
            avatarVersion = a.optJSONObject("avatar")?.let { v -> v.optString("version").takeUnless { v.isNull("version") || it.isBlank() } },
            canEditAvatar = a.optBoolean("can_edit_avatar"),
            supportsAvatars = a.has("can_edit_avatar"),
            // Absent from an older host: no routine controls.
            canManageRoutines = a.optBoolean("can_manage_routines"),
            displayName = a.optString("display_name").takeUnless { a.isNull("display_name") || it.isBlank() }?.let { runCatching { normalizeAgentName(it) }.getOrNull() },
            nameRevision = a.optLong("name_revision").coerceAtLeast(0),
            canEditName = a.optBoolean("can_edit_name"),
            supportsNames = a.has("can_edit_name"),
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

/** What the bridge's read position of a conversation means for this device's. */
internal sealed interface ReadStep {
    /** Read further on another device: this device takes it. */
    data class Take(val seq: Long) : ReadStep

    /** Past the newest message here: taken once the messages came, never past them. */
    data class Wait(val seq: Long) : ReadStep

    /** Read further here (offline, say): the bridge is told. */
    data class Tell(val seq: Long) : ReadStep

    data object Same : ReadStep
}

/** Compares how far a conversation was read [here] and on the [bridge], with the [newest] message here. */
internal fun readStep(here: Long, bridge: Long, newest: Long): ReadStep = when {
    bridge > here && bridge <= newest -> ReadStep.Take(bridge)
    bridge > here -> ReadStep.Wait(bridge)
    here > bridge -> ReadStep.Tell(here)
    else -> ReadStep.Same
}

/**
 * The sync ended with a read position [unsettled] still past the [newest]
 * message: taken up to that message, as the bridge never goes further.
 * Null when that moves nothing [here].
 */
internal fun settledRead(here: Long, unsettled: Long, newest: Long): Long? =
    minOf(unsettled, newest).takeIf { it > here }

/**
 * Whether a bridge's attachment id names one folder of `cache/attachments`
 * and nothing above it: deleting the folder of `..` would delete the cache.
 */
internal fun attachmentFolderName(id: String) = id.isNotEmpty() && id != "." && id != ".." && '/' !in id && '\\' !in id

/** A message the app sends to a bridge: its type and fields. */
internal data class OutgoingFrame(val type: String, val fields: List<Pair<String, Any?>>)

/**
 * What sending [text] in [conversation] sends, in order, on one connection.
 * A [draft] becomes a conversation on the bridge with its first message
 * (`conversation.new` first), and with the name the person gave it
 * meanwhile, right after, so the bridge's unnamed confirmation does not stay.
 */
internal fun firstSent(conversation: Conversation, draft: Boolean, text: String): List<OutgoingFrame> = buildList {
    if (draft) {
        add(OutgoingFrame("conversation.new", listOf("conversation" to conversation.id, "agent" to conversation.agent)))
        if (conversation.titledBy == "person" && conversation.title != null) {
            add(OutgoingFrame("conversation.rename", listOf("conversation" to conversation.id, "title" to conversation.title)))
        }
    }
    add(OutgoingFrame("message.send", listOf("conversation" to conversation.id, "text" to text)))
}
