package io.github.victorbonnet.enclos

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    /** A key store that only records what is made and deleted. */
    private class FakeKeys(private val failMaking: Boolean = false) : PairingKeys {
        val made = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override fun newDeviceKey(alias: String) {
            made += alias
            if (failMaking) throw IllegalStateException("no key store")
        }

        override fun deleteDeviceKey(alias: String) {
            deleted += alias
        }

        override fun wrap(plain: ByteArray): ByteArray = byteArrayOf(9) + plain
    }

    private fun host(name: String, key: Byte, alias: String = "old-$name") = Host(
        name = name,
        url = "https://$name.tail1.ts.net",
        bridgeKey = ByteArray(32) { key },
        deviceId = "d",
        noiseKeyWrapped = byteArrayOf(1),
        keyAlias = alias,
        pushKeyWrapped = byteArrayOf(2),
    )

    private fun attempt(keys: FakeKeys, replaces: Host? = null) = PairingAttempt(
        name = "mac",
        url = "https://mac.tail1.ts.net",
        bridgeKey = ByteArray(32) { 7 },
        replaces = replaces,
        keys = keys,
        decode = { it.toByteArray() },
        alias = "new",
    )

    private val accepted = JSONObject().put("type", "pair.accepted").put("device", "dev-1").put("push_key", "pk")

    @Test
    fun a_name_not_paired_is_a_new_pairing() {
        assertEquals(PairingTarget.New, pairingTarget(listOf(host("pi", 1)), "mac", ByteArray(32) { 1 }))
    }

    @Test
    fun the_same_name_and_bridge_key_pairs_again() {
        val mac = host("mac", 1)
        val target = pairingTarget(listOf(host("pi", 2), mac), "mac", ByteArray(32) { 1 })
        assertSame(mac, (target as PairingTarget.Again).old)
    }

    @Test
    fun the_same_name_with_another_bridge_key_is_another_bridge() {
        val mac = host("mac", 1)
        val target = pairingTarget(listOf(mac), "mac", ByteArray(32) { 2 })
        assertSame(mac, (target as PairingTarget.Other).old)
    }

    @Test
    fun an_address_is_shown_as_its_host_and_port() {
        assertEquals("mac.tail1.ts.net", shownAddress("https://mac.tail1.ts.net"))
        assertEquals("mac.tail1.ts.net:8443", shownAddress("https://mac.tail1.ts.net:8443/x"))
        assertEquals("evil.example", shownAddress("https://evil.example/mac.tail1.ts.net"))
    }

    @Test
    fun an_accepted_pairing_keeps_its_key_and_deletes_the_replaced_one() {
        val keys = FakeKeys()
        val a = attempt(keys, replaces = host("mac", 1))
        a.makeDeviceKey()
        val made = a.host(accepted, byteArrayOf(5))
        a.kept()
        a.ended()
        assertEquals("new", made.keyAlias)
        assertEquals("dev-1", made.deviceId)
        assertArrayEquals(byteArrayOf(9, 5), made.noiseKeyWrapped)
        assertArrayEquals(byteArrayOf(9) + "pk".toByteArray(), made.pushKeyWrapped)
        assertEquals(listOf("old-mac"), keys.deleted)
    }

    @Test
    fun a_new_pairing_accepted_deletes_nothing() {
        val keys = FakeKeys()
        val a = attempt(keys)
        a.makeDeviceKey()
        a.host(accepted, byteArrayOf(5))
        a.kept()
        a.ended()
        assertEquals(emptyList<String>(), keys.deleted)
    }

    @Test
    fun a_pairing_that_ends_unaccepted_deletes_its_key_once() {
        // Refused, closed, link expired, no network: all end the connection.
        val keys = FakeKeys()
        val a = attempt(keys, replaces = host("mac", 1))
        a.makeDeviceKey()
        a.ended()
        a.ended()
        assertEquals(listOf("new"), keys.deleted)
    }

    @Test
    fun an_unreadable_acceptance_keeps_nothing() {
        val keys = FakeKeys()
        val a = attempt(keys, replaces = host("mac", 1))
        a.makeDeviceKey()
        assertThrows(JSONException::class.java) {
            a.host(JSONObject().put("type", "pair.accepted").put("push_key", "pk"), byteArrayOf(5))
        }
        a.ended()
        // The replaced pairing's key stays: it was not replaced.
        assertEquals(listOf("new"), keys.deleted)
    }

    @Test
    fun a_key_that_cannot_be_made_is_deleted_and_the_failure_said() {
        val keys = FakeKeys(failMaking = true)
        val a = attempt(keys)
        assertThrows(IllegalStateException::class.java) { a.makeDeviceKey() }
        assertTrue("new" in keys.deleted)
    }

    @Test
    fun reconnections_wait_longer_after_each_failure_up_to_a_minute() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (0..6).map { backoff(it) },
        )
        assertEquals(60_000L, backoff(100))
    }
}
