// OkHttp's platform hook (LoopbackCleartext) is internal to OkHttp: used by this test only.
@file:OptIn(okhttp3.internal.OkHttpInternalApi::class)

package io.github.victorbonnet.enclos

import android.os.SystemClock
import io.github.victorbonnet.enclos.core.authSignatureMessage
import io.github.victorbonnet.enclos.core.decodeBytes
import okhttp3.internal.platform.Platform
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/*
 * A bridge for instrumented tests, inside the test process: a WebSocket
 * server on the loopback address, the bridge's side of the device leg's
 * Noise handshake (`Noise_IK_25519_ChaChaPoly_SHA256`, prologue
 * `enclos/device/1`, as enclos-protocol's `noise.rs` builds it), then sealed
 * JSON messages. The app's side is the production one: AppModel.connect,
 * BridgeConnection, OkHttp and the Rust core's DeviceHandshake. Keys and data
 * are synthetic, made for each test. It is no live host: no HTTP service, no
 * model, no answer handling; it only replays what the test scripted.
 */

/** X25519 (RFC 7748), for the bridge's side only: the core does the device's. */
internal object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121_665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    private fun littleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

    private fun encoded(n: BigInteger): ByteArray {
        val big = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return (ByteArray(32 - big.size) + big).reversedArray()
    }

    fun publicKey(secret: ByteArray): ByteArray = dh(secret, BASE)

    fun dh(scalar: ByteArray, point: ByteArray): ByteArray {
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kn = littleEndian(k)
        val u = point.copyOf()
        u[31] = (u[31].toInt() and 127).toByte()
        val x1 = littleEndian(u).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = kn.testBit(t)
            if (swap != bit) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = bit
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        return encoded(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }
}

/** ChaCha20-Poly1305 as Noise uses it: a 64-bit counter, little-endian, after 4 zero bytes. */
private fun chachaPoly(encrypt: Boolean, key: ByteArray, n: Long, ad: ByteArray, data: ByteArray): ByteArray {
    val nonce = ByteArray(12)
    for (i in 0 until 8) nonce[4 + i] = (n ushr (8 * i)).toByte()
    val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
    if (ad.isNotEmpty()) cipher.updateAAD(ad)
    return cipher.doFinal(data)
}

private fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }

private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update); doFinal() }

/** Noise's HKDF with two outputs. */
private fun hkdf(chainingKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
    val temp = hmac(chainingKey, input)
    val one = hmac(temp, byteArrayOf(1))
    return one to hmac(temp, one, byteArrayOf(2))
}

/** One side's transport keys after the handshake, as snow's TransportState uses them: no associated data, counters from 0. */
internal class NoiseTransport(private val receiveKey: ByteArray, private val sendKey: ByteArray, val handshakeHash: ByteArray) {
    private var sent = 0L
    private var received = 0L

    fun encrypt(plain: ByteArray): ByteArray = chachaPoly(true, sendKey, sent++, ByteArray(0), plain)
    fun decrypt(sealed: ByteArray): ByteArray = chachaPoly(false, receiveKey, received++, ByteArray(0), sealed)
}

/** The bridge's side of `Noise_IK_25519_ChaChaPoly_SHA256`: reads the device's hello, answers it. */
internal class NoiseResponder(private val staticPrivate: ByteArray) {
    private val staticPublic = X25519.publicKey(staticPrivate)
    // Its name is 32 bytes, the hash's length: it is the first hash as it is.
    private var h = "Noise_IK_25519_ChaChaPoly_SHA256".toByteArray(Charsets.US_ASCII).copyOf(32)
    private var ck = h.copyOf()
    private var k: ByteArray? = null
    private var n = 0L

    /** The device's static public key, once [readHello] read it. */
    lateinit var remoteStatic: ByteArray
        private set
    private lateinit var remoteEphemeral: ByteArray

    init {
        mixHash("enclos/device/1".toByteArray(Charsets.US_ASCII))
        // IK's pre-message: the responder's static key, known to the device.
        mixHash(staticPublic)
    }

