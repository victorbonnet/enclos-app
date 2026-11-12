package io.github.victorbonnet.enclos

import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a pairing link means for the hosts already paired (docs/spec.md,
 * Pairing). A link names its host itself, so a name proves nothing: the
 * bridge's key, which the handshake checks, tells the same host from another.
 */
sealed interface PairingTarget {
    /** No host of that name is paired. */
    data object New : PairingTarget

    /** [old]'s own bridge, by its key: pairs again, in place of [old]. */
    data class Again(val old: Host) : PairingTarget

    /** A host of that name is paired with another bridge: replaces [old] only once the person confirms. */
    data class Other(val old: Host) : PairingTarget
}

fun pairingTarget(paired: List<Host>, name: String, bridgeKey: ByteArray): PairingTarget {
    val old = paired.firstOrNull { it.name == name } ?: return PairingTarget.New
    return if (old.bridgeKey.contentEquals(bridgeKey)) PairingTarget.Again(old) else PairingTarget.Other(old)
}

/** A bridge's address as the app shows it: its host, and its port when it has one. */
fun shownAddress(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    return if (uri.port == -1) host else "$host:${uri.port}"
}

/** What a pairing needs of the key store: [Keys] on a phone, a fake in tests. */
interface PairingKeys {
    fun newDeviceKey(alias: String)
    fun deleteDeviceKey(alias: String)
    fun wrap(plain: ByteArray): ByteArray
}

/**
 * One pairing, from its link to its outcome: the device key it makes, the
 * host the bridge's `pair.accepted` makes, and the keys it leaves behind. The
 * device key is deleted again unless the host was [kept]; once it is, the key
 * of the pairing it [replaces] is deleted.
 */
class PairingAttempt(
    val name: String,
    val url: String,
    val bridgeKey: ByteArray,
    val replaces: Host?,
    private val keys: PairingKeys,
    /** Bytes as the protocol writes them in JSON; the core's `decodeBytes` on a phone. */
    private val decode: (String) -> ByteArray,
    val alias: String = "enclos-device-$name-${UUID.randomUUID()}",
) {
    @Volatile private var kept = false
    private val ended = AtomicBoolean(false)

    /** Makes the device key; on a failure, deletes whatever it left and throws again. */
    fun makeDeviceKey() {
        try {
            keys.newDeviceKey(alias)
        } catch (e: Exception) {
            runCatching { keys.deleteDeviceKey(alias) }
            throw e
        }
    }

    /** The host [accepted] (`pair.accepted`) makes; throws when a field is missing. */
    fun host(accepted: JSONObject, noisePrivate: ByteArray): Host = Host(
        name = name,
        url = url,
        bridgeKey = bridgeKey,
        deviceId = accepted.getString("device"),
        noiseKeyWrapped = keys.wrap(noisePrivate),
        keyAlias = alias,
        pushKeyWrapped = keys.wrap(decode(accepted.getString("push_key"))),
    )

    /** The host was saved: the device key stays, and the replaced pairing's key goes. */
    fun kept() {
        kept = true
        replaces?.keyAlias?.takeIf { it != alias }?.let { runCatching { keys.deleteDeviceKey(it) } }
    }

    /** The pairing's connection ended (refused, closed, failed): unless the host was kept, its key goes. Once. */
    fun ended() {
        if (ended.compareAndSet(false, true) && !kept) runCatching { keys.deleteDeviceKey(alias) }
    }
}

/**
 * How long to wait before opening a connection again after [failures]
 * attempts that failed: 2 s, then 4, 8, 16, 32, then every 60 s.
 */
fun backoff(failures: Int): Long = minOf(60_000L, 2_000L shl minOf(failures, 5))
