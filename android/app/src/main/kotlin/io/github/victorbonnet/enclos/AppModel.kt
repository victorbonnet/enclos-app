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
    private val connections = mutableMapOf<String, BridgeConnection>()

    val paired = mutableStateListOf<Host>().apply { addAll(hosts.all()) }
    /** By host: connected, the reason it is not, or connecting. */
    val status = mutableStateMapOf<String, String>()
    val agents = mutableStateListOf<Agent>()
    val conversations = mutableStateListOf<Conversation>()
    val lines = mutableStateMapOf<String, List<Line>>()
    val pairing = mutableStateOf<String?>(null)

    init {
        paired.forEach { connect(it) }
    }

    private fun ui(block: () -> Unit) = main.post(block)

    /** Pairs with the host whose link was scanned or typed. */
    fun pair(link: String) {
        val parsed = try {
            parsePairLink(link)
        } catch (e: Exception) {
            pairing.value = "Not an enclos pairing link: ${e.message}"
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
                            pushKey = decodeBytes(m.getString("push_key")),
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

    /** Opens the connection to [host]: `auth`, then its agents and every message since the start. */
    fun connect(host: Host) {
        connections.remove(host.name)?.close()
        status[host.name] = "connecting"
        val connection = BridgeConnection(
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
            onClosed = { why -> ui { status[host.name] = why } },
        )
        connections[host.name] = connection
        connection.open()
    }

    private fun received(host: Host, m: JSONObject) {
        when (m.optString("type")) {
            "authenticated" -> {
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

    fun send(conversation: Conversation, text: String) {
        connections[conversation.host]?.send(
            message("message.send", "conversation" to conversation.id, "text" to text),
        )
    }

    fun reconnect(name: String) {
        paired.firstOrNull { it.name == name }?.let { connect(it) }
    }

    override fun onCleared() {
        connections.values.forEach { it.close() }
    }
}
