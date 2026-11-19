package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** On Robolectric, whose Android classes the app's `org.json` parsing runs against. */
@RunWith(RobolectricTestRunner::class)
class ModelClientTest {
    private val address = "mac/lab/main"

    private class Fixture {
        var connected = true
        var supported = true
        var sending = true
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val timers = mutableListOf<() -> Unit>()
        val logs = mutableListOf<String>()
        // The host by name, the agent by its full address: anything else is refused.
        val client = ModelClient(send = { host, m -> sent += host to m; sending }, connected = { it == "mac" && connected },
            supported = { it == "mac/lab/main" && supported }, later = { _, callback -> timers += callback }, log = { logs += it })
        fun last() = sent.last().second
    }

    private fun reply(of: String, result: String) = JSONObject().put("type", "model.response").put("agent", "lab/main").put("of", of).put("result", result)
    private fun error(of: String, code: String, message: String = "") = JSONObject().put("type", "error").put("of", of).put("code", code).put("message", message)

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
        assertTrue(f.client.modelErrors[f.client.modelsKey(address, "provider-a")]!!.text.contains("another model provider"))
        assertEquals(1, f.logs.size)
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
        assertTrue(f.client.errors[address]!!.startsWith("The agent's model changed since this page read it."))
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
        assertEquals("The connection ended before the host confirmed the change. Reconnect and refresh before trying again.", f.client.errors[address])
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
        assertEquals(ModelListError("Hermes could not read its model inventory.", retry = true), f.client.modelErrors[f.client.modelsKey(address, "provider-a")])
        assertNull("not an error of the page", f.client.errors[address])
        assertNotNull(f.client.set(address, "provider-a", "model-2"))
    }

    @Test fun requests_carry_their_fields_and_a_save_the_latest_revision() {
        val f = Fixture(); ready(f)
        f.client.load(address, "provider-a")!!
        assertEquals(listOf("lab/main", "models", "provider-a"), listOf(f.last().getString("agent"), f.last().getString("action"), f.last().getString("provider")))
        f.client.received("mac", reply(f.last().getString("id"), "models").put("provider", "provider-a").put("models", JSONArray()))
        val id = f.client.refresh(address)!!
        f.client.received("mac", options(id).put("revision", "r2"))
        f.client.set(address, "provider-a", "  model-2 ")!!
        val set = f.last()
        assertEquals(listOf("lab/main", "provider-a", "model-2", "r2"), listOf(set.getString("agent"), set.getString("provider"), set.getString("model"), set.getString("revision")))
    }

    @Test fun the_current_model_padded_is_not_sent_again() {
        val f = Fixture(); ready(f)
        assertNull(f.client.set(address, "provider-a", " model-1 "))
    }

    @Test fun a_request_that_cannot_be_sent_says_so_and_waits_for_nothing() {
        val f = Fixture(); ready(f)
        f.sending = false
        assertNull(f.client.set(address, "provider-a", "model-2"))
        assertNull(f.client.pending[address])
        assertEquals("The request was not sent. Reconnect and refresh.", f.client.errors[address])
        assertFalse(f.client.options[address]!!.fresh)
    }

    @Test fun a_save_whose_reply_cannot_be_read_is_unknown_never_saved() {
        val f = Fixture(); ready(f)
        val id = f.client.set(address, "provider-a", "model-2")!!
        assertTrue(f.client.received("mac", reply(id, "set").put("revision", "r2").put("applies", "live")))
        assertFalse(f.client.options[address]!!.fresh)
        assertTrue(f.client.errors[address]!!.contains("may have changed"))
        assertNull(f.client.notices[address])
        assertEquals(1, f.logs.size)
    }

    @Test fun a_reply_of_another_kind_is_not_taken_and_the_request_still_waits() {
        val f = Fixture()
        val id = f.client.refresh(address)!!
        assertFalse(f.client.received("mac", reply(id, "models").put("provider", "provider-a").put("models", JSONArray())))
        assertNotNull(f.client.pending[address])
        assertEquals(1, f.logs.size)
    }

    @Test fun a_confirmed_save_is_not_undone_by_its_timer_and_keeps_the_runtimes_own_id() {
        val f = Fixture(); ready(f)
        val id = f.client.set(address, "provider-a", "model-2")!!
        f.client.received("mac", reply(id, "set").put("current", JSONObject().put("provider", "provider-a").put("model", "model-2-2026"))
            .put("revision", "r2").put("applies", "live"))
        f.timers.last().invoke()
        assertTrue(f.client.options[address]!!.fresh)
        assertNull(f.client.errors[address])
        assertEquals(ModelSelection("provider-a", "model-2-2026"), f.client.options[address]!!.current)
        assertTrue(f.client.notices[address]!!.contains("model-2-2026"))
        assertEquals(1, f.client.confirmed[address])
    }

    @Test fun a_notice_is_dropped_once_the_page_asks_again() {
        val f = Fixture(); ready(f)
        val id = f.client.set(address, "provider-a", "model-2")!!
        f.client.received("mac", reply(id, "set").put("current", JSONObject().put("provider", "provider-a").put("model", "model-2")).put("revision", "r2").put("applies", "live"))
        assertNotNull(f.client.notices[address])
        f.client.refresh(address)!!
        assertNull(f.client.notices[address])
    }

    @Test fun an_unknown_outcome_says_the_model_may_have_changed_and_a_refusal_keeps_the_options() {
        val f = Fixture(); ready(f)
        var id = f.client.set(address, "provider-a", "model-2")!!
        f.client.received("mac", error(id, "unknown", "native control could not be confirmed"))
        assertTrue(f.client.errors[address]!!.startsWith("The host could not confirm whether the model changed."))
        assertTrue(f.client.errors[address]!!.contains("native control could not be confirmed"))
        assertFalse(f.client.options[address]!!.fresh)
        ready(f)
        id = f.client.set(address, "provider-a", "model-9")!!
        f.client.received("mac", error(id, "not_allowed", "Hermes refused the model."))
        assertEquals("Hermes refused the model.", f.client.errors[address])
        assertTrue("nothing changed: the options still hold", f.client.options[address]!!.fresh)
    }

    @Test fun a_model_list_too_large_is_not_offered_again() {
        val f = Fixture(); ready(f)
        val id = f.client.load(address, "provider-a")!!
        f.client.received("mac", error(id, "unsupported"))
        assertFalse(f.client.modelErrors[f.client.modelsKey(address, "provider-a")]!!.retry)
    }

    @Test fun a_model_list_timeout_changes_nothing_else() {
        val f = Fixture(); ready(f)
        f.client.load(address, "provider-a")!!
        f.timers.last().invoke()
        assertTrue(f.client.options[address]!!.fresh)
        assertTrue(f.client.modelErrors[f.client.modelsKey(address, "provider-a")]!!.retry)
    }

    @Test fun an_unknown_applies_is_taken_as_a_restart() {
        val f = Fixture()
        val id = f.client.refresh(address)!!
        f.client.received("mac", options(id, applies = "reload"))
        assertTrue(f.client.options[address]!!.restarts)
        val set = f.client.set(address, "provider-a", "model-2")!!
        f.client.received("mac", reply(set, "set").put("current", JSONObject().put("provider", "provider-a").put("model", "model-2")).put("revision", "r2").put("applies", "restart"))
        assertEquals("Saved. model-2 (provider-a) applies once the runtime restarts.", f.client.notices[address])
    }

    @Test fun a_current_model_without_a_provider_or_with_a_null_one_names_none() {
        assertEquals(ModelSelection(null, "m"), ModelSelection.from(JSONObject().put("model", "m")))
        assertEquals(ModelSelection(null, "m"), ModelSelection.from(JSONObject("{\"provider\":null,\"model\":\"m\"}")))
        assertEquals("m", ModelSelection(null, "m").label)
        val f = Fixture()
        val id = f.client.refresh(address)!!
        f.client.received("mac", options(id).apply { remove("current") })
        assertNull(f.client.options[address]!!.current)
    }

    @Test fun only_an_agent_whose_host_lets_this_person_choose_its_model_offers_it() {
        val agents = agentsIn("mac", JSONObject().put("agents", JSONArray()
            .put(JSONObject().put("agent", "lab/main").put("runtime", "hermes").put("capabilities", JSONArray().put("model")).put("can_manage_model", true))
            .put(JSONObject().put("agent", "lab/guest").put("runtime", "hermes").put("capabilities", JSONArray().put("model")))
            .put(JSONObject().put("agent", "lab/old").put("runtime", "hermes").put("capabilities", JSONArray()).put("can_manage_model", true))))
        assertEquals(listOf(true, false, false), agents.map { it.offersModel })
    }

    /** The connection takes a model reply in (enclos#326): one it did not know would be answered `unknown_type` and dropped. */
    @Test fun a_model_reply_gets_through_the_connection() {
        assertTrue(inbound("""{"v":1,"id":"r-1","type":"model.response","agent":"lab/main","of":"x","result":"options"}""") is Inbound.Use)
    }

    @Test fun a_runtime_that_restarts_says_so() {
        val f = Fixture()
        val id = f.client.refresh(address)!!
        f.client.received("mac", options(id, applies = "restart"))
        assertTrue(f.client.options[address]!!.restarts)
    }
}
