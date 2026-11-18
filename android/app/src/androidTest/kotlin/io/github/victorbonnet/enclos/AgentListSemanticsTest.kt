package io.github.victorbonnet.enclos

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentListSemanticsTest {
    private fun roots(view: View): List<RootForTest> = (if (view is RootForTest) listOf(view) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun text(node: SemanticsNode) = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
    private fun layout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    @Test fun attention_and_usage_do_not_squeeze_the_agent_name_or_activity() {
        val runner = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(false, true)) for (font in listOf(1f, 2f)) {
            val activity = runner.startActivitySync(Intent().setClassName(runner.targetContext,
                "io.github.victorbonnet.enclos.AgentListProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("width", 360).putExtra("dark", dark).putExtra("font", font)) as AgentListProbeActivity
            try {
                runner.waitForIdleSync()
                val image = runner.uiAutomation.takeScreenshot()
                try {
                    val file = java.io.File(runner.targetContext.getExternalFilesDir(null), "agent-row-$dark-$font.png")
                    file.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
                runner.runOnMainSync {
                    val root = roots(activity.window.decorView).single()
                    root.measureAndLayoutForTest()
                    val all = nodes(root.semanticsOwner.unmergedRootSemanticsNode)
                    val name = all.first { text(it) == "Director" }
                    val status = all.first { text(it).contains("active just now") }
                    val attention = all.first { text(it).contains("4 needs you") }
                    val usage = all.first { text(it) == "161.2M tokens · 24h" }
                    assertFalse("Director must remain readable at $font: ${layout(name).size}", layout(name).hasVisualOverflow)
                    assertTrue("Activity must not wrap one character per line", layout(status).lineCount <= 2)
                    assertFalse("Short activity and count must be readable", layout(status).hasVisualOverflow)
                    assertTrue("Needs you belongs below the name", attention.boundsInRoot.top >= name.boundsInRoot.bottom - 1)
                    assertTrue("Usage needs its own space below the name", usage.boundsInRoot.top >= name.boundsInRoot.bottom - 1)
                    val open = all.first { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains("Open Director") ||
                        it.config.contains(SemanticsActions.OnClick) && it.config[SemanticsActions.OnClick].label == "Open Director" }
                    assertTrue(open.config[SemanticsActions.OnClick].action!!.invoke())
                }
                assertTrue(activity.opened)
            } finally { runner.runOnMainSync { activity.finish() }; runner.waitForIdleSync() }
        }
    }
}
