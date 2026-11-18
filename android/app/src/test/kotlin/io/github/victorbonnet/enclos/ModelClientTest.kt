package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** On Robolectric for Android's real `org.json`. */
@RunWith(RobolectricTestRunner::class)
class ModelClientTest {
    private val address = "mac/lab/main"

    private class Fixture {
        var connected = true
        var supported = true
        var sending = true
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val timers = mutableListOf<() -> Unit>()
        val client = ModelClient(send = { host, m -> sent += host to m; sending }, connected = { connected }, supported = { supported },
            later = { _, callback -> timers += callback })
        fun last() = sent.last().second
    }

    private fun options(of: String, applies: String = "live") = JSONObject()
        .put("type", "model.response").put("agent", "lab/main").put("of", of).put("result", "options")
        .put("current", JSONObject().put("provider", "provider-a").put("model", "model-1"))
        .put("revision", "r1").put("applies", applies).put("custom_models", true)
        .put("providers", JSONArray()
            .put(JSONObject().put("id", "provider-a").put("label", "Provider A").put("usable", true))
            .put(JSONObject().put("id", "provider-b").put("label", "Provider B").put("usable", false).put("setup", "Log in on the host.")))

    private fun ready(f: Fixture) {
        val id = f.client.refresh(address)!!
        assertTrue(f.client.received("mac", options(id)))
    }

    @Test fun options_ask_the_agent_by_its_sandbox_address_and_read_the_providers() {
        val f = Fixture()
        val id = f.client.refresh(address)!!
        val sent = f.last()
        assertEquals("mac", f.sent.last().first)
        assertEquals("model.request", sent.getString("type"))
        assertEquals("lab/main", sent.getString("agent"))
        assertEquals("options", sent.getString("action"))
        assertTrue(f.client.received("mac", options(id)))
        val read = f.client.options[address]!!
        assertEquals(ModelSelection("provider-a", "model-1"), read.current)
        assertEquals(listOf(true, false), read.providers.map { it.usable })
        assertEquals("Log in on the host.", read.providers[1].setup)
        assertFalse(read.restarts)
        assertNull(f.client.pending[address])
    }

    @Test fun models_are_kept_per_provider_and_only_for_the_provider_asked() {
        val f = Fixture(); ready(f)
        val id = f.client.load(address, "provider-a")!!
        val wrong = JSONObject().put("type", "model.response").put("agent", "lab/main").put("of", id).put("result", "models")
            .put("provider", "provider-b").put("models", JSONArray())
        assertTrue(f.client.received("mac", wrong))
        assertNull(f.client.models[f.client.modelsKey(address, "provider-a")])
        assertNotNull(f.client.errors[address])
        ready(f)
        val again = f.client.load(address, "provider-a")!!
        val right = JSONObject().put("type", "model.response").put("agent", "lab/main").put("of", again).put("result", "models")
            .put("provider", "provider-a").put("limited", true)
            .put("models", JSONArray().put(JSONObject().put("id", "model-2").put("label", "Model Two")))
        assertTrue(f.client.received("mac", right))
        val listed = f.client.models[f.client.modelsKey(address, "provider-a")]!!
        assertEquals(listOf(ModelChoice("model-2", "Model Two")), listed.models)
        assertTrue(listed.limited)
    }

    @Test fun set_sends_the_revision_and_refuses_a_provider_not_set_up_or_the_same_model() {
        val f = Fixture(); ready(f)
        assertNull(f.client.set(address, "provider-b", "b-1"))
        assertNull(f.client.set(address, "provider-a", "model-1"))
        assertNull(f.client.set(address, "provider-a", " "))
        val id = f.client.set(address, "provider-a", "model-2")!!
        val sent = f.last()
        assertEquals("set", sent.getString("action"))
        assertEquals("r1", sent.getString("revision"))
        assertNull("one request at a time", f.client.set(address, "provider-a", "model-3"))
        val saved = JSONObject().put("type", "model.response").put("agent", "lab/main").put("of", id).put("result", "set")
            .put("current", JSONObject().put("provider", "provider-a").put("model", "model-2")).put("revision", "r2").put("applies", "live")
        assertTrue(f.client.received("mac", saved))
        assertEquals(ModelSelection("provider-a", "model-2"), f.client.options[address]!!.current)
        assertEquals("r2", f.client.options[address]!!.revision)
        assertTrue(f.client.notices[address]!!.contains("next message"))
    }

