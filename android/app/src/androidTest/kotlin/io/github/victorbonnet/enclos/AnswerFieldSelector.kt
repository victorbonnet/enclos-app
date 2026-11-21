package io.github.victorbonnet.enclos

/** Test-driver identity only: never infer a label from text or descendants beyond a direct child. */
internal object AnswerFieldSelector {
    data class Shape(
        val className: String?, val pkg: String?, val window: Int,
        val description: String?, val editable: Boolean,
        val children: List<Shape> = emptyList(),
    )

    fun matches(field: Shape, pkg: String, window: Int): Boolean =
        field.className == "android.widget.EditText" && field.pkg == pkg && field.window == window &&
            (field.description == "Your answer" || field.children.any {
                !it.editable && it.className != "android.widget.EditText" &&
                    it.pkg == pkg && it.window == window && it.description == "Your answer"
            })

    fun <T> unique(fields: List<T>): T? {
        if (fields.size > 1) throw AssertionError("C82 FAIL: ambiguous Your answer fields (${fields.size})")
        return fields.singleOrNull()
    }
}
