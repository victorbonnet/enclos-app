package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PushTest {
    private val decode: (String) -> ByteArray = { Base64.getUrlDecoder().decode(it) }

    private fun data(
        v: String? = "1",
        host: String? = "mac",
        seq: String? = "42",
        preview: String? = "c2VhbGVk",
    ) = listOfNotNull(v?.let { "v" to it }, host?.let { "host" to it }, seq?.let { "seq" to it }, preview?.let { "preview" to it }).toMap()

    @Test
    fun a_push_is_read_from_fcms_strings() {
        assertEquals(PushData("mac", 42, "sealed".toByteArray()), Push.parse(data(), decode))
    }

    @Test
    fun anything_but_a_push_of_this_version_is_left_alone() {
        for (bad in listOf(
            data(v = "2"),
            data(v = null),
            data(host = ""),
            data(host = "h".repeat(64)),
            data(seq = "x"),
            data(seq = "-1"),
            data(preview = "not base64 !"),
            data(preview = null),
        )) {
            assertNull(bad.toString(), Push.parse(bad, decode))
        }
    }

    private fun host(name: String, key: Byte) = Host(
        name = name,
        url = "https://$name.example.ts.net",
        bridgeKey = byteArrayOf(1),
        deviceId = "d-$name",
        noiseKeyWrapped = byteArrayOf(2),
        keyAlias = "alias-$name",
        pushKeyWrapped = byteArrayOf(key),
    )

    private val preview = JSONObject()
        .put("agent", "lab/main")
        .put("conversation", "c-1")
        .put("text", "**Sunny** in Lyon")
        .toString()
        .toByteArray()

    /** Opens only what was sealed with key 7, for host `mac` and seq 42, as the core does. */
    private val open: (ByteArray, String, Long, ByteArray) -> ByteArray? = { key, host, seq, _ ->
        if (key.contentEquals(byteArrayOf(7)) && host == "mac" && seq == 42L) preview else throw IllegalStateException("sealed")
    }

    @Test
    fun the_host_whose_key_opens_a_push_is_the_one_it_came_from() {
        val push = PushData("mac", 42, byteArrayOf())
        val opened = Push.open(push, listOf(host("pi", 3), host("mac", 7)), { it }, open)
        assertEquals(PushPreview("mac", "lab/main", "c-1", "**Sunny** in Lyon", 42), opened)
        // Under another name the phone knows it by, the key still tells.
        val renamed = Push.open(push, listOf(host("laptop", 7)), { it }, open)
        assertEquals("laptop", renamed?.host)
    }

    @Test
    fun a_push_no_paired_host_opens_shows_nothing() {
        assertNull(Push.open(PushData("mac", 42, byteArrayOf()), listOf(host("mac", 8)), { it }, open))
        assertNull(Push.open(PushData("mac", 43, byteArrayOf()), listOf(host("mac", 7)), { it }, open))
        assertNull(Push.open(PushData("mac", 42, byteArrayOf()), emptyList(), { it }, open))
        // A key the key store cannot unwrap is one that does not open it.
        assertNull(Push.open(PushData("mac", 42, byteArrayOf()), listOf(host("mac", 7)), { error("gone") }, open))
    }

    @Test
    fun a_preview_needs_an_agent_and_a_conversation_without_hidden_characters() {
        fun read(json: JSONObject) = Push.preview("mac", 1, json.toString().toByteArray())
        assertNull(Push.preview("mac", 1, "not json".toByteArray()))
        assertNull(read(JSONObject().put("conversation", "c-1").put("text", "x")))
        assertNull(read(JSONObject().put("agent", "lab/main").put("text", "x")))
        assertNull(read(JSONObject().put("agent", "lab/‮main").put("conversation", "c-1")))
        assertEquals("", read(JSONObject().put("agent", "lab/main").put("conversation", "c-1"))?.text)
    }

    @Test
    fun a_notification_shows_the_words_without_marks_or_nothing_behind_the_app_lock() {
        assertEquals("Sunny in Lyon", Push.shown("**Sunny** in\n\n_Lyon_", locked = false))
        assertEquals(Push.GENERIC, Push.shown("**Sunny** in Lyon", locked = true))
        assertEquals(Push.GENERIC, Push.shown("", locked = false))
        assertEquals(Push.GENERIC, Push.shown("​", locked = false))
        assertEquals("a‮b".length - 1, Push.shown("a‮b", locked = false).length)
        val long = Push.shown("word ".repeat(60), locked = false)
        assertTrue(long, long.endsWith("…"))
        assertEquals(Push.PREVIEW_CHARS, long.codePointCount(0, long.length))
    }

    @Test
    fun a_push_already_shown_or_older_is_dropped() {
        val push = PushPreview("mac", "lab/main", "c-1", "", 42)
        assertFalse(Push.seen(push, null))
        assertFalse(Push.seen(push, 41))
        assertTrue(Push.seen(push, 42))
        assertTrue(Push.seen(push, 50))
    }

    @Test
    fun one_channel_per_agent_and_one_notification_per_conversation() {
        assertEquals("agent:mac/lab/main", Push.channel("mac", "lab/main"))
        assertEquals(Push.notificationId("mac", "c-1"), Push.notificationId("mac", "c-1"))
        assertFalse(Push.notificationId("mac", "c-1") == Push.notificationId("pi", "c-1"))
    }

    @Test
    fun the_token_goes_as_the_protocol_names_it() {
        assertEquals("""{"kind":"fcm","token":"t-1"}""", Push.token("t-1").toString())
    }
}
