package io.github.victorbonnet.enclos

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.victorbonnet.enclos.ui.EnclosTheme
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
 * Robolectric tests for [AppModel.raisingTasks] and Compose [InboxScreen]
 * rendering of raised agents and conversations (C2, C3, C13, C14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h1200dp")
class InboxRaiseScreenTest {
    @get:Rule val compose = createComposeRule()

    private val host = "enclos-org"
    private val pmAddress = "$host/org/pm"
    private val designerAddress = "$host/org/designer"
    private val engineerAddress = "$host/org/engineer"

    private fun activityMessage(agent: String, vararg tasks: String) = JSONObject()
        .put("agent", agent)
        .put("since", 1_000L)
        .put("doing", JSONArray(tasks.map { JSONObject().put("kind", "task").put("task", it) }))

    private fun task(
        id: String,
        assignee: String = "org/pm",
        title: String = "Check Lyon forecast",
        conversation: String? = "c-weather",
        state: TaskState = TaskState.Running,
        since: Long = System.currentTimeMillis() / 1000 - 12 * 60,
    ) = AgentTask(
        host = host,
        id = id,
        title = title,
        assignee = assignee,
        askedBy = null,
        conversation = conversation,
        state = state,
        since = since,
        summary = null,
    )

    private fun baseModel(now: Long): AppModel {
        val model = fixtureModel()
        FakeBridge().attach(model, host)
        model.paired.add(fixtureHost(host))
        model.agents.addAll(
            listOf(
                Agent(host, "org/pm", "hermes", listOf("tasks"), displayName = "PM"),
                Agent(host, "org/designer", "hermes", listOf("tasks"), displayName = "Designer"),
                Agent(host, "org/engineer", "hermes", listOf("tasks"), displayName = "Engineer"),
            ),
        )
        fun addConv(c: Conversation, time: Long, text: String, unreadCount: Int = 0) {
            model.conversations.add(c)
            val lines = if (unreadCount > 0) {
                (1..unreadCount).map { i -> Line(i.toLong(), "agent", text, "reply", time - (unreadCount - i)) }
            } else {
                listOf(Line(1L, "agent", text, "reply", time))
            }
            model.lines[c.id] = lines
            if (unreadCount == 0) model.markRead(c.id)
        }
        addConv(Conversation(host, "org/designer", "home.org.designer", title = "Home", home = true), now - 30 * 60, "Mocks ready.")
        addConv(Conversation(host, "org/pm", "home.org.pm", title = "Home", home = true), now - 2 * 3600, "PM home preview.")
        addConv(Conversation(host, "org/pm", "c-reply", title = "Reply to one message"), now - 45 * 60, "Waiting on reply.", unreadCount = 2)
        addConv(Conversation(host, "org/pm", "c-extra-1", title = "Extra 1"), now - 46 * 60, "Extra 1 line.")
        addConv(Conversation(host, "org/pm", "c-extra-2", title = "Extra 2"), now - 47 * 60, "Extra 2 line.")
        addConv(Conversation(host, "org/pm", "c-extra-3", title = "Extra 3"), now - 48 * 60, "Extra 3 line.")
        addConv(Conversation(host, "org/pm", "c-extra-4", title = "Extra 4"), now - 49 * 60, "Extra 4 line.")
        addConv(Conversation(host, "org/pm", "c-extra-5", title = "Extra 5"), now - 50 * 60, "Extra 5 line.")
        addConv(Conversation(host, "org/pm", "c-weather", title = "Weather in Lyon"), now - 26 * 3600, "Light rain tomorrow.", unreadCount = 0)
        addConv(Conversation(host, "org/engineer", "home.org.engineer", title = "Home", home = true), now - 3 * 3600, "Build green.")
        return model
    }

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun raisingTasks_filters_by_state_capability_and_cache() {
        val model = fixtureModel()
        try {
            model.agents.add(Agent(host, "org/pm", "hermes", listOf("tasks")))
            model.agents.add(Agent(host, "org/plain", "hermes", emptyList()))

            val queued = task("t-q", state = TaskState.Queued, since = 100)
            val running = task("t-r", state = TaskState.Running, since = 200)
            val blocked = task("t-b", state = TaskState.Blocked, since = 300)
            val done = task("t-d", state = TaskState.Done, since = 400)
            val failed = task("t-f", state = TaskState.Failed, since = 500)
            val cancelled = task("t-c", state = TaskState.Cancelled, since = 600)
            val plainRunning = task("t-p", assignee = "org/plain", state = TaskState.Running, since = 700)
            listOf(queued, running, blocked, done, failed, cancelled, plainRunning).forEach(model.tasks::received)

            // No tasks capability -> empty.
            model.activities.received(host, activityMessage("org/plain", "t-p"))
            assertEquals(emptyList<AgentTask>(), model.raisingTasks("$host/org/plain"))

            // Task not in cache -> empty.
            model.activities.received(host, activityMessage("org/pm", "t-missing"))
            assertEquals(emptyList<AgentTask>(), model.raisingTasks(pmAddress))

            // Mixed states: only queued and running raise; blocked/done/failed/cancelled do not.
            model.activities.received(host, activityMessage("org/pm", "t-b", "t-q", "t-d", "t-r", "t-f", "t-c", "t-missing"))
            assertEquals(listOf(queued, running), model.raisingTasks(pmAddress))
            // activeTask is unchanged (still returns the first cached task named by activity, even blocked).
            assertEquals(blocked, model.activeTask(pmAddress))
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun c2_c3_inbox_screen_renders_raised_agent_branch_conversation_and_folded_count() {
        val now = System.currentTimeMillis() / 1000
        val model = baseModel(now)
        try {
            compose.setContent {
                EnclosTheme(dark = false) {
                    InboxScreen(
                        model = model,
                        query = "",
                        unreadOnly = false,
                        archivedOnly = false,
                        open = {},
                        start = {},
                        openAgent = {},
                        openRoutines = {},
                        pair = {},
                        openApproval = {},
                    )
                }
            }
            compose.waitForIdle()

            // Before raise: Designer above PM above Engineer; folded line says "2 more conversations".
            assertTrue(topOf("Designer") < topOf("PM"))
            assertTrue(topOf("PM") < topOf("Engineer"))
            compose.onNodeWithText("2 more conversations").assertExists()

            // Start a running task in Weather in Lyon.
            val running = task("t1", title = "Check Lyon forecast", conversation = "c-weather", state = TaskState.Running, since = now - 12 * 60)
            model.tasks.received(running)
            model.activities.received(host, activityMessage("org/pm", "t1"))
            compose.waitForIdle()

            // C2: PM above Designer above Engineer; task branch sits between PM and Weather in Lyon;
            // Weather in Lyon sits above Reply to one message and keeps its own time and 0 unread.
            assertTrue(topOf("PM") < topOf("Designer"))
            assertTrue(topOf("Designer") < topOf("Engineer"))
            assertTrue(topOf("PM") < topOf("Check Lyon forecast"))
            assertTrue(topOf("Check Lyon forecast") < topOf("Weather in Lyon"))
            assertTrue(topOf("Weather in Lyon") < topOf("Reply to one message"))
            compose.onNodeWithText(Times.ago(now - 26 * 3600, now)).assertExists()
            // C3: folded line drops from 2 more conversations to 1 more conversation.
            compose.onNodeWithText("1 more conversation").assertExists()
            assertTrue(compose.onAllNodesWithText("2 more conversations").fetchSemanticsNodes().isEmpty())
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun c13_c14_needs_you_stays_first_and_groups_order_is_unchanged() {
        val now = System.currentTimeMillis() / 1000
        val model = baseModel(now)
        try {
            // Needs you item for Designer.
            val req = JSONObject()
                .put("id", "q-1")
                .put("kind", "question")
                .put("agent", "org/designer")
                .put("conversation", "home.org.designer")
                .put("summary", "Approve the palette?")
                .put("since", now - 60)
                .put("expired", false)
            model.needsYou.received(
                host,
                JSONObject().put("revision", 1L).put("reset", true).put("complete", true).put("items", JSONArray(listOf(req))),
            )

            // Two groups: Newer group (5 min) and Team standup (1 h).
            val newerGroup = Conversation(host, "org", "g-newer", title = "Newer group", members = listOf("org/pm", "org/designer"))
            val standup = Conversation(host, "org", "g-standup", title = "Team standup", members = listOf("org/pm", "org/engineer"))
            model.conversations.addAll(listOf(newerGroup, standup))
            model.lines[newerGroup.id] = listOf(Line(1L, "agent", "Latest update.", "reply", now - 5 * 60))
            model.lines[standup.id] = listOf(Line(1L, "agent", "Standup notes.", "reply", now - 3600))
            model.markRead(newerGroup.id)
            model.markRead(standup.id)

            // PM runs a task asked in the group Team standup.
            val groupTask = task("t-g", title = "Draft agenda", conversation = standup.id, state = TaskState.Running, since = now - 60)
            model.tasks.received(groupTask)
            model.activities.received(host, activityMessage("org/pm", "t-g"))

            compose.setContent {
                EnclosTheme(dark = false) {
                    InboxScreen(
                        model = model,
                        query = "",
                        unreadOnly = false,
                        archivedOnly = false,
                        open = {},
                        start = {},
                        openAgent = {},
                        openRoutines = {},
                        pair = {},
                        openApproval = {},
                    )
                }
            }
            compose.waitForIdle()

            // C14: Needs you above Groups above Agents.
            assertTrue(topOf("Needs you") < topOf("Groups"))
            assertTrue(topOf("Groups") < topOf("Agents"))
            assertTrue(topOf("enclos-org · Approve the palette?") < topOf("Groups"))
            // C13: Groups order is unchanged (Newer group above Team standup), while PM rises above Designer in Agents.
            assertTrue(topOf("Newer group") < topOf("Team standup"))
            val agentsTop = topOf("Agents")
            val designerInAgents = compose.onAllNodesWithText("Designer").fetchSemanticsNodes()
                .single { it.boundsInRoot.top > agentsTop }.boundsInRoot.top
            assertTrue(topOf("PM") < designerInAgents)
        } finally {
            model.endFixture()
        }
    }
}
