package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolErrorsTest {
    @Test fun a_message_the_app_cannot_use_is_answered_and_named() {
        val bad = answerTo(JSONObject("""{"v":1,"id":"m-1","type":"message"}"""), "bad_message")!!
        assertEquals("error", bad.getString("type"))
        assertEquals("bad_message", bad.getString("code"))
        assertEquals("m-1", bad.getString("of"))
        val unknown = answerTo(JSONObject("""{"v":1,"id":"m-2","type":"later.feature"}"""), "unknown_type")!!
        assertEquals("unknown_type", unknown.getString("code"))
        assertEquals("m-2", unknown.getString("of"))
    }

    @Test fun of_is_left_out_when_the_id_cannot_be_read() {
        assertFalse(answerTo(null, "bad_message")!!.has("of"))
        assertFalse(answerTo(JSONObject("""{"type":"message"}"""), "bad_message")!!.has("of"))
        assertFalse(answerTo(JSONObject("""{"id":7,"type":"message"}"""), "bad_message")!!.has("of"))
        assertFalse(answerTo(JSONObject().put("id", "a\nb"), "bad_message")!!.has("of"))
        assertFalse(answerTo(JSONObject().put("id", "x".repeat(129)), "bad_message")!!.has("of"))
    }

    @Test fun an_error_is_never_answered() {
        assertNull(answerTo(JSONObject("""{"v":1,"id":"e-1","type":"error"}"""), "bad_message"))
    }

    @Test fun the_app_knows_every_type_the_bridge_sends_and_no_other() {
        val copy = javaClass.getResource("/to-device-types.txt")!!.readText()
            .lines().filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        assertEquals(copy, BRIDGE_TYPES)
        // So none of them is answered `unknown_type`.
        for (type in BRIDGE_TYPES) {
            val m = inbound("""{"v":1,"id":"k-1","type":"$type"}""")
            assertTrue("$type is read", m is Inbound.Use)
        }
    }

    @Test fun an_unknown_type_is_answered_and_ignored_and_the_connection_goes_on() {
        val skip = inbound("""{"v":1,"id":"n-1","type":"later.feature"}""") as Inbound.Skip
        assertEquals("unknown_type", skip.answer!!.getString("code"))
        assertEquals("n-1", skip.answer.getString("of"))
        // Only ignored: nothing to show on the host's screens.
        assertNull(skip.unreadable)
        // An empty type is one the app does not know, as for the bridge.
        val empty = inbound("""{"v":1,"id":"n-2","type":""}""") as Inbound.Skip
        assertEquals("unknown_type", empty.answer!!.getString("code"))
    }

    @Test fun what_is_not_a_message_is_answered_bad_message_and_skipped() {
        val notJson = inbound("not json") as Inbound.Skip
        assertEquals("bad_message", notJson.answer!!.getString("code"))
        assertFalse(notJson.answer.has("of"))
        assertFalse(notJson.unreadable!!.history)
        for (plain in listOf(
            """{"id":"b-1","type":"message"}""",
            """{"v":"1","id":"b-1","type":"message"}""",
            """{"v":1.5,"id":"b-1","type":"message"}""",
            """{"v":-1,"id":"b-1","type":"message"}""",
            """{"v":1,"id":"b-1"}""",
            """{"v":1,"id":"b-1","type":7}""",
        )) {
            val skip = inbound(plain) as Inbound.Skip
            assertEquals(plain, "bad_message", skip.answer!!.getString("code"))
            assertEquals(plain, "b-1", skip.answer.getString("of"))
            assertTrue(plain, skip.unreadable != null)
        }
    }

    @Test fun a_version_the_app_does_not_speak_is_answered_then_ends_the_connection() {
        val end = inbound("""{"v":2,"id":"v-2","type":"message"}""") as Inbound.End
        assertEquals("version", end.answer!!.getString("code"))
        assertEquals("v-2", end.answer.getString("of"))
        assertTrue(end.why, end.why.endsWith("update the app"))
        val older = inbound("""{"v":0,"id":"v-0","type":"message"}""") as Inbound.End
        assertTrue(older.why, older.why.endsWith("update enclos on the host"))
        // An `error` is not answered, even of another version, but it ends the connection too.
        assertNull((inbound("""{"v":2,"id":"v-3","type":"error","code":"bad_message"}""") as Inbound.End).answer)
    }

    @Test fun a_known_message_with_a_field_wrong_is_skipped_and_the_connection_goes_on() {
        val m = (inbound("""{"v":1,"id":"m-1","type":"message","conversation":"c-1"}""") as Inbound.Use).message
        val skip = readOrSkip(m) { it.getLong("seq") }!!
        assertEquals("bad_message", skip.answer!!.getString("code"))
        assertEquals("m-1", skip.answer.getString("of"))
        // A history message: only Clear cache fetches it again.
        assertTrue(skip.unreadable!!.history)
        assertTrue(unreadableNotice(skip.unreadable).endsWith("Update the app, then Clear cache to fetch it again."))
        // A live one would not come again: updating is all there is to do.
        val live = readOrSkip(JSONObject("""{"v":1,"id":"t-1","type":"typing"}""")) { it.getString("conversation") }!!
        assertFalse(live.unreadable!!.history)
        assertTrue(unreadableNotice(live.unreadable).endsWith(". Update the app."))
        // One the app reads is not answered.
        assertNull(readOrSkip(m) { it.getString("conversation") })
    }

    @Test fun why_shows_on_one_line_with_nothing_hidden() {
        assertEquals("JSONObject[\"seq\"] not found.", unreadableShown("JSONObject[\"seq\"] not found."))
        assertEquals("a b", unreadableShown("a\n‮b"))
        assertEquals("no reason given", unreadableShown(null))
        assertEquals(200, unreadableShown("x".repeat(500)).length)
    }
}
