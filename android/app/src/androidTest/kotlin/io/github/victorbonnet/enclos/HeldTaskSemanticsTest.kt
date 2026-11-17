package io.github.victorbonnet.enclos

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose semantics, using existing Compose APIs rather than new test dependencies. */
@RunWith(AndroidJUnit4::class)
class HeldTaskSemanticsTest {
    private fun roots(view: View): List<RootForTest> =
        (if (view is RootForTest) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    @Test fun false_has_plain_instruction_and_no_resume_semantics_with_true_controls_in_both_themes() {
        val runner = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(false, true)) for (resumable in listOf(false, true)) for (linked in listOf(false, true)) {
            val card = JSONObject("""{"agent":"lab/main","conversation":"home","task":"probe",
                "title":"Report","reason":"Waiting for a decision","since":1,"revision":1,
                "state":"held","resume":"Decide on the agent's task board."}""").put("resumable", resumable)
            val activity = runner.startActivitySync(Intent().setClassName(runner.targetContext,
                "io.github.victorbonnet.enclos.HeldTaskProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("card", card.toString()).putExtra("dark", dark).putExtra("linked", linked))
            try {
                runner.waitForIdleSync()
                runner.runOnMainSync {
                    val root = roots(activity.window.decorView).single()
                    root.measureAndLayoutForTest()
                    val all = nodes(root.semanticsOwner.unmergedRootSemanticsNode)
                    val texts = all.flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
                    assertTrue(texts.contains("Held · waiting for you"))
                    assertEquals(resumable, texts.contains("Resume task"))
                    assertEquals(resumable, texts.any { it.startsWith("Resume:") })
                    if (!resumable) {
                        assertTrue(texts.contains("Held task"))
                        assertTrue(texts.contains("Decide on the agent's task board."))
                        assertFalse(all.any { it.config.contains(SemanticsActions.OnClick) })
                    }
                }
                val image = runner.uiAutomation.takeScreenshot()
                assertNotNull(image)
                try {
                    val file = java.io.File(runner.targetContext.getExternalFilesDir(null),
                        "held-$dark-$resumable-$linked.png")
                    file.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            } finally { runner.runOnMainSync { activity.finish() }; runner.waitForIdleSync() }
        }
    }
}
