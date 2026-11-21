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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        compose.onNodeWithText("Answer in your own words").performScrollTo().performClick()
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
        // Back online, after the sync: a new connection, so the draft is earlier text, reviewed
        // before it is reused (#82); used, it goes with Send.
        compose.runOnIdle {
            online = true
            client.authenticated("mac", answers = true)
            client.synced("mac")
        }
        compose.onNodeWithText("The connection changed. Review this request before reusing your earlier text.").assertExists()
        compose.onNodeWithText("Use text in Your answer").performScrollTo().assertIsEnabled().performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithText("Send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(HeldTaskAnswer.Text("Wait for Monday"), sent.single().answer)
    }

    @Test
    fun a_configuration_change_keeps_the_text_from_the_model_and_a_new_model_has_none() {
        var current by mutableStateOf(client(written))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HeldTaskAnswerPanel(current, current.cards[written.key] ?: written) {}
            }
        }
        compose.onNodeWithText("Answer in your own words").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Kept in memory")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Kept in memory").assertExists()
        compose.onNodeWithText("Not sent. Kept temporarily in this app.").assertExists()
        compose.onNodeWithText("It may be lost if Android closes the app.").assertExists()
        // The model ended (Android closed the app) while the saved state stayed: the text is gone.
        compose.runOnIdle { current = client(written) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Kept in memory").assertDoesNotExist()
        compose.onNodeWithText("Not sent. Kept temporarily in this app.").assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun text_that_may_have_reached_the_agent_is_never_called_not_sent() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Answer in your own words").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Wait for Monday")
        compose.onNodeWithText("Not sent. Kept temporarily in this app.").assertExists()
        compose.onNodeWithText("Send").performScrollTo().performClick()
        compose.runOnIdle {
            online = false
            client.ended("mac")
        }
        compose.onNodeWithText("Wait for Monday").assertExists()
        compose.onNodeWithText("Kept temporarily in this app.").assertExists()
        compose.onNodeWithText("Not sent. Kept temporarily in this app.").assertDoesNotExist()
        assertEquals(1, sent.size)
    }

    @Test
    fun a_changed_request_shows_earlier_text_read_only_and_use_fills_the_field_without_sending() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Answer in your own words").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Wait for Monday")
        compose.runOnIdle { client.merge(written.copy(revision = 2, ask = written.ask!!.copy(decision = "Allow two more hours?"))) }
        compose.onNodeWithText("Allow two more hours?").assertExists()
        compose.onNodeWithText("This request changed. Your earlier text has not been added to it.").assertExists()
        compose.onNodeWithText("Earlier text · read-only").assertExists()
        compose.onNodeWithText(
            "Review the current decision, blocker and choices first. Using earlier text fills Your answer; it does not send it.",
        ).assertExists()
        compose.onNodeWithText("Wait for Monday").assertExists()
        compose.onNodeWithText("Discard earlier text").assertExists()
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Use text in Your answer").performScrollTo().assertIsEnabled().performClick()
        assertTrue("using earlier text sends nothing", sent.isEmpty())
        compose.onNodeWithText("Earlier text · read-only").assertDoesNotExist()
        compose.onNodeWithText("Not sent. Kept temporarily in this app.").assertExists()
        compose.onNodeWithText("Send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(HeldTaskAnswer.Text("Wait for Monday"), sent.single().answer)
        assertEquals(2L, sent.single().card.revision)
    }

    @Test
    fun offline_or_unsynced_disables_send_and_use_and_discard_drops_the_earlier_text() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Answer in your own words").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Wait for Monday")
        compose.runOnIdle {
            client.ended("mac")
            client.authenticated("mac", answers = true)
        }
        compose.onNodeWithText("The connection changed. Review this request before reusing your earlier text.").assertExists()
        compose.onNodeWithText("Use text in Your answer").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            online = false
            client.synced("mac")
        }
        compose.onNodeWithText("Use text in Your answer").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { online = true }
        compose.onNodeWithText("Use text in Your answer").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Discard earlier text").performScrollTo().performClick()
        compose.onNodeWithText("Earlier text · read-only").assertDoesNotExist()
        compose.onNodeWithText("Wait for Monday").assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    /**
     * #82: type an answer, open the exact conversation (the sheet leaves composition), go back to
     * Inbox and reopen the same unchanged request: the same unsent text is back in Your answer,
     * and nothing was sent on the way.
     */
    @Test
    fun the_same_request_keeps_its_unsent_answer_across_a_conversation_round_trip() {
        val client = client(written)
        var sheet by mutableStateOf(true)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (sheet) HeldTaskAnswerPanel(client, client.cards[written.key] ?: written) {}
                else Text("Inbox")
            }
        }
        compose.onNodeWithText("Answer in your own words").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("UNSENT something else, never sent")
        compose.runOnIdle { sheet = false }
        compose.onNodeWithText("Inbox").assertExists()
        compose.runOnIdle { sheet = true }
        compose.onNodeWithText("UNSENT something else, never sent").assertExists()
        compose.onNodeWithText("Send").performScrollTo().assertIsEnabled()
        assertTrue("nothing sent by typing or navigation", sent.isEmpty())
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun the_agents_words_come_first_and_the_recommended_choice_leads_marked() {
        val client = client(written)
        show(client, written)
        compose.onNodeWithText("Allow one more hour to fix Hermes backups?").assertExists()
        compose.onNodeWithText("The two approved coding runs are used up.").assertExists()
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
    fun a_typed_answer_is_folded_until_wanted_and_sent_with_send() {
        val client = client(written)
        show(client, written)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithText("Answer in your own words").performScrollTo().performClick()
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("  Wait for Monday ")
        compose.onNodeWithText("Send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(HeldTaskAnswer.Text("Wait for Monday"), sent.single().answer)
    }

    @Test
    fun a_card_no_agent_wrote_shows_the_whole_reason_and_asks_the_agent_for_choices() {
        val long = "Existing decision remains unanswered: " + "both reviews hold, the coding runs are spent. ".repeat(12) + "Allow one more hour, or keep paused?"
        val card = written.copy(ask = null, reason = long)
        val client = client(card)
        show(client, card)
        compose.onNodeWithText("Deliver the Hermes backup fix").assertExists()
        // The reason in full, not cut to two lines, and not again under Details.
        compose.onNodeWithText(long).assertExists()
        compose.onNodeWithText(ASK_FOR_CHOICES_HINT).assertExists()
        compose.onNodeWithText("Resume task").assertDoesNotExist()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("Task t_f9f3").assertExists()
        assertEquals(1, compose.onAllNodesWithText(long).fetchSemanticsNodes().size)
        compose.onNodeWithText(ASK_FOR_CHOICES).performScrollTo().performClick()
        assertEquals(HeldTaskAnswer.Agent, sent.single().answer)
    }

    @Test
    fun an_ask_without_choices_opens_the_field_at_once() {
        val card = written.copy(ask = HeldTaskAsk("What should the release notes say?", "The notes need the person's words."))
        val client = client(card)
        show(client, card)
        compose.onNodeWithText("Answer in your own words").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Say it fixes backups")
        compose.onNodeWithText("Send").performScrollTo().performClick()
        assertEquals(HeldTaskAnswer.Text("Say it fixes backups"), sent.single().answer)
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

    @Test
    fun an_ask_that_says_nothing_is_blocked_shows_the_reason_once() {
        val card = written.copy(ask = written.ask!!.copy(blocked = ""))
        val client = client(card)
        show(client, card)
        compose.onNodeWithText("Details").performScrollTo().performClick()
        assertEquals(1, compose.onAllNodesWithText(written.reason).fetchSemanticsNodes().size)
    }

    @Test
    fun a_kept_draft_keeps_the_field_open_when_choices_come() {
        val bare = written.copy(ask = HeldTaskAsk("What should the notes say?", "The notes need your words."))
        val client = client(bare)
        show(client, bare)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Say it fixes backups")
        // The agent asks again, now with choices: the draft stays, in its open field.
        compose.runOnIdle { client.merge(written.copy(revision = 2)) }
        compose.onNodeWithText("Say it fixes backups").assertExists()
        compose.onNodeWithText("Answer in your own words").assertDoesNotExist()
        compose.onNodeWithText("Keep #346 paused").assertExists()
    }

    @Test
    fun an_answered_card_no_agent_wrote_offers_no_answer_and_keeps_its_reason_in_details() {
        val card = written.copy(ask = null, stateWire = "answered", answer = "Asked the agent to look into it.")
        val client = client(card)
        show(client, card)
        compose.onNodeWithText("Asked the agent to look into it.").assertExists()
        compose.onNodeWithText(ASK_FOR_CHOICES).assertDoesNotExist()
        compose.onNodeWithText("Answer in your own words").assertDoesNotExist()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText(written.reason).assertExists()
    }
}
