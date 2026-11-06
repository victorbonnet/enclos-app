package io.github.victorbonnet.enclos

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import io.github.victorbonnet.enclos.core.decodeBytes
import io.github.victorbonnet.enclos.core.encodeBytes
import io.github.victorbonnet.enclos.core.generateNoiseKeypair
import io.github.victorbonnet.enclos.core.pairingProof
import io.github.victorbonnet.enclos.core.parsePairLink
import org.json.JSONObject
import java.util.UUID

/** An agent of a host, as its bridge lists it. */
data class Agent(val host: String, val name: String, val runtime: String)

/** A message of a conversation. */
data class Line(val seq: Long, val from: String, val text: String, val kind: String)

/** A conversation: its host, agent and id. */
data class Conversation(val host: String, val agent: String, val id: String)

/** What the screens show, kept across the screens and updated on the main thread. */
class AppModel(application: Application) : AndroidViewModel(application) {
    private val hosts = Hosts(application)
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
    val agents = mutableStateListOf<Agent>()
    val conversations = mutableStateListOf<Conversation>()
    val lines = mutableStateMapOf<String, List<Line>>()
    val pairing = mutableStateOf<String?>(null)

    private fun ui(block: () -> Unit) = main.post(block)

    /** Pairs with the host whose link was scanned or typed. */
    fun pair(link: String) {
        val parsed = try {
            parsePairLink(link)
        } catch (e: Exception) {
            pairing.value = "Not an enclos pairing link: ${e.message}"
            return
        }
        // Its address must name a host to connect to: `https://` alone passes
        // the link's own check, and makes no WebSocket address.
        val address = runCatching { java.net.URI(parsed.url) }.getOrNull()
        if (address?.scheme != "https" || address.host.isNullOrBlank()) {
            pairing.value = "Not an enclos pairing link: its address, ${parsed.url}, names no host"
            return
        }
        pairing.value = "Pairing with ${parsed.host}… confirm it in the host's terminal."
        val noise = generateNoiseKeypair()
        val alias = "enclos-device-${parsed.host}-${UUID.randomUUID()}"
        Keys.newDeviceKey(alias)
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
                            pushKeyWrapped = Keys.wrap(decodeBytes(m.getString("push_key"))),
                        )
                        hosts.add(host)
                        ui {
                            paired.removeAll { it.name == host.name }
                            paired.add(host)
                            pairing.value = "Paired with ${host.name}."
                        }
                        connection.close()
                        ui { connect(host) }
                    }
                    "pair.refused" -> {
                        Keys.deleteDeviceKey(alias)
                        ui { pairing.value = "Refused: ${m.optString("reason")}" }
                        connection.close()
                    }
                }
            },
            onClosed = { why -> ui { if (pairing.value?.startsWith("Pairing") == true) pairing.value = "Not paired: $why" } },
        )
        connection.open()
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
                    agents.add(Agent(host.name, a.getString("agent"), a.optString("runtime")))
                }
            }
            "message" -> {
                val id = m.getString("conversation")
                if (conversations.none { it.id == id }) {
                    conversations.add(Conversation(host.name, m.getString("agent"), id))
                }
                val line = Line(m.getLong("seq"), m.getString("from"), m.getString("text"), m.getString("kind"))
                val now = lines[id].orEmpty()
                if (now.none { it.seq == line.seq }) lines[id] = (now + line).sortedBy { it.seq }
            }
            "error" -> status[host.name] = "the bridge said: ${m.optString("message")}"
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
        val out = message("message.send", "conversation" to conversation.id, "text" to text)
        if (!fits(out)) {
            return "Too long to send: ${text.toByteArray(Charsets.UTF_8).size / 1000} KB, and at most about 63 KB go at once."
        }
        val sent = connections[conversation.host]?.send(out) == true
        return if (sent) null else "Not sent: not connected to ${conversation.host}. Your message is still here."
    }

    fun reconnect(name: String) {
        failures.remove(name)
        reopen(name)
    }

    override fun onCleared() {
        connections.values.forEach { it.close() }
    }
}
