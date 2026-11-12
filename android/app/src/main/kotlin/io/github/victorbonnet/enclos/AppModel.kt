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
import io.github.victorbonnet.enclos.core.PairLink
import io.github.victorbonnet.enclos.core.decodeBytes
import io.github.victorbonnet.enclos.core.encodeBytes
import io.github.victorbonnet.enclos.core.generateNoiseKeypair
import io.github.victorbonnet.enclos.core.pairingProof
import io.github.victorbonnet.enclos.core.parsePairLink
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

/** A message of a conversation; [time] in seconds since the Unix epoch. */
data class Line(val seq: Long, val from: String, val text: String, val kind: String, val time: Long)

/** A conversation: its host, agent (`<sandbox>/<agent>`) and id. */
data class Conversation(val host: String, val agent: String, val id: String)

/** A pairing link naming [old]'s name with another bridge: replaces [old] only once the person confirms. */
data class Replacing(val link: PairLink, val old: Host)

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
    /** By conversation: whether the agent said it is writing (`typing`). */
    val typing = mutableStateMapOf<String, Boolean>()
    /** By conversation: a message was sent and the agent has not answered yet. */
    private val awaiting = mutableStateMapOf<String, Boolean>()
    /** The messages behind [awaiting], by id: a refusal of one names it. */
    private val unanswered = Awaiting()
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
    fun working(conversation: String) = typing[conversation] == true || awaiting[conversation] == true

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
        val parsed = try {
            parsePairLink(link)
        } catch (e: Exception) {
            pairLinkError.value = "Not an enclos pairing link: ${e.message}"
            return
        }
        // Its address must name a host to connect to: `https://` alone passes
        // the link's own check, and makes no WebSocket address.
        val address = runCatching { java.net.URI(parsed.url) }.getOrNull()
        if (address?.scheme != "https" || address.host.isNullOrBlank()) {
            pairLinkError.value = "Not an enclos pairing link: its address, ${parsed.url}, names no host"
            return
        }
        pairLinkError.value = null
        // The link chooses the name it gives: a name already paired is
        // replaced only by its own bridge, or once the person confirms.
        when (val target = pairingTarget(paired, parsed.host, parsed.key)) {
            PairingTarget.New -> start(parsed, null)
            is PairingTarget.Again -> start(parsed, target.old)
            is PairingTarget.Other -> {
                pairing.value = null
                replacing.value = Replacing(parsed, target.old)
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

    /** The pairing [link] starts, as the pairing screen shows it. */
    private fun shown(link: PairLink) = Pairing(link.host, link.url.removePrefix("https://"), link.expires.toLong())

    /**
     * Makes the keys, off the main thread (StrongBox can take seconds), then
     * connects and asks the bridge to pair; in place of [old] once accepted.
     */
    private fun start(parsed: PairLink, old: Host?) {
        val attempt = PairingAttempt(parsed.host, parsed.url, parsed.key, old, Keys, ::decodeBytes)
        pairing.value = shown(parsed)
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
                url = parsed.url,
                bridgeKey = parsed.key,
                noisePrivate = noise.privateKey,
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
        paired.removeAll { it.name == host.name }
        paired.add(host)
        // Another bridge under the same name: nothing of the old one stays.
        if (old != null && !old.bridgeKey.contentEquals(host.bridgeKey)) {
            agents.removeAll { it.host == host.name }
            conversations.filter { it.host == host.name }.forEach {
                lines.remove(it.id)
                typing.remove(it.id)
                answered(it.id)
            }
            conversations.removeAll { it.host == host.name }
            status.remove(host.name)
            said.remove(host.name)
        }
        failures.remove(host.name)
        pairing.value = pairing.value?.copy(paired = true)
        connect(host)
    }

    /** Forgets the pairing shown, to scan another code. */
    fun clearPairing() {
        pairing.value = null
        pairLinkError.value = null
    }

    /**
     * Opens the connection to [host]: `auth`, then its agents and every
     * message since the start. [foreground] opens it, and [ended] again.
     */
    fun connect(host: Host) {
        connections.remove(host.name)?.close()
        // While retrying, why the last attempt ended stays on screen.
        if (status[host.name]?.startsWith("connected") != false) status[host.name] = "connecting"
        lateinit var connection: BridgeConnection
        connection = BridgeConnection(
            url = host.url,
            bridgeKey = host.bridgeKey,
            noisePrivate = Keys.unwrap(host.noiseKeyWrapped),
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
        if (unreadable.remove(host.name) || !foreground) return
        val n = failures.getOrDefault(host.name, 0)
        failures[host.name] = n + 1
        main.postDelayed({ if (foreground && host.name !in connections) reopen(host.name) }, backoff(n))
    }

    /** The app came to the foreground: opens the connection of every host that has none. */
    fun foreground() {
        foreground = true
        paired.filter { it.name !in connections }.forEach { connect(it) }
    }

    /** The app left the foreground: its connections stay up while the phone lets them. */
    fun background() {
        foreground = false
    }

    private fun reopen(name: String) {
        paired.firstOrNull { it.name == name }?.let { connect(it) }
    }

    private fun received(host: Host, m: JSONObject) {
        when (m.optString("type")) {
            "authenticated" -> {
                failures.remove(host.name)
                status[host.name] = "connected as ${m.optString("person")}"
                connections[host.name]?.send(message("agents"))
                connections[host.name]?.send(message("sync", "since" to 0))
            }
            "agents.list" -> {
                val list = agentsIn(host.name, m)
                agents.removeAll { it.host == host.name }
                agents.addAll(list)
            }
            "message" -> {
                val id = m.getString("conversation")
                val line = lineIn(m)
                if (conversations.none { it.id == id }) {
                    conversations.add(Conversation(host.name, m.getString("agent"), id))
                }
                if (line.from == "agent") {
                    typing.remove(id)
                    answered(id)
                    // The bridge learns what an agent's channel can do once it
                    // reached it, which an agent's message shows: ask again.
                    val agent = m.getString("agent")
                    if (agents.any { it.host == host.name && it.name == agent && it.capabilities.isEmpty() }) {
                        connections[host.name]?.send(message("agents"))
                    }
                }
                val now = lines[id].orEmpty()
                if (now.none { it.seq == line.seq }) lines[id] = (now + line).sortedBy { it.seq }
            }
            "typing" -> {
                val id = m.getString("conversation")
                if (m.optBoolean("on")) {
                    typing[id] = true
                } else {
                    typing.remove(id)
                    answered(id)
                }
            }
            "error" -> {
                said[host.name] = m.optString("message")
                // The bridge refused a message of the person's (the agent is
                // not reachable, say): nothing will answer it now.
                unanswered.refused(m.optString("of"))?.let { stillAwaiting(it) }
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

    override fun onCleared() {
        connections.values.forEach { it.close() }
        work.shutdown()
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
fun lineIn(m: JSONObject): Line =
    Line(m.getLong("seq"), m.getString("from"), m.getString("text"), m.getString("kind"), m.optLong("time"))
