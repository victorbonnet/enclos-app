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

    /** Android idle does not include the next Compose frame after a semantic click. */
    private fun read(activity: NeedsYouProbeActivity, check: (SemanticsNode) -> Unit) {
        val runner = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.uptimeMillis() + 3_000
        while (true) {
            runner.waitForIdleSync()
            try {
                runner.runOnMainSync {
                    val root = roots(activity.window.decorView).single()
                    root.measureAndLayoutForTest(); check(root.semanticsOwner.unmergedRootSemanticsNode)
                }
                return
            } catch (failure: Throwable) {
                if (failure !is AssertionError && failure !is NoSuchElementException || android.os.SystemClock.uptimeMillis() >= deadline) throw failure
                Thread.sleep(20)
            }
        }
    }

    @Test fun requests_precede_conversations_and_expired_questions_are_read_only_in_both_themes_and_large_text() {
        val runner = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(false, true)) for (font in listOf(1f, 2f)) for (expired in listOf(false, true)) {
            val activity = runner.startActivitySync(Intent().setClassName(runner.targetContext,
                "io.github.victorbonnet.enclos.NeedsYouProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("dark", dark).putExtra("font", font).putExtra("expired", expired)) as NeedsYouProbeActivity
            fun click(label: String, last: Boolean = false) = read(activity) { root ->
                val targets = nodes(root).filter { it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) && label in texts(it) }
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
                read(activity) { root ->
                    val all = texts(root)
                    assertTrue(all.indexOf("Needs you (3)") < all.indexOf("Conversations"))
                    assertTrue(all.containsAll(listOf("Director" + if (expired) " · Expired" else "", "Reviewer", "Engineer")))
                }
                capture("inbox")
                click("Director" + if (expired) " · Expired" else "")
                if (expired) {
                    read(activity) { root ->
                        val all = texts(root)
                        assertTrue(all.contains("Expired · this request can no longer be answered."))
                        assertFalse(all.any { it in listOf("Send answer", "Write reply", "Other", "Choose one", "Skip question") })
                        assertFalse(nodes(root).any { it.config.contains(SemanticsActions.SetText) })
                        // Historical options are plain text, with no action bound to them.
                        assertTrue(all.contains("1. Fix Inbox filters"))
                        assertFalse(nodes(root).any { "1. Fix Inbox filters" in texts(it) && it.config.contains(SemanticsActions.OnClick) })
                    }
                    assertNull(activity.submitted)
                } else {
                    click("Fix Inbox filters")
                    click("Send answer")
                    runner.waitForIdleSync()
                    assertEquals(QuestionAnswer.Choices(listOf(0)), activity.submitted)
                }
                capture("choice")
                // The independent ordinary-message buttons send exactly their own label.
                click("Keep paused", last = true)
                assertEquals("Keep paused", activity.reply)
            } finally { runner.runOnMainSync { activity.finish() }; runner.waitForIdleSync() }
        }
    }
    @Test fun expired_runtime_approvals_have_no_response_buttons() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val activity = runner.startActivitySync(Intent().setClassName(runner.targetContext,
            "io.github.victorbonnet.enclos.NeedsYouProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("expired", true).putExtra("runtime_approval", true)) as NeedsYouProbeActivity
        try {
            read(activity) { root ->
                val row = nodes(root).first { it.config.contains(SemanticsActions.OnClick) && "Director · Expired" in texts(it) }
                assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
            }
            read(activity) { root ->
                val all = texts(root)
                assertTrue(all.toString(), all.contains("Expired"))
                assertFalse(all.any { label -> RuntimeApprovalAction.entries.any { it.label == label } })
                assertFalse(nodes(root).any { it.config.contains(SemanticsActions.SetText) })
            }
            assertNull(activity.submitted)
        } finally { runner.runOnMainSync { activity.finish() }; runner.waitForIdleSync() }
    }

}
