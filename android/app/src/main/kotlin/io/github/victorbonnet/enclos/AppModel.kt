package io.github.victorbonnet.enclos

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import io.github.victorbonnet.enclos.core.decodeBytes
import io.github.victorbonnet.enclos.core.encodeBytes
import io.github.victorbonnet.enclos.core.generateNoiseKeypair
import io.github.victorbonnet.enclos.core.pairingProof
import io.github.victorbonnet.enclos.core.parsePairLink
import io.github.victorbonnet.enclos.ui.ThemeChoice
import io.github.victorbonnet.enclos.ui.applyTheme
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** An agent of a host, as its bridge lists it: `name` is `<sandbox>/<agent>`. */
data class Agent(val host: String, val name: String, val runtime: String, val capabilities: List<String>, val avatarRevision: Long = 0, val avatarVersion: String? = null, val canEditAvatar: Boolean = false, val supportsAvatars: Boolean = false, val displayName: String? = null, val nameRevision: Long = 0, val canEditName: Boolean = false, val supportsNames: Boolean = false) {
    val sandbox: String get() = name.substringBefore('/')
    val agent: String get() = name.substringAfter('/')
    /** The shared human name; canonical identifiers never change when it does. */
    val label: String get() = displayName ?: agent
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
    /** In a group, the member who said it (`sandbox/agent`); none otherwise. */
    val agent: String? = null,
    /** Typed native question metadata is delivered separately, before this message. */
    val question: String? = null,
)

