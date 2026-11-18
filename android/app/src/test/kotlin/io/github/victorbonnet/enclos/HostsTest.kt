package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsTest {
    private val mac = Host(
        name = "mac",
        url = "https://mac.tail1.ts.net",
        bridgeKey = ByteArray(32) { it.toByte() },
        deviceId = "dev-1",
        noiseKeyWrapped = byteArrayOf(1, 2, 3),
        keyAlias = "enclos-device-mac-1",
        pushKeyWrapped = byteArrayOf(4, 5),
    )

    private fun assertSameHost(want: Host, got: Host) {
        assertEquals(want.name, got.name)
        assertEquals(want.url, got.url)
        assertArrayEquals(want.bridgeKey, got.bridgeKey)
        assertEquals(want.deviceId, got.deviceId)
        assertArrayEquals(want.noiseKeyWrapped, got.noiseKeyWrapped)
        assertEquals(want.keyAlias, got.keyAlias)
        assertArrayEquals(want.pushKeyWrapped, got.pushKeyWrapped)
    }

    @Test
    fun hosts_read_back_as_written() {
        val pi = mac.copy(name = "pi", keyAlias = "enclos-device-pi-1")
        val (hosts, migrated) = decodeHosts(encodeHosts(listOf(mac, pi))) { error("nothing to wrap") }
        assertFalse(migrated)
        assertEquals(2, hosts.size)
        assertSameHost(mac, hosts[0])
        assertSameHost(pi, hosts[1])
    }

    @Test
    fun hosts_written_by_earlier_versions_still_read() {
        // As android.util.Base64 with NO_WRAP wrote bytes: standard alphabet
        // (`+` and `/`, not `-` and `_`), padded, one line.
        val old = mac.copy(bridgeKey = byteArrayOf(0xfb.toByte(), 0xff.toByte()))
        val earlier = JSONArray().put(
            JSONObject()
                .put("name", "mac")
                .put("url", "https://mac.tail1.ts.net")
                .put("bridgeKey", "+/8=")
                .put("deviceId", "dev-1")
                .put("noiseKey", "AQID")
                .put("keyAlias", "enclos-device-mac-1")
                .put("pushKeyWrapped", "BAU="),
        ).toString()
        val (hosts, migrated) = decodeHosts(earlier) { error("nothing to wrap") }
        assertFalse(migrated)
        assertSameHost(old, hosts.single())
        assertEquals(earlier, encodeHosts(hosts))
    }

    @Test
    fun a_push_key_kept_in_the_clear_is_wrapped_and_written_back() {
        val old = JSONArray(encodeHosts(listOf(mac))).getJSONObject(0)
        old.remove("pushKeyWrapped")
        old.put("pushKey", "BwgJ")
        val (hosts, migrated) = decodeHosts(JSONArray().put(old).toString()) { byteArrayOf(0) + it }
        assertTrue(migrated)
        assertArrayEquals(byteArrayOf(0, 7, 8, 9), hosts.single().pushKeyWrapped)
    }
}
