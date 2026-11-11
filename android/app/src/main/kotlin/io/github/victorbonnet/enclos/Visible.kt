package io.github.victorbonnet.enclos

/**
 * Text an agent or a person wrote, checked before another person reads it.
 *
 * A control character, a bidirectional override or isolate, a zero-width
 * or other default-ignorable character, a line separator or a blank filler
 * changes how the text around it reads without showing itself:
 * "\u202Eniam" reads as "main", and a command can hide its tail. Names,
 * labels and file names refuse or drop them; a command, which must be
 * shown exactly as it runs, shows them instead ([revealed]).
 *
 * The joiners U+200C and U+200D and the emoji variation selectors U+FE0E
 * and U+FE0F are part of ordinary text (Persian spelling, joined emoji), so
 * they count as hidden only where they cannot be: next to ASCII, at an end,
 * or beside another hidden character. The same rule as enclos-protocol's
 * `visible` module and the Hermes plugin's `visible` module.
 */
object Visible {
    /** Whether [c] is one of the joiners ordinary text uses between two non-ASCII characters. */
    fun isJoiner(c: Int): Boolean = c == 0x200C || c == 0x200D || c == 0xFE0E || c == 0xFE0F

    /**
     * Whether [c] (a code point) changes how the text around it is shown without being seen,
     * wherever it stands. A joiner counts too: a caller that looks at one character at a time
     * cannot tell where it stands; [hasHidden], [stripped] and [revealed] look at its neighbours.
     */
    fun isHidden(c: Int): Boolean =
        Character.isISOControl(c) || isJoiner(c) ||
            c == 0x00AD || c == 0x034F || c == 0x061C || c == 0x115F || c == 0x1160 ||
            c == 0x17B4 || c == 0x17B5 || c in 0x180B..0x180F ||
            c == 0x200B || c == 0x200E || c == 0x200F || c in 0x2028..0x202E || c in 0x2060..0x206F ||
            c == 0x2800 || c == 0x3164 || c in 0xFE00..0xFE0D || c == 0xFEFF || c == 0xFFA0 ||
            c in 0xFFF0..0xFFFB || c in 0x1BCA0..0x1BCA3 || c in 0x1D173..0x1D17A || c in 0xE0000..0xE0FFF

    /** For each code point of [text], whether it is hidden where it stands. */
    private fun marks(text: String): List<Pair<Int, Boolean>> {
        val points = text.codePoints().toArray()
        fun plain(i: Int) = i in points.indices && points[i] >= 0x80 && !isHidden(points[i])
        return points.mapIndexed { i, c ->
            val hidden = when {
                c == 0xFE0E || c == 0xFE0F -> !plain(i - 1)
                isJoiner(c) -> !(plain(i - 1) && plain(i + 1))
                else -> isHidden(c)
            }
            c to hidden
        }
    }

    /** Whether [text] holds a character hidden where it stands, newlines aside when [lines] is true. */
    fun hasHidden(text: String, lines: Boolean = false): Boolean =
        marks(text).any { (c, hidden) -> hidden && !(lines && c == '\n'.code) }

    /** [text] without the characters hidden where they stand (newlines kept when [lines] is true). */
    fun stripped(text: String, lines: Boolean = false): String = buildString {
        marks(text).forEach { (c, hidden) -> if (!hidden || (lines && c == '\n'.code)) appendCodePoint(c) }
    }

    /** [text] with each hidden character but a newline written as ⟨U+XXXX⟩ and each tab as ⟨tab⟩: what is shown is what runs. */
    fun revealed(text: String): String = buildString {
        marks(text).forEach { (c, hidden) ->
            when {
                c == '\n'.code -> append('\n')
                c == '\t'.code -> append("⟨tab⟩")
                hidden -> append("⟨U+%04X⟩".format(c))
                else -> appendCodePoint(c)
            }
        }
    }

    /** [text] without joiners, for telling whether two names read the same. */
    fun bare(text: String): String = buildString {
        text.codePoints().forEach { if (!isJoiner(it)) appendCodePoint(it) }
    }
}
