package io.github.victorbonnet.enclos

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The agent's Model page, in Compose on Robolectric, against a host that answers as told. */
@RunWith(RobolectricTestRunner::class)
class ModelScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val address = "mac/lab/main"

    /** A host: what the page sent, and the answers it gets once [answer] is called. */
    private inner class Host(val applies: String = "live", val current: JSONObject? = JSONObject().put("provider", "provider-a").put("model", "model-1"), val failModels: Boolean = false) {
        val sent = mutableListOf<JSONObject>()
        private val queued = mutableListOf<JSONObject>()
        val client = ModelClient(send = { _, m -> sent += m; queued += reply(m); true }, connected = { it == "mac" }, supported = { it == address },
            later = { _, _ -> }, log = {})

        fun actions(action: String) = sent.filter { it.getString("action") == action }

        private fun reply(m: JSONObject): JSONObject {
            val base = JSONObject().put("type", "model.response").put("agent", "lab/main").put("of", m.getString("id"))
            return when (m.getString("action")) {
                "options" -> base.put("result", "options").put("revision", "r1").put("applies", applies).put("custom_models", true)
                    .apply { if (current != null) put("current", current) }
                    .put("providers", JSONArray()
                        .put(JSONObject().put("id", "provider-a").put("label", "Provider A").put("usable", true))
                        .put(JSONObject().put("id", "provider-b").put("label", "Provider B").put("usable", false).put("setup", "Log in on the host.")))
                "models" -> if (failModels) JSONObject().put("type", "error").put("of", m.getString("id")).put("code", "agent_unavailable").put("message", "Hermes could not read its model inventory.")
                else base.put("result", "models").put("provider", m.getString("provider"))
                    .put("models", JSONArray().put(JSONObject().put("id", "model-1").put("label", "Model One")).put(JSONObject().put("id", "model-2").put("label", "Model Two")))
                else -> base.put("result", "set").put("current", JSONObject().put("provider", m.getString("provider")).put("model", m.getString("model")))
                    .put("revision", "r2").put("applies", applies)
            }
        }

        /** Delivers what the host answered, until the page asks nothing more. */
        fun answer() {
            repeat(10) {
                compose.waitForIdle()
                if (queued.isEmpty()) return
                val now = queued.toList(); queued.clear()
                compose.runOnIdle { now.forEach { client.received("mac", it) } }
            }
            compose.waitForIdle()
        }
    }

    private fun show(host: Host) {
        compose.setContent { ModelScreen(host.client, address, hostConnected = true, back = {}) }
        host.answer()
    }

    @Test
    fun a_runtime_that_restarts_saves_only_once_the_person_confirms() {
        val host = Host(applies = "restart")
        show(host)
        compose.onNodeWithText("Model Two").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Restart the sandbox's agents?").assertExists()
        assertTrue("nothing sent before confirming", host.actions("set").isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("dismissed, nothing sent", host.actions("set").isEmpty())

        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Save and restart").performClick()
        compose.waitForIdle()
        val set = host.actions("set").single()
        assertEquals(listOf("provider-a", "model-2", "r1"), listOf(set.getString("provider"), set.getString("model"), set.getString("revision")))
    }

    @Test
    fun a_runtime_that_applies_live_saves_at_once() {
        val host = Host()
        show(host)
        compose.onNodeWithText("Model Two").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Restart the sandbox's agents?").assertDoesNotExist()
        assertEquals(1, host.actions("set").size)
        host.answer()
        compose.onNodeWithText("Now: model-2 (provider-a)").assertExists()
    }

    @Test
    fun a_model_provider_not_set_up_is_shown_disabled_with_how_to_set_it_up() {
        val host = Host()
        show(host)
        compose.onNodeWithText("Log in on the host.").assertExists()
        compose.onNodeWithText("Provider B").assertIsNotEnabled()
        compose.onNodeWithText("Provider B").performScrollTo().performClick()
        host.answer()
        assertTrue("no list asked for it", host.actions("models").none { it.getString("provider") == "provider-b" })
    }

    @Test
    fun an_agent_on_a_model_provider_the_runtime_does_not_list_is_asked_to_choose_one() {
        val host = Host(current = JSONObject().put("provider", "auto").put("model", "x"))
        show(host)
        compose.onNodeWithText("Its model provider, auto, is not among those the runtime lists. Choose one below.").assertExists()
        assertTrue("nothing loaded before a choice", host.actions("models").isEmpty())
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Provider A").performScrollTo().performClick()
        host.answer()
        assertEquals(1, host.actions("models").size)
    }

    @Test
    fun a_model_list_that_fails_is_not_asked_again_until_the_person_does() {
        val host = Host(failModels = true)
        show(host)
        host.answer()
        assertEquals("loaded once, no loop", 1, host.actions("models").size)
        compose.onNodeWithText("Hermes could not read its model inventory. Tap to try again.").performScrollTo().performClick()
        host.answer()
        assertEquals(2, host.actions("models").size)
    }

    @Test
    fun a_typed_model_id_survives_a_recreation() {
        val host = Host()
        val restore = StateRestorationTester(compose)
        restore.setContent { ModelScreen(host.client, address, hostConnected = true, back = {}) }
        host.answer()
        compose.onNode(hasSetTextAction()).performTextInput("custom-9")
        restore.emulateSavedInstanceStateRestore()
        host.answer()
        compose.onNodeWithText("custom-9").assertExists()
    }
}
