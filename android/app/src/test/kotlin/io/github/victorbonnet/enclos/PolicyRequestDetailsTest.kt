package io.github.victorbonnet.enclos

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The policy request page's details and decisions, drawn by [PolicyRequestDetails] and [PolicyDecisions]
 * (`docs/spec.md`, the sandbox policy request): the same words, links and controls whether the real
 * [PolicyRequestScreen] gives them the model's state or a probe gives them synthetic state. Synthetic
 * requests only; no decision, close or question is sent: the decisions are checked with their callbacks
 * recorded, not the model's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PolicyRequestDetailsTest {
    @get:Rule val compose = createComposeRule()

    private val host = "test-host"
    private val request = PolicyRequest(
        host, "fixture-1", "lab", "lab/worker", "c1", "network", "api.example.test", false,
        "Connect to api.example.test:443", 1_700_000_000, "pending", "Allow network access to api.example.test", false,
        "fixture-1", true, "Fetch a dependency", "api.example.test",
    )
    private val worker = Agent(host, "lab/worker", "hermes", emptyList(), displayName = "Worker")

    private fun screen(shown: PolicyRequest, listed: Boolean = true, holds: Boolean = true, asked: MutableList<String>, opened: MutableList<String>, body: (AppModel) -> Unit) {
        val model = fixtureModel()
        try {
            FakeBridge().attach(model, host, files = false)
            model.paired.add(fixtureHost(host))
            if (listed) model.agents.add(worker)
            if (holds) model.conversations.add(Conversation(host, "lab/worker", "c1"))
            model.approvals[shown.key] = shown
            compose.setContent {
                EnclosTheme(dark = false) {
                    PolicyRequestScreen(model, shown.key, back = {}, openChat = { opened += it }, ask = { asked += it }, insets = WindowInsets(bottom = 24.dp))
                }
            }
            compose.waitForIdle()
            body(model)
        } finally {
            model.endFixture()
        }
    }

    @Test fun the_screen_asks_the_agent_that_asked_and_opens_its_conversation() {
        val asked = mutableListOf<String>()
        val opened = mutableListOf<String>()
        screen(request, asked = asked, opened = opened) {
            compose.onNodeWithText("Requested by Worker").assertExists()
            compose.onNode(hasText("Ask Worker why →") and hasClickAction()).performScrollTo().performClick()
            compose.onNode(hasText("Open conversation →") and hasClickAction()).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf("test-host/lab/worker"), asked)
            assertEquals(listOf("c1"), opened)
        }
    }

    @Test fun no_conversation_link_when_the_phone_does_not_hold_it() {
        screen(request, holds = false, asked = mutableListOf(), opened = mutableListOf()) {
            compose.onNodeWithText("Ask Worker why →").assertExists()
            compose.onNodeWithText("Open conversation →").assertDoesNotExist()
        }
    }

    @Test fun an_agent_the_phone_does_not_list_is_named_and_not_asked() {
        screen(request, listed = false, holds = false, asked = mutableListOf(), opened = mutableListOf()) {
            compose.onNodeWithText("why, this phone needs to list it: it was removed, or its host has not listed it yet.", substring = true).assertExists()
            compose.onNode(hasText("why →", substring = true) and hasClickAction()).assertDoesNotExist()
        }
    }

    @Test fun a_proxy_refusal_asks_the_first_listed_agent_when_none_wrote() {
        val asked = mutableListOf<String>()
        screen(request.copy(agent = "", conversation = null), holds = false, asked = asked, opened = mutableListOf()) {
            compose.onNodeWithText("The proxy records this for the sandbox; it cannot identify which agent tried it.").assertExists()
            compose.onNodeWithText("No agent of lab has a message here yet: this asks the first listed; you can pick another before sending.").assertExists()
            compose.onNode(hasText("Ask Worker why →") and hasClickAction()).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf("test-host/lab/worker"), asked)
        }
    }

    @Test fun a_failed_request_offers_no_question() {
        screen(request.copy(state = "failed"), asked = mutableListOf(), opened = mutableListOf()) {
            compose.onNode(hasText("why", substring = true) and hasClickAction()).assertDoesNotExist()
            compose.onNodeWithText("To ask", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Decision needs review on the host").assertExists()
            compose.onNodeWithText("Open conversation →").assertExists()
        }
    }

    @Test fun a_proxy_refusal_names_the_agent_with_the_newest_message() {
        compose.setContent {
            EnclosTheme(dark = false) {
                Column {
                    PolicyRequestDetails(request.copy(agent = "", conversation = null), agentLabel = { "Worker" }, asked = worker,
                        askedWrote = true, ask = {}, openConversation = null)
                }
            }
        }
        compose.onNodeWithText("This asks the agent of lab with the newest message here; you can pick another before sending.").assertExists()
    }

    /** Which control the decisions call, and only from an enabled one. */
    @Test fun the_decisions_call_back_only_from_enabled_controls() {
        val calls = mutableListOf<String>()
        var live by mutableStateOf(true)
        var closable by mutableStateOf(true)
        var operation by mutableStateOf<PolicyRequestAction?>(null)
        compose.setContent {
            EnclosTheme(dark = false) {
                Column {
                    PolicyDecisions(request, operation, live, closable, error = null,
                        decide = { calls += if (it) "approve" else "deny" }, close = { calls += "close" })
                }
            }
        }
        compose.onNodeWithText("Approve").assertIsEnabled().performClick()
        compose.onNodeWithText("Deny").assertIsEnabled().performClick()
        compose.onNodeWithText("Close request").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("approve", "deny", "close"), calls)

        calls.clear()
        compose.runOnIdle { closable = false }
        compose.onNodeWithText("Close request").assertIsNotEnabled().performClick()
        compose.onNodeWithText("This host cannot close requests yet.").assertExists()
        compose.onNodeWithText("Approve").assertIsEnabled()

        compose.runOnIdle { closable = true; live = false }
        compose.onNodeWithText("Approve").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Deny").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Close request").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Reconnect to test-host and sync to respond.").assertExists()

        compose.runOnIdle { operation = PolicyRequestAction.Approve }
        compose.onNodeWithText("Saving decision…").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        compose.onNodeWithText("Close request").assertIsNotEnabled()

        compose.runOnIdle { operation = PolicyRequestAction.Close; live = true }
        compose.onNodeWithText("Closing request…").assertIsNotEnabled()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun a_decision_in_flight_offline_says_to_reconnect_to_check_it() {
        compose.setContent {
            EnclosTheme(dark = false) {
                Column {
                    PolicyDecisions(request.copy(state = "applying"), operation = null, live = false, closable = true, error = "Nothing was sent.",
                        decide = {}, close = {})
                }
            }
        }
        compose.onNodeWithText("Saving decision…").assertIsNotEnabled()
        compose.onNodeWithText("Reconnect to test-host and sync to check the decision.").assertExists()
        compose.onNodeWithText("Close request").assertDoesNotExist()
        compose.onNodeWithText("Nothing was sent.").assertExists()
    }
}
