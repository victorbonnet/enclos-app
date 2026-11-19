package io.github.victorbonnet.enclos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileComposerControlsTest {
    @get:Rule val compose = createComposeRule()
    private var online by mutableStateOf(true)
    private var preparing by mutableStateOf(false)
    private var waiting by mutableStateOf(false)
    private var progress by mutableStateOf<Int?>(null)
    private var names by mutableStateOf(listOf("photo.jpg", "notes.pdf"))
    private val actions = mutableListOf<String>()

    private fun show(large: Boolean = false) {
        compose.setContent {
            EnclosTheme(dark = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                    Box(Modifier.width(320.dp)) {
                        FileComposerControls(names, online = online, preparing = preparing,
                            waiting = waiting, progress = progress,
                            onCamera = { actions += "Camera" }, onPhotos = { actions += "Photos" },
                            onFiles = { actions += "Files" },
                            onRemove = { index -> names = names.filterIndexed { i, _ -> i != index } },
                        ) { attach, enabled ->
                            Composer("", {}, "Message", canSend = enabled, hasFiles = names.isNotEmpty(),
                                attachment = attach, onSend = { actions += "Send" })
                        }
                    }
                }
            }
        }
    }

    @Test fun production_menu_dispatches_each_action_and_removal_updates_send() {
        show()
        for (label in listOf("Camera", "Photos", "Files")) {
            compose.onNodeWithContentDescription("Attach files").performClick()
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        assertEquals(listOf("Camera", "Photos", "Files"), actions)
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals("Send", actions.last())
        compose.onNodeWithText("photo.jpg · Remove").performScrollTo().performClick()
        compose.onNodeWithText("photo.jpg · Remove").assertDoesNotExist()
        compose.onNodeWithText("notes.pdf · Remove").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test fun offline_preparation_transfer_and_receipt_wait_disable_send() {
        show()
        compose.runOnIdle { online = false }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.runOnIdle { online = true; preparing = true }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Attach files").assertIsNotEnabled()
        compose.onNodeWithText("photo.jpg · Remove").assertIsNotEnabled()
        compose.runOnIdle { preparing = false; progress = 42 }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Sending files: 42%").assertExists()
        compose.runOnIdle { progress = null; waiting = true }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.runOnIdle { waiting = false }
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun large_text_menu_and_full_filename_wrap_without_overflow() {
        val name = "a-long-prepared-document-name-with-details-and-more-details.pdf"
        names = listOf(name)
        show(large = true)
        compose.onNodeWithContentDescription("Attach files").performClick()
        for (label in listOf("Camera", "Photos", "Files", "$name · Remove")) {
            val button = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("label=$label: expected one text layout", 1, layouts.size)
            val layout = layouts.single()
            val semantics = node.fetchSemanticsNode()
            val input = layout.layoutInput
            val diagnostic = buildString {
                appendLine("label=$label size=${layout.size} lineCount=${layout.lineCount} " +
                    "hasVisualOverflow=${layout.hasVisualOverflow} " +
                    "didOverflowWidth=${layout.didOverflowWidth} didOverflowHeight=${layout.didOverflowHeight}")
                appendLine("constraints=${input.constraints} text=${input.text.text} " +
                    "maxLines=${input.maxLines} softWrap=${input.softWrap} overflow=${input.overflow} " +
                    "layoutDirection=${input.layoutDirection}")
                appendLine("font/style=${input.style} density=${input.density.density} " +
                    "fontScale=${input.density.fontScale}")
                appendLine("paragraphWidth=${layout.multiParagraph.width} " +
                    "paragraphHeight=${layout.multiParagraph.height} " +
                    "didExceedMaxLines=${layout.multiParagraph.didExceedMaxLines}")
                val buttonSemantics = button.fetchSemanticsNode()
                appendLine("buttonBoundsInRoot=${buttonSemantics.boundsInRoot} " +
                    "buttonSize=${buttonSemantics.size}")
                appendLine("textBoundsInRoot=${semantics.boundsInRoot} " +
                    "nodeBoundsInWindow=${semantics.boundsInWindow} nodeSize=${semantics.size}")
                for (line in 0 until layout.lineCount) {
                    appendLine("line=$line start=${layout.getLineStart(line)} " +
                        "end=${layout.getLineEnd(line)} visibleEnd=${layout.getLineEnd(line, visibleEnd = true)} " +
                        "top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} " +
                        "left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} " +
                        "ellipsis=${layout.isLineEllipsized(line)}")
                }
            }
            // Hosted Camera: result 95x32, paragraph 268x32, complete line 0..6 within
            // [0, 94.47266] x [0, 32]. didOverflowWidth compares paragraph width to result
            // width, so that flag alone rejects fitting text. Check actual lines instead.
            assertEquals("$diagnostic: layout must contain the complete label", label, input.text.text)
            assertTrue("$diagnostic: label must have lines", layout.lineCount > 0)
            assertFalse("$diagnostic: maxLines must not truncate content", layout.multiParagraph.didExceedMaxLines)
            assertEquals("$diagnostic: label must start at zero", 0, layout.getLineStart(0))
            assertEquals("$diagnostic: full label must be laid out", label.length,
                layout.getLineEnd(layout.lineCount - 1))
            assertEquals("$diagnostic: final content must be visible", label.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            for (line in 0 until layout.lineCount) {
                assertFalse("$diagnostic: line $line must not be ellipsized", layout.isLineEllipsized(line))
                assertTrue("$diagnostic: line $line must fit horizontally",
                    layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width)
                assertTrue("$diagnostic: line $line must fit vertically",
                    layout.getLineTop(line) >= 0f && layout.getLineBottom(line) <= layout.size.height)
                if (line > 0) {
                    assertEquals("$diagnostic: lines must cover consecutive content",
                        layout.getLineEnd(line - 1), layout.getLineStart(line))
                }
            }
            assertEquals("$diagnostic: text node must have the layout width", layout.size.width, semantics.size.width)
            assertEquals("$diagnostic: text node must have the layout height", layout.size.height, semantics.size.height)
            assertTrue("$diagnostic: text must not be clipped by its ancestors horizontally",
                semantics.boundsInRoot.width >= layout.size.width)
            assertTrue("$diagnostic: text must not be clipped by its ancestors vertically",
                semantics.boundsInRoot.height >= layout.size.height)
            if (label.contains(name)) {
                assertTrue("$diagnostic: full filename must wrap", layout.lineCount > 1)
            }
        }
    }
}
