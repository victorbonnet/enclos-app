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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeedsYouSemanticsTest {
    private fun roots(view: View): List<RootForTest> = (if (view is RootForTest) listOf(view) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun texts(node: SemanticsNode): List<String> = nodes(node).flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }

    @Test fun requests_precede_conversations_and_choices_send_literal_replies_in_both_themes_and_large_text() {
        val runner = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(false, true)) for (font in listOf(1f, 2f)) for (expired in listOf(false, true)) {
            val activity = runner.startActivitySync(Intent().setClassName(runner.targetContext,
                "io.github.victorbonnet.enclos.NeedsYouProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("dark", dark).putExtra("font", font).putExtra("expired", expired)) as NeedsYouProbeActivity
            fun read(check: (SemanticsNode) -> Unit) {
                runner.waitForIdleSync()
                runner.runOnMainSync {
                    val root = roots(activity.window.decorView).single()
                    root.measureAndLayoutForTest(); check(root.semanticsOwner.unmergedRootSemanticsNode)
                }
            }
            fun click(label: String, last: Boolean = false) = read { root ->
                val targets = nodes(root).filter { it.config.contains(SemanticsActions.OnClick) && label in texts(it) }
                val target = if (last) targets.last() else targets.first()
                assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
            }
            fun capture(stage: String) {
                runner.waitForIdleSync()
                val image = runner.uiAutomation.takeScreenshot(); assertNotNull(image)
                try {
                    val out = java.io.File(runner.targetContext.getExternalFilesDir(null), "needs-you-$dark-$font-$expired-$stage.png")
                    out.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            }
            try {
                read { root ->
                    val all = texts(root)
                    assertTrue(all.indexOf("Needs you (3)") < all.indexOf("Conversations"))
                    assertTrue(all.containsAll(listOf("Director" + if (expired) " · Expired" else "", "Reviewer", "Engineer")))
                }
                capture("inbox")
                click("Director" + if (expired) " · Expired" else "")
                click("Fix Inbox filters")
                capture("choice")
                click(if (expired) "Open reply conversation" else "Submit")
                runner.waitForIdleSync()
                if (expired) {
                    assertNull(activity.submitted)
                    assertTrue(activity.drafted!!.endsWith("Answer: Fix Inbox filters"))
                } else assertEquals(QuestionAnswer.Choices(listOf(0)), activity.submitted)
                // The independent ordinary-message buttons send exactly their own label.
                click("Keep paused", last = true)
                assertEquals("Keep paused", activity.reply)
            } finally { runner.runOnMainSync { activity.finish() }; runner.waitForIdleSync() }
        }
    }
}
