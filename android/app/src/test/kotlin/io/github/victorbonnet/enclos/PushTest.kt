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
        // At the bounds, and with fields it does not know.
        assertEquals("h".repeat(63), Push.parse(data(host = "h".repeat(63)), decode)?.host)
        assertEquals(Long.MAX_VALUE, Push.parse(data(seq = Long.MAX_VALUE.toString()), decode)?.seq)
        assertEquals(42L, Push.parse(data() + ("later" to "x"), decode)?.seq)
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
            data(seq = "9223372036854775808"),
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

    private fun previewOf(conversation: String = "c-1") = JSONObject()
        .put("agent", "lab/main")
        .put("conversation", conversation)
        .put("text", "**Sunny** in Lyon")
        .toString()
        .toByteArray()

    /** Opens only what was sealed with key 7 for host `mac`, seq 42, as the core does; refuses by throwing. */
    private val open: (ByteArray, String, Long, ByteArray) -> ByteArray? = { key, host, seq, _ ->
        if (key.contentEquals(byteArrayOf(7)) && host == "mac" && seq == 42L) previewOf() else throw IllegalStateException("sealed")
    }

    @Test
    fun the_host_whose_key_opens_a_push_is_the_one_it_came_from() {
        val push = PushData("mac", 42, byteArrayOf())
        val opened = Push.open(push, listOf(host("pi", 3), host("mac", 7)), { it }, open)
        assertEquals(PushPreview("mac", "lab/main", "c-1", "**Sunny** in Lyon", 42), opened)
        // Under another name the phone knows it by, the key still tells.
        assertEquals("laptop", Push.open(push, listOf(host("laptop", 7)), { it }, open)?.host)
    }

    @Test
    fun the_host_named_in_the_push_is_tried_first_and_a_key_that_returns_nothing_is_passed_over() {
        val tried = mutableListOf<Byte>()
        val nullForWrongKey: (ByteArray, String, Long, ByteArray) -> ByteArray? = { key, _, _, _ ->
            tried += key[0]
            if (key[0] == 7.toByte()) previewOf() else null
        }
        val push = PushData("mac", 42, byteArrayOf())
        assertEquals("pi", Push.open(push, listOf(host("pi", 7), host("mac", 3)), { it }, nullForWrongKey)?.host)
        assertEquals(listOf<Byte>(3, 7), tried)
    }

    @Test
    fun a_push_no_paired_host_opens_shows_nothing() {
        assertNull(Push.open(PushData("mac", 42, byteArrayOf()), listOf(host("mac", 8)), { it }, open))
        assertNull(Push.open(PushData("mac", 43, byteArrayOf()), listOf(host("mac", 7)), { it }, open))
        assertNull(Push.open(PushData("mac", 42, byteArrayOf()), emptyList(), { it }, open))
    }

    @Test
    fun a_key_the_key_store_cannot_give_is_reported_and_passed_over() {
        val failed = mutableListOf<String>()
        val opened = Push.open(
            PushData("mac", 42, byteArrayOf()),
            listOf(host("mac", 7), host("laptop", 7)),
            { if (failed.isEmpty()) throw java.security.KeyStoreException("gone") else it },
            open,
            { host, _ -> failed += host.name },
        )
        assertEquals(listOf("mac"), failed)
        assertEquals("laptop", opened?.host)
    }

    @Test(expected = UnsatisfiedLinkError::class)
    fun an_error_is_not_taken_for_a_key_that_does_not_fit() {
        Push.open(PushData("mac", 42, byteArrayOf()), listOf(host("mac", 7)), { it }, { _, _, _, _ -> throw UnsatisfiedLinkError("no core") })
    }

    @Test
    fun a_preview_needs_an_agent_and_a_conversation_without_hidden_characters() {
        fun read(json: JSONObject) = Push.preview("mac", 1, json.toString().toByteArray())
        assertNull(Push.preview("mac", 1, "not json".toByteArray()))
        assertNull(Push.preview("mac", 1, "[1,2]".toByteArray()))
        assertNull(read(JSONObject().put("conversation", "c-1").put("text", "x")))
        assertNull(read(JSONObject().put("agent", "lab/main").put("text", "x")))
        assertNull(read(JSONObject().put("agent", "lab/‮main").put("conversation", "c-1")))
        assertEquals("a".repeat(256), read(JSONObject().put("agent", "a".repeat(256)).put("conversation", "c-1"))?.agent)
        assertNull(read(JSONObject().put("agent", "a".repeat(257)).put("conversation", "c-1")))
        assertEquals("", read(JSONObject().put("agent", "lab/main").put("conversation", "c-1"))?.text)
    }

    @Test
    fun a_notification_shows_the_words_without_marks_or_nothing_behind_the_app_lock() {
        assertEquals("Sunny in Lyon", Push.shown("**Sunny** in\n\n_Lyon_", locked = false))
        assertEquals(Push.GENERIC, Push.shown("**Sunny** in Lyon", locked = true))
        assertEquals(Push.GENERIC, Push.shown("", locked = false))
        assertEquals(Push.GENERIC, Push.shown("​", locked = false))
        assertEquals("ab", Push.shown("a‮b", locked = false))
    }

    @Test
    fun a_notification_holds_at_most_120_characters_cut_between_two() {
        val exact = "x".repeat(Push.PREVIEW_CHARS)
        assertEquals(exact, Push.shown(exact, locked = false))
        val long = Push.shown("word ".repeat(60), locked = false)
        assertTrue(long, long.endsWith("…"))
        assertTrue(long.codePointCount(0, long.length) <= Push.PREVIEW_CHARS)
        // Emoji are two UTF-16 units each: the cut never splits one.
        val emoji = Push.shown("😀".repeat(130), locked = false)
        assertEquals(Push.PREVIEW_CHARS, emoji.codePointCount(0, emoji.length))
        assertEquals("😀".repeat(Push.PREVIEW_CHARS - 1) + "…", emoji)
    }

    private fun decide(push: Map<String, String>, shown: Map<String, Long> = emptyMap()) = Push.decide(
        data = push,
        hosts = listOf(host("mac", 7)),
        decode = decode,
        unwrap = { it },
        open = { key, h, seq, _ -> if (key[0] == 7.toByte() && h == "mac") previewOf(if (seq == 41L) "c-2" else "c-1") else null },
        lastShown = { h, c -> shown[Push.shownKey(h, c)] },
    )

    @Test
    fun a_push_is_shown_once_and_one_for_another_conversation_whatever_its_order() {
        assertEquals(
            PushDecision.Show(PushPreview("mac", "lab/main", "c-1", "**Sunny** in Lyon", 42)),
            decide(data(seq = "42")),
        )
        // Shown already, or older, in its conversation: dropped, with no fault to log.
        assertEquals(PushDecision.Drop(null), decide(data(seq = "42"), mapOf(Push.shownKey("mac", "c-1") to 42L)))
        // Another conversation's push that FCM brings later, with a lower seq: shown.
        val older = decide(data(seq = "41"), mapOf(Push.shownKey("mac", "c-1") to 42L))
        assertEquals("c-2", (older as PushDecision.Show).preview.conversation)
    }

    @Test
    fun a_push_dropped_for_a_fault_says_why() {
        val other = decide(data(v = "2"))
        assertTrue(other.toString(), (other as PushDecision.Drop).why!!.contains("version 2"))
        assertEquals(PushDecision.Drop("a push no paired host opens"), decide(data(host = "pi")))
    }

    @Test
    fun a_hosts_marks_and_channels_are_its_own() {
        assertEquals("shown.mac/c-1", Push.shownKey("mac", "c-1"))
        assertTrue(Push.shownKey("mac", "c-1").startsWith(Push.shownPrefix("mac")))
        assertFalse(Push.shownKey("mac2", "c-1").startsWith(Push.shownPrefix("mac")))
        assertEquals("agent:mac/lab/main", Push.channel("mac", "lab/main"))
        assertTrue(Push.channel("mac", "lab/main").startsWith(Push.channelPrefix("mac")))
        assertFalse(Push.channel("mac2", "lab/main").startsWith(Push.channelPrefix("mac")))
        assertEquals(Push.notificationId("c-1"), Push.notificationId("c-1"))
    }

    @Test
    fun the_token_goes_as_the_protocol_names_it() {
        assertEquals("""{"kind":"fcm","token":"t-1"}""", Push.token("t-1").toString())
    }
}
