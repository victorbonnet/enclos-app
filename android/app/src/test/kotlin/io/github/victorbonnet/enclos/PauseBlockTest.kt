package io.github.victorbonnet.enclos

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Renders [SandboxPauseBlock] in Compose on Robolectric, including large text and disabled in-flight state. */
@RunWith(RobolectricTestRunner::class)
class PauseBlockTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pause_block_renders_large_text_accessible_nodes_and_invokes_action() {
        var clicked: PauseAction? = null
        val state = SandboxPauseBlockState(
            heading = "PAUSE CODER-LAB",
            headingDescription = "Pause coder-lab",
            stateLine = "Running · nothing of coder-lab is paused.",
            button = PauseBlockButton("Pause every agent in coder-lab", enabled = true, action = PauseAction.Pause),
            notice = "Resumed · coder-lab runs again, 1 sandbox stays paused.",
        )
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.3f)) {
                EnclosTheme {
                    SandboxPauseBlock(state = state, onAction = { clicked = it })
                }
            }
        }
        compose.onNodeWithContentDescription("Pause coder-lab")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Running · nothing of coder-lab is paused.").assertIsDisplayed()
        compose.onNodeWithText("Pause every agent in coder-lab")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals(PauseAction.Pause, clicked)
        compose.onNodeWithText("Resumed · coder-lab runs again, 1 sandbox stays paused.").assertIsDisplayed()
    }

    @Test
    fun pause_block_shows_disabled_pausing_button_while_in_flight() {
        var clicks = 0
        val state = SandboxPauseBlockState(
            heading = "PAUSE CODER-LAB",
            headingDescription = "Pause coder-lab",
            stateLine = "Running · nothing of coder-lab is paused.",
            button = PauseBlockButton("Pausing…", enabled = false, action = PauseAction.Pause),
            notice = null,
        )
        compose.setContent {
            EnclosTheme {
                SandboxPauseBlock(state = state, onAction = { clicks++ })
            }
        }
        compose.onNodeWithText("Pausing…")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(48.dp)
        assertEquals(0, clicks)
    }
}
