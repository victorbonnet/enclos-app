package io.github.victorbonnet.enclos

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import io.github.victorbonnet.enclos.core.authSignatureMessage
import io.github.victorbonnet.enclos.core.decodeBytes
import io.github.victorbonnet.enclos.core.encodeBytes
import io.github.victorbonnet.enclos.core.generateNoiseKeypair
import io.github.victorbonnet.enclos.core.pairingProof
import io.github.victorbonnet.enclos.core.pairingSignatureMessage
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
    /** What the agent's model calls for this answer used, live from the bridge; kept in memory only. */
    val usage: UsageTotal? = null,
    /** In a group, the member who said it (`host/sandbox/agent`); none otherwise. */
    val agent: String? = null,
    /** Typed native question metadata is delivered separately, before this message. */
    val question: String? = null,
    /** The held task this message is about (a card of the same agent), whose card comes before it. */
    val heldTask: String? = null,
    val suggestedReplies: List<String> = emptyList(),
)

/** A file of a message, fetched from the bridge when it is shown. */
data class Attachment(val id: String, val mime: String, val name: String, val size: Long) {
    val mediaType: String get() = mime.substringBefore(';').trim().lowercase()
    /** The name as shown: without hidden characters: a right-to-left override (U+202E) before `fdp.apk` would make it read as `kpa.pdf`. */
    val shownName: String get() = Visible.stripped(name).ifBlank { "file" }
    /**
     * An image the platform decodes, drawn in the bubble. Any other image type (SVG, TIFF, AVIF on
     * an older Android) is a file line, which opens the document viewer with its notice and Share.
     */
    val image: Boolean get() = mediaType in DecodedImages
    /** A static HTML page, opened separately from the message's markdown. */
    val artefact: Boolean get() = mediaType == "text/html"
    val pdf: Boolean get() = mediaType == "application/pdf"
    /** Other text is drawn literally, without fetching resources or executing content. */
    val text: Boolean get() = !artefact && (mediaType.startsWith("text/") || mediaType in setOf("application/json", "application/xml"))
}

/** The image types `BitmapFactory` decodes on every Android the app runs on (9 and later). */
private val DecodedImages = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif", "image/bmp")

/**
 * The name [name] is cached under: letters, digits, `.`, `_`, space and `-` only, no leading dot,
 * at most 80 characters. A longer name is shortened before its extension, which another app may
 * read the type from, so a listed type keeps it ([viewIntentType]).
 */
fun cachedFileName(name: String): String {
    val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trimStart('.')
    val dot = safe.lastIndexOf('.')
    val extension = if (safe.length > 80 && dot > 0 && safe.length - dot <= 16) safe.substring(dot) else ""
    return (safe.dropLast(extension.length).take(80 - extension.length) + extension).ifBlank { "file" }
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

/** The messages whose `conversation` names what this phone keeps by that id: the conversation, its lines, typing, steps, usage, a group's state. */
val conversationScoped = setOf("conversation", "message", "group.state", "typing", "tool", "usage")

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
) {
    fun label(turnOf: String?): String? = when (state) {
        "working" -> if (turnOf != null) "$turnOf is answering · round ${round + 1} of $maxRounds" else "Working"
        "settled" -> "Settled"
        "bounded" -> if (reason == "max_replies") "Paused: reply limit reached" else "Paused after $maxRounds rounds"
        "stopped" -> reason?.let { Visible.stripped(it) }?.takeIf(String::isNotBlank) ?: "Stopped"
        "failed" -> "Failed" + (reason?.let { ": $it" } ?: "")
        else -> null
    }
}

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

