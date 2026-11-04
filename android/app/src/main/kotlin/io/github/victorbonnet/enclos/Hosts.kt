package io.github.victorbonnet.enclos

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

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
    val pushKey: ByteArray,
)

/** The paired hosts, in the app's private preferences. Nothing here is usable without the keystore. */
class Hosts(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    fun all(): List<Host> {
        val array = JSONArray(prefs.getString("hosts", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Host(
                name = o.getString("name"),
                url = o.getString("url"),
                bridgeKey = unb64(o.getString("bridgeKey")),
                deviceId = o.getString("deviceId"),
                noiseKeyWrapped = unb64(o.getString("noiseKey")),
                keyAlias = o.getString("keyAlias"),
                pushKey = unb64(o.getString("pushKey")),
            )
        }
    }

    fun add(host: Host) {
        val hosts = all().filter { it.name != host.name } + host
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
                    .put("pushKey", b64(h.pushKey)),
            )
        }
        prefs.edit().putString("hosts", array.toString()).apply()
    }

    fun remove(name: String) {
        val kept = all().filter { it.name != name }
        prefs.edit().putString("hosts", "[]").apply()
        kept.forEach { add(it) }
    }
}
