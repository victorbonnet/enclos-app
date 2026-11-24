package io.github.victorbonnet.enclos

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No Activity, fixture, UI action or journey invocation; exercises the capture driver's selector. */
@RunWith(AndroidJUnit4::class)
class AnswerFieldSelectorTest {
    private val pkg = "io.github.victorbonnet.enclos"
    private val window = 29
    private val label = AnswerFieldSelector.Shape("android.view.View", pkg, window, "Your answer", false)
    private val field = AnswerFieldSelector.Shape("android.widget.EditText", pkg, window, null, true, listOf(label))
    private fun resolve(vararg nodes: AnswerFieldSelector.Shape) =
        AnswerFieldSelector.unique(nodes.filter { AnswerFieldSelector.matches(it, pkg, window) })

    @Test fun split_and_same_node_return_the_actual_field() {
        assertSame(field, resolve(label, field))
        val same = field.copy(description = "Your answer", children = emptyList())
        assertSame(same, resolve(same, label))
        // Disabled EditText can lose editable semantics, but remains the readback target.
        val disabled = field.copy(editable = false)
        assertSame(disabled, resolve(disabled, label))
    }

    @Test fun missing_wrong_package_window_and_decoys_do_not_resolve() {
        assertNull(resolve())
        assertNull(resolve(label))
        assertNull(resolve(field.copy(pkg = "$pkg.test")))
        assertNull(resolve(field.copy(window = window + 1)))
        assertNull(resolve(field.copy(children = listOf(label.copy(pkg = "$pkg.test")))))
        assertNull(resolve(field.copy(children = listOf(label.copy(window = window + 1)))))
        assertNull(resolve(field.copy(children = listOf(label.copy(editable = true)))))
        assertNull(resolve(field.copy(children = listOf(label.copy(className = "android.widget.EditText")))))
        assertNull(resolve(field.copy(children = listOf(label.copy(description = null, children = listOf(label))))))
        assertNull(resolve(field.copy(description = null, children = emptyList())))
        val decoy = field.copy(description = "Your answer", className = "android.view.View")
        assertNull(resolve(decoy))
        assertSame(field, resolve(decoy, field, field.copy(window = window + 1)))
    }

    @Test fun draft_placeholder_and_fuzzy_descriptions_are_not_labels() {
        // Text/draft/placeholder are deliberately absent from the resolver's input contract.
        for (description in listOf("Write your answer", "unsent draft", "Your answer ", "your answer", "prefix Your answer")) {
            assertNull(resolve(field.copy(description = description, children = emptyList())))
            assertNull(resolve(field.copy(children = listOf(label.copy(description = description)))))
        }
    }

    @Test fun ambiguous_actual_fields_fail_closed_even_if_one_loses_editability() {
        try {
            resolve(field, field.copy(editable = false, description = "Your answer"))
            fail("ambiguous fields resolved")
        } catch (expected: AssertionError) {
            assertTrue(expected.message.orEmpty().startsWith("C82 FAIL: ambiguous Your answer fields"))
        }
    }
}