    @Test fun a_refusal_or_a_lost_reply_leaves_the_options_stale_and_nothing_resent() {
        val f = Fixture(); ready(f)
        val id = f.client.set(address, "provider-a", "model-2")!!
        val conflict = JSONObject().put("type", "error").put("of", id).put("code", "conflict").put("message", "The agent's model changed.")
        assertTrue(f.client.received("mac", conflict))
        assertFalse(f.client.options[address]!!.fresh)
        assertEquals("The agent's model changed.", f.client.errors[address])
        assertNull("stale options cannot be saved against", f.client.set(address, "provider-a", "model-2"))
        ready(f)
        f.client.set(address, "provider-a", "model-2")!!
        val count = f.sent.size
        f.timers.last().invoke()
        assertEquals(count, f.sent.size)
        assertFalse(f.client.options[address]!!.fresh)
        assertTrue(f.client.errors[address]!!.contains("not confirmed"))
    }

    @Test fun another_hosts_or_agents_reply_is_not_taken() {
        val f = Fixture()
        val id = f.client.refresh(address)!!
        assertFalse(f.client.received("pi", options(id)))
        assertFalse(f.client.received("mac", options(id).put("agent", "lab/other")))
        assertFalse(f.client.received("mac", options("another")))
        assertTrue(f.client.received("mac", options(id)))
    }

    @Test fun a_connection_that_ends_fails_the_pending_change() {
        val f = Fixture(); ready(f)
        f.client.set(address, "provider-a", "model-2")!!
        f.client.ended("mac")
        assertNull(f.client.pending[address])
        assertTrue(f.client.errors[address]!!.contains("connection ended"))
        assertFalse(f.client.options[address]!!.fresh)
        f.client.forget("mac")
        assertNull(f.client.options[address])
    }

    @Test fun nothing_is_sent_without_the_capability_or_a_connection() {
        val f = Fixture()
        f.supported = false
        assertNull(f.client.refresh(address))
        f.supported = true; f.connected = false
        assertNull(f.client.refresh(address))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun a_refresh_drops_the_model_lists_read_before() {
        val f = Fixture(); ready(f)
        val id = f.client.load(address, "provider-a")!!
        f.client.received("mac", JSONObject().put("type", "model.response").put("agent", "lab/main").put("of", id).put("result", "models")
            .put("provider", "provider-a").put("models", JSONArray().put(JSONObject().put("id", "model-2").put("label", "Model Two"))))
        assertNotNull(f.client.models[f.client.modelsKey(address, "provider-a")])
        f.client.refresh(address)!!
        assertNull(f.client.models[f.client.modelsKey(address, "provider-a")])
    }

    @Test fun a_model_list_that_fails_leaves_the_options_fresh() {
        val f = Fixture(); ready(f)
        val id = f.client.load(address, "provider-a")!!
        f.client.received("mac", JSONObject().put("type", "error").put("of", id).put("code", "agent_unavailable").put("message", "Hermes could not read its model inventory."))
        assertTrue("Save stays available", f.client.options[address]!!.fresh)
        assertEquals("Hermes could not read its model inventory.", f.client.errors[address])
        assertNotNull(f.client.set(address, "provider-a", "model-2"))
    }

    @Test fun a_runtime_that_restarts_says_so() {
        val f = Fixture()
        val id = f.client.refresh(address)!!
        f.client.received("mac", options(id, applies = "restart"))
        assertTrue(f.client.options[address]!!.restarts)
    }
}
