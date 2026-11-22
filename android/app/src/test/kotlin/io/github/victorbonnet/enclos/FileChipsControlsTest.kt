package io.github.victorbonnet.enclos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The draft's files as chips, and a documents-only agent's sheet (JVM, Compose on Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileChipsControlsTest {
    @get:Rule val compose = createComposeRule()
    private var names by mutableStateOf(listOf("photo.jpg", "notes.pdf"))
    private var online by mutableStateOf(true)
    private val actions = mutableListOf<String>()

    private fun show(width: Int = 360, large: Boolean = false, images: Boolean = true) {
        compose.setContent {
            EnclosTheme(dark = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                    Box(Modifier.width(width.dp)) {
                        FileComposerControls(names, offersImages = images, online = online,
                            onCamera = { actions += "Camera" }, onPhotos = { actions += "Photos" }, onFiles = { actions += "Files" },
                            onRemove = { index -> actions += "Remove $index"; names = names.filterIndexed { i, _ -> i != index } },
                        ) { attach, enabled -> Composer("", {}, "Message", canSend = enabled, hasFiles = names.isNotEmpty(), attachment = attach, onSend = {}) }
                    }
                }
            }
        }
    }

    @Test fun each_file_is_a_chip_whose_name_does_nothing_and_whose_own_remove_button_is_48dp() {
        show()
        compose.onAllNodesWithTag(FILE_CHIP).assertCountEquals(2)
        for (name in names.toList()) {
            compose.onNodeWithText(name).assertHasNoClickAction()
            compose.onNodeWithContentDescription("Remove $name").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertHasClickAction()
        }
        compose.onNodeWithText("notes.pdf").performClick()
        assertTrue("tapping a name removes nothing", actions.isEmpty())
        // Chips sit side by side when they fit: not one full-width row each.
        val first = compose.onAllNodesWithTag(FILE_CHIP)[0].fetchSemanticsNode().boundsInRoot
        val second = compose.onAllNodesWithTag(FILE_CHIP)[1].fetchSemanticsNode().boundsInRoot
        assertEquals("side by side", first.top, second.top, 0.5f)
        assertTrue(first.width < compose.onRoot().fetchSemanticsNode().boundsInRoot.width)
        compose.onNodeWithContentDescription("Remove photo.jpg").performClick()
        assertEquals(listOf("Remove 0"), actions)
        compose.onAllNodesWithTag(FILE_CHIP).assertCountEquals(1)
    }

    @Test fun on_a_small_screen_at_large_text_offline_chips_wrap_in_full_and_stay_removable() {
        val long = "a-very-long-quarterly-report-name-with-every-detail-in-it.pdf"
        names = listOf(long, "b.txt")
        online = false
        show(width = 280, large = true)
        compose.onNodeWithContentDescription("Attach files").assertIsNotEnabled()
        compose.onNodeWithText(long).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove $long").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val chip = compose.onAllNodesWithTag(FILE_CHIP)[0].fetchSemanticsNode().boundsInRoot
        assertTrue("a chip never exceeds the screen", chip.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right + 0.5f)
        val text = compose.onNodeWithText(long).fetchSemanticsNode()
        assertEquals("the name is not cut", long, text.config[SemanticsProperties.Text].single().text)
        compose.onNodeWithContentDescription("Remove b.txt").performScrollTo().performClick()
        assertEquals(listOf("Remove 1"), actions)
    }

    @Test fun a_documents_only_agent_disables_camera_and_photos_with_why_and_keeps_files() {
        show(images = false)
        compose.onNodeWithContentDescription("Attach files").performClick()
        compose.onNodeWithText("Camera").assertIsNotEnabled()
        compose.onNodeWithText("Photos").assertIsNotEnabled()
        compose.onNodeWithText(DOCUMENTS_ONLY).assertExists()
        compose.onNodeWithText("Files").assertIsEnabled().performClick()
        assertEquals(listOf("Files"), actions)
    }

    @Test fun an_agent_taking_images_offers_all_three_without_the_explanation() {
        show(images = true)
        compose.onNodeWithContentDescription("Attach files").performClick()
        compose.onNodeWithText("Camera").assertIsEnabled()
        compose.onNodeWithText("Photos").assertIsEnabled()
        compose.onNodeWithText(DOCUMENTS_ONLY).assertDoesNotExist()
    }
}