    private fun mixHash(data: ByteArray) {
        h = sha256(h, data)
    }

    private fun mixKey(input: ByteArray) {
        val (chaining, key) = hkdf(ck, input)
        ck = chaining
        k = key
        n = 0
    }

    private fun encryptAndHash(plain: ByteArray): ByteArray {
        val sealed = k?.let { chachaPoly(true, it, n++, h, plain) } ?: plain
        mixHash(sealed)
        return sealed
    }

    private fun decryptAndHash(sealed: ByteArray): ByteArray {
        val plain = k?.let { chachaPoly(false, it, n++, h, sealed) } ?: sealed
        mixHash(sealed)
        return plain
    }

    /** `-> e, es, s, ss`, with no payload. */
    fun readHello(hello: ByteArray) {
        require(hello.size == 32 + 48 + 16) { "a hello of ${hello.size} bytes, not 96" }
        remoteEphemeral = hello.copyOfRange(0, 32)
        mixHash(remoteEphemeral)
        mixKey(X25519.dh(staticPrivate, remoteEphemeral))
        remoteStatic = decryptAndHash(hello.copyOfRange(32, 80))
        mixKey(X25519.dh(staticPrivate, remoteStatic))
        require(decryptAndHash(hello.copyOfRange(80, 96)).isEmpty()) { "a hello with a payload" }
    }

    /** `<- e, ee, se`, with no payload; the answer and the bridge's transport. */
    fun answer(): Pair<ByteArray, NoiseTransport> {
        val ephemeral = ByteArray(32).also(SecureRandom()::nextBytes)
        val ephemeralPublic = X25519.publicKey(ephemeral)
        mixHash(ephemeralPublic)
        mixKey(X25519.dh(ephemeral, remoteEphemeral))
        mixKey(X25519.dh(ephemeral, remoteStatic))
        val tag = encryptAndHash(ByteArray(0))
        val (initiatorToResponder, responderToInitiator) = hkdf(ck, ByteArray(0))
        return (ephemeralPublic + tag) to NoiseTransport(initiatorToResponder, responderToInitiator, h.copyOf())
    }
}

/**
 * OkHttp's platform hook, for the test process only: cleartext to the
 * loopback address, where [FakeBridge] listens. The app's network security
 * policy refuses cleartext everywhere (no config file: the default for its
 * target SDK), and nothing in the app is changed for this.
 */
internal class LoopbackCleartext(private val original: Platform) : Platform() {
    override fun isCleartextTrafficPermitted(hostname: String): Boolean =
        hostname == "127.0.0.1" || original.isCleartextTrafficPermitted(hostname)
}

/**
 * What a [FakeBridge] says, scripted by the test: the host [capabilities] its
 * `authenticated` lists (as enclos's bridge does for the host's person), the
 * [agents] of its `agents.list`, and the [sync] frames it sends, in order,
 * before `synced` and a sentinel `commands` for [sentinelAgent].
 */
internal class BridgeScript(
    val capabilities: List<String>,
    val agents: List<JSONObject>,
    val sync: List<JSONObject>,
    val sentinelAgent: String,
)

/**
 * A host's bridge, scripted by [script]: it authenticates the device it was
 * made for (its Noise key, and `auth` signed by its Keystore key over this
 * session's handshake hash), answers `agents` and `sync` as scripted, then
 * sends a sentinel `commands` (`sync-N`, N the session's number), so the test
 * knows when the app has read all that came before it. Each authenticated
 * connection is a [Session], which logs every message the app sent after
 * `auth` ([Session.received]).
 *
 * Every connection it accepts is its own from that moment, whatever phase it
 * is in: [accepted] holds it, with the thread that serves it, before that
 * thread starts. [close] closes the server socket and every accepted socket,
 * then waits, for a bounded time, until every one of its threads ended; what
 * it could not close or end it throws, and keeps, for a later [close].
 */
