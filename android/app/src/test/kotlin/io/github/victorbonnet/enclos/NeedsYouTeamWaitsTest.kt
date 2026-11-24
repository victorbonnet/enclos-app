package io.github.victorbonnet.enclos

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Held tasks whose runtime says they wait on the team stay out of Needs you and its counts,
 * while their conversation cards stay visible and unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NeedsYouTeamWaitsTest {
    @get:Rule
    val compose = createComposeRule()

    private val host = "mac"
    private val agent = "lab/runtimes"
    private val conversation = "home.lab.runtimes"

    private fun heldRow(id: String, waitsOn: String? = null, since: Long = 100): JSONObject = JSONObject()
        .put("id", id).put("kind", "held_task").put("agent", agent).put("conversation", conversation)
        .put("summary", "Held summary $id").put("since", since).put("expired", false).put("version", "held:1")
        .apply { if (waitsOn != null) put("waits_on", waitsOn) }

    private fun questionRow(id: String, since: Long = 90): JSONObject = JSONObject()
        .put("id", id).put("kind", "question").put("agent", agent).put("conversation", conversation)
        .put("summary", "Allow a fix?").put("since", since).put("expired", false)

    private fun policyRow(id: String, since: Long = 80): JSONObject = JSONObject()
        .put("id", id).put("kind", "policy_request").put("agent", agent).put("conversation", conversation)
        .put("summary", "Allow network access").put("since", since).put("expired", false)

    private fun openItemsFrame(revision: Long, vararg items: JSONObject): JSONObject = message(
        "open_items",
        "revision" to revision,
        "reset" to true,
        "complete" to true,
        "items" to JSONArray(items),
    )

    @Test
    fun team_waited_held_task_is_excluded_from_attention_items_while_other_rows_stay() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost(host))
            model.receive(
                host,
                openItemsFrame(
                    1,
                    heldRow("t-team", waitsOn = "team", since = 120),
                    heldRow("t-person", waitsOn = "person", since = 110),
                    questionRow("q-1", since = 100),
                    policyRow("p-1", since = 90),
                ),
            )
            settle { model.needsYou.items.size == 4 }

            val shown = model.attentionItems()
            assertEquals(listOf("t-person", "q-1", "p-1"), shown.map { it.id })

            val unfiltered = attentionItems(
                model.needsYou.items.values,
                model.approvals.values,
                model.needsYou.indexedHosts.keys,
                model.needsYou.staleHosts.keys,
                model.needsYou.coveredPolicies,
            )
            assertEquals(shown, withoutTeamWaits(unfiltered))
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun counts_drop_by_one_for_a_team_waited_held_task_and_agree_with_the_list() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost(host))
            model.receive(
                host,
                openItemsFrame(
                    1,
                    heldRow("t-team", waitsOn = "team", since = 120),
                    heldRow("t-person", waitsOn = "person", since = 110),
                    questionRow("q-1", since = 100),
                    policyRow("p-1", since = 90),
                ),
            )
            settle { model.needsYou.items.size == 4 }

            val withTeam = model.attentionItems()
            val withTeamCount = attentionCounts(withTeam)["$host/$agent"] ?: 0
            assertEquals(3, withTeam.size)
            assertEquals(withTeam.size, withTeamCount)
            assertEquals(withTeam.size, model.attentionCount("$host/$agent"))

            model.receive(
                host,
                openItemsFrame(
                    2,
                    heldRow("t-team", waitsOn = "person", since = 120),
                    heldRow("t-person", waitsOn = "person", since = 110),
                    questionRow("q-1", since = 100),
                    policyRow("p-1", since = 90),
                ),
            )
            settle { model.attentionItems().size == 4 }

            val withPerson = model.attentionItems()
            val withPersonCount = attentionCounts(withPerson)["$host/$agent"] ?: 0
            assertEquals(withPerson.size - 1, withTeam.size)
            assertEquals(withPersonCount - 1, withTeamCount)
            assertEquals(withPerson.size, withPersonCount)
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun frame_without_waits_on_keeps_and_counts_all_four_rows() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost(host))
            model.receive(
                host,
                openItemsFrame(
                    1,
                    heldRow("t-1", waitsOn = null, since = 120),
                    heldRow("t-2", waitsOn = null, since = 110),
                    questionRow("q-1", since = 100),
                    policyRow("p-1", since = 90),
                ),
            )
            settle { model.needsYou.items.size == 4 }

            val shown = model.attentionItems()
            assertEquals(listOf("t-1", "t-2", "q-1", "p-1"), shown.map { it.id })
            assertEquals(4, shown.size)
            assertEquals(4, attentionCounts(shown)["$host/$agent"])
            assertEquals(4, model.attentionCount("$host/$agent"))
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun hidden_team_waited_card_stays_in_conversation_and_draws_as_held() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost(host))
            val home = Conversation(host, agent, conversation, home = true)
            model.conversations.add(home)
            val card = HeldTask(
                host = host,
                agent = agent,
                conversation = conversation,
                task = "t-team",
                title = "Wait on reviewer pass",
                assignee = "reviewer",
                reason = "Scheduled team review is pending.",
                since = 1_763_164_800,
                resume = "Resume when ready.",
                stateWire = "held",
                revision = 1,
                resumable = false,
            )

            model.receive(
                host,
                openItemsFrame(
                    1,
                    heldRow("t-team", waitsOn = "team", since = 120),
                    heldRow("t-person", waitsOn = "person", since = 110),
                    questionRow("q-1", since = 100),
                    policyRow("p-1", since = 90),
                ),
            )
            model.receive(host, message("held_task", "held_task" to card.json()))
            settle { model.needsYou.items.size == 4 && card.key in model.heldTasks.cards }

            assertTrue(model.attentionItems().none { it.id == "t-team" })
            assertEquals(card, model.heldTasks.cards[card.key])

            compose.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HeldTaskCard(model.heldTasks, model.heldTasks.cards[card.key] ?: card)
                }
            }
            compose.onNodeWithText("Held · waiting for you").assertExists()
        } finally {
            model.endFixture()
        }
    }
}