/** Missing channels keep their history visible; only a host-confirmed removal can hide it. */
internal fun listedWith(conversation: Conversation, lines: List<Line>, agents: List<Agent>, confirmedRemoved: Boolean = false): Boolean {
    if (conversation.group) return true
    return (conversation.home || lines.isNotEmpty()) &&
        (!confirmedRemoved || agents.any { it.host == conversation.host && it.name == conversation.agent })
}

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
    /** By host: why the last message it sent that the app could not read was skipped, while the app runs. */
    val unreadable = mutableStateMapOf<String, Unreadable>()
    /** Whether a screen of the app is visible: only then are ended connections retried. */
    private var foreground = false

    val paired = mutableStateListOf<Host>().apply { addAll(hosts.all()) }
    /** By host: where its connection stands; none before the first attempt. */
    val links = mutableStateMapOf<String, HostLink>()
    val audit = mutableStateMapOf<String, HostAudit>()
    /** By host: what each agent used over the window last asked for. */
    val hostUsage = mutableStateMapOf<String, HostUsage>()
    /** Each host's last 24 hours by agent, for the agents list and an agent's page. */
    val recentUsage = mutableStateMapOf<String, RecentUsage>()
    private val hostCapabilities = mutableStateMapOf<String, Set<String>>()
    /** By host: the last error its bridge sent, such as an agent it could not reach. */
    val said = mutableStateMapOf<String, String>()
    private val policyCache = application.getSharedPreferences("sandbox-policies", Context.MODE_PRIVATE)
    private val approvalCache = application.getSharedPreferences("policy-requests", Context.MODE_PRIVATE)
    private val taskBoardPrefs = application.getSharedPreferences("task-board-links", Context.MODE_PRIVATE)
    internal val taskBoardLinks = TaskBoardLinks { changes ->
        saving.execute {
            taskBoardPrefs.edit {
                changes.forEach { (key, address) -> if (address == null) remove(key) else putString(key, address) }
            }
        }
    }
    val approvals = mutableStateMapOf<String, PolicyRequest>()
    val approvalsFresh = mutableStateMapOf<String, Boolean>()
    val pendingApprovalActions = mutableStateMapOf<String, PendingPolicyRequestAction>()
    val approvalErrors = mutableStateMapOf<String, String>()
    /** By agent (`host/sandbox/agent`): what it is doing now, live from its bridge; never kept on the phone. */
    val activities = Activities(mutableStateMapOf())
    /** The tasks the bridges report to their host's person, kept on the phone by host ([taskCache]). */
    val tasks = TaskCache(mutableStateMapOf())
    /** The phone's copy of [tasks], by `host/sandbox/id`, in the app's private preferences (never backed up). */
    private val taskCache = application.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    val agents = mutableStateListOf<Agent>()
    private val provisioningClient = ProvisioningClient(
        connected = ::connected,
        send = { host, command -> connections[host]?.send(command) == true },
        agents = { host -> agents.filter { it.host == host } },
    )
    private val sandboxes get() = provisioningClient.sandboxes
    val runtimes get() = provisioningClient.runtimes
    val provisioning get() = provisioningClient.provisioning
    val agentNames = AgentNames(
        send = { host, command -> connections[host]?.send(command) == true },
        connected = ::connected,
        agent = { address -> agents.firstOrNull { it.address == address } },
        hostExists = { host -> paired.any { it.name == host } },
        changed = ::applyAgentName,
        later = { wait, action -> main.postDelayed({ action() }, wait) },
    )
    /** The task sheet: which task it shows, and that task's detail, fetched from its host while it shows. */
    val taskDetails = TaskDetails(
        send = { host, command -> connections[host]?.send(command) == true },
        task = { key -> tasks[key] },
        available = { task -> connected(task.host) && offersTaskDetails(task.assigneeAddress) },
        heldAvailable = ::offersHeldTaskDetails,
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
    val needsYou: NeedsYou = NeedsYou(policies = { approvals.values }, dropped = { android.util.Log.w("enclos", it) }) { host, items ->
        val coverage = needsYou.policyCoverage(host)
        save { openItems(host, items, coverage) }
    }
    val attentionItems: List<OpenItem> by derivedStateOf {
        attentionItems(needsYou.items.values, approvals.values, needsYou.indexedHosts.keys, needsYou.staleHosts.keys, needsYou.coveredPolicies)
    }
    private val attentionByAgent: Map<String, Int> by derivedStateOf { attentionCounts(attentionItems) }
    fun attentionCount(agent: String): Int = attentionByAgent[agent] ?: 0
    var focusedRequest by mutableStateOf<OpenItem?>(null)
    val reminderDismissals = ReminderDismissals(
        index = needsYou,
        connected = ::connected,
        supported = { host -> hostSupports(host, "open_item.dismiss") },
        send = { item, id -> connections[item.host]?.send(message("open_item.dismiss", "kind" to item.kind, "item" to item.id, "agent" to item.agent, "version" to item.version).put("id", id)) == true },
        later = { wait, action -> main.postDelayed({ action() }, wait) },
    )

    val questions = QuestionClient(
        send = { request -> connections[request.question.host]?.send(request.command()) == true },
        connected = ::connected,
        accepts = { question -> paired.any { it.name == question.host } && conversations.any(question::belongsTo) },
        changed = { question -> save { question(question) } },
        later = { wait, action -> main.postDelayed({ action() }, wait) },
        resync = { host -> connections[host]?.send(message("sync", "since" to since(host), "open_items" to true)) == true },
        dropped = { why -> android.util.Log.d("enclos", "question not shown: $why") },
        closeSupported = { host -> hostSupports(host, "question.close") },
        sendClose = { question, id -> connections[question.host]?.send(message("question.close", "question" to question.id, "revision" to question.revision).put("id", id)) == true },
    )
    val heldTasks = HeldTaskClient(
        send = { request -> connections[request.card.host]?.send(request.command()) == true },
        connected = ::connected,
        // Kept even before its Home is known: a sync sends a held card, never its first message again.
        accepts = { card -> paired.any { it.name == card.host } && ConversationKey(card.host, card.conversation) !in deletion.deleted },
        changed = { card -> save { heldTask(card) } },
        later = { wait, action -> main.postDelayed({ action() }, wait) },
        resync = { host -> connections[host]?.send(message("sync", "since" to since(host), "open_items" to true)) == true },
        dropped = { why -> android.util.Log.d("enclos", "held task not shown: $why") },
    )
    val deletion = ConversationDeletion(
        connected = ::connected,
        send = { host, command -> connections[host]?.send(command) == true },
        removed = ::removeConversation,
        later = { wait, action -> main.postDelayed({ action() }, wait) },
        isDraft = { it.id in drafts },
    )
    val forgetting = AgentForgetting(
        connected = ::connected,
        supported = { host -> hostSupports(host, AGENT_FORGET) },
        send = { host, command -> connections[host]?.send(command) == true },
        later = { wait, action -> main.postDelayed({ action() }, wait) },
        removedAgent = taskBoardLinks::forget,
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
            val cachedTasks = taskCache.all.values.mapNotNull { (it as? String)?.let { json -> AgentTask.kept(json) } }
                .filter { t -> paired.any { it.name == t.host } }
            val recent = read { recent() }
            val savedBoardLinks = taskBoardPrefs.all
            // The connections open next: their HTTP client is built here, not on the main thread.
            BridgeConnection.prepare()
            ui {
                taskBoardLinks.restore(savedBoardLinks)
                cachedRequests.forEach { approvals[it.key] = it }
                cachedPolicies.forEach { sandboxPolicies[it.address] = it }
                tasks.load(cachedTasks)
                if (recent != null) {
                    agents.addAll(recent.agents.map(agentNames::listed))
                    deletion.deleted.putAll(recent.deleted.associateWith { true })
                    conversations.addAll(recent.conversations.filter { ConversationKey(it.host, it.id) !in deletion.deleted })
                    recent.questions.forEach { questions.merge(it, cached = true) }
                    needsYou.restore(recent.openItems, recent.indexedHosts, recent.policyCoverage, recent.deleted.toSet())
                    recent.heldTasks.forEach { heldTasks.merge(it, cached = true) }
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
    /** By conversation: what the turn under way used so far, from each `usage` call. */
    val turnUsage = mutableStateMapOf<String, UsageTotal>()
    /** By conversation: what it used in all, as the bridge summed it (sent with the conversation and each call). */
    val usage = mutableStateMapOf<String, UsageTotal>()
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
        keep = { on ->
            settings.edit(commit = true) { putBoolean("lock", on) }
            // Notifications shown before keep their words: they go, as the
            // ones to come say only "New message".
            if (on) Notifier.cancelAll(getApplication())
        },
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
        if (settings.contains("inbox.grouped")) settings.edit { remove("inbox.grouped") }
        // A new FCM token, while the app runs: given at once to each host
        // connected; the others get it when they next connect.
        PushTokens.changed = { token -> ui { connections.keys.toList().forEach { pushToken(it, token) } } }
    }

    private fun ui(block: () -> Unit) = main.post(block)

    /** Gives [host] this phone's FCM token (enclos `docs/protocol.md`, `push.token`). */
    private fun pushToken(host: String, token: String) {
        connections[host]?.send(message("push.token", "kind" to "fcm", "token" to token))
    }

    /**
     * A conversation a notification was tapped for, with its host: opened
     * once the phone holds it, which may be after the sync the connection
     * starts. The person going anywhere else first drops it.
     */
    val openedConversation = mutableStateOf<ConversationKey?>(null)

    /** A notification of [conversation], from [host], was tapped. */
    fun openFromNotification(host: String, conversation: String) {
        if (paired.none { it.name == host }) return
        openedConversation.value = ConversationKey(host, conversation)
    }

    /**
     * A link that names a host already paired with another bridge, waiting
     * for the person to confirm the replacement (docs/spec.md, Pairing).
     */
    val replacing = mutableStateOf<Replacing?>(null)

    fun hostSupports(host: String, capability: String) = capability in hostCapabilities[host].orEmpty()

    fun canReadAudit(host: String) = connected(host) && hostSupports(host, "audit")

    /** Refresh newest entries, or append the next page. No unknown requests to older bridges. */
    fun loadAudit(host: String, older: Boolean = false) {
        if (!canReadAudit(host)) return
        val current = audit[host] ?: HostAudit()
        if (current.request != null) return
        val before = if (older) current.nextBefore ?: return else null
        val request = message("audit")
        if (before != null) request.put("before", before)
        val id = request.getString("id")
        audit[host] = current.loading(id, before)
        if (connections[host]?.send(request) != true) {
            audit[host] = audit.getValue(host).failed(id, "The audit request could not be sent. Reconnect and try again.")
            return
        }
        main.postDelayed({
            audit[host]?.let { audit[host] = it.failed(id, "The host did not return the audit. Try again.") }
        }, 15_000)
    }

    /** Whether [host]'s bridge answers usage windows (its `usage` capability). */
    fun canReadUsage(host: String) = connected(host) && hostSupports(host, "usage")

    /** Ask [host] what each agent used in this person's conversations over [window]. */
    fun loadUsage(host: String, window: UsageWindow) {
        if (!canReadUsage(host)) return
        val now = System.currentTimeMillis()
        val since = now / 1000 - window.seconds
        val request = message("usage.get", "since" to since.coerceAtLeast(0))
        // Bars over time, from a bridge that gives series: by the window's bucket, days at this
        // phone's midnight.
        if (hostSupports(host, "usage.series")) {
            request.put("bucket", window.bucket)
            request.put("offset", java.util.TimeZone.getDefault().getOffset(now) / 1000)
            // The same offset is kept with the report, for its chart.
        }
        val id = request.getString("id")
        val offset = java.util.TimeZone.getDefault().getOffset(now) / 1000L
        hostUsage[host] = (hostUsage[host] ?: HostUsage()).loading(id, window, now / 1000, offset)
        if (connections[host]?.send(request) != true) {
            hostUsage[host] = hostUsage.getValue(host).failed(id, "The usage request could not be sent. Reconnect and try again.")
            return
        }
        main.postDelayed({
            hostUsage[host]?.let { hostUsage[host] = it.failed(id, "The host did not return its usage. Try again.") }
        }, 15_000)
    }

    /**
     * Ask [host] what each agent used over the last 24 hours, unless an answer younger than
     * [RECENT_USAGE_FRESH_SECONDS] or a request is out: the screens that show it call this
     * while they show.
     */
    fun refreshRecentUsage(host: String) {
        if (!canReadUsage(host)) return
        val now = System.currentTimeMillis() / 1000
        val current = recentUsage[host] ?: RecentUsage()
        if (!current.due(now)) return
        val request = message("usage.get", "since" to (now - UsageWindow.Day.seconds).coerceAtLeast(0))
        val id = request.getString("id")
        recentUsage[host] = current.loading(id, now)
        if (connections[host]?.send(request) != true) {
            recentUsage[host] = recentUsage.getValue(host).failed(id, "The usage request could not be sent.")
            return
        }
        main.postDelayed({
            recentUsage[host]?.let { recentUsage[host] = it.failed(id, "The host did not return its usage.") }
        }, 15_000)
    }

    private fun usageEnded(host: String) {
        recentUsage[host]?.let { state ->
            state.request?.let { recentUsage[host] = state.failed(it, "The connection ended.") }
        }
        hostUsage[host]?.let { state ->
            state.request?.let { hostUsage[host] = state.failed(it, "The connection ended. Reconnect to refresh the usage.") }
        }
    }

    private fun auditEnded(host: String) {
        hostCapabilities.remove(host)
        audit[host]?.let { state ->
            state.request?.let { audit[host] = state.failed(it, "The connection ended. Reconnect to refresh the audit.") }
        }
    }

    /** Whether [host] is connected and authenticated. */
    fun connected(host: String) = links[host].connected

    fun canManageSandboxes(host: String) = provisioningClient.canManageSandboxes(host)
    fun sandboxesOf(host: String) = provisioningClient.sandboxesOf(host)
    fun refreshProvisioning(host: String) = provisioningClient.refreshProvisioning(host)
    fun clearProvisioning(key: String) = provisioningClient.clearProvisioning(key)
    fun createSandbox(host: String, name: String, template: String = "minimal") = provisioningClient.createSandbox(host, name, template)
    fun installRuntime(host: String, sandbox: String, runtime: String) = provisioningClient.installRuntime(host, sandbox, runtime)
    private fun provisioningEnded(host: String) = provisioningClient.ended(host)

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
        typing[conversation] == true || awaiting[conversation] == true || steps[conversation].orEmpty().any { !it.done } ||
            conversations.firstOrNull { it.id == conversation }?.let { activities.answering(it.host, conversation) } == true

    /** What the agent at [address] (`host/sandbox/agent`) is doing, as its runtime reports it; none while it is idle. */
    fun activityOf(address: String): AgentActivity? = activities.of(address)

    /** Whether the agent at [address] (`host/sandbox/agent`) reports its tasks: its runtime has the `tasks` capability. */
    fun offersTasks(address: String) = agents.any { it.address == address && "tasks" in it.capabilities }

    /** The tasks of the agent at [address] (`host/sandbox/agent`), those still going first; none when it does not report them. */
    fun tasksOf(address: String): List<AgentTask> = if (offersTasks(address)) tasks.of(address) else emptyList()

    /** The tasks asked for in [conversation], each a card in it, in the order they were placed. */
    fun taskCards(conversation: Conversation): List<AgentTask> =
        tasks.askedIn(conversation.host, conversation.id).filter { offersTasks(it.assigneeAddress) }

    /** Whether the agent at [address] (`host/sandbox/agent`) shares its tasks' details: its channel has the `task_details` capability. */
    fun offersHeldTaskDetails(card: HeldTask): Boolean = connected(card.host) && hostSupports(card.host, "held_task.get") &&
        agents.any { it.host == card.host && it.name == card.agent && "held_task_details" in it.capabilities }

    fun offersTaskDetails(address: String) = agents.any { it.address == address && "task_details" in it.capabilities }

    /** The task the agent at [address] (`host/sandbox/agent`) is working on, as its activity names it, when the phone keeps that task. */
    fun activeTask(address: String): AgentTask? {
        if (!offersTasks(address)) return null
        val sandbox = address.substringAfter('/').substringBefore('/')
        return activityOf(address)?.doing?.filterIsInstance<Doing.Task>()
            ?.firstNotNullOfOrNull { tasks[TaskKey(address.substringBefore('/'), sandbox, it.task)] }
    }

    /** The key a task is kept under on the phone. */
    private fun taskKey(key: TaskKey) = "${key.host}/${key.sandbox}/${key.id}"

    /** Keeps [task] on the phone, off the main thread. */
    private fun keepTask(task: AgentTask) {
        val json = task.json().toString()
        val key = taskKey(task.key)
        saving.execute { taskCache.edit { putString(key, json) } }
    }

    /** Deletes the phone's copy of the tasks of [keys], off the main thread. */
    private fun dropTasks(keys: Collection<TaskKey>) {
        if (keys.isEmpty()) return
        val names = keys.map(::taskKey)
        saving.execute { taskCache.edit { names.forEach { remove(it) } } }
    }

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
                        "sig" to encodeBytes(Keys.sign(attempt.alias, pairingSignatureMessage(session.handshakeHash()))),
                        // This phone's FCM token, when push is on: the host pushes to it.
                        "push" to PushTokens.current(getApplication())?.let(Push::token),
                    )
                },
                // A field missing here ends the connection (BridgeConnection),
                // and with it the attempt; so does an answer that is not JSON.
                // A type the app does not know is answered `unknown_type` and
                // ignored; another known type is ignored here.
                onUnreadable = { skipped -> connection.close("the bridge sent what the app cannot read: ${skipped.why}") },
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
            turnUsage.remove(it.id)
        }
        // Paired again, as another device: what the host sent before is forgotten.
        forget(host.name)
        // Its pushes too: a bridge paired again may count from the start.
        Notifier.forget(getApplication(), host.name)
        paired.removeAll { it.name == host.name }
        paired.add(host)
        // Another bridge under the same name: nothing of the old one stays.
        if (old != null && !old.bridgeKey.contentEquals(host.bridgeKey)) {
            links.remove(host.name)
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
        provisioningEnded(host.name)
        auditEnded(host.name)
        usageEnded(host.name)
        agentNames.ended(host.name)
        routines.ended(host.name)
        questions.ended(host.name)
        needsYou.ended(host.name)
        reminderDismissals.ended(host.name)
        heldTasks.ended(host.name)
        // What the agents were doing comes again after `agents.list`; a sync the old connection began drops nothing.
        activities.ended(host.name)
        tasks.ended(host.name)
        taskDetails.ended(host.name)
        // A connection replaced says nothing more ([ended] is not called): what went over it may be lost.
        connections.remove(host.name)?.let {
            it.close()
            outgoing.ended(host.name, "the connection was opened again")
        }
        // While retrying, why the last attempt ended stays on screen.
        links[host.name] = links[host.name].reopening()
        lateinit var connection: BridgeConnection
        connection = BridgeConnection(
            url = host.url,
            bridgeKey = host.bridgeKey,
            noisePrivate = { Keys.unwrap(host.noiseKeyWrapped) },
            first = { session ->
                val signature = Keys.sign(host.keyAlias, authSignatureMessage(session.handshakeHash()))
                message("auth", *authFields(host.deviceId, encodeBytes(signature)))
            },
            // A message the app cannot read is answered `bad_message` and
            // skipped; the connection goes on (enclos docs/protocol.md,
            // Messages): the two repositories release separately. A
            // connection [connect] replaced, or that Forget or Clear cache
            // closed, says nothing more: closing is asynchronous, and a late
            // message would refill what was just deleted, or a forgotten host.
            onMessage = { m ->
                ui {
                    if (connections[host.name] !== connection) return@ui
                    readOrSkip(m) { received(host, it) }?.let { skip ->
                        skip.answer?.let(connection::send)
                        skip.unreadable?.let { unreadable[host.name] = it }
                    }
                }
            },
            onUnreadable = { skipped -> ui { if (connections[host.name] === connection) unreadable[host.name] = skipped } },
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
        auditEnded(host.name)
        usageEnded(host.name)
        connections.remove(host.name)
        provisioningEnded(host.name)
        deletion.ended(host.name)
        forgetting.ended(host.name)
        clearArchiveRequests(host.name, "The connection ended before the archive state was confirmed. Reconnect to check it.")
        routines.ended(host.name)
        questions.ended(host.name)
        needsYou.ended(host.name)
        reminderDismissals.ended(host.name)
        heldTasks.ended(host.name)
        agentNames.ended(host.name)
        agentImages.ended(host.name)
        approvalsFresh[host.name] = false
        policyConnectionEnded(host.name)
        pendingApprovalActions.keys.filter { it.startsWith("${host.name}/") }.forEach { pendingApprovalActions.remove(it) }
        links[host.name] = HostLink.Ended(why)
        // What the bridge did not show back may not have reached it: back in its field.
        outgoing.ended(host.name, "the connection ended")
        // Nothing will come over a closed connection: stop showing the agent at work.
        val ids = conversations.filter { it.host == host.name }.map { it.id }.toSet()
        typing.keys.filter { it in ids }.forEach { typing.remove(it) }
        ids.forEach { answered(it) }
        steps.keys.filter { it in ids }.forEach { steps.remove(it) }
        turnUsage.keys.filter { it in ids }.forEach { turnUsage.remove(it) }
        // Its agents' commands come again with the next connection.
        commands.keys.filter { it.startsWith("${host.name}/") }.forEach { commands.remove(it) }
        // What its agents were doing is live state: unknown until the next connection says it.
        activities.ended(host.name)
        tasks.ended(host.name)
        taskDetails.ended(host.name)
        // Files being fetched from it stop; asked again, they go on from where they were.
        fetching.filterValues { it.host == host.name }.keys.forEach {
            fetching.remove(it)
            files[it] = FileState.Failed("the connection ended")
        }
        if (!foreground) return
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

    /**
     * The app left the foreground. With push on, it closes its connections:
     * a host pushes only to a person with no device connected, so a
     * connection kept up in the background would keep every notification
     * away (enclos `docs/protocol.md`, Notifications). Without push they stay
     * up while the phone lets them, as before. Back in the foreground,
     * [foreground] opens them again.
     */
    fun background() {
        foreground = false
        if (PushTokens.current(getApplication()) == null) return
        connections.values.toList().forEach { it.close() }
    }

    private fun reopen(name: String) {
        paired.firstOrNull { it.name == name }?.let { connect(it) }
    }

    private fun received(host: Host, m: JSONObject) {
        if (provisioningClient.received(host.name, m)) return
        if (deletion.received(host.name, m)) return
        if (forgetting.received(host.name, m)) return
        val conversationId = m.optString("conversation")
        if (m.optString("type") in conversationScoped && ConversationKey(host.name, conversationId) in deletion.deleted) return
        // Once for every message about a conversation: another host's with the same id keeps it.
        val about = m.optString("conversation").ifBlank { null }
        if (about != null && leftOutForClash(conversations, host.name, m.optString("type"), about)) {
            return refusedClash(host.name, about, m.optString("agent").ifBlank { null })
        }
        if (reminderDismissals.received(host.name, m)) return
        if (m.optString("type") == "open_items") {
            if (needsYou.received(host.name, m)) reminderDismissals.refreshed(host.name)
            return
        }
        if (m.optString("type") in setOf("question", "question.result", "question.closed", "error") && questions.received(host.name, m)) return
        if (m.optString("type") in setOf("held_task", "held_task.result", "error") && heldTasks.received(host.name, m)) return
        if (m.optString("type") in setOf("agent.name", "error") && agentNames.received(host.name, m)) return
        if (m.optString("type") in setOf("routine.response", "error") && routines.received(host.name, m)) return
        if (m.optString("type") in setOf("task.detail", "error") && taskDetails.received(host.name, m)) return
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
                // Never let a delayed pre-close broadcast reopen the row or overwrite its cache.
                if (newestPolicyRequest(approvals[request.key], request) !== request) return
                approvals[request.key] = request
                pendingApprovalActions.remove(request.key)
                approvalErrors.remove(request.key)
                val json = JSONObject(m.toString()).put("host", host.name).toString()
                saving.execute { approvalCache.edit { putString(request.key, json) } }
            }
            "authenticated" -> {
                questions.authenticated(host.name)
                heldTasks.authenticated(host.name)
                approvalsFresh[host.name] = false
                // A connection that replaced another without ending it: what the old one said is not fresh.
                policyConnectionEnded(host.name)
                hostCapabilities[host.name] = hostCapabilitiesIn(m)
                needsYou.authenticated(host.name, hostSupports(host.name, "open_items"))
                if (!hostSupports(host.name, "audit")) audit.remove(host.name)
                if (!hostSupports(host.name, "usage")) {
                    hostUsage.remove(host.name)
                    recentUsage.remove(host.name)
                }
                pendingApprovalActions.keys.filter { it.startsWith("${host.name}/") }.forEach { pendingApprovalActions.remove(it) }
                approvals.keys.filter { approvals[it]?.host == host.name }.forEach { approvals.remove(it) }
                saving.execute { approvalCache.edit { approvalCache.all.keys.filter { it.startsWith("${host.name}/") }.forEach { remove(it) } } }
                failures.remove(host.name)
                links[host.name] = HostLink.Connected(m.optString("person"))
                provisioningClient.authenticated(host.name, hostCapabilitiesIn(m))
                connections[host.name]?.send(message("agents"))
                // The sync sends every task the bridge keeps for this person: those it does not are dropped at `synced`.
                tasks.syncing(host.name)
                forgetting.authenticated(host.name)
                // What is on the phone is kept: only what is newer comes.
                connections[host.name]?.send(message("sync", "since" to since(host.name), "open_items" to true))
                // The token at each connection: the host keeps the last one given.
                // None yet (Firebase could not give one at start): asked again.
                when (val token = PushTokens.current(getApplication())) {
                    null -> PushSetup.askToken(getApplication())
                    else -> pushToken(host.name, token)
                }
            }
            // A usage window this phone asked for.
            "usage.report" -> {
                val of = m.optString("of")
                hostUsage[host.name]?.let { hostUsage[host.name] = it.received(of, m) }
                recentUsage[host.name]?.let { recentUsage[host.name] = it.received(of, m) }
            }
            // A model call of an agent's turn: the turn's usage, and the conversation's new total.
            "usage" -> {
                val id = m.getString("conversation")
                val call = UsageTotal.call(m)
                val answered = if (usageGoesToLastAnswer(working(id), m.optBoolean("background"))) addedToLastAnswer(lines[id].orEmpty(), call) else null
                if (answered != null) lines[id] = answered else turnUsage[id] = (turnUsage[id] ?: UsageTotal()) + call
                m.optJSONObject("total")?.let { usage[id] = UsageTotal.of(it) }
            }
            "audit.list" -> {
                val state = audit[host.name] ?: return
                val of = m.optString("of")
                if (state.request != of || !canReadAudit(host.name)) return
                val list = m.getJSONArray("entries")
                val entries = (0 until list.length()).map { i ->
                    val e = list.getJSONObject(i)
                    HostAuditEntry(e.getLong("seq"), e.getLong("time"), e.getString("user"), e.getString("host"),
                        e.getString("command"), e.optString("target").takeUnless { e.isNull("target") },
                        e.getString("result"), e.optString("message").takeUnless { e.isNull("message") },
                        if (e.isNull("finished")) null else e.getLong("finished"))
                }
                audit[host.name] = state.received(of, entries, if (m.isNull("next_before")) null else m.getLong("next_before"))
            }
            "agent.avatar" -> agentImages.received(host.name, m)
            "agents.list" -> {
                val list = agentsIn(host.name, m)
                agents.removeAll { it.host == host.name }
                agents.addAll(list.map { agentNames.listed(it) })
                val listed = agents.filter { it.host == host.name }
                agentImages.listed(host.name, listed)
                save { agents(host.name, listed) }
                forgetting.listed(host.name, listed.mapTo(HashSet()) { it.name })
                // The bridge sends what each agent not idle is doing right after its list.
                activities.listed(host.name)
            }
            "agent.activity" -> activities.received(host.name, m)
            // A task, new or changed: at each sync and whenever it changes, to the host's person only.
            // An open sheet on it asks for its detail again when its state or comments changed.
            "task" -> {
                val incoming = AgentTask.from(host.name, m)
                val before = tasks[incoming.key]
                val kept = tasks.received(incoming)
                keepTask(kept)
                taskDetails.updated(before, kept)
            }
            "task.removed" -> tasks.removed(host.name, m.getString("agent"), m.getString("task"))?.let { dropTasks(listOf(it)) }
            // A conversation and its name: at each sync (so an empty one is
            // known too), when one starts, and when its name changes.
            "conversation" -> {
                val id = m.getString("conversation")
                // Read before anything changes: one the app cannot read is
                // skipped ([readOrSkip]) and keeps its draft.
                val known = conversationOf(host.name, m)
                val bridgeRead = if (m.has("read")) m.getLong("read") else 0L
                // Started on the bridge: no longer a draft.
                drafts -= id
                // A group started here: the bridge made it.
                startingGroups.values.remove(id)
                val at = conversations.indexOfFirst { it.id == id }
                if (!acceptsConversationMetadata(conversations.getOrNull(at), known)) return
                // What it used in all, as its bridge summed it; none from an older bridge.
                m.optJSONObject("usage")?.let { usage[id] = UsageTotal.of(it) }
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
                approvalsFresh[host.name] = true
                dropTasks(tasks.synced(host.name))
                questions.synced(host.name)
                heldTasks.synced(host.name)
                conversations.filter { it.host == host.name && it.id in unsettledRead }.forEach { c ->
                    val newest = lines[c.id]?.lastOrNull()?.seq ?: 0L
                    val seq = settledRead(lastRead[c.id] ?: 0L, unsettledRead.remove(c.id) ?: 0L, newest)
                    if (seq != null) read(c.id, seq)
                }
            }
            "message" -> {
                val id = m.getString("conversation")
                // In a group, who of its members said it; read before anything
                // changes, as a message the app cannot read is skipped
                // ([readOrSkip]) and must leave nothing half done.
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
                        turnUsage.remove(id)?.let { line = line.copy(usage = it) }
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
                    // The person wrote, maybe from another device: a new turn, whose usage starts from none.
                    if (line.from != "agent") turnUsage.remove(id)
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
                val usageOf = m.optString("of")
                if (hostUsage[host.name]?.request == usageOf) {
                    hostUsage[host.name]?.let { hostUsage[host.name] = it.failed(usageOf, m.optString("message")) }
                    return
                }
                if (recentUsage[host.name]?.request == usageOf) {
                    recentUsage[host.name]?.let { recentUsage[host.name] = it.failed(usageOf, m.optString("message")) }
                    return
                }
                val auditOf = m.optString("of")
                if (audit[host.name]?.request == auditOf) {
                    audit[host.name]?.let { audit[host.name] = it.failed(auditOf, m.optString("message")) }
                    return
                }
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
            // The rest of BRIDGE_TYPES, which the clients above read. A type
            // the app does not know never comes here: BridgeConnection
            // answered it `unknown_type` ([inbound]).
            else -> {}
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

    /** Whether the inbox lists [conversation] with its host's current removal confirmation. */
    fun listed(conversation: Conversation) = listedWith(conversation, lines[conversation.id].orEmpty(), agents,
        forgetting.isRemoved(AgentKey(conversation.host, conversation.agent)))

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

    /** No offline queue or optimistic outcome: the host confirms each action. */
    fun decideApproval(request: PolicyRequest, approve: Boolean) {
        sendApprovalAction(request, if (approve) PolicyRequestAction.Approve else PolicyRequestAction.Deny)
    }

    fun closeApproval(request: PolicyRequest): Boolean = sendApprovalAction(request, PolicyRequestAction.Close)

    private fun sendApprovalAction(request: PolicyRequest, action: PolicyRequestAction): Boolean {
        // Decisions retain the revision the person reviewed; the host rejects a stale one.
        val fields = action.send(request, approvals[request.key], connected(request.host), approvalsFresh[request.host] == true,
            hostSupports(request.host, "approval.close"), request.key in pendingApprovalActions) ?: return false
        val command = message(action.type, *fields.toTypedArray())
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

    // Files. Fetched from the bridge over the conversation's connection, a
    // part at a time, into the app's cache (never backed up), one folder per
    // attachment under the file's own name, which sharing shows.

    /** By attachment id: on the phone, being fetched, or failed; none until it is asked for. */
    val files = mutableStateMapOf<String, FileState>()
    /** A file being fetched: from [host], [at] bytes of it written so far. */
    private class Fetch(val host: String, val a: Attachment, val at: Long)

    /** By attachment id: the file being fetched. */
    private val fetching = mutableMapOf<String, Fetch>()
    private val retiredFiles = mutableSetOf<Pair<String, String>>()
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
        if ((host to a.id) in retiredFiles) return
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
                if ((host to a.id) in retiredFiles) return@ui
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

    /** Buttons send exactly their label and keep any text already in the composer's draft. */
    fun sendSuggested(conversation: Conversation, line: Line, reply: String): String? {
        return outgoing.sendSuggested(conversation.id, line, reply, lines[conversation.id].orEmpty()) { send(conversation, it) }
    }

    /**
     * Sends [text] in [conversation]; why it did not leave the field, or
     * `null` once it did. It goes only while the host is connected: the
     * screen does not offer it otherwise. Should the connection end, or the
     * bridge refuse it, before the bridge shows it back, it comes back to the
     * field ([Outgoing]).
     */
    fun send(conversation: Conversation, text: String): String? {
        if (ConversationKey(conversation.host, conversation.id) in deletion.pending) return "This conversation is being deleted."
        if (ConversationKey(conversation.host, conversation.id) in deletion.deleted) return "This conversation was deleted."
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
        // A new turn: steps and usage left by one that ended with no answer are not its own.
        steps.remove(conversation.id)
        turnUsage.remove(conversation.id)
        main.postDelayed({ stillAwaiting(conversation.id) }, unanswered.limit)
        return null
    }

    /** Removes the confirmed conversation everywhere this phone keeps it. */
    private fun removeConversation(key: ConversationKey) {
        val c = conversations.firstOrNull { it.host == key.host && it.id == key.id }
        val attached = if (c == null) emptyList() else lines[key.id].orEmpty().flatMap { it.attachments }
        questions.forgetConversation(key.host, key.id)
        needsYou.forgetConversation(key.host, key.id)
        heldTasks.forgetConversation(key.host, key.id)
        if (c != null) {
            conversations.removeAll { it.host == key.host && it.id == key.id }
            outgoing.forget(setOf(key.id))
            drafts.remove(key.id)
            groups.remove(key.id)
            lines.remove(key.id)
            typing.remove(key.id)
            answered(key.id)
            steps.remove(key.id)
            turnUsage.remove(key.id)
            usage.remove(key.id)
            lastRead.remove(key.id)
            unsettledRead.remove(key.id)
            reads.edit { remove(key.id) }
        }
        archivePending.remove(ArchiveKey(key.host, key.id))
        archiveProblems.remove(ArchiveKey(key.host, key.id))
        archiveRequests.entries.removeAll { it.value == ArchiveKey(key.host, key.id) }
        retireFiles(key.host, attached.map { it.id })
        save {
            val ids = (attached.map { it.id } + attachmentIds(key.host, key.id)).toSet()
            deleteConversation(key)
            ui {
                retireFiles(key.host, ids)
                // Run after queued attachment writes on their own executor.
                disk.execute { ids.forEach { attachmentFolder(key.host, it)?.deleteRecursively() } }
            }
        }
    }

    private fun retireFiles(host: String, ids: Collection<String>) {
        ids.forEach { id ->
            retiredFiles += host to id
            fetching.remove(id); files.remove(id); asked.values.removeAll { it == id }
        }
    }

    /** What prevents a server-confirmed archive change; nothing is queued offline. */
    fun archiveUnavailable(conversation: Conversation): String? = when {
        ConversationKey(conversation.host, conversation.id) in deletion.pending -> "Deleting conversation…"
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
        Notifier.forget(getApplication(), name)
        // The Keystore off the main thread; should it fail, the next start's sweep deletes the key.
        saving.execute {
            try {
                Keys.deleteDeviceKey(host.keyAlias)
            } catch (e: Exception) {
                android.util.Log.w("enclos", "the forgotten host's key was not deleted: ${e.javaClass.simpleName}")
            }
        }
        paired.removeAll { it.name == name }
        links.remove(name)
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
            turnUsage.remove(it)
            usage.remove(it)
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
        audit.clear()
        // Every host syncs again from the start: what was skipped comes again.
        unreadable.clear()
        paired.forEach { deletion.ended(it.name) }
        paired.forEach { questions.forget(it.name); needsYou.forget(it.name); reminderDismissals.forget(it.name) }
        paired.forEach { heldTasks.forget(it.name) }
        paired.forEach { agentNames.forget(it.name) }
        paired.forEach { routines.forget(it.name) }
        archiveRequests.clear(); archivePending.clear(); archiveProblems.clear(); clashes.clear(); clashAgents.clear()
        agentImages.clear(paired.map { it.name })
        sandboxPolicies.clear(); policyBusy.clear(); policyFresh.clear(); policyErrors.clear(); policyReviews.clear(); policyRequests.clear(); notPolicyOwner.clear()
        saving.execute { policyCache.edit { clear() } }
        approvals.clear()
        approvalsFresh.clear()
        pendingApprovalActions.clear()
        hostCapabilities.clear()
        forgetting.clear()
        approvalErrors.clear()
        saving.execute { approvalCache.edit { clear() } }
        activities.clear()
        tasks.clear()
        taskDetails.close()
        saving.execute { taskCache.edit { clear() } }
        conversations.clear()
        // What was written goes too: its conversations are gone, and drafts do not come back.
        outgoing.clear()
        drafts.clear()
        groups.clear()
        lines.clear()
        agents.clear()
        sandboxes.clear()
        kept.clear()
        steps.clear()
        turnUsage.clear()
        usage.clear()
        hostUsage.clear()
        recentUsage.clear()
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
        provisioningEnded(host)
        taskBoardLinks.forget(host)
        unreadable.remove(host)
        sandboxes.remove(host)
        provisioning.keys.filter { it == host || it.startsWith("$host/") }.forEach { provisioning.remove(it) }
        audit.remove(host)
        hostUsage.remove(host)
        recentUsage.remove(host)
        hostCapabilities.remove(host)
        clashes.remove(host)
        clashAgents.remove(host)
        questions.forget(host)
        needsYou.forget(host)
        reminderDismissals.forget(host)
        heldTasks.forget(host)
        deletion.forget(host)
        forgetting.clear(host)
        clearArchiveRequests(host)
        archiveProblems.keys.filter { it.host == host }.forEach { archiveProblems.remove(it) }
        agentImages.forget(host)
        agentNames.forget(host)
        routines.forget(host)
        forgetPolicy(host)
        activities.ended(host)
        tasks.forget(host)
        taskDetails.forget(host)
        saving.execute { taskCache.edit { taskCache.all.keys.filter { it.startsWith("$host/") }.forEach { remove(it) } } }
        val ids = conversations.filter { it.host == host }.map { it.id }.toSet()
        conversations.removeAll { it.host == host }
        ids.forEach { lines.remove(it) }
        ids.forEach { usage.remove(it); turnUsage.remove(it) }
        ids.forEach { unsettledRead.remove(it) }
        agents.removeAll { it.host == host }
        approvals.keys.filter { approvals[it]?.host == host }.forEach { approvals.remove(it) }
        pendingApprovalActions.keys.filter { it.startsWith("$host/") }.forEach { pendingApprovalActions.remove(it) }
        approvalErrors.keys.filter { it.startsWith("$host/") }.forEach { approvalErrors.remove(it) }
        approvalsFresh.remove(host)
        saving.execute { approvalCache.edit { approvalCache.all.keys.filter { it.startsWith("$host/") }.forEach { remove(it) } } }
        kept.remove(host)
        saving.execute { gaps.clear(host) }
        save { forget(host) }
    }

    override fun onCleared() {
        PushTokens.changed = null
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
    return Line(
        m.getLong("seq"), m.getString("from"), m.getString("text"), m.getString("kind"), m.optLong("time"), attached,
        // Typed native question metadata is delivered separately, before this message.
        question = (m.opt("question") as? String)?.takeIf { it.isNotBlank() },
        // A held task's id, without a hidden character, as the protocol carries one; anything else links no card.
        heldTask = protocolId(m.opt("held_task"))?.takeUnless { Visible.hasHidden(it) },
        suggestedReplies = suggestedReplies(m.opt("suggested_replies")),
    )
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
