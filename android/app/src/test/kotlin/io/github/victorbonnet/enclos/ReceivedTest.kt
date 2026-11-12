package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceivedTest {
    @Test
    fun an_unreadable_message_raises_what_the_model_catches() {
        assertThrows(JSONException::class.java) { agentsIn("mac", JSONObject().put("type", "agents.list")) }
        assertThrows(JSONException::class.java) {
            agentsIn("mac", JSONObject().put("agents", JSONArray().put(JSONObject().put("runtime", "hermes"))))
        }
        assertThrows(JSONException::class.java) {
            lineIn(JSONObject().put("seq", 1).put("from", "agent").put("text", "hi"))
        }
    }

    @Test
    fun messages_read_as_the_bridge_sends_them() {
        val agents = agentsIn(
            "mac",
            JSONObject().put("agents", JSONArray().put(JSONObject().put("agent", "ada").put("runtime", "hermes"))),
        )
        assertEquals(listOf(Agent("mac", "ada", "hermes", emptyList())), agents)
        val line = lineIn(JSONObject().put("seq", 3).put("from", "agent").put("text", "hi").put("kind", "notify"))
        assertEquals(Line(3, "agent", "hi", "notify", 0), line)
    }
}
