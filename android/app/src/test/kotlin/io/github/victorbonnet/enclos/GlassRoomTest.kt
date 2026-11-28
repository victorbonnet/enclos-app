package io.github.victorbonnet.enclos

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The room `GlassScaffold` gives a screen's content (`docs/spec.md`, §4 "The bars"), on real screens:
 * what is at rest clears the bars, and a scrolling list's viewport runs under them, so what scrolls passes
 * beneath the glass instead of being cut off at its edge.
 *
 * - Settings: its top bar holds two rows, Back then the large title. They must stack (not overlap), and the
 *   first section must start below both.
 * - A new group's picker, populated and scrolled: its scroll viewport is the whole screen, rows pass under the
 *   top bar once scrolled, and at the end the last row rests above the composer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class GlassRoomTest {
    @get:Rule val compose = createComposeRule()

    private val host = "fixture-host"

    private fun fixture(agents: Int = 0): AppModel {
        val model = fixtureModel()
        FakeBridge().attach(model, host)
        model.paired.add(fixtureHost(host))
        (1..agents).forEach { i -> model.agents.add(Agent(host, "box/agent-$i", "hermes", emptyList(), displayName = "Agent $i")) }
        return model
    }

    private fun Rect.show() = "[top=%.1f bottom=%.1f]".format(top, bottom)

    /** The one vertical scroll container on screen: the tallest, so a text field's own scroll never counts. */
    private fun scroller(): SemanticsNode = compose.onAllNodes(
        hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    ).fetchSemanticsNodes().maxByOrNull { it.boundsInRoot.height } ?: throw AssertionError("no vertical scroll container")

    private fun rootBounds(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    @Test
    fun settings_back_and_title_stack_and_the_content_clears_them() {
        val model = fixture()
        try {
            compose.setContent {
                EnclosTheme(dark = false) { SettingsScreen(model, back = {}, pair = {}, ask = { _, _ -> }, openHost = {}, core = { "test" }) }
            }
            compose.waitForIdle()
            val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithText("HOSTS").fetchSemanticsNode().boundsInRoot
            assertTrue("the title ${title.show()} overlaps Back ${back.show()}: they must stack", title.top >= back.bottom - 0.5f)
            assertTrue("the first section ${first.show()} starts under the title ${title.show()}", first.top >= title.bottom - 0.5f)
            // What scrolls passes under the bar: the scroll viewport starts at the window's top, not under the bar.
            val viewport = scroller().boundsInRoot
            val root = rootBounds()
            assertTrue("Settings' viewport ${viewport.show()} does not run under the bars of ${root.show()}",
                viewport.top <= root.top + 0.5f && viewport.bottom >= root.bottom - 0.5f)
        } finally {
            model.endFixture()
        }
    }

    @Test
    fun a_populated_group_picker_scrolls_under_the_bars_and_rests_clear_of_them() {
        val count = 30
        val model = fixture(agents = count)
        try {
            compose.setContent { EnclosTheme(dark = false) { NewChatScreen(model, back = {}, opened = {}) } }
            compose.waitForIdle()
            compose.onNode(hasClickAction() and hasText("Agent 1")).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("New group").performClick()
            compose.waitForIdle()

            val close = compose.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
            val root = rootBounds()
            val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
            val (nameField, composerField) = fields.first() to fields.last()
            assertTrue("the group name field ${nameField.show()} rests under the top bar (Close ${close.show()})", nameField.top >= close.bottom - 0.5f)

            // The viewport is the whole screen: the room is padding inside the scroll, not around it.
            val viewport = scroller().boundsInRoot
            assertTrue("the group picker's viewport ${viewport.show()} stops short of the bars of ${root.show()}",
                viewport.top <= root.top + 0.5f && viewport.bottom >= root.bottom - 0.5f)

            // Scrolled to its end: rows pass under the top bar, and the last one rests above the composer.
            compose.onNode(SemanticsMatcher("the group picker") { it.id == scroller().id })
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            val rows = (1..count).mapNotNull { i ->
                compose.onAllNodes(hasText("Agent $i") and hasClickAction()).fetchSemanticsNodes()
                    .map { it.boundsInRoot }.firstOrNull { it.height > 0f }?.let { "Agent $i" to it }
            }.toMap()
            val under = rows.filterValues { it.top < close.bottom && it.bottom > root.top }
            assertTrue("no row passes under the top bar once scrolled (Close ${close.show()}); rows: " +
                rows.entries.take(4).joinToString { "${it.key} ${it.value.show()}" }, under.isNotEmpty())
            val last = rows["Agent $count"] ?: throw AssertionError("the last agent's row is not on screen at the end")
            assertTrue("the last row ${last.show()} rests under the composer ${composerField.show()}", last.bottom <= composerField.top + 0.5f)
        } finally {
            model.endFixture()
        }
    }
}
