package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun why_shows_on_one_line_with_nothing_hidden() {
        assertEquals("JSONObject[\"seq\"] not found.", unreadableShown("JSONObject[\"seq\"] not found."))
        assertEquals("a b", unreadableShown("a\n‮b"))
        assertEquals("no reason given", unreadableShown(null))
        assertEquals(200, unreadableShown("x".repeat(500)).length)
    }
}
