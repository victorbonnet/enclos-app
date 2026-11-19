package io.github.victorbonnet.enclos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A held task's Needs you panel (enclos ADR 0073), in Compose on Robolectric, against a client that records what it sends. */
@RunWith(RobolectricTestRunner::class)
class HeldTaskAnswerPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val home = Conversation("mac", "lab/runtimes", "home.lab.runtimes", home = true)

    private val written = HeldTask(
        host = "mac", agent = "lab/runtimes", conversation = "home.lab.runtimes", task = "t_f9f3",
        title = "Deliver the Hermes backup fix", assignee = "director",
        reason = "Both coding runs are spent; the person must decide.", since = 1_763_164_800,
        resume = "Decide on the agent's task board.", stateWire = "held", revision = 1, resumable = false,
        ask = HeldTaskAsk(
            "Allow one more hour to fix Hermes backups?",
            "The two approved coding runs are used up.",
            listOf("Keep #346 paused", "Allow one 60-min fix, then review and CI"),
            recommended = 1,
        ),
    )

    private val sent = mutableListOf<HeldTaskRequest>()

    /** Snapshot state, as the app's connection state is: the panel redraws when it changes. */
    private var online by mutableStateOf(true)

    private fun client(card: HeldTask): HeldTaskClient {
        val client = HeldTaskClient(
            send = { sent += it; true }, connected = { online }, accepts = { it.belongsTo(home) },
            changed = {}, later = { _, _ -> },
        )
        client.authenticated("mac", answers = true); client.merge(card); client.synced("mac")
        return client
    }

    private fun show(client: HeldTaskClient, card: HeldTask) {
        compose.setContent {
            // The card as the client holds it now: a result redraws the panel. The layout is
            // chosen as the Needs you sheet chooses it.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (client.answersOn(card.host)) {
                    HeldTaskAnswerPanel(client, client.cards[card.key] ?: card) {
                        RequestSecondaryButton("View task") {}
                    }
                } else {
                    Text("The layout before answers")
                }
            }
        }
    }

    @Test
    fun a_connection_that_ends_mid_draft_keeps_the_panel_its_draft_and_says_why() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Wait for Monday")
        compose.runOnIdle {
            online = false
            client.ended("mac")
        }
        compose.onNodeWithText("The layout before answers").assertDoesNotExist()
        compose.onNodeWithText("Wait for Monday").assertExists()
        compose.onNodeWithText("Task t_f9f3").assertExists()
        compose.onNodeWithText("Connect to mac to answer.").assertExists()
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Keep #346 paused").performScrollTo().assertIsNotEnabled()
        // Back online, after the sync, the same draft goes.
        compose.runOnIdle {
            online = true
            client.authenticated("mac", answers = true)
            client.synced("mac")
        }
        compose.onNodeWithText("Send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(HeldTaskAnswer.Text("Wait for Monday"), sent.single().answer)
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun the_agents_words_come_first_and_the_recommended_choice_leads_marked() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Allow one more hour to fix Hermes backups?").assertExists()
        compose.onNodeWithText("Blocked: The two approved coding runs are used up.").assertExists()
        compose.onNodeWithText("Recommended", useUnmergedTree = true).assertExists()
        assertTrue("recommended first", top("Allow one 60-min fix, then review and CI") < top("Keep #346 paused"))
        // Details are folded: the task's id, the runtime's reason and the other actions.
        compose.onNodeWithText("Task t_f9f3").assertDoesNotExist()
        compose.onNodeWithText("View task").assertDoesNotExist()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("Task t_f9f3").assertExists()
        compose.onNodeWithText("Both coding runs are spent; the person must decide.").assertExists()
        compose.onNodeWithText("View task").assertExists()
        // The recommended choice answers by its own index, once.
        compose.onNodeWithText("Allow one 60-min fix, then review and CI").performScrollTo().performClick()
        assertEquals(HeldTaskAnswer.Choice(1), sent.single().answer)
        compose.onNodeWithText("Keep #346 paused").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(1, sent.size)
        compose.onNodeWithText("Sending your answer…").assertExists()
    }

    @Test
    fun a_typed_answer_is_sent_with_send() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("  Wait for Monday ")
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        assertEquals(HeldTaskAnswer.Text("Wait for Monday"), sent.single().answer)
    }

    @Test
    fun a_card_no_agent_wrote_asks_the_agent_and_shows_the_runtimes_reason() {
        val card = written.copy(ask = null)
        val client = client(card)
        show(client, card)
        compose.onNodeWithText("Deliver the Hermes backup fix").assertExists()
        compose.onNodeWithText("Blocked: Both coding runs are spent; the person must decide.").assertExists()
        compose.onNodeWithText("Resume task").assertDoesNotExist()
        compose.onNodeWithText("Ask the agent").performScrollTo().performClick()
        assertEquals(HeldTaskAnswer.Agent, sent.single().answer)
    }

    @Test
    fun a_resumable_card_no_agent_wrote_keeps_resume() {
        val card = written.copy(ask = null, resumable = true)
        val client = client(card)
        show(client, card)
        compose.onNodeWithText("Resume task").performScrollTo().performClick()
        assertEquals(null, sent.single().answer)
        assertEquals("held_task.resume", sent.single().command().getString("type"))
    }

    @Test
    fun the_answered_result_says_what_was_answered_and_offers_no_answer() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Keep #346 paused").performScrollTo().performClick()
        assertEquals(HeldTaskAnswer.Choice(0), sent.single().answer)
        val result = JSONObject().put("v", 1).put("id", "r-1").put("type", "held_task.result").put("of", sent.single().id)
            .put("held_task", written.copy(stateWire = "answered", revision = 2, answer = "Keep #346 paused").json())
        compose.runOnIdle { client.received("mac", result) }
        compose.onNodeWithText("Answered: Keep #346 paused").assertExists()
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithText("Recommended", useUnmergedTree = true).assertDoesNotExist()
    }
}
