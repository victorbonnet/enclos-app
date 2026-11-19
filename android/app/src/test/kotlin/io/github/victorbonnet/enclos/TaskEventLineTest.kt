package io.github.victorbonnet.enclos

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.victorbonnet.enclos.ui.TaskEventLine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** enclos's task event fixture, as the conversation receives it. */
    private val received = lineIn(JSONObject(javaClass.getResource("/protocol/to_device_message_task_event.json")!!.readText()))

    private val kept = AgentTask("mac", "t_a09759c7", "S2a", "lab/reviewer", "lab/main", "home.lab.main", TaskState.Queued, 1000, null)

    @Test
    fun the_line_says_the_task_who_and_what_happened_and_open_task_opens_it() {
        var opened = 0
        compose.setContent {
            TaskEventLine(taskEventSubject(event), taskEventWhat(event, "Reviewer"), "14:02") { opened++ }
        }
        // One node for the line, read as one, then Open task.
        compose.onNodeWithText("t_a09759c7 S2a", substring = true).assertExists()
        compose.onNodeWithText("Reviewer · sent to triage · 14:02", substring = true).assertExists()
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
        compose.onNodeWithText("sent to triage · 14:02", substring = true).assertExists()
        compose.onNodeWithText("Open task").assertDoesNotExist()
        assertEquals(0, compose.onAllNodes(clickable).fetchSemanticsNodes().size)
    }

    @Test
    fun a_conversations_task_line_names_its_assignee_and_opens_the_task_the_phone_keeps() {
        val event = received.taskEvent!!
        var named: String? = null
        var opened: TaskKey? = null
        compose.setContent {
            TaskLineItem("mac", received, event, { named = it; "Reviewer" }, { if (it == kept.key) kept else null }, { opened = it }) { Text("the files") }
        }
        // The assignee by its shown name, asked with its host.
        assertEquals("mac/lab/reviewer", named)
        compose.onNodeWithText("Reviewer · sent to triage · ${Times.clock(received.time)}", substring = true).assertExists()
        compose.onNodeWithText("the files").assertDoesNotExist()
        compose.onNodeWithText("Open task").performClick()
        assertEquals(kept.key, opened)
    }

    @Test
    fun a_conversations_task_line_shows_its_files_and_without_the_task_kept_no_open_task() {
        val line = received.copy(attachments = listOf(Attachment("f-1", "text/plain", "notes.txt", 3)))
        var opened: TaskKey? = null
        compose.setContent {
            TaskLineItem("mac", line, line.taskEvent!!, { "Reviewer" }, { null }, { opened = it }) { Text("the files") }
        }
        compose.onNodeWithText("the files").assertExists()
        compose.onNodeWithText("Open task").assertDoesNotExist()
        assertNull(opened)
    }
}
