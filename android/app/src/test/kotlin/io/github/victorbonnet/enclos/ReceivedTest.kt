package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun a_groups_conversation_has_its_members_and_its_sandbox_as_agent() {
        val c = conversationOf(
            "mac",
            JSONObject("""{"type":"conversation","conversation":"g1","agent":"lab","title":"Plan","titled_by":"person","members":["lab/main","lab/coder"]}"""),
        )
        assertEquals(Conversation("mac", "lab", "g1", "Plan", "person", members = listOf("lab/main", "lab/coder")), c)
        assertTrue(c.group)
    }

    @Test
    fun a_conversation_with_one_agent_has_no_members() {
        val c = conversationOf("mac", JSONObject("""{"conversation":"c1","agent":"lab/main","home":true}"""))
        assertEquals(Conversation("mac", "lab/main", "c1", null, null, home = true), c)
        assertFalse(c.group)
    }

    @Test
    fun in_a_group_an_agents_message_says_which_member_spoke() {
        val m = JSONObject("""{"conversation":"g1","agent":"lab/coder","from":"agent","text":"Done.","kind":"reply","time":10,"seq":7}""")
        assertEquals(Line(7, "agent", "Done.", "reply", 10, agent = "mac/lab/coder"), lineOf("mac", m, group = true))
        // Outside a group, no speaker.
        assertNull(lineOf("mac", m, group = false).agent)
    }

    @Test
    fun the_persons_own_message_in_a_group_has_no_speaker() {
        val m = JSONObject("""{"conversation":"g1","agent":"lab","from":"alice","text":"@all hi","kind":"message","time":9,"seq":6}""")
        assertNull(lineOf("mac", m, group = true).agent)
    }

    @Test
    fun a_messages_files_are_read() {
        val m = JSONObject(
            """{"conversation":"c1","agent":"lab/main","from":"agent","text":"","kind":"reply","time":1,"seq":2,
               "attachments":[{"id":"a1","mime":"image/png","name":"chart.png","size":42}]}""",
        )
        assertEquals(listOf(Attachment("a1", "image/png", "chart.png", 42)), lineOf("mac", m, group = false).attachments)
    }

    @Test
    fun a_group_state_gives_each_members_turn() {
        val s = groupStatusOf(
            "mac",
            JSONObject(
                """{"type":"group.state","conversation":"g1","state":"working","round":1,"max_rounds":3,
                   "members":[{"agent":"lab/main","turn":"replied"},{"agent":"lab/coder","turn":"working"},{"agent":"lab/docs"}]}""",
            ),
        )
        assertEquals(
            GroupStatus("working", 1, 3, listOf("mac/lab/main" to "replied", "mac/lab/coder" to "working", "mac/lab/docs" to null), null),
            s,
        )
    }

    @Test
    fun a_failed_group_says_why() {
        val s = groupStatusOf("mac", JSONObject("""{"conversation":"g1","state":"failed","round":2,"max_rounds":3,"members":[],"reason":"lab/coder is unreachable"}"""))
        assertEquals("failed", s.state)
        assertEquals("lab/coder is unreachable", s.reason)
        assertTrue(s.turns.isEmpty())
    }
}
