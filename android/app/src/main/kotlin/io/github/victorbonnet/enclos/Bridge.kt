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
import java.util.concurrent.atomic.AtomicBoolean

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
 * messages, each sealed in a Noise transport message. [onClosed] is called
 * once, with why the connection ended, whichever side ended it: a frame
 * that does not decrypt or a `v` the app does not speak ends it, a message
 * that is not JSON or of an unknown `type` does not ([inbound]).
 */
class BridgeConnection(
    private val url: String,
    private val bridgeKey: ByteArray,
    /** The device's Noise private key, asked for on the connection's thread (unwrapping it is slow). */
    private val noisePrivate: () -> ByteArray,
    private val first: (DeviceSession) -> JSONObject,
    private val onMessage: (JSONObject) -> Unit,
    /** A message that decrypted but that the app cannot read (not JSON, no `v` or `type`), answered `bad_message`; the connection goes on. */
    private val onUnreadable: (Unreadable) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    @Volatile private var socket: WebSocket? = null
    private var handshake: DeviceHandshake? = null
    /** Set once [first] is sealed and queued: only then may [send] seal anything. */
    @Volatile private var session: DeviceSession? = null
    private val ended = AtomicBoolean(false)

    private fun end(why: String) {
        if (ended.compareAndSet(false, true)) onClosed(why)
    }

    fun open() {
        // An address OkHttp cannot build a request from ends the connection,
        // as any failure does, rather than the app.
        val request = try {
            Request.Builder().url(url.replaceFirst("https://", "wss://") + "/").build()
        } catch (e: IllegalArgumentException) {
            end("not an address to connect to: $url")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                try {
                    val h = DeviceHandshake(noisePrivate(), bridgeKey)
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
                        // The first message takes the session's first nonce:
                        // it is sealed and queued before any other send can
                        // see the session.
                        synchronized(this@BridgeConnection) {
                            webSocket.send(opened.encrypt(line(first(opened))).toByteString())
                            session = opened
                        }
                    } else {
                        // A bad or unknown message is answered, and the
                        // connection goes on; another `v` ends it
                        // (docs/protocol.md, Messages).
                        when (val next = inbound(String(s.decrypt(bytes.toByteArray()), Charsets.UTF_8))) {
                            is Inbound.Use -> onMessage(next.message)
                            is Inbound.Skip -> {
                                next.answer?.let { this@BridgeConnection.send(it) }
                                next.unreadable?.let(onUnreadable)
                            }
                            is Inbound.End -> {
                                next.answer?.let { this@BridgeConnection.send(it) }
                                fail(webSocket, next.why)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // A handshake or a frame that does not decrypt ends the connection.
                    fail(webSocket, "the bridge sent what the app cannot read: ${e.message}")
                }
            }

            // The bridge ends the connection: answer its close, and say why
            // now rather than when the ping next fails.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                end(reason.ifEmpty { "closed by the bridge" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                end(t.message ?: "the connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                end(reason.ifEmpty { "closed" })
            }
        })
    }

    private fun fail(webSocket: WebSocket, why: String) {
        webSocket.close(1002, null)
        end(why)
    }

    /**
     * Sends [message], sealed; nothing before the handshake is done, nor a
     * message larger than one Noise message ([fits]). Whether it went.
     * Sealing and queueing are one step, so messages go in nonce order.
     */
    @Synchronized
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

    /** Ends the connection from the app's side, saying [why]. */
    fun close(why: String = "closed") {
        socket?.close(1000, null)
        end(why)
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        /** Builds the HTTP client and its TLS setup, slow the first time: off the main thread, before connecting. */
        fun prepare() {
            client.dispatcher
        }
    }
}
