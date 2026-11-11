package io.github.victorbonnet.enclos

/**
 * Text an agent or a person wrote, checked before another person reads it.
 *
 * A control character, a bidirectional override or isolate, or a
 * zero-width character changes how the text around it reads without
 * showing itself: "‮niam" reads as "main", and a command can hide its
 * tail. Names, labels and file names refuse or drop them; a command, which
 * must be shown exactly as it runs, shows them instead ([revealed]). The
 * same rule as enclos-protocol's `visible` module.
 */
object Visible {
    /** Whether [c] (a code point) changes how the text around it is shown without being seen. */
    fun isHidden(c: Int): Boolean =
        Character.isISOControl(c) ||
            c == 0x00AD || c == 0x061C || c == 0x180E || c == 0xFEFF ||
            c in 0x200B..0x200F || c in 0x202A..0x202E || c in 0x2060..0x2064 || c in 0x2066..0x2069

    /** Whether [text] holds a hidden character, newlines aside when [lines] is true. */
    fun hasHidden(text: String, lines: Boolean = false): Boolean =
        text.codePoints().anyMatch { isHidden(it) && !(lines && it == '\n'.code) }

    /** [text] without its hidden characters (newlines kept when [lines] is true). */
    fun stripped(text: String, lines: Boolean = false): String = buildString {
        text.codePoints().forEach { if (!isHidden(it) || (lines && it == '\n'.code)) appendCodePoint(it) }
    }

    /** [text] with each hidden character but a newline written as ⟨U+XXXX⟩ and each tab as ⟨tab⟩: what is shown is what runs. */
    fun revealed(text: String): String = buildString {
        text.codePoints().forEach {
            when {
                it == '\n'.code -> append('\n')
                it == '\t'.code -> append("⟨tab⟩")
                isHidden(it) -> append("⟨U+%04X⟩".format(it))
                else -> appendCodePoint(it)
            }
        }
    }
}
