package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RoutineClient.received] and its timers, with org.json on the JVM and a fake bridge. */
class RoutineClientTest {
    private val address = "mac/lab/coder"
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private val timers = mutableListOf<() -> Unit>()
    private var sends = true
    private val client = RoutineClient(
        send = { host, command -> sent += host to command; sends },
        connected = { it == "mac" },
        supported = { it == address },
        later = { _, action -> timers += action },
    )

    private fun summary(id: String = "r1", revision: String = "1", name: String? = "Morning brief", enabled: Boolean = true) = JSONObject()
        .put("id", id).put("revision", revision).put("name", name ?: JSONObject.NULL)
        .put("schedule", JSONObject().put("kind", "cron").put("expression", "0 9 * * *"))
        .put("enabled", enabled).put("prompt_preview", "Summarise the news").put("delivery_label", "Home").put("editable", true)

    private fun reply(of: String, result: String, agent: String = "lab/coder") = JSONObject()
        .put("v", 1).put("id", "reply").put("type", "routine.response").put("of", of).put("agent", agent).put("result", result)

    private fun listReply(of: String, vararg routines: JSONObject) = reply(of, "list")
        .put("routines", JSONArray(routines.toList()))
        .put("options", JSONObject().put("timezone", "Europe/Paris").put("features", JSONArray(listOf("run"))))
        .put("deliveries", JSONArray(listOf(JSONObject().put("id", "home").put("label", "Home"))))
        .put("next_cursor", JSONObject.NULL)

    private fun error(of: String, message: String) = JSONObject().put("v", 1).put("id", "e").put("type", "error").put("of", of).put("code", "bad_message").put("message", message)

    private fun lastId() = sent.last().second.getString("id")

    /** Reads the list once, as the page does on opening. */
    private fun listed(vararg routines: JSONObject) {
        client.refresh(address)
        assertTrue(client.received("mac", listReply(lastId(), *(if (routines.isEmpty()) arrayOf(summary()) else routines))))
    }

    @Test fun a_list_reply_to_the_pending_request_fills_the_list() {
        client.refresh(address)
        val command = sent.single()
        assertEquals("mac", command.first)
        assertEquals("routine.request", command.second.getString("type"))
        assertEquals("lab/coder", command.second.getString("agent"))
        assertEquals("list", command.second.getString("action"))
        assertTrue(client.received("mac", listReply(lastId(), summary())))
        val list = client.lists.getValue(address)
        assertTrue(list.fresh)
        assertEquals(listOf("r1"), list.items.map { it.id })
        assertEquals("Europe/Paris", list.options?.timezone)
        assertTrue(client.pending.isEmpty())
        assertTrue(client.canChange(address))
    }

    @Test fun a_reply_for_another_request_agent_or_host_is_ignored() {
        client.refresh(address)
        val id = lastId()
        assertFalse(client.received("mac", listReply("another-request", summary())))
        assertFalse(client.received("mac", listReply(id, summary()).put("agent", "lab/other")))
        assertFalse(client.received("pi", listReply(id, summary())))
        assertFalse(client.received("pi", error(id, "Routine saved.")))
        assertFalse(client.received("mac", reply(id, "routine").put("routine", summary().put("definition", JSONObject()))))
        assertEquals(id, client.pending[address]?.id)
        assertNull(client.lists[address])
        assertTrue(client.received("mac", listReply(id, summary())))
    }

    @Test fun a_reply_for_another_routine_does_not_finish_the_request() {
        listed()
        val routine = client.lists.getValue(address).items.single()
        val id = client.act(address, routine, "run")!!
        assertFalse(client.received("mac", reply(id, "run").put("routine", "r2")))
        assertFalse(client.received("mac", reply(id, "deleted").put("routine", "r1")))
        assertEquals(id, client.pending[address]?.id)
        assertTrue(client.received("mac", reply(id, "run").put("routine", "r1")))
        assertEquals(id, client.receipts[address])
        assertEquals("list", client.pending[address]?.operation) // a done change reads the list again
    }

    @Test fun an_error_marks_the_list_stale_and_quotes_the_host() {
        listed()
        val routine = client.lists.getValue(address).items.single()
        val id = client.act(address, routine, "delete")!!
        assertTrue(client.received("mac", error(id, "\u202ERoutine saved.")))
        assertFalse(client.lists.getValue(address).fresh)
        assertFalse(client.canChange(address))
        assertTrue(client.pending.isEmpty())
        assertEquals("The host replied: \u201CRoutine saved.\u201D Refresh to check its routines.", client.errors[address])
        client.refresh(address)
        assertTrue(client.received("mac", error(lastId(), "")))
        assertEquals("The host could not complete the request. Refresh to check its routines.", client.errors[address])
    }