internal class FakeBridge(
    val name: String,
    private val bridgePrivate: ByteArray,
    private val devicePublic: ByteArray,
    private val deviceId: String,
    private val keyAlias: String,
    private val script: BridgeScript,
    /** How long [close] waits for the bridge's threads to end, in all. */
    private val closeWithinMillis: Long = 5_000,
) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String get() = "http://127.0.0.1:${server.localPort}"
    private val authenticated = LinkedBlockingQueue<Session>()
    val sessions = CopyOnWriteArrayList<Session>()
    /** What went wrong on the bridge's side, for the test's messages: never a product result. */
    val problems = CopyOnWriteArrayList<String>()

    /** Guards [closed] and [accepted]: a connection is registered, or refused, on one side of [close] only. */
    private val lock = Any()
    @Volatile private var closed = false
    private val acceptedConnections = mutableListOf<Session>()
    private val acceptor: Thread

    /**
     * A controlled disconnect: while set, a connection accepted is closed at
     * once, before its upgrade, as a host that went away; the app's retries
     * then fail and its cached state stays offline.
     */
    @Volatile var refusing = false

    /** Whether its server socket is open: false once [close] ran. */
    val listening: Boolean get() = !server.isClosed
    /** Whether [close] started: from then on, a connection accepted is closed, never served. */
    val closing: Boolean get() = closed
    /** Every connection accepted, in any phase, authenticated or not: each one the bridge's to close. */
    val accepted: List<Session> get() = synchronized(lock) { acceptedConnections.toList() }
    /** The bridge's threads still running: its accept loop and its connections'. Empty once [close] succeeded. */
    val threadsAlive: List<String>
        get() = (listOf(acceptor) + accepted.mapNotNull { it.worker }).filter { it.isAlive }.map { it.name }

    init {
        acceptor = try {
            thread(name = "fake-bridge-$name", isDaemon = true, start = false) { acceptLoop() }.also(Thread::start)
        } catch (t: Throwable) {
            // Not made: nothing holds the server socket but this.
            runCatching { server.close() }.onFailure(t::addSuppressed)
            throw t
        }
    }

    private fun acceptLoop() {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (!closed) problems += "$name: accept failed: ${e.javaClass.simpleName}: ${e.message}"
                return
            }
            // The socket is the bridge's from here: registered even when [close] started, for [close] to close it.
            val start = try {
                synchronized(lock) {
                    val session = Session(socket, acceptedConnections.size + 1)
                    acceptedConnections += session
                    if (closed) {
                        null
                    } else {
                        thread(name = "fake-bridge-$name-connection-${session.index}", isDaemon = true, start = false) { session.run() }
                            .also { session.worker = it }
                    }
                }
            } catch (t: Throwable) {
                // Not registered: closed here, and said, never dropped silently.
                problems += "$name: an accepted connection not registered: ${t.javaClass.simpleName}: ${t.message}"
                runCatching { socket.close() }.onFailure { problems += "$name: an unregistered socket not closed: $it" }
                null
            }
            start?.start()
        }
    }

    /** The next connection the bridge authenticated, or null after [seconds]. */
    fun nextSession(seconds: Long = 20): Session? = authenticated.poll(seconds, TimeUnit.SECONDS)

    /** Every message the app sent after `auth`, over all of this bridge's sessions, in session order. */
    fun receivedAll(): List<JSONObject> = sessions.flatMap { it.received }

    /**
     * Closes the server socket and every connection accepted, in any phase;
     * waits up to [closeWithinMillis] for the accept loop and every
     * connection's thread to end. Throws [NotClosed] naming what it could not
     * close or end: a later [close] tries again. A second [close] after one
     * that succeeded does nothing more.
     */
    override fun close() {
        val failures = mutableListOf<Throwable>()
        synchronized(lock) { closed = true }
        try {
            server.close()
        } catch (t: Throwable) {
            failures += t
        }
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeWithinMillis)
        fun join(t: Thread) {
            val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
            if (left > 0) t.join(left)
        }
        // The accept loop ends with the server socket: after it, no connection is registered any more.
        join(acceptor)
        accepted.forEach { session ->
            try {
                session.drop()
            } catch (t: Throwable) {
                failures += t
            }
        }
        accepted.mapNotNull { it.worker }.forEach(::join)
        val open = accepted.filter { !it.socketClosed }.map { "connection ${it.index} (${it.phase})" }
        val alive = threadsAlive
        if (failures.isNotEmpty() || open.isNotEmpty() || alive.isNotEmpty() || !server.isClosed) {
            throw NotClosed("$name: not closed: sockets open $open, threads alive $alive, listening ${!server.isClosed}").also { e ->
                failures.forEach(e::addSuppressed)
            }
        }
    }

    /** What [close] could not close or end; the bridge keeps it, for a later [close]. */
    class NotClosed(message: String) : IllegalStateException(message)

    inner class Session(private val socket: Socket, /** Its order among the connections accepted, from 1. */ val index: Int) {
        /** The thread serving it: null when it was accepted after [close] started, and never served. */
        @Volatile var worker: Thread? = null
            internal set
        /** Where it is: `upgrade`, `noise`, `auth`, `authenticated`, then `ended` (or `refused`). */
        @Volatile var phase = "upgrade"
            private set
        /** Whether its socket is closed, on the bridge's side. */
        val socketClosed: Boolean get() = socket.isClosed
        var number = 0
            private set
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output: OutputStream = socket.getOutputStream()
        private lateinit var transport: NoiseTransport
        /** Every message the app sent after `auth`, in order: a log, never consumed. */
        val received = CopyOnWriteArrayList<JSONObject>()
        /** Counted down once the connection is gone: the app read the bridge's close and closed its side, or it failed. */
        val gone = CountDownLatch(1)
        /** Whether the app asked to close this connection (a WebSocket close frame). */
        @Volatile var appClosed = false
            private set
        /** Whether the bridge closed first ([closeFromBridge]): the app's close then answers it, and is not answered. */
        @Volatile private var bridgeClosed = false

        fun run() {
            try {
                if (refusing) {
                    phase = "refused"
                    return
                }
                upgrade()
                phase = "noise"
                val responder = NoiseResponder(bridgePrivate)
                responder.readHello(readBinary() ?: return)
                if (!responder.remoteStatic.contentEquals(devicePublic)) {
                    problems += "$name: a Noise static key that is not the test device's"
                    return
                }
                val (reply, session) = responder.answer()
                transport = session
                writeFrame(2, reply)
                phase = "auth"
                val auth = JSONObject(String(transport.decrypt(readBinary() ?: return), Charsets.UTF_8))
                if (auth.optString("type") != "auth" || auth.optString("device") != deviceId || !signed(auth.getString("sig"), session.handshakeHash)) {
                    problems += "$name: first message not a valid auth for $deviceId: type ${auth.optString("type")}"
                    return
                }
                synchronized(this@FakeBridge) {
                    number = sessions.size + 1
                    sessions += this
                }
                phase = "authenticated"
                send(message("authenticated", "person" to "synthetic-person", "capabilities" to JSONArray(script.capabilities)))
                authenticated += this
                while (true) {
                    val plain = readBinary() ?: break
                    val m = JSONObject(String(transport.decrypt(plain), Charsets.UTF_8))
                    received += m
                    respond(m)
                }
            } catch (e: Exception) {
                if (!closed && !appClosed && !bridgeClosed) problems += "$name: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { socket.close() }.onFailure { problems += "$name: connection $index not closed at its end: $it" }
                if (phase != "refused") phase = "ended"
                gone.countDown()
            }
        }

        private fun respond(m: JSONObject) {
            when (m.optString("type")) {
                "agents" -> send(message("agents.list", "agents" to JSONArray(script.agents)))
                "sync" -> {
                    script.sync.forEach(::send)
                    send(message("synced"))
                    sentinel("sync-$number")
                }
            }
        }

        /** A `commands` for the sentinel agent named [name]: once the app holds it, it read all this connection sent before. */
        fun sentinel(name: String) = send(
            message("commands", "agent" to script.sentinelAgent, "commands" to JSONArray().put(JSONObject().put("name", name).put("description", "synthetic"))),
        )

        /**
         * The app-to-bridge half of a processing barrier: a message of a type
         * the app does not know, which it answers `unknown_type` over the same
         * connection, from its reader. Once that answer is here, everything the
         * app queued on this connection before it is here too (one WebSocket,
         * one ordered writer); a Compose tap's send runs on the main thread, so
         * the caller idles the main thread first. Returns the answer, or throws.
         */
        fun roundTrip(label: String, seconds: Long = 20): JSONObject {
            val id = "barrier-$label-$number"
            send(JSONObject().put("v", 1).put("id", id).put("type", "c82.barrier"))
            val until = SystemClock.uptimeMillis() + seconds * 1000
            while (SystemClock.uptimeMillis() < until) {
                received.firstOrNull { it.optString("type") == "error" && it.optString("of") == id }?.let { answer ->
                    if (answer.optString("code") != "unknown_type") throw AssertionError("SETUP (no product result): $name barrier $id answered ${answer.optString("code")}")
                    return answer
                }
                SystemClock.sleep(20)
            }
            throw AssertionError("SETUP (no product result): $name: no answer to barrier $id within $seconds s (phase $phase)")
        }

        private fun signed(sig: String, handshakeHash: ByteArray): Boolean = try {
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getCertificate(keyAlias).publicKey
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(authSignatureMessage(handshakeHash))
                verify(decodeBytes(sig))
            }
        } catch (e: Exception) {
            problems += "$name: auth signature not checked: ${e.javaClass.simpleName}"
            false
        }

        /** Sends [m], sealed, as the bridge does. */
        fun send(m: JSONObject) = synchronized(this) {
            writeFrame(2, transport.encrypt((m.toString() + "\n").toByteArray(Charsets.UTF_8)))
        }

        /** Ends the connection from the bridge's side, as a bridge restarting does: a close frame, then waits for the app's. */
        fun closeFromBridge(reason: String) = synchronized(this) {
            bridgeClosed = true
            val why = reason.toByteArray(Charsets.UTF_8)
            writeFrame(8, byteArrayOf(0x03, 0xE8.toByte()) + why)
        }

        /** Closes its socket, whatever its phase: its thread's reads and writes then fail, and the thread ends. Throws what failed. */
        fun drop() {
            socket.close()
        }

        private fun upgrade() {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException("the request ended")
                if (c == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    if (text.isEmpty()) break
                    lines += text
                    line.clear()
                } else {
                    line.append(c.toChar())
                }
            }
            val key = lines.firstNotNullOfOrNull { l ->
                l.split(":", limit = 2).takeIf { it.size == 2 && it[0].trim().equals("Sec-WebSocket-Key", ignoreCase = true) }?.get(1)?.trim()
            } ?: throw IllegalStateException("not a WebSocket request: ${lines.firstOrNull()}")
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
            )
            synchronized(this) {
                output.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                output.flush()
            }
        }

        /**
         * The next binary message from the app, answering pings; null once it
         * closed (its close frame answered) or the connection ended.
         */
        private fun readBinary(): ByteArray? {
            while (true) {
                val first = input.read()
                if (first < 0) return null
                val second = input.readUnsignedByte()
                val opcode = first and 0x0F
                var length = (second and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                val mask = if (second and 0x80 != 0) ByteArray(4).also(input::readFully) else null
                val payload = ByteArray(length.toInt()).also(input::readFully)
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    2 -> return payload
                    9 -> synchronized(this) { writeFrame(10, payload) }
                    8 -> {
                        appClosed = true
                        if (!bridgeClosed) synchronized(this) { writeFrame(8, payload.copyOf(minOf(payload.size, 2))) }
                        // Read until the app closes its side: it has then read all sent before.
                        var next: Int
                        do {
                            next = input.read()
                        } while (next >= 0)
                        return null
                    }
                    else -> Unit
                }
            }
        }

        private fun writeFrame(opcode: Int, payload: ByteArray) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xFFFF -> {
                    output.write(126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
                }
            }
            output.write(payload)
            output.flush()
        }
    }
}
