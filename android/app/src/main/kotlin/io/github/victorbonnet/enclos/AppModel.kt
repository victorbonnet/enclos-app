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
import org.json.JSONObject
import java.util.UUID

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
    val agents = mutableStateListOf<Agent>()
    val conversations = mutableStateListOf<Conversation>()
    val lines = mutableStateMapOf<String, List<Line>>()
    /** By conversation: whether the agent is writing. */
    val typing = mutableStateMapOf<String, Boolean>()
    /** By conversation: the last message read on this device, kept across launches. */
    private val lastRead = mutableStateMapOf<String, Long>().apply {
        reads.all.forEach { (id, seq) -> if (seq is Long) put(id, seq) }
    }
    val pairing = mutableStateOf<Pairing?>(null)
    /** Why the last link scanned or typed is not a pairing link. */
    val pairLinkError = mutableStateOf<String?>(null)

    private fun ui(block: () -> Unit) = main.post(block)

    /** Whether [host] is connected and authenticated. */
    fun connected(host: String) = status[host]?.startsWith("connected") == true

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
        val parsed = try {
            parsePairLink(link)
        } catch (e: Exception) {
            pairLinkError.value = "Not an enclos pairing link: ${e.message}"
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
            noisePrivate = noise.privateKey,
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
                            pushKey = decodeBytes(m.getString("push_key")),
                        )
                        hosts.add(host)
                        ui {
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
            onMessage = { m -> ui { received(host, m) } },
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
        typing.keys.filter { id -> conversations.any { it.id == id && it.host == host.name } }.forEach { typing.remove(it) }
        if (!foreground) return
        val n = failures.getOrDefault(host.name, 0)
        failures[host.name] = n + 1
        val wait = minOf(60_000L, 2_000L shl minOf(n, 5))
        main.postDelayed({ if (foreground && host.name !in connections) reopen(host.name) }, wait)
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
                agents.removeAll { it.host == host.name }
                val list = m.getJSONArray("agents")
                for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    val capabilities = a.optJSONArray("capabilities")
                    agents.add(
                        Agent(
                            host.name,
                            a.getString("agent"),
                            a.optString("runtime"),
                            (0 until (capabilities?.length() ?: 0)).map { capabilities!!.getString(it) },
                        ),
                    )
                }
            }
            "message" -> {
                val id = m.getString("conversation")
                if (conversations.none { it.id == id }) {
                    conversations.add(Conversation(host.name, m.getString("agent"), id))
                }
                val line = Line(m.getLong("seq"), m.getString("from"), m.getString("text"), m.getString("kind"), m.optLong("time"))
                if (line.from == "agent") {
                    typing.remove(id)
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
                if (m.optBoolean("on")) typing[id] = true else typing.remove(id)
            }
            "error" -> said[host.name] = m.optString("message")
        }
    }

    /** Starts a conversation with [agent]; returns its id. */
    fun newConversation(agent: Agent): String {
        val id = UUID.randomUUID().toString()
        conversations.add(Conversation(agent.host, agent.name, id))
        connections[agent.host]?.send(message("conversation.new", "conversation" to id, "agent" to agent.name))
        return id
    }

    fun send(conversation: Conversation, text: String) {
        said.remove(conversation.host)
        connections[conversation.host]?.send(
            message("message.send", "conversation" to conversation.id, "text" to text),
        )
    }

    fun reconnect(name: String) {
        failures.remove(name)
        reopen(name)
    }

    override fun onCleared() {
        connections.values.forEach { it.close() }
    }
}
