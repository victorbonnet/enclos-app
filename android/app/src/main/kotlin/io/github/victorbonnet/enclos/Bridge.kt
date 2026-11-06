package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.core.DeviceHandshake
import io.github.victorbonnet.enclos.core.DeviceSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The most a message may hold before it is sealed: one Noise transport
 * message (65,535 bytes) less its 16-byte tag (docs/protocol.md, Handshake).
 */
const val MAX_PLAINTEXT = 65_519

/** A message as it is sealed: its JSON and a line break. */
private fun line(message: JSONObject) = (message.toString() + "\n").toByteArray(Charsets.UTF_8)

/** Whether [message] fits in one Noise message, the most that goes at once. */
fun fits(message: JSONObject): Boolean = line(message).size <= MAX_PLAINTEXT

/** A protocol message: `v`, a new `id`, its `type`, then [fields]. */
fun message(type: String, vararg fields: Pair<String, Any?>): JSONObject {
    val o = JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", type)
    fields.forEach { (k, v) -> if (v != null) o.put(k, v) }
    return o
}

/**
 * One connection to a host's bridge (docs/protocol.md, The device leg): a
 * WebSocket over the tailnet, the Noise handshake, then [first] (`auth` or
 * `pair.request`, built from the session's handshake hash), then JSON
 * messages, each sealed in a Noise transport message.
 */
class BridgeConnection(
    private val url: String,
    private val bridgeKey: ByteArray,
    private val noisePrivate: ByteArray,
    private val first: (DeviceSession) -> JSONObject,
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private var socket: WebSocket? = null
    private var handshake: DeviceHandshake? = null
    @Volatile private var session: DeviceSession? = null

    fun open() {
        // An address OkHttp cannot build a request from ends the connection,
        // as any failure does, rather than the app.
        val request = try {
            Request.Builder().url(url.replaceFirst("https://", "wss://") + "/").build()
        } catch (e: IllegalArgumentException) {
            onClosed("not an address to connect to: $url")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    val h = DeviceHandshake(noisePrivate, bridgeKey)
                    handshake = h
                    webSocket.send(h.hello().toByteString())
                } catch (e: Exception) {
                    fail(webSocket, "handshake: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val s = session
                    if (s == null) {
                        val opened = handshake!!.finish(bytes.toByteArray())
                        session = opened
                        send(first(opened))
                    } else {
                        onMessage(JSONObject(String(s.decrypt(bytes.toByteArray()), Charsets.UTF_8)))
                    }
                } catch (e: Exception) {
                    fail(webSocket, "the bridge sent what the app cannot read: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(t.message ?: "the connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(reason.ifEmpty { "closed" })
            }
        })
    }

    private fun fail(webSocket: WebSocket, why: String) {
        webSocket.close(1002, null)
        onClosed(why)
    }

    /**
     * Sends [message], sealed; nothing before the handshake is done, nor a
     * message larger than one Noise message ([fits]). Whether it went.
     */
    fun send(message: JSONObject): Boolean {
        val s = session ?: return false
        val plain = line(message)
        if (plain.size > MAX_PLAINTEXT) return false
        val sealed = try {
            s.encrypt(plain)
        } catch (e: Exception) {
            return false
        }
        return socket?.send(sealed.toByteString()) ?: false
    }

    fun close() {
        socket?.close(1000, null)
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
