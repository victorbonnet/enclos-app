package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A held card's one-line texts (the ask's decision and choices, the answer), read without a
 * regex: Android's engine refuses `(?U)`, which the JVM these tests run on accepts, so every card
 * with an ask or an answer was dropped on phones while these tests passed.
 */
class HeldOneLineTest {
    @Test fun each_run_of_white_space_is_one_space() {
        assertEquals("Allow one more hour?", heldOneLine("  Allow\tone\n\nmore  hour?　 "))
        assertEquals("a b", heldOneLine("a  \u0085b"))
        assertEquals("", heldOneLine(" \t\n "))
    }

    @Test fun white_space_is_unicodes_as_rust_reads_it() {
        val rust = listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A) +
            listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        for (c in 0..0x3000) assertEquals("U+%04X".format(c), c in rust, isUnicodeWhiteSpace(c))
        // Zero-width space is not white space: a hidden character, dropped as one.
        assertFalse(isUnicodeWhiteSpace(0x200B))
        assertEquals("ab", heldOneLine("a​b"))
    }

    @Test fun a_card_with_an_ask_and_an_answer_is_read() {
        val json = org.json.JSONObject(
            """{"agent":"lab/runtimes","conversation":"home.lab.runtimes","task":"t_1","title":"Fix backups",
               "reason":"Waiting.","since":1,"resume":"","state":"answered","revision":9,"resumable":false,
               "ask":{"decision":"Allow one\nmore hour?","blocked":"Reviews hold.","choices":["Allow  60 min","Keep paused"],"recommended":0},
               "answer":"Asked the agent to look into it."}""",
        )
        val card = HeldTask.from("mac", json)
        assertEquals("Allow one more hour?", card.ask?.decision)
        assertEquals(listOf("Allow 60 min", "Keep paused"), card.ask?.choices)
        assertEquals("Asked the agent to look into it.", card.answer)
    }

    @Test fun no_app_source_uses_the_unicode_class_flag_android_refuses() {
        // Inline in any flag group (`(?U)`, `(?iU)`, …) or as the Pattern constant.
        val refused = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]|UNICODE_CHARACTER_CLASS""")
        val sources = File("src").walkTopDown()
            .filter { it.extension in setOf("kt", "java") && "test" !in it.path.split(File.separatorChar) && "androidTest" !in it.path.split(File.separatorChar) }
            .toList()
        assertTrue("sources found", sources.any { it.path.endsWith("HeldTasks.kt") })
        for (file in sources) assertFalse(file.path, refused.containsMatchIn(file.readText()))
    }
}