    @Test fun an_unreadable_reply_marks_the_list_stale() {
        listed()
        client.refresh(address)
        val unreadable = listReply(lastId(), summary()).apply { remove("routines") }
        assertTrue(client.received("mac", unreadable))
        assertFalse(client.lists.getValue(address).fresh)
        assertTrue(client.pending.isEmpty())
        assertEquals("The host's reply could not be read. Refresh to check the outcome before trying again.", client.errors[address])
    }

    @Test fun a_timeout_says_the_outcome_is_unknown_and_resends_nothing() {
        listed()
        val routine = client.lists.getValue(address).items.single()
        client.act(address, routine, "set_enabled", false)
        val before = sent.size
        timers.toList().forEach { it() }
        assertEquals(before, sent.size)
        assertTrue(client.pending.isEmpty())
        assertFalse(client.lists.getValue(address).fresh)
        assertEquals("The host has not confirmed the outcome. Refresh and check its routines before trying again. The request was not resent.", client.errors[address])
    }

    @Test fun a_timeout_after_the_reply_changes_nothing() {
        listed()
        timers.toList().forEach { it() }
        assertTrue(client.lists.getValue(address).fresh)
        assertNull(client.errors[address])
    }

    @Test fun an_ended_connection_leaves_the_outcome_unknown() {
        listed()
        val routine = client.lists.getValue(address).items.single()
        val id = client.act(address, routine, "run")!!
        client.ended("mac")
        assertTrue(client.pending.isEmpty())
        assertFalse(client.lists.getValue(address).fresh)
        assertEquals("The connection ended before the host confirmed the outcome. Reconnect and refresh before trying again.", client.errors[address])
        assertFalse("a late reply after the connection ended is not taken", client.received("mac", reply(id, "run").put("routine", "r1")))
    }

    @Test fun a_request_that_could_not_be_sent_is_not_pending() {
        sends = false
        client.refresh(address)
        assertTrue(client.pending.isEmpty())
        assertEquals("The request was not sent. Reconnect and refresh its routines.", client.errors[address])
    }

    @Test fun a_routine_that_changed_since_it_was_chosen_says_so() {
        listed()
        val chosen = client.lists.getValue(address).items.single()
        listed(summary(revision = "2"))
        val before = sent.size
        assertNull(client.act(address, chosen, "delete"))
        assertEquals(before, sent.size)
        assertEquals("This routine changed or is no longer listed since you chose it. Check it and try again.", client.errors[address])
    }

    @Test fun runtime_text_is_shown_without_hidden_characters() {
        listed(summary(name = "\u202Eniam").put("delivery_label", "Ho\u200Bme").put("last_error", "line one\nline\u2066 two"))
        val routine = client.lists.getValue(address).items.single()
        assertEquals("niam", routine.name)
        assertEquals("Home", routine.delivery)
        assertEquals("line one\nline two", routine.lastError)
        assertNull(RoutineSummary.from(summary(name = "\u200B")).name)
        val choices = RoutineOptions.choices(JSONArray(listOf(JSONObject().put("id", "id\u202E").put("label", "\u202Elabel"))))
        assertEquals(listOf(RoutineChoice("id\u202E", "label")), choices) // the id goes back as it came
    }

    @Test fun a_definition_is_sent_as_written() {
        val definition = RoutineDefinition("Brief\u202E", "Line one\nline two\u200B", RoutineSchedule.Interval(90), "home", listOf("news"), "m", "p", emptyList(), null, false, "/work")
        val json = definition.json()
        assertEquals(definition, RoutineDefinition.from(json))
        assertTrue(json.isNull("script"))
        assertEquals(0, json.getJSONArray("toolsets").length())
        assertNull(RoutineDefinition.from(definition.copy(toolsets = null).json()).toolsets)
    }

    @Test fun get_reads_the_definition_and_updates_the_listed_revision() {
        listed()
        client.get(address, "r1")
        val routine = summary(revision = "2").put("definition", RoutineDefinition("Morning brief", "Summarise", RoutineSchedule.Cron("0 9 * * *"), null).json())
        assertTrue(client.received("mac", reply(lastId(), "routine").put("routine", routine)))
        assertEquals("Summarise", client.details[client.key(address, "r1")]?.definition?.prompt)
        assertEquals("2", client.lists.getValue(address).items.single().revision)
    }
}
