package io.github.victorbonnet.enclos

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A card with an agent's ask and the person's answer, read on Android's own regex and text
 * classes: the JVM unit tests accepted a pattern (`(?U)`) that Android refuses, and every such
 * card was dropped on phones.
 */
@RunWith(AndroidJUnit4::class)
class HeldTaskAskParseTest {
    @Test fun a_card_with_an_ask_and_an_answer_is_read_on_android() {
        val json = JSONObject()
            .put("agent", "lab/runtimes").put("conversation", "home.lab.runtimes").put("task", "t_1")
            .put("title", "Fix backups").put("reason", "Waiting.").put("since", 1).put("resume", "")
            .put("state", "answered").put("revision", 9).put("resumable", false)
            .put("ask", JSONObject().put("decision", "Allow one\nmore hour?").put("blocked", "Reviews hold.")
                .put("choices", org.json.JSONArray(listOf("Allow  60 min", "Keep paused"))).put("recommended", 0))
            .put("answer", "Asked the agent\tto look into it.")
        val card = HeldTask.from("mac", json)
        assertEquals("Allow one more hour?", card.ask?.decision)
        assertEquals(listOf("Allow 60 min", "Keep paused"), card.ask?.choices)
        assertEquals(0, card.ask?.recommended)
        assertEquals("Asked the agent to look into it.", card.answer)
    }
}
