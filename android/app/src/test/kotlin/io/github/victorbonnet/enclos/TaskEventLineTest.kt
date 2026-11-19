package io.github.victorbonnet.enclos

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.victorbonnet.enclos.ui.TaskEventLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A task line (enclos ADR 0071), in Compose on Robolectric: one quiet line, and Open task only when the phone keeps the task. */
@RunWith(RobolectricTestRunner::class)
class TaskEventLineTest {
    @get:Rule
    val compose = createComposeRule()

    private val event = TaskEvent("t_a09759c7", "S2a", "lab/reviewer", TaskEventKind.Triage, TaskState.Queued)

    private val clickable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

    @Test
    fun the_line_says_the_task_who_and_what_happened_and_open_task_opens_it() {
        var opened = 0
        compose.setContent {
            TaskEventLine(taskEventSubject(event), taskEventWhat(event, "Reviewer"), "14:02") { opened++ }
        }
        // One node for the line, read as one, then Open task.
        compose.onNodeWithText("t_a09759c7 S2a", substring = true).assertExists()
        compose.onNodeWithText("Reviewer · sent to triage → queued · 14:02", substring = true).assertExists()
        compose.onNodeWithText("Open task").assertHasClickAction().performClick()
        assertEquals(1, opened)
        // Open task is the line's only action.
        assertEquals(1, compose.onAllNodes(clickable).fetchSemanticsNodes().size)
    }

    @Test
    fun without_the_task_on_the_phone_the_line_offers_nothing() {
        compose.setContent {
            TaskEventLine(taskEventSubject(event), taskEventWhat(event, null), "14:02", open = null)
        }
        compose.onNodeWithText("sent to triage → queued · 14:02", substring = true).assertExists()
        compose.onNodeWithText("Open task").assertDoesNotExist()
        assertEquals(0, compose.onAllNodes(clickable).fetchSemanticsNodes().size)
    }
}
