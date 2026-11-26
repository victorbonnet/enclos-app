package io.github.victorbonnet.enclos

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
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
    private inner class Host(
        val applies: String = "live",
        val current: JSONObject? = JSONObject().put("provider", "provider-a").put("model", "model-1"),
        val failModels: Boolean = false,
        val extraModels: Int = 0,
        val customModels: Boolean = true,
        val providers: List<JSONObject>? = null,
        val models: List<JSONObject>? = null,
        val failCode: String = "agent_unavailable",
        val failMessage: String? = "Hermes could not read its model inventory.",
        val limited: Boolean = false,
    ) {
        val sent = mutableListOf<JSONObject>()
        private val queued = mutableListOf<JSONObject>()
        val client = ModelClient(send = { _, m -> sent += m; queued += reply(m); true }, connected = { it == "mac" }, supported = { it == address },
            later = { _, _ -> }, log = {})

        fun actions(action: String) = sent.filter { it.getString("action") == action }

        private fun reply(m: JSONObject): JSONObject {
            val base = JSONObject().put("type", "model.response").put("agent", "lab/main").put("of", m.getString("id"))
            return when (m.getString("action")) {
                "options" -> base.put("result", "options").put("revision", "r1").put("applies", applies).put("custom_models", customModels)
                    .apply { if (current != null) put("current", current) }
                    .put("providers", JSONArray().apply {
                        val list = providers ?: listOf(
                            JSONObject().put("id", "provider-a").put("label", "Provider A").put("usable", true),
                            JSONObject().put("id", "provider-b").put("label", "Provider B").put("usable", false).put("setup", "Log in on the host."),
                        )
                        list.forEach(::put)
                    })
                "models" -> if (failModels) JSONObject().put("type", "error").put("of", m.getString("id")).put("code", failCode)
                    .apply { if (failMessage != null) put("message", failMessage) }
                else base.put("result", "models").put("provider", m.getString("provider")).put("limited", limited)
                    .put("models", JSONArray().apply {
                        val list = models ?: buildList {
                            add(JSONObject().put("id", "model-1").put("label", "Model One"))
                            add(JSONObject().put("id", "model-2").put("label", "Model Two"))
                            (3 until 3 + extraModels).forEach { n -> add(JSONObject().put("id", "model-$n").put("label", "Model $n")) }
                        }
                        list.forEach(::put)
                    })
                else -> base.put("result", "set").put("current", JSONObject().put("provider", m.getString("provider")).put("model", m.getString("model")))
                    .put("revision", "r2").put("applies", applies)
            }
        }

        /** Delivers the queued options answer only, leaving the models unanswered. */
        fun deliverOptions() {
            val next = queued.firstOrNull { it.optString("result") == "options" } ?: return
            queued.remove(next)
            compose.runOnIdle { client.received("mac", next) }
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
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Restart the sandbox's agents?").assertExists()
        assertTrue("nothing sent before confirming", host.actions("set").isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("dismissed, nothing sent", host.actions("set").isEmpty())

        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save and restart").performClick()
        compose.waitForIdle()
        val set = host.actions("set").single()
        assertEquals(listOf("provider-a", "model-2", "r1"), listOf(set.getString("provider"), set.getString("model"), set.getString("revision")))
    }

    @Test
    fun save_stays_in_reach_and_an_unsaved_choice_is_named() {
        val host = Host(extraModels = 40)
        show(host)
        compose.onNodeWithText("Model 42").performScrollTo().performClick()
        // Picked at the end of a long list: Save is still on screen, and the
        // page says the pick is not saved yet.
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Not saved: model-42 (provider-a). Tap Save to change the model.").assertExists()
        assertTrue("picking sends nothing", host.actions("set").isEmpty())

        compose.onNodeWithText("Save").performClick()
        host.answer()
        compose.onNodeWithText("Now: model-42 (provider-a)").assertExists()
        compose.onNodeWithText("Not saved: model-42 (provider-a). Tap Save to change the model.").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun a_runtime_that_applies_live_saves_at_once() {
        val host = Host()
        show(host)
        compose.onNodeWithText("Model Two").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
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
        compose.onNodeWithContentDescription("Another model ID").performScrollTo().performTextInput("custom-9")
        restore.emulateSavedInstanceStateRestore()
        host.answer()
        compose.onNodeWithText("custom-9").assertExists()
    }

    @Test
    fun search_filters_highlights_keeps_checked_rows_and_sends_no_request() {
        val host = Host()
        show(host)
        val initialSent = host.sent.size

        compose.onNodeWithText("2 providers").assertExists()
        compose.onNodeWithText("2 listed").assertExists()
        compose.onNodeWithText("MODEL · PROVIDER A").assertExists()

        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("two")
        compose.waitForIdle()

        // Provider A does not match "two", so it is kept with its label; Provider B is hidden.
        compose.onNodeWithText("0 of 2").assertExists()
        compose.onNodeWithText("Provider A").assertExists()
        compose.onNodeWithText("Provider B").assertDoesNotExist()
        // Model One is kept; Model Two matches and is highlighted.
        compose.onNodeWithText("1 of 2").assertExists()
        compose.onNodeWithText("Model One").assertExists()
        val modelTwoTexts = compose.onNodeWithText("Model Two").fetchSemanticsNode().config[SemanticsProperties.Text]
        assertTrue("matched model label is highlighted", modelTwoTexts.any { it.text == "Model Two" && it.spanStyles.isNotEmpty() })
        compose.onAllNodesWithText("Kept: your pick.").assertCountEquals(2)

        // Clear restores the full lists and sends nothing.
        compose.onNodeWithContentDescription("Clear the search").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2 providers").assertExists()
        compose.onNodeWithText("2 listed").assertExists()
        compose.onNodeWithText("Provider B").assertExists()
        compose.onNodeWithText("Kept: your pick.").assertDoesNotExist()
        assertEquals("searching and clearing send no request", initialSent, host.sent.size)
    }

    @Test
    fun chips_filter_switch_toggle_off_and_send_no_request() {
        val host = Host(
            providers = listOf(
                JSONObject().put("id", "provider-a").put("label", "Provider A").put("usable", true),
                JSONObject().put("id", "provider-c").put("label", "Provider C").put("usable", true),
                JSONObject().put("id", "provider-b").put("label", "Provider B").put("usable", false).put("setup", "Log in on the host."),
            ),
        )
        show(host)
        val initialSent = host.sent.size

        compose.onNodeWithText("Ready here · 2").assertExists()
        compose.onNodeWithText("Needs host setup · 1").assertExists()
        compose.onNodeWithText("3 providers").assertExists()

        // Ready here on.
        compose.onNodeWithText("Ready here · 2").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("MODEL PROVIDER · READY HERE").assertExists()
        compose.onNodeWithText("2 of 3").assertExists()
        compose.onNodeWithText("Provider A").assertExists()
        compose.onNodeWithText("Provider C").assertExists()
        compose.onNodeWithText("Provider B").assertDoesNotExist()

        // Switching to Needs host setup turns Ready here off and keeps picked Provider A.
        compose.onNodeWithText("Needs host setup · 1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("MODEL PROVIDER · NEEDS HOST SETUP").assertExists()
        compose.onNodeWithText("1 of 3").assertExists()
        compose.onNodeWithText("Provider A").assertExists()
        compose.onNodeWithText("Kept: your pick.").assertExists()
        compose.onNodeWithText("Provider B").assertExists()
        compose.onNodeWithText("Provider C").assertDoesNotExist()

        // Tapping the active chip clears it.
        compose.onNodeWithText("Needs host setup · 1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("MODEL PROVIDER").assertExists()
        compose.onNodeWithText("3 providers").assertExists()
        compose.onNodeWithText("Provider C").assertExists()
        compose.onNodeWithText("Kept: your pick.").assertDoesNotExist()
        assertEquals("filtering sends no request", initialSent, host.sent.size)
    }

    @Test
    fun provider_no_match_wordings_and_show_all_link() {
        val host = Host()
        show(host)
        val initialSent = host.sent.size

        // Query only.
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("zzz")
        compose.waitForIdle()
        compose.onNodeWithText("No model provider matches \u201Czzz\u201D.").assertExists()
        compose.onNodeWithText("Show all 2 model providers").assertExists()
        compose.onNodeWithText("Provider A").assertExists()

        // Query + Ready here on.
        compose.onNodeWithText("Ready here · 1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No model provider matches \u201Czzz\u201D with Ready here on.").assertExists()
        compose.onNodeWithText("1 of the 2 is ready to use here.").assertExists()

        // Query + Needs host setup on.
        compose.onNodeWithText("Needs host setup · 1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No model provider matches \u201Czzz\u201D with Needs host setup on.").assertExists()
        compose.onNodeWithText("1 of the 2 needs setup on the host.").assertExists()

        // Show all clears the query and leaves the chip on.
        compose.onNodeWithText("Show all 2 model providers").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("MODEL PROVIDER · NEEDS HOST SETUP").assertExists()
        compose.onNodeWithText("1 of 2").assertExists()
        compose.onNodeWithText("Provider B").assertExists()
        assertEquals("clearing via link sends no request", initialSent, host.sent.size)
    }

    @Test
    fun chip_only_no_match_wordings() {
        // No usable providers -> Ready here empty state.
        val noReady = Host(
            current = null,
            providers = listOf(JSONObject().put("id", "provider-b").put("label", "Provider B").put("usable", false).put("setup", "Log in.")),
        )
        show(noReady)
        compose.onNodeWithText("Ready here · 0").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No model provider is ready to use here.").assertExists()
        compose.onNodeWithText("Turn the filter off to see the one that needs setup on the host.").assertExists()
        compose.onAllNodesWithText("Show all", substring = true).assertCountEquals(0)
    }

    @Test
    fun chip_only_needs_setup_no_match_wording() {
        val allReady = Host(
            providers = listOf(JSONObject().put("id", "provider-a").put("label", "Provider A").put("usable", true)),
        )
        show(allReady)
        compose.onNodeWithText("Needs host setup · 0").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No model provider needs host setup.").assertExists()
        compose.onNodeWithText("Turn the filter off to see the one that is ready to use here.").assertExists()
        compose.onAllNodesWithText("Show all", substring = true).assertCountEquals(0)
    }

    @Test
    fun chips_show_no_count_until_the_list_arrives() {
        val host = Host()
        compose.setContent { ModelScreen(host.client, address, hostConnected = true, back = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Ready here").assertExists()
        compose.onNodeWithText("Needs host setup").assertExists()
        compose.onNodeWithText("Ready here · 0").assertDoesNotExist()
        compose.onNodeWithText("Needs host setup · 0").assertDoesNotExist()
        host.answer()
        compose.onNodeWithText("Ready here · 1").assertExists()
        compose.onNodeWithText("Needs host setup · 1").assertExists()
    }

    @Test
    fun one_provider_reads_singular() {
        val host = Host(
            current = null,
            providers = listOf(JSONObject().put("id", "provider-a").put("label", "Provider A").put("usable", true)),
        )
        show(host)
        compose.onNodeWithText("1 provider").assertExists()
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("zzz")
        compose.waitForIdle()
        compose.onNodeWithText("Show all 1 model provider").assertExists()
        compose.onNodeWithText("Show all 1 model providers").assertDoesNotExist()
    }

    @Test
    fun use_query_as_model_id_fills_custom_field_and_sends_nothing() {
        val host = Host(customModels = true)
        show(host)
        val initialSent = host.sent.size

        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("deepseek/r1:free")
        compose.waitForIdle()
        compose.onNodeWithText("No model matches \u201Cdeepseek/r1:free\u201D.").assertExists()
        compose.onNodeWithText("Use \u201Cdeepseek/r1:free\u201D as a model ID").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Not saved: deepseek/r1:free (provider-a). Tap Save to change the model.").assertExists()
        compose.onNodeWithText("Save").assertIsEnabled()
        assertEquals("using query as model id sends nothing", initialSent, host.sent.size)
    }

    @Test
    fun loading_models_shows_the_header_and_a_query_waits_for_them() {
        val host = Host(extraModels = 1)
        compose.setContent { ModelScreen(host.client, address, hostConnected = true, back = {}) }
        compose.waitForIdle()
        host.deliverOptions()
        compose.waitForIdle()
        assertTrue("models asked for", host.actions("models").isNotEmpty())
        compose.onNodeWithText("loading…").assertExists()
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("two")
        compose.waitForIdle()
        compose.onNodeWithText("loading…").assertExists()
        compose.onNodeWithText("No model matches \u201Ctwo\u201D.").assertDoesNotExist()
        compose.onNodeWithText("Use \u201Ctwo\u201D as a model ID").assertDoesNotExist()
        host.answer()
        compose.onNodeWithText("1 of 3").assertExists()
        compose.onNodeWithText("Model Two").assertExists()
        compose.onNodeWithText("loading…").assertDoesNotExist()
        compose.onNodeWithText("Model 3").assertDoesNotExist()
    }

    @Test
    fun use_query_as_model_id_is_hidden_when_custom_models_is_false() {
        val host = Host(customModels = false)
        show(host)
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("deepseek/r1:free")
        compose.waitForIdle()
        compose.onNodeWithText("No model matches \u201Cdeepseek/r1:free\u201D.").assertExists()
        compose.onNodeWithText("Use \u201Cdeepseek/r1:free\u201D as a model ID").assertDoesNotExist()
    }

    @Test
    fun too_large_and_failed_and_limited_model_states() {
        val tooLarge = Host(failModels = true, failCode = "unsupported", failMessage = null, customModels = true)
        show(tooLarge)
        compose.onNodeWithText("not loaded").assertExists()
        compose.onNodeWithText("This model provider lists too many models to show on the phone.").assertExists()
        compose.onNodeWithText("Search can only match models that load on the phone. Type the model's id below.").assertExists()
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("gpt")
        compose.waitForIdle()
        compose.onNodeWithText("not loaded").assertExists()
        compose.onNodeWithText("Search can only match models that load on the phone. Type the model's id below.").assertExists()
        compose.onNodeWithText("No model matches \u201Cgpt\u201D.").assertDoesNotExist()
        compose.onNodeWithText("Use \u201Cgpt\u201D as a model ID").assertDoesNotExist()
    }

    @Test
    fun too_large_without_custom_models_shows_host_guidance() {
        val tooLarge = Host(failModels = true, failCode = "unsupported", failMessage = null, customModels = false)
        show(tooLarge)
        compose.onNodeWithText("not loaded").assertExists()
        compose.onNodeWithText("Search can only match models that load on the phone. Choose another model provider, or set the model on the host.").assertExists()
    }

    @Test
    fun failed_and_limited_models_show_search_notes_only_with_query() {
        val failed = Host(failModels = true)
        show(failed)
        compose.onNodeWithText("not loaded").assertExists()
        compose.onNodeWithText("Search matches its models once they load.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("gpt")
        compose.waitForIdle()
        compose.onNodeWithText("not loaded").assertExists()
        compose.onNodeWithText("Search matches its models once they load.").assertExists()
        compose.onNodeWithText("No model matches \u201Cgpt\u201D.").assertDoesNotExist()
        compose.onNodeWithText("Use \u201Cgpt\u201D as a model ID").assertDoesNotExist()
    }

    @Test
    fun limited_models_show_search_note_with_query() {
        val host = Host(limited = true)
        show(host)
        compose.onNodeWithText("This model provider offers more models than are listed.").assertExists()
        compose.onNodeWithText("Search matches the listed ones.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("one")
        compose.waitForIdle()
        compose.onNodeWithText("Search matches the listed ones.").assertExists()
        compose.onNodeWithContentDescription("Search model providers and models").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithText("Search matches the listed ones.").assertDoesNotExist()
    }

    @Test
    fun query_and_filter_survive_a_recreation() {
        val host = Host()
        val restore = StateRestorationTester(compose)
        restore.setContent { ModelScreen(host.client, address, hostConnected = true, back = {}) }
        host.answer()
        compose.onNodeWithContentDescription("Search model providers and models").performTextInput("one")
        compose.onNodeWithText("Ready here · 1").performClick()
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        host.answer()
        compose.onNodeWithContentDescription("Search model providers and models").assertTextEquals("one")
        compose.onNodeWithText("Ready here · 1").assertIsOn()
        compose.onNodeWithText("MODEL PROVIDER · READY HERE").assertExists()
        compose.onNodeWithText("1 of 2").assertExists()
    }
}
