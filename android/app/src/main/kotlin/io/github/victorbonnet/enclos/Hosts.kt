package io.github.victorbonnet.enclos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** A host the device is paired with. */
data class Host(
    /** The host's name, as its pairing link gave it. */
    val name: String,
    /** The bridge's address on the tailnet, `https://<host>.<tailnet>.ts.net`. */
    val url: String,
    /** The bridge's Noise public key, pinned at pairing. */
    val bridgeKey: ByteArray,
    /** This device's id on that host. */
    val deviceId: String,
    /** This device's Noise private key, wrapped by the keystore. */
    val noiseKeyWrapped: ByteArray,
    /** The alias of this device's signing key for that host. */
    val keyAlias: String,
    /**
     * The key the bridge seals notification previews with, wrapped by the
     * keystore as the Noise key is: a copy of the preferences opens no
     * preview ([Keys.unwrap] when one is opened).
     */
    val pushKeyWrapped: ByteArray,
)

/** The paired hosts, in the app's private preferences. Nothing here is usable without the keystore. */
class Hosts(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)

    fun all(): List<Host> {
        val (hosts, migrated) = decodeHosts(prefs.getString("hosts", null) ?: "[]", Keys::wrap)
        if (migrated) save(hosts)
        return hosts
    }

    /** Keeps [host], in place of the host of the same name if one is paired. */
    fun add(host: Host) {
        save(all().filter { it.name != host.name } + host)
    }

    private fun save(hosts: List<Host>) {
        // Written at once: a key must not stay in the clear, or be lost, if the app stops right after.
        prefs.edit().putString("hosts", encodeHosts(hosts)).commit()
    }

    fun remove(name: String) {
        save(all().filter { it.name != name })
    }
}

// Standard base64 with padding and no line breaks, as android.util.Base64's
// NO_WRAP wrote it in earlier versions.
private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

/** [hosts] as the preferences keep them: a JSON array, bytes in base64. */
fun encodeHosts(hosts: List<Host>): String {
    val array = JSONArray()
    hosts.forEach { h ->
        array.put(
            JSONObject()
                .put("name", h.name)
                .put("url", h.url)
                .put("bridgeKey", b64(h.bridgeKey))
                .put("deviceId", h.deviceId)
                .put("noiseKey", b64(h.noiseKeyWrapped))
                .put("keyAlias", h.keyAlias)
                .put("pushKeyWrapped", b64(h.pushKeyWrapped)),
        )
    }
    return array.toString()
}

/**
 * The hosts [json] holds, and whether one was migrated and must be written
 * back: an earlier version kept the push key as it came, which [wrap] wraps now.
 */
fun decodeHosts(json: String, wrap: (ByteArray) -> ByteArray): Pair<List<Host>, Boolean> {
    val array = JSONArray(json)
    var migrated = false
    val hosts = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        Host(
            name = o.getString("name"),
            url = o.getString("url"),
            bridgeKey = unb64(o.getString("bridgeKey")),
            deviceId = o.getString("deviceId"),
            noiseKeyWrapped = unb64(o.getString("noiseKey")),
            keyAlias = o.getString("keyAlias"),
            pushKeyWrapped = if (o.has("pushKeyWrapped")) {
                unb64(o.getString("pushKeyWrapped"))
            } else {
                migrated = true
                wrap(unb64(o.getString("pushKey")))
            },
        )
    }
    return hosts to migrated
}
