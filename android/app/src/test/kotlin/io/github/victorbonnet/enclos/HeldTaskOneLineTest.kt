package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A held card's one-line texts (an ask's decision and choices, and the answer an `answered`
 * card carries), read through [HeldTask.from] as enclos-protocol's `held_task::one_line` and
 * `cut` read them: each run of Unicode White_Space (Rust's `char::is_whitespace`) one space,
 * then the characters hidden where they stand dropped (`visible::stripped`), then trimmed and
 * cut to the field's bound in code points. `blocked` and a typed answer keep their own rules.
 */
class HeldTaskOneLineTest {
    /** Unicode's White_Space property, all of it: Rust's `char::is_whitespace`. */
    private val whiteSpace = listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680) +
        (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)

    /** Characters hidden wherever they stand (`visible::is_hidden`) that are not White_Space. */
    private val hidden = listOf(
        0x00, 0x07, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x7F, 0x9F, 0xAD, 0x034F, 0x061C, 0x115F, 0x1160,
        0x17B4, 0x17B5, 0x180B, 0x180E, 0x180F, 0x200B, 0x200E, 0x200F, 0x202A, 0x202E, 0x2060, 0x2066,
        0x206F, 0x2800, 0x3164, 0xFE00, 0xFE0D, 0xFEFF, 0xFFA0, 0xFFF9, 0xFFFB, 0x1BCA0, 0x1D173,
        0xE0041, 0xE0100, 0xE000, 0xF8FF, 0xFDD0, 0xFFFE, 0x1FFFE, 0xF0000, 0x10FFFD,
        // Joiners, hidden next to ASCII, at an end or beside white space.
        0x200C, 0x200D, 0xFE0E, 0xFE0F,
    )

    private fun cp(vararg points: Int) = buildString { points.forEach { appendCodePoint(it) } }
    private fun hex(c: Int) = "U+%04X".format(c)
    private fun label(text: String) = text.codePoints().toArray().joinToString(" ") { hex(it) }

    /** The enclos fixture's card, held at revision 1, with no answer. */
    private fun held(): JSONObject {
        val m = JSONObject(javaClass.getResource("/protocol/to_device_held_task_answered.json")!!.readText())
        return m.getJSONObject("held_task").put("state", "held").put("revision", 1).apply { remove("answer") }
    }

    private fun read(card: JSONObject) = HeldTask.from("mac", card)
    private fun ask(ask: JSONObject) = read(held().put("ask", ask)).ask

    private fun decision(text: String) = ask(JSONObject().put("decision", text))?.decision
    private fun choice(text: String) = ask(JSONObject().put("decision", "Go?").put("choices", JSONArray().put(text)))?.choices?.single()
    private fun answer(text: String) = read(held().put("answer", text)).answer
    private fun blocked(text: String) = ask(JSONObject().put("decision", "Go?").put("blocked", text))!!.blocked

    private class Field(val name: String, val max: Int, val read: (String) -> String?)
    private val oneLineFields = listOf(
        Field("decision", MAX_HELD_DECISION_CHARS) { decision(it) },
        Field("choice", MAX_HELD_CHOICE_CHARS) { choice(it) },
        Field("answer", MAX_HELD_ANSWER_CHARS) { answer(it) },
    )

    /** [text] read as every one-line field reads it; null where it reads as none (an empty text). */
    private fun assertOneLine(expected: String?, text: String) {
        for (field in oneLineFields) assertEquals("${field.name} of ${label(text)}", expected, field.read(text))
    }

    @Test fun each_unicode_white_space_character_separates_two_words() {
        assertEquals(25, whiteSpace.toSet().size)
        for (c in whiteSpace) {
            val ws = cp(c)
            assertOneLine("Keep paused", "Keep${ws}paused")
            assertOneLine("Keep paused", "${ws}Keep$ws$ws paused$ws")
            assertOneLine(null, ws)
        }
        val all = cp(*whiteSpace.toIntArray())
        assertOneLine("Keep paused", "Keep${all}paused")
        assertOneLine("Keep paused now", "${all}Keep${all}paused${all}now$all")
        assertOneLine(null, all)
        // Named, as the ones a narrower rule misses: next line, no-break, figure and narrow no-break spaces.
        assertOneLine("a b c d e", "a\u0085b c d e")
        // Line and paragraph separators are white space before they are hidden: they still separate.
        assertOneLine("a b c", "a b c")
    }

    @Test fun white_space_outside_unicodes_set_is_hidden_and_joins_rather_than_separates() {
        // U+001C to U+001F are white space to Java and Kotlin, but control characters to the bridge: dropped.
        for (c in 0x1C..0x1F) {
            assertOneLine("Keeppaused", "Keep${cp(c)}paused")
            assertOneLine("Keep  paused", "Keep ${cp(c)} paused")
            assertOneLine(null, cp(c))
        }
        // No longer white space since Unicode 6.3 (U+180E), never white space (U+200B, U+FEFF, U+2060).
        for (c in listOf(0x180E, 0x200B, 0xFEFF, 0x2060)) assertOneLine("Keeppaused", "Keep${cp(c)}paused")
        // A blank filler is not white space either: dropped, not a space.
        for (c in listOf(0x115F, 0x1160, 0x2800, 0x3164, 0xFFA0)) assertOneLine("Keeppaused", "Keep${cp(c)}paused")
    }

    @Test fun white_space_collapses_before_hidden_characters_are_dropped() {
        for (c in hidden) {
            val h = cp(c)
            assertOneLine("Keeppaused", "Keep${h}paused")
            assertOneLine("Keep paused", "Keep$h paused")
            assertOneLine("Keep paused", "Keep ${h}paused")
            // Two runs of white space a hidden character kept apart stay two spaces once it is dropped.
            assertOneLine("Keep  paused", "Keep $h paused")
            assertOneLine("Keep  paused", "Keep $h　paused")
            assertOneLine("Keep paused", "$h Keep paused $h")
            assertOneLine(null, h)
            assertOneLine(null, "$h $h $h")
        }
        // White space that is also hidden (next line, line and paragraph separators) is white space first.
        assertOneLine("Keep paused", "Keep ​paused")
        assertOneLine("Keep paused", "Keep​ paused")
        assertOneLine("Keep paused", "Keep \u0085 paused")
        assertOneLine("Keep paused", "Keep\u001F paused")
        assertOneLine("Keep  paused", "Keep \u001F paused")
    }

    @Test fun joiners_are_kept_between_non_ascii_characters_and_dropped_beside_white_space() {
        val worker = cp(0x1F469, 0x200D, 0x1F4BB)
        val persian = "می‌خواهم"
        val heart = "❤️"
        for (text in listOf(worker, persian, heart)) {
            assertOneLine(text, text)
            assertOneLine("$text ok", "$text　ok")
        }
        // White space first: a joiner then beside a space is hidden there, though not beside the
        // non-ASCII white space it replaced.
        assertOneLine(cp(0x1F469) + " " + cp(0x1F4BB), cp(0x1F469, 0x200D, 0xA0, 0x1F4BB))
        assertOneLine(cp(0x1F469) + " " + cp(0x1F4BB), cp(0x1F469, 0xA0, 0x200D, 0x1F4BB))
        assertOneLine("❤", "❤　️")
        assertOneLine("❤  ok", "❤　️ ok")
        // Next to ASCII, a joiner is hidden.
        assertOneLine("main", "ma‍in")
        assertOneLine("main", "main️")
    }

    @Test fun each_one_line_field_is_cut_to_its_bound_in_code_points_after_it_is_on_one_line() {
        val face = cp(0x1F600)
        for (field in oneLineFields) {
            val max = field.max
            fun assertCut(expected: String, text: String) = assertEquals("${field.name}, $max", expected, field.read(text))
            assertCut(face.repeat(max), face.repeat(max + 10))
            assertCut(face.repeat(max), face.repeat(max))
            assertCut(face.repeat(max - 1), face.repeat(max - 1))
            // Hidden characters do not count, and a run of white space counts as one.
            assertCut("x".repeat(max), "x​".repeat(max + 5))
            assertCut("x y", "x" + "　".repeat(max + 10) + "y")
            assertCut("xy", "x" + "​".repeat(max + 10) + "y")
            // An even bound: the cut ends on a space, trimmed.
            assertCut("x ".repeat(max / 2).trimEnd(), "x  ".repeat(max))
            assertCut("x".repeat(max - 1), "x".repeat(max - 1) + "  yyy")
            assertCut("x".repeat(max - 2) + " y", "x".repeat(max - 2) + " " + "y".repeat(10))
        }
    }

    @Test fun blocked_keeps_its_lines_and_white_space_drops_hidden_characters_and_is_cut_at_400() {
        assertEquals("Line one　　end\nline two  threex",
            blocked("Line one　　end\nline two  \u0085\u001F three\tx"))
        assertEquals("a\n\nb", blocked("  a\n\nb​  "))
        val face = cp(0x1F600)
        assertEquals(face.repeat(MAX_HELD_BLOCKED_CHARS), blocked(face.repeat(MAX_HELD_BLOCKED_CHARS + 10)))
        assertEquals("y".repeat(10) + "z", blocked("y".repeat(10) + "​".repeat(MAX_HELD_BLOCKED_CHARS + 10) + "z"))
        assertEquals("", blocked("​ "))
    }

    @Test fun choices_keep_their_indexes_and_recommendation_once_on_one_line() {
        val ask = ask(JSONObject().put("decision", "Allow one more hour?")
            .put("choices", JSONArray(listOf("Keep paused", "Allow one hour", "Stop​ now", "　Ask\u0085me　")))
            .put("recommended", 1))!!
        assertEquals("Allow one more hour?", ask.decision)
        assertEquals(listOf("Keep paused", "Allow one hour", "Stop now", "Ask me"), ask.choices)
        assertEquals(1, ask.recommended)
        assertEquals(listOf(1 to "Allow one hour", 0 to "Keep paused", 2 to "Stop now", 3 to "Ask me"), ask.shown())
        // A choice that reads as nothing on one line makes an ask the bridge would not send: none,
        // rather than a choice list whose indexes are not the bridge's.
        for (blank in listOf("", " ", " ", "\u0085", "​", "\u001F", "　​ ")) {
            assertNull(label(blank), ask(JSONObject().put("decision", "Go?")
                .put("choices", JSONArray(listOf("Keep paused", blank))).put("recommended", 0)))
        }
        // A blank decision likewise, whatever the choices.
        for (blank in listOf("", " 　", "​", "\u001C\u001D")) {
            assertNull(label(blank), ask(JSONObject().put("decision", blank).put("choices", JSONArray(listOf("a", "b")))))
        }
        // More than four choices, or a recommendation naming none, as before.
        assertNull(ask(JSONObject().put("decision", "Go?").put("choices", JSONArray(listOf("a ", "b", "c", "d", "e")))))
        assertNull(ask(JSONObject().put("decision", "Go?").put("choices", JSONArray(listOf("a", "b"))).put("recommended", 2))!!.recommended)
        assertNull(ask(JSONObject().put("decision", "Go?").put("choices", JSONArray(listOf("a", "b"))).put("recommended", 1L shl 40))!!.recommended)
    }

    @Test fun an_answered_card_shows_its_answer_on_one_line_and_a_wrong_type_still_refuses_the_card() {
        val card = read(held().put("state", "answered").put("revision", 2).put("answer", "Keep #346 paused​"))
        assertEquals("Keep #346 paused", card.answer)
        assertEquals("Answered: Keep #346 paused", heldTaskStatus(card))
        for (sentence in HELD_ANSWER_SENTENCES) assertEquals(sentence, read(held().put("answer", sentence)).answer)
        assertNull(read(held().put("answer", JSONObject.NULL)).answer)
        assertNull(read(held().put("answer", "　​")).answer)
        for (bad in listOf<Any>(3, JSONArray(), JSONObject())) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { read(held().put("answer", bad)) }
        }
    }

    @Test fun a_typed_answer_keeps_its_own_rule_and_is_not_put_on_one_line() {
        // Trimmed at its ends only; its white space inside is kept, not collapsed.
        assertEquals("Wait　for  Monday", heldAnswerText("  Wait　for  Monday  "))
        assertEquals("Two\nlines", heldAnswerText("Two\nlines"))
        // Hidden characters, white space that is hidden among them, refuse it.
        for (c in listOf(0x85, 0x2028, 0x2029, 0x09, 0x1F, 0x200B, 0x202E, 0xFEFF)) {
            assertNull(hex(c), heldAnswerText("Wait${cp(c)}Monday"))
        }
        assertNull(heldAnswerText(" 　 "))
        val face = cp(0x1F600)
        assertEquals(face.repeat(MAX_HELD_ANSWER_TEXT_CHARS), heldAnswerText(face.repeat(MAX_HELD_ANSWER_TEXT_CHARS)))
        assertNull(heldAnswerText(face.repeat(MAX_HELD_ANSWER_TEXT_CHARS + 1)))
    }
}
