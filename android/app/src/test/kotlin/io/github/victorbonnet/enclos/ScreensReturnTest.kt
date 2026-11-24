package io.github.victorbonnet.enclos

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #66: the first screen keeps its place under a full screen. What the person
 * scrolled and chose — each list's first row and pixel offset, the settled
 * view, the search, the inbox's filters — is where Back returns, and opening
 * conversation after conversation leaves nothing per conversation behind.
 *
 * The real `Screens`, `HomeShell`, `InboxScreen` and `ConversationScreen`
 * with their route transition and predictive back; a synthetic socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class ScreensReturnTest {
    @get:Rule val compose = createComposeRule()

    private val host = "fixture-host"

    /** Enough agents that the lists are several viewports long. */
    private val count = 40

    private fun fixture(archived: Int = 0): AppModel {
        val model = fixtureModel()
        FakeBridge().attach(model, host)
        model.paired.add(fixtureHost(host))
        (1..count).forEach { i ->
            val agent = Agent(host, "box/agent-$i", "hermes", emptyList(), displayName = "Agent $i")
            model.agents.add(agent)
            model.conversations.add(
                Conversation(host, agent.name, "home.box.$i", home = true, archived = i <= archived),
            )
        }
        return model
    }

    private fun show(model: AppModel, dispatcher: Array<OnBackPressedDispatcher?>? = null) {
        compose.setContent {
            dispatcher?.set(0, LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher)
            EnclosTheme(dark = false) { Screens(model) { _, _ -> } }
        }
        compose.waitForIdle()
    }

    /**
     * The list the person sees. The pager keeps the page beside the settled one
     * composed, off the window, so only the page at the window's left edge counts.
     */
    private fun list() = compose.onNode(
        hasScrollAction() and hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher("the settled page") { it.boundsInRoot.left in -1f..1f },
    )

    /** The window the settled page fills, for telling its rows from the page beside it. */
    private fun page(): Rect = list().fetchSemanticsNode().boundsInRoot

    /** Every agent row on that page, by name, with its bounds in the window. */
    private fun rows(): Map<String, Rect> {
        val page = page()
        val seen = LinkedHashMap<String, Rect>()
        (1..count).forEach { i ->
            val node = compose.onAllNodesWithText("Agent $i").fetchSemanticsNodes()
                .firstOrNull { it.boundsInRoot.left >= page.left && it.boundsInRoot.left < page.right }
            if (node != null) seen["Agent $i"] = node.boundsInRoot
        }
        return seen
    }

    /**
     * Well past the first viewport, stopped between two rows, so that what is
     * kept has both a row and a pixel offset to keep. The pull is exact: the
     * rows it leaves on screen must have moved by exactly [pull] pixels.
     */
    private fun scrollTwoViewports(pull: Float = 137f): Map<String, Rect> {
        list().performScrollToIndex(18)
        compose.waitForIdle()
        val flush = rows()
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pull) }
        compose.waitForIdle()
        val pulled = rows()
        val top = page().top
        val inside = pulled.keys.intersect(flush.keys).filter {
            flush.getValue(it).top > top + 150f && pulled.getValue(it).top > top + 20f
        }
        val moved = inside.map { flush.getValue(it).top - pulled.getValue(it).top }.distinct()
        assertEquals("the rows did not move together: $moved", 1, moved.size)
        assertTrue("the pull did not move the rows: $moved", moved.first() > 20f)
        // A row cut off by the window's top edge is what a pixel offset looks like.
        val cut = pulled.values.minByOrNull { it.top }!!
        val whole = pulled.values.maxByOrNull { it.height }!!
        assertTrue("the list stands exactly on a row", cut.height < whole.height)
        return pulled
    }

    /** A row well inside the window, so that a click on it lands. */
    private fun anchor(rows: Map<String, Rect>): String =
        rows.entries.filter { it.value.top > 150f }.minByOrNull { it.value.top }?.key
            ?: throw AssertionError("no agent row well inside the window: $rows")

    private fun waitForFullScreen() = compose.waitUntil(10_000L) {
        compose.onAllNodesWithContentDescription("Agents").fetchSemanticsNodes().isEmpty()
    }

    private fun waitForFirstScreen() = compose.waitUntil(10_000L) {
        compose.onAllNodesWithContentDescription("Agents").fetchSemanticsNodes().isNotEmpty()
    }

    private fun open(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and !hasSetTextAction()).performClick()
        waitForFullScreen()
        compose.waitForIdle()
    }

    private fun back() {
        compose.onNodeWithContentDescription("Back").performClick()
        waitForFirstScreen()
        compose.waitForIdle()
    }

    private fun assertSamePlace(before: Map<String, Rect>, after: Map<String, Rect>, what: String) {
        assertEquals("$what: other rows are on screen", before.keys, after.keys)
        before.forEach { (label, rect) ->
            val now = after.getValue(label)
            assertEquals("$what: $label is not where it was", rect.top, now.top, 1f)
            assertEquals("$what: $label is not as much of itself as it was", rect.height, now.height, 1f)
        }
    }

    @Test
    fun back_returns_the_inbox_to_the_row_and_offset_it_was_at() {
        val model = fixture()
        try {
            show(model)
            val before = scrollTwoViewports()
            assertFalse("the inbox never left its first row", before.containsKey("Agent 1"))

            open(anchor(before))
            back()

            assertSamePlace(before, rows(), "the inbox")
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun the_two_lists_keep_their_own_place_across_a_tab_switch() {
        val model = fixture()
        try {
            show(model)
            val inbox = scrollTwoViewports()
            compose.onNodeWithContentDescription("Agents").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Conversations", substring = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Conversations", substring = true).assertIsSelected()
            assertSamePlace(inbox, rows(), "the inbox after a tab round trip")
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun back_returns_the_agents_view_and_the_inbox_keeps_its_own_place() {
        val model = fixture()
        try {
            show(model)
            val inbox = scrollTwoViewports()

            compose.onNodeWithContentDescription("Agents").performClick()
            compose.waitUntil(10_000L) {
                compose.onAllNodesWithText("Agent 1").fetchSemanticsNodes().any { it.boundsInRoot.left >= 0f }
            }
            compose.waitForIdle()
            val agents = scrollTwoViewports()
            assertFalse("the agents view is at its first row", agents.containsKey("Agent 1"))

            open(anchor(agents))
            back()

            compose.onNodeWithContentDescription("Agents").assertIsSelected()
            assertSamePlace(agents, rows(), "the agents view")

            compose.onNodeWithContentDescription("Conversations", substring = true).performClick()
            compose.waitForIdle()
            assertSamePlace(inbox, rows(), "the inbox after the agents round trip")
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun the_search_and_the_archived_choice_are_still_what_they_were() {
        val model = fixture(archived = 2)
        try {
            show(model)
            compose.onNodeWithContentDescription("Conversation filters").performClick()
            compose.onNodeWithText("Archived conversations").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Search archived conversations").performTextInput("Agent 1")
            compose.waitForIdle()

            open("Agent 1")
            back()

            // The archived choice is most of the placeholder: losing it would rename the field.
            val field = compose.onNodeWithContentDescription("Search archived conversations").fetchSemanticsNode()
            assertEquals("the search is gone", "Agent 1", field.config[SemanticsProperties.EditableText].text)
            assertTrue("the archived conversation is gone", compose.onAllNodesWithText("Agent 2").fetchSemanticsNodes().isEmpty())
            assertEquals("more than the searched-for row is showing", 1, rows().size)
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun the_reveal_shows_the_list_in_place_and_cancelling_keeps_the_conversation() {
        val model = fixture()
        val dispatcher = arrayOfNulls<OnBackPressedDispatcher>(1)
        try {
            show(model, dispatcher)
            val before = scrollTwoViewports()
            open(anchor(before))
            compose.onNode(hasSetTextAction()).performTextInput("half written")

            dispatcher[0]!!.dispatchOnBackStarted(BackEventCompat(progress = 0f, swipeEdge = BackEventCompat.EDGE_LEFT, touchX = 0f, touchY = 0f, frameTimeMillis = 0L))
            dispatcher[0]!!.dispatchOnBackProgressed(BackEventCompat(progress = 0.6f, swipeEdge = BackEventCompat.EDGE_LEFT, touchX = 240f, touchY = 400f, frameTimeMillis = 0L))
            compose.waitForIdle()
            assertSamePlace(before, rows(), "the reveal")

            dispatcher[0]!!.dispatchOnBackCancelled()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Back").assertExists()
            compose.onNodeWithText("half written").assertExists()

            dispatcher[0]!!.onBackPressed()
            waitForFirstScreen()
            compose.waitForIdle()
            assertSamePlace(before, rows(), "after the gesture completed")
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun opening_conversations_again_and_again_comes_back_to_the_same_place() {
        val model = fixture()
        try {
            show(model)
            val before = scrollTwoViewports()
            val anchors = before.entries.filter { it.value.top > 150f }.sortedBy { it.value.top }.map { it.key }.take(3)
            assertEquals("three rows to open in turn", 3, anchors.size)

            anchors.forEach { label ->
                open(label)
                back()
                assertSamePlace(before, rows(), "after opening $label")
            }
        } finally {
            model.endFixture()
        }
    }
}