/** A file of a message, fetched from the bridge when it is shown. */
data class Attachment(val id: String, val mime: String, val name: String, val size: Long) {
    val mediaType: String get() = mime.substringBefore(';').trim().lowercase()
    val image: Boolean get() = mediaType.startsWith("image/")
    /** A static HTML page, opened separately from the message's markdown. */
    val artefact: Boolean get() = mediaType == "text/html"
    val pdf: Boolean get() = mediaType == "application/pdf"
    /** Other text is drawn literally, without fetching resources or executing content. */
    val text: Boolean get() = !artefact && (mediaType.startsWith("text/") || mediaType in setOf("application/json", "application/xml"))
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

/** A delayed broadcast or sync must not overwrite newer metadata from the same host. */
fun acceptsConversationMetadata(current: Conversation?, incoming: Conversation): Boolean =
    incoming.revision >= 0 && (current == null || current.host != incoming.host || current.id != incoming.id || incoming.revision >= current.revision)

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

/** A message the runtime sends about itself (gateway online, shutting down): a quiet line, never unread. */
val Line.status: Boolean get() = kind == "status"

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
    /** By host: its connection while it is open or opening, none once it ended. */
    private val connections = mutableMapOf<String, BridgeConnection>()
    /** By host: failed attempts since it was last authenticated, for the backoff. */
    private val failures = mutableMapOf<String, Int>()
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
    val pendingApprovalActions = mutableStateMapOf<String, PendingPolicyRequestAction>()
    val approvalCloseSupported = mutableStateMapOf<String, Boolean>()
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
        supported = { address -> agents.any { it.address == address && "routines" in it.capabilities } },
        later = { wait, action -> main.postDelayed({ action() }, wait) },
    )
    val agentImages = AgentImages(application,
        send = { host, command -> connected(host) && connections[host]?.send(command) == true },
        connected = ::connected,
        agent = { address -> agents.firstOrNull { it.address == address } },
        hostExists = { host -> paired.any { it.name == host } },
    )
    val conversations = mutableStateListOf<Conversation>()
    val lines = mutableStateMapOf<String, List<Line>>()
    val questions = QuestionClient(
        send = { request -> connections[request.question.host]?.send(request.command()) == true },
        connected = ::connected,
        accepts = { question -> paired.any { it.name == question.host } && conversations.any(question::belongsTo) },
        changed = { question -> save { question(question) } },
        later = { wait, action -> main.postDelayed({ action() }, wait) },
    )
    private data class ArchiveKey(val host: String, val conversation: String)
    private val archiveRequests = mutableMapOf<String, ArchiveKey>()
    private val archivePending = mutableStateMapOf<ArchiveKey, Boolean>()
    private val archiveProblems = mutableStateMapOf<ArchiveKey, String>()

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
                    recent.questions.forEach { questions.merge(it, cached = true) }
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
                    // What is shown is the newest of [older], then what came
                    // since, which is newer than all of it: older first, then that.
                    if (id !in known) return@forEach
                    val now = lines[id].orEmpty()
                    if (now.size >= older.size && now.firstOrNull()?.seq == older.first().seq) return@forEach
                    val last = older.last().seq
                    lines[id] = older + now.filter { it.seq > last }
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

    /** Keeps something on the phone; a failed write leaves it to the next sync from the bridge. */
    private fun save(write: Store.() -> Unit) = saving.execute {
        try {
            store.write()
        } catch (e: android.database.SQLException) {
            // Its type only: nothing of what was said goes to logcat.
            android.util.Log.w("enclos", "not kept on the phone: ${e.javaClass.simpleName}")
        }
    }
    /** By conversation: whether the agent said it is writing (`typing`). */
    val typing = mutableStateMapOf<String, Boolean>()
    /** By conversation: a message was sent and the agent has not answered yet. */
    private val awaiting = mutableStateMapOf<String, Boolean>()
    /** By conversation: the tools the agent runs now, live from the bridge. */
    val steps = mutableStateMapOf<String, List<Step>>()
    /**
     * By agent (`host/sandbox/agent`): the commands the person may type to
     * its runtime, as its bridge last sent them; kept while the app runs.
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
        // Written at once (commit), not later: a choice must survive the app being stopped right after.
        settings.edit(commit = true) { if (choice == ThemeChoice.System) remove("theme") else putString("theme", choice.name) }
        applyTheme(getApplication(), choice)
    }

    /** Whether the app lock is on: the person's choice, kept on this device. */
    val lockOn = mutableStateOf(settings.getBoolean("lock", false))
    /** Whether the app is locked now: from its start while the lock is on, and again after a minute away. */
    val locked = mutableStateOf(lockOn.value)
    /** Why the last unlock did not happen, as the system said: shown on the lock screen. */
    val lockSaid = mutableStateOf<String?>(null)
    /** When the app went to the background (elapsed time); none while it shows. */
    private var leftAt: Long? = null
    /** Whether the prompt is still to show on its own for this lock: once, so cancelling it does not bring it back. */
    var promptDue = locked.value

    /** Turns the app lock on or off; on only once the person proved it works ([AppLock], [Unlocker]). */
    fun setLock(on: Boolean) {
        lockOn.value = on
        if (on) lockDropped.value = false
        settings.edit(commit = true) { putBoolean("lock", on) }
    }

    /** The app went to the background now; not while an unlock prompt took the screen. */
    fun left(now: Long) {
        leftAt = now
    }

    /** Whether the lock turned itself off because the phone no longer has a screen lock: said in Settings. */
    val lockDropped = mutableStateOf(false)

    /**
     * The app is back: locked again when the lock is on and it was away
     * long enough. A phone whose screen lock was removed has nothing left
     * to unlock the app with: the lock turns off, rather than lock the
     * person out, and Settings says so.
     */
    fun returned(now: Long) {
        if (lockOn.value && !AppLock.available(getApplication())) {
            setLock(false)
            locked.value = false
            lockDropped.value = true
        }
        if (AppLock.locksOnReturn(lockOn.value, leftAt, now)) {
            locked.value = true
            lockSaid.value = null
            promptDue = true
        }
        leftAt = null
    }

    fun unlocked(ok: Boolean, why: String?) {
        if (ok) locked.value = false
        lockSaid.value = if (ok) null else why
    }

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
        val read = lastRead[conversation] ?: 0
        return lines[conversation].orEmpty().count { it.from == "agent" && !it.status && it.seq > read }
    }

    /** Marks every message of [conversation] read. */
    fun markRead(conversation: String) {
        val last = lines[conversation]?.lastOrNull()?.seq ?: return
        if ((lastRead[conversation] ?: 0) >= last) return
        read(conversation, last)
        // The bridge keeps it for the person's other devices and the next
        // sync; offline, it is sent when the bridge's is seen behind.
        val host = conversations.firstOrNull { it.id == conversation }?.host ?: return
        connections[host]?.send(message("conversation.read", "conversation" to conversation, "seq" to last))
    }

    /** [conversation] is read up to [seq] (here or on another device): kept on this one. */
    private fun read(conversation: String, seq: Long) {
        lastRead[conversation] = seq
        reads.edit { putLong(conversation, seq) }
    }

    /** Pairs with the host whose link was scanned or typed. */
    fun pair(link: String) {
        openedLink.value = null
        val parsed = try {
            // The enclos:// link, also from the https link to a bridge's pairing page.
            parsePairLink(pairLink(link))
        } catch (e: Exception) {
            pairLinkError.value = "Not an enclos pairing link: ${e.message}"
            return
        }
        val address = runCatching { java.net.URI(parsed.url) }.getOrNull()
        if (address?.scheme != "https" || address.host.isNullOrBlank()) {
            pairLinkError.value = "Not an enclos pairing link: its address names no host"
            return
        }
        pairLinkError.value = null
        pairing.value = Pairing(parsed.host, parsed.url.removePrefix("https://"), parsed.expires.toLong())
        val noise = generateNoiseKeypair()
        val alias = "enclos-device-${parsed.host}-${UUID.randomUUID()}"
        Keys.newDeviceKey(alias)
        pairing.value = pairing.value?.copy(keyMade = true)
        lateinit var connection: BridgeConnection
        connection = BridgeConnection(
            url = parsed.url,
            bridgeKey = parsed.key,
            noisePrivate = { noise.privateKey },
            first = { session ->
                message(
                    "pair.request",
                    "name" to Build.MODEL,
                    "device_key" to encodeBytes(Keys.devicePublicKey(alias)),
                    "proof" to encodeBytes(pairingProof(parsed.code, session.handshakeHash())),
                )
            },
            onMessage = { m ->
                when (m.optString("type")) {
                    "pair.accepted" -> {
                        val host = Host(
                            name = parsed.host,
                            url = parsed.url,
                            bridgeKey = parsed.key,
                            deviceId = m.getString("device"),
                            noiseKeyWrapped = Keys.wrap(noise.privateKey),
                            keyAlias = alias,
                            pushKeyWrapped = Keys.wrap(decodeBytes(m.getString("push_key"))),
                        )
                        hosts.add(host)
                        ui {
                            // Paired again, as another device: what the host sent before is forgotten.
                            forget(host.name)
                            paired.removeAll { it.name == host.name }
                            paired.add(host)
                            pairing.value = pairing.value?.copy(paired = true)
                        }
                        connection.close()
                        ui { connect(host) }
                    }
                    "pair.refused" -> {
                        Keys.deleteDeviceKey(alias)
                        ui { pairing.value = pairing.value?.copy(stopped = "Refused on ${parsed.host}: ${m.optString("reason")}") }
                        connection.close()
                    }
                }
            },
            onClosed = { why ->
                ui {
                    val now = pairing.value
                    if (now != null && !now.paired && now.stopped == null) pairing.value = now.copy(stopped = "Not paired: $why")
                }
            },
        )
        connection.open()
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
        if (now != null && !now.paired && now.stopped == null) return
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(link.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val handled = settings.getString("pair.links.handled", "").orEmpty().split(' ').filter { it.isNotEmpty() }
        if (hash in handled) return
        settings.edit(commit = true) { putString("pair.links.handled", (handled.takeLast(19) + hash).joinToString(" ")) }
        pairing.value = null
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
        questions.ended(host.name)
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
            // A connection [connect] replaced, or that Forget or Clear cache
            // closed, says nothing more: closing is asynchronous, and a late
            // message would refill what was just deleted, or a forgotten host.
            onMessage = { m -> ui { if (connections[host.name] === connection) received(host, m) } },
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
        questions.ended(host.name)
        agentNames.ended(host.name)
        agentImages.ended(host.name)
        approvalsFresh[host.name] = false
        approvalCloseSupported.remove(host.name)
        policyFresh.keys.filter { it.startsWith("${host.name}/") }.forEach { policyFresh[it] = false; policyReviews.remove(it) }
        policyRequests.filterValues { it.startsWith("${host.name}/") }.keys.toList().forEach { id ->
            val address = policyRequests.remove(id)
            if (address != null) { policyBusy.remove(address); policyErrors[address] = "The connection ended. Refresh the policy to check the outcome before retrying." }
        }
        pendingApprovalActions.keys.filter { it.startsWith("${host.name}/") }.forEach { pendingApprovalActions.remove(it) }
        status[host.name] = why
        // Nothing will come over a closed connection: stop showing the agent at work.
        val ids = conversations.filter { it.host == host.name }.map { it.id }.toSet()
        typing.keys.filter { it in ids }.forEach { typing.remove(it) }
        awaiting.keys.filter { it in ids }.forEach { awaiting.remove(it) }
        steps.keys.filter { it in ids }.forEach { steps.remove(it) }
        // Files being fetched from it stop; asked again, they go on from where they were.
        fetching.filterValues { it.first == host.name }.keys.forEach {
            fetching.remove(it)
            files[it] = FileState.Failed("the connection ended")
        }
        if (!foreground) return
        val n = failures.getOrDefault(host.name, 0)
        failures[host.name] = n + 1
        val wait = minOf(60_000L, 2_000L shl minOf(n, 5))
        main.postDelayed({ if (foreground && host.name !in connections) reopen(host.name) }, wait)
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
        if (m.optString("type") in setOf("question", "question.result", "error") && questions.received(host.name, m)) return
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
                val request = PolicyRequest.from(host.name, m)
                // Never let a delayed pre-close broadcast reopen the row or overwrite its cache.
                if (newestPolicyRequest(approvals[request.key], request) !== request) return
                approvals[request.key] = request
                pendingApprovalActions.remove(request.key)
                approvalErrors.remove(request.key)
                val json = JSONObject(m.toString()).put("host", host.name).toString()
                saving.execute { approvalCache.edit { putString(request.key, json) } }
            }
            "synced" -> { approvalsFresh[host.name] = true; questions.synced(host.name) }
            "authenticated" -> {
                questions.authenticated(host.name)
                approvalsFresh[host.name] = false
                val capabilities = m.optJSONArray("capabilities")
                approvalCloseSupported[host.name] = (0 until (capabilities?.length() ?: 0)).any { capabilities?.optString(it) == "approval.close" }
                pendingApprovalActions.keys.filter { it.startsWith("${host.name}/") }.forEach { pendingApprovalActions.remove(it) }
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
                agents.removeAll { it.host == host.name }
                val list = m.getJSONArray("agents")
                for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    val capabilities = a.optJSONArray("capabilities")
                    agents.add(
                        agentNames.listed(Agent(
                            host.name,
                            a.getString("agent"),
                            a.optString("runtime"),
                            (0 until (capabilities?.length() ?: 0)).map { capabilities!!.getString(it) },
                            avatarRevision = a.optJSONObject("avatar")?.optLong("revision") ?: 0,
                            avatarVersion = a.optJSONObject("avatar")?.let { v -> v.optString("version").takeUnless { v.isNull("version") || it.isBlank() } },
                            canEditAvatar = a.optBoolean("can_edit_avatar"),
                            supportsAvatars = a.has("can_edit_avatar"),
                            displayName = a.optString("display_name").takeUnless { a.isNull("display_name") || it.isBlank() }?.let { runCatching { normalizeAgentName(it) }.getOrNull() },
                            nameRevision = a.optLong("name_revision").coerceAtLeast(0),
                            canEditName = a.optBoolean("can_edit_name"),
                            supportsNames = a.has("can_edit_name"),
                        )),
                    )
                }
                val listed = agents.filter { it.host == host.name }
                agentImages.listed(listed)
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
                    m.optString("title").ifBlank { null },
                    m.optString("titled_by").ifBlank { null },
                    home = m.optBoolean("home"),
                    members = m.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    archived = m.optBoolean("archived", false),
                    revision = m.optLong("revision", 0),
                )
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
                // say): the bridge is told.
                val bridgeRead = if (m.has("read")) m.getLong("read") else 0L
                val here = lastRead[id] ?: 0L
                if (bridgeRead > here) {
                    read(id, bridgeRead)
                } else if (here > bridgeRead) {
                    connections[host.name]?.send(message("conversation.read", "conversation" to id, "seq" to here))
                }
            }
            "message" -> {
                val id = m.getString("conversation")
                if (conversations.none { it.id == id }) {
                    val started = Conversation(host.name, m.getString("agent"), id)
                    conversations.add(started)
                    save { conversation(started) }
                }
                val files = m.optJSONArray("attachments")
                val attached = (0 until (files?.length() ?: 0)).map {
                    val a = files!!.getJSONObject(it)
                    Attachment(a.getString("id"), a.optString("mime"), a.optString("name"), a.optLong("size"))
                }
                var line = Line(m.getLong("seq"), m.getString("from"), m.getString("text"), m.getString("kind"), m.optLong("time"), attached,
                    question = (m.opt("question") as? String)?.takeIf { it.isNotBlank() })
                // In a group, who of its members said it.
                if (line.from == "agent" && conversations.firstOrNull { it.id == id }?.group == true) {
                    line = line.copy(agent = "${host.name}/${m.getString("agent")}")
                }
                if (line.from == "agent") {
                    val agent = m.getString("agent")
                    // Only a reply answers the person; any other kind (notify,
                    // and later ones) leaves the turn and its steps alone.
                    if (line.kind == "reply") {
                        // An answer ends the turn: the steps that led to it go with it.
                        typing.remove(id)
                        awaiting.remove(id)
                        steps.remove(id)?.let { line = line.copy(steps = it) }
                    } else if (agents.none { it.host == host.name && it.name == agent && "typing" in it.capabilities }) {
                        // A message on its own (a cron job's) while the agent
                        // works keeps the turn's steps and its working state;
                        // only a runtime that says nothing of its typing has
                        // no other sign that a turn ended.
                        awaiting.remove(id)
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
                    val kept = line
                    save { message(host.name, id, kept) }
                }
            }
            // Where a group's discussion is.
            "group.state" -> {
                val id = m.getString("conversation")
                val list = m.optJSONArray("members")
                groups[id] = GroupStatus(
                    state = m.optString("state"),
                    round = m.optInt("round"),
                    maxRounds = m.optInt("max_rounds"),
                    turns = (0 until (list?.length() ?: 0)).map { i ->
                        val t = list!!.getJSONObject(i)
                        "${host.name}/${t.getString("agent")}" to t.optString("turn").ifBlank { null }
                    },
                    reason = m.optString("reason").ifBlank { null },
                )
            }
            "typing" -> {
                val id = m.getString("conversation")
                if (m.optBoolean("on")) {
                    typing[id] = true
                } else {
                    typing.remove(id)
                    awaiting.remove(id)
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
                }
            }
            // A tool the agent starts, or ends: added, or marked done.
            "tool" -> {
                val id = m.getString("conversation")
                val call = m.getString("call")
                val now = steps[id].orEmpty()
                val at = now.indexOfFirst { it.call == call }
                steps[id] = if (m.optString("phase") == "end") {
                    val ended = { s: Step ->
                        s.copy(
                            done = true,
                            ok = m.optBoolean("ok", true),
                            durationMs = if (m.has("duration_ms")) m.getLong("duration_ms") else null,
                        )
                    }
                    if (at >= 0) now.toMutableList().also { it[at] = ended(it[at]) } else now + ended(Step(call, m.getString("name"), null))
                } else if (at < 0) {
                    now + Step(call, m.getString("name"), m.optString("label").ifBlank { null })
                } else {
                    now
                }
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
                    policyErrors[policyAddress] = m.optString("message")
                    policyReviews.remove(policyAddress)
                    return
                }
                val approval = pendingApprovalActions.entries.firstOrNull { it.key.startsWith("${host.name}/") && it.value.messageId == m.optString("of") }?.key
                if (approval != null) {
                    pendingApprovalActions.remove(approval)
                    approvalErrors[approval] = m.optString("message")
                    return
                }
                // An error about a file asked for fails that file; else it is the bridge's word.
                val file = asked.remove(m.optString("of"))
                if (file != null) {
                    fetching.remove(file)
                    files[file] = FileState.Failed(m.optString("message"))
                } else {
                    said[host.name] = m.optString("message")
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

    /** Whether the inbox lists [conversation]: once something was said in it, an agent's Home and a group always. */
    fun listed(conversation: Conversation) = conversation.home || conversation.group || !lines[conversation.id].isNullOrEmpty()

    /**
     * Starts a group of [members] (two to six agents of one sandbox of
     * one host) named [title] (their names when blank); returns its id.
     * The bridge confirms it with the group's conversation.
     */
    fun newGroup(members: List<Agent>, title: String): String? {
        val host = members.firstOrNull()?.host ?: return null
        val connection = connections[host] ?: return null
        val id = UUID.randomUUID().toString()
        val names = members.map { it.name }
        val message = message("group.new", "conversation" to id, "members" to org.json.JSONArray(names))
        if (title.isNotBlank()) message.put("title", title.trim())
        if (!connection.send(message)) return null
        // Shown at once, as the bridge will send it.
        conversations.add(
            Conversation(host, names.first().substringBefore('/'), id, title.trim().ifBlank { members.joinToString(", ") { it.agent } }, "person",
                members = names),
        )
        return id
    }

    val sandboxPolicies = mutableStateMapOf<String, SandboxPolicy>()
    val policyBusy = mutableStateMapOf<String, String>()
    val policyFresh = mutableStateMapOf<String, Boolean>()
    val policyErrors = mutableStateMapOf<String, String>()
    val policyReviews = mutableStateMapOf<String, PolicyReview>()
    private val policyRequests = mutableMapOf<String, String>()

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
        if (policyFresh[address] != true || !policy.editable) return
        policyCommand(address, message("sandbox.policy.preview", "sandbox" to address.substringAfter('/'), "base" to policy.base, "change" to edit.json()))
    }
    fun applyPolicy(address: String, review: PolicyReview) {
        if (policyFresh[address] != true || sandboxPolicies[address]?.base != review.base) return
        policyCommand(address, message("sandbox.policy.apply", "sandbox" to address.substringAfter('/'), "base" to review.base, "revision" to review.revision, "change" to review.edit.json()))
    }
    private fun forgetPolicy(host: String) {
        saving.execute { policyCache.edit { policyCache.all.keys.filter { it.startsWith("$host/") }.forEach { remove(it) } } }
        val prefix = "$host/"
        sandboxPolicies.keys.filter { it.startsWith(prefix) }.forEach { sandboxPolicies.remove(it) }
        policyFresh.keys.filter { it.startsWith(prefix) }.forEach { policyFresh.remove(it) }
        policyBusy.keys.filter { it.startsWith(prefix) }.forEach { policyBusy.remove(it) }
        policyErrors.keys.filter { it.startsWith(prefix) }.forEach { policyErrors.remove(it) }
        policyReviews.keys.filter { it.startsWith(prefix) }.forEach { policyReviews.remove(it) }
        policyRequests.entries.removeAll { it.value.startsWith(prefix) }
    }

    /** No offline queue or optimistic outcome: the host confirms each action. */
    fun decideApproval(request: PolicyRequest, approve: Boolean) {
        sendApprovalAction(request, if (approve) PolicyRequestAction.Approve else PolicyRequestAction.Deny)
    }

    fun closeApproval(request: PolicyRequest): Boolean = sendApprovalAction(request, PolicyRequestAction.Close)

    private fun sendApprovalAction(request: PolicyRequest, action: PolicyRequestAction): Boolean {
        val current = approvals[request.key] ?: return false
        if (!action.allowed(current, connected(request.host), approvalsFresh[request.host] == true,
                approvalCloseSupported[request.host] == true, request.key in pendingApprovalActions)) return false
        // Decisions retain the revision the person reviewed; the host rejects a stale one.
        val command = message(action.type, *action.fields(request).toTypedArray())
        if (connections[request.host]?.send(command) == true) {
            pendingApprovalActions[request.key] = PendingPolicyRequestAction(command.getString("id"), action)
            approvalErrors.remove(request.key)
            return true
        }
        approvalErrors[request.key] = if (action == PolicyRequestAction.Close) "The close request was not sent. Reconnect and try again." else "The decision was not sent. Reconnect and try again."
        return false
    }

    /** Stops group [conversation]'s discussion; what was said stays. */
    fun stopGroup(conversation: Conversation) {
        connections[conversation.host]?.send(message("group.stop", "conversation" to conversation.id))
    }

    /** By group: where its discussion is, as its bridge said last. */
    val groups = mutableStateMapOf<String, GroupStatus>()

    /**
     * Names [conversation] (an empty [title] clears its name, and the agent
     * may name it again); shown at once, and confirmed by the bridge.
     */
    // Files. Fetched from the bridge over the conversation's connection, a
    // part at a time, into the app's cache (never backed up), one folder per
    // attachment under the file's own name, which sharing shows.

    /** By attachment id: on the phone, being fetched, or failed; none until it is asked for. */
    val files = mutableStateMapOf<String, FileState>()
    /** By attachment id: the host and the file being fetched. */
    private val fetching = mutableMapOf<String, Pair<String, Attachment>>()
    /** By the id of an `attachment.get` sent: its attachment. */
    private val asked = mutableMapOf<String, String>()

    private fun folderOf(a: Attachment) = java.io.File(java.io.File(getApplication<Application>().cacheDir, "attachments"), a.id)
    private fun fileOf(a: Attachment) =
        java.io.File(folderOf(a), a.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trimStart('.').take(80).ifBlank { "file" })
    private fun partOf(a: Attachment) = java.io.File(folderOf(a), ".part")

    /** Fetches [a] from [host]'s bridge, or finds it on the phone; again after a failure. */
    fun fetch(host: String, a: Attachment) {
        val done = fileOf(a)
        if (done.isFile) {
            files[a.id] = FileState.Ready(done)
            return
        }
        if (a.id in fetching) return
        folderOf(a).mkdirs()
        // What a broken fetch left is kept: it goes on from there.
        val have = partOf(a).length()
        files[a.id] = FileState.Loading(have, a.size)
        fetching[a.id] = host to a
        ask(host, a.id, have)
    }

    private fun ask(host: String, id: String, offset: Long) {
        val get = message("attachment.get", "attachment" to id, "offset" to offset)
        asked[get.getString("id")] = id
        if (connections[host]?.send(get) != true) {
            fetching.remove(id)
            files[id] = FileState.Failed("not connected")
        }
    }

    private fun received(host: Host, id: String, offset: Long, size: Long, data: String) {
        val (_, a) = fetching[id] ?: return
        val part = partOf(a)
        if (offset != part.length()) return
        part.appendBytes(decodeBytes(data))
        val now = part.length()
        if (now >= size) {
            fetching.remove(id)
            files[id] = if (part.renameTo(fileOf(a))) FileState.Ready(fileOf(a)) else FileState.Failed("cannot keep the file")
        } else {
            files[id] = FileState.Loading(now, size)
            ask(host.name, id, now)
        }
    }

    fun rename(conversation: Conversation, title: String) {
        val name = title.trim()
        renameConversationLocally(conversations, conversation.id, name)
        // A draft's name stays here: the bridge does not know the
        // conversation yet, and is given the name with its first message.
        if (conversation.id in drafts) return
        connections[conversation.host]?.send(
            message("conversation.rename", "conversation" to conversation.id, "title" to name),
        )
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
        if (archived && conversation.home) return false
        val key = ArchiveKey(conversation.host, conversation.id)
        if (archiveUnavailable(conversation) != null) return false
        val connection = connections[conversation.host] ?: return false
        val command = message("conversation.archive", "conversation" to conversation.id, "archived" to archived)
        archiveProblems.remove(key)
        if (!connection.send(command)) {
            archiveProblems[key] = "Not saved: the connection ended. Reconnect to check the archive state."
            return false
        }
        archiveRequests[command.getString("id")] = key
        archivePending[key] = archived
        return true
    }

    private fun clearArchiveRequests(host: String, problem: String? = null) {
        val keys = archivePending.keys.filter { it.host == host }
        keys.forEach { key ->
            archivePending.remove(key)
            if (problem != null) archiveProblems[key] = problem
        }
        archiveRequests.entries.removeAll { it.value.host == host }
    }

    /** By conversation: what the person wrote and has not sent yet, kept while the app runs. */
    val unsent = mutableStateMapOf<String, String>()

    /**
     * Sends [text] in [conversation]; whether it went. It goes only while
     * the host is connected: the screen does not offer it otherwise, and a
     * connection that ended meanwhile leaves the text with the person.
     */
    fun send(conversation: Conversation, text: String): Boolean {
        said.remove(conversation.host)
        val out = message("message.send", "conversation" to conversation.id, "text" to text)
        if (!fits(out)) {
            said[conversation.host] = "Too long to send: ${text.toByteArray(Charsets.UTF_8).size / 1000} KB, and at most about 63 KB go at once."
            return false
        }
        if (!connected(conversation.host)) return false
        val connection = connections[conversation.host] ?: return false
        // A draft becomes a conversation on the bridge with its first message.
        if (conversation.id in drafts) {
            val started = connection.send(
                message("conversation.new", "conversation" to conversation.id, "agent" to conversation.agent),
            )
            if (!started) return false
            drafts -= conversation.id
            val named = conversations.firstOrNull { it.id == conversation.id }
            if (named?.titledBy == "person" && named.title != null) {
                connection.send(message("conversation.rename", "conversation" to conversation.id, "title" to named.title))
            }
        }
        val sent = connection.send(out)
        if (!sent) said[conversation.host] = "Not sent: the connection ended or could not encrypt the message. Your message is still here."
        if (sent) {
            awaiting[conversation.id] = true
            unsent.remove(conversation.id)
        }
        return sent
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
        Keys.deleteDeviceKey(host.keyAlias)
        paired.removeAll { it.name == name }
        status.remove(name)
        said.remove(name)
        commands.keys.filter { it.startsWith("$name/") }.forEach { commands.remove(it) }
        val ids = conversations.filter { it.host == name }.map { it.id }.toSet()
        val attached = ids.flatMap { lines[it].orEmpty() }.flatMap { it.attachments }
        ids.forEach {
            typing.remove(it)
            awaiting.remove(it)
            steps.remove(it)
            lastRead.remove(it)
        }
        reads.edit { ids.forEach { remove(it) } }
        attached.forEach {
            fetching.remove(it.id)
            files.remove(it.id)
        }
        // The files, off the main thread, after what is queued.
        saving.execute { attached.forEach { folderOf(it).deleteRecursively() } }
        forget(name)
        return told
    }

    /**
     * Clears what this phone keeps (conversations, messages, agents,
     * downloaded files) and syncs everything again from each bridge.
     * Pairings stay, and so does how far each conversation was read.
     */
    fun clearCache() {
        paired.forEach { questions.forget(it.name) }
        paired.forEach { agentNames.forget(it.name) }
        paired.forEach { routines.forget(it.name) }
        archiveRequests.clear(); archivePending.clear(); archiveProblems.clear()
        agentImages.clear(paired.map { it.name })
        sandboxPolicies.clear(); policyBusy.clear(); policyFresh.clear(); policyErrors.clear(); policyReviews.clear(); policyRequests.clear()
        saving.execute { policyCache.edit { clear() } }
        approvals.clear()
        approvalsFresh.clear()
        pendingApprovalActions.clear()
        approvalCloseSupported.clear()
        approvalErrors.clear()
        saving.execute { approvalCache.edit { clear() } }
        conversations.clear()
        lines.clear()
        agents.clear()
        kept.clear()
        steps.clear()
        fetching.clear()
        asked.clear()
        files.clear()
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
        questions.forget(host)
        clearArchiveRequests(host)
        archiveProblems.keys.filter { it.host == host }.forEach { archiveProblems.remove(it) }
        agentImages.forget(host)
        agentNames.forget(host)
        routines.forget(host)
        forgetPolicy(host)
        val ids = conversations.filter { it.host == host }.map { it.id }.toSet()
        conversations.removeAll { it.host == host }
        ids.forEach { lines.remove(it) }
        agents.removeAll { it.host == host }
        approvals.keys.filter { approvals[it]?.host == host }.forEach { approvals.remove(it) }
        pendingApprovalActions.keys.filter { it.startsWith("$host/") }.forEach { pendingApprovalActions.remove(it) }
        approvalErrors.keys.filter { it.startsWith("$host/") }.forEach { approvalErrors.remove(it) }
        approvalsFresh.remove(host)
        approvalCloseSupported.remove(host)
        saving.execute { approvalCache.edit { approvalCache.all.keys.filter { it.startsWith("$host/") }.forEach { remove(it) } } }
        kept.remove(host)
        save { forget(host) }
    }

    override fun onCleared() {
        agentImages.close()
        connections.values.forEach { it.close() }
        // What is queued is still written.
        saving.shutdown()
    }
}
