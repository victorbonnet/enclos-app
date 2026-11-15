package io.github.victorbonnet.enclos

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTest {
    private fun activity(agent: String, doing: String, since: Long = 5) =
        JSONObject("""{"v":1,"type":"agent.activity","id":"d1","agent":"$agent","doing":$doing,"since":$since}""")

    @Test
    fun an_empty_list_is_idle_and_leaves_no_activity() {
        val activities = Activities()
        activities.received("mac", activity("lab/engineer", """[{"kind":"task","task":"t1"}]"""))
        assertTrue(activities.of("mac/lab/engineer") != null)
        activities.received("mac", activity("lab/engineer", "[]"))
        assertNull(activities.of("mac/lab/engineer"))
        assertEquals(0L, activityIn(activity("lab/engineer", "[]")).second.since)
    }

    @Test
    fun a_task_entry_reads_as_working_on_a_task_whatever_else_it_does() {
        val (_, both) = activityIn(activity("lab/engineer", """[{"kind":"answering","conversation":"c1"},{"kind":"task","task":"t1"}]"""))
        assertEquals("working on a task", both.label)
        assertEquals("answering", activityIn(activity("lab/engineer", """[{"kind":"answering","conversation":"c1"}]""")).second.label)
        assertEquals("running a routine", activityIn(activity("lab/engineer", """[{"kind":"routine","routine":"r1"}]""")).second.label)
        assertNull(AgentActivity(emptyList(), 0).label)
    }

    @Test
    fun an_unknown_kind_or_an_entry_without_its_id_is_left_out() {
        val (agent, a) = activityIn(
            activity("lab/engineer", """[{"kind":"dreaming","about":"x"},{"kind":"task"},{"kind":"task","task":""},{"kind":"task","task":"t1"},{"kind":"task","task":"t1"}]"""),
        )
        assertEquals("lab/engineer", agent)
        assertEquals(listOf(Doing.Task("t1")), a.doing)
        // Nothing the app knows: idle.
        assertTrue(activityIn(activity("lab/engineer", """[{"kind":"dreaming"}]""")).second.idle)
    }

    @Test
    fun a_message_without_its_agent_is_not_read() {
        assertThrows(JSONException::class.java) { activityIn(JSONObject("""{"type":"agent.activity","doing":[]}""")) }
    }

    @Test
    fun a_connection_that_ends_clears_its_hosts_activity_only() {
        val held = mutableMapOf<String, AgentActivity>()
        val activities = Activities(held)
        activities.received("mac", activity("lab/engineer", """[{"kind":"task","task":"t1"}]"""))
        activities.received("pi", activity("lab/engineer", """[{"kind":"answering","conversation":"c9"}]"""))
        activities.ended("mac")
        assertNull(activities.of("mac/lab/engineer"))
        assertEquals("answering", activities.of("pi/lab/engineer")?.label)
        // A host named as the other's prefix keeps its own.
        activities.received("macbook", activity("lab/engineer", """[{"kind":"task","task":"t1"}]"""))
        activities.ended("mac")
        assertTrue(activities.of("macbook/lab/engineer") != null)
        activities.clear()
        assertTrue(held.isEmpty())
    }

    @Test
    fun a_new_agents_list_clears_what_was_held_until_the_bridge_says_it_again() {
        val activities = Activities()
        activities.received("mac", activity("lab/engineer", """[{"kind":"task","task":"t1"}]"""))
        activities.listed("mac")
        assertNull(activities.of("mac/lab/engineer"))
    }

    @Test
    fun answering_is_known_by_host_and_conversation() {
        val activities = Activities()
        activities.received("mac", activity("lab/engineer", """[{"kind":"answering","conversation":"c1"}]"""))
        assertTrue(activities.answering("mac", "c1"))
        assertFalse(activities.answering("pi", "c1"))
        assertFalse(activities.answering("mac", "c2"))
    }
}
