package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskBoardClientTest {
    private val card = HeldTask(
        host = "mac", agent = "lab/main", conversation = "home.lab.main", task = "board/t_1",
        title = "Review", reason = "Needs a decision", since = 1,
        resume = "Open the board", stateWire = "held", revision = 3, resumable = false,
    )
    private val bridge = "https://mac.example.ts.net:8443/"
    private val path = "/task-board/open#" + "a".repeat(43)

    private class Fixture {
        var current = true
        var sending = true
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val timers = mutableListOf<() -> Unit>()
        val opened = mutableListOf<String>()
        val client = TaskBoardClient(
            send = { host, request -> sent += host to request; sending },
            current = { current },
            later = { _, timeout -> timers += timeout },
        )
        val requestId: String get() = sent.last().second.getString("id")
    }

    private fun reply(of: String, path: Any) = message(HELD_TASK_BOARD_LINK, "of" to of, "path" to path)

    @Test fun tap_sends_exact_held_task_and_only_its_paired_host_can_open_the_board() {
        val f = Fixture()
        f.client.open(card, bridge, f.opened::add)
        f.client.open(card, bridge, f.opened::add)
        val (host, message) = f.sent.single()
        assertEquals("mac", host)
        assertEquals(HELD_TASK_BOARD, message.getString("type"))
        assertEquals(card.agent, message.getString("agent"))
        assertEquals(card.task, message.getString("task"))
        assertEquals(card.revision, message.getLong("revision"))
        assertTrue(f.client.isOpening(card.key))

        f.client.received("other", reply(f.requestId, path))
        f.client.received("mac", reply("unrelated", path))
        assertTrue(f.opened.isEmpty())
        assertTrue(f.client.isOpening(card.key))
        f.client.received("mac", reply(f.requestId, path))
        assertEquals(listOf("https://mac.example.ts.net:8443" + path), f.opened)
        assertFalse(f.client.isOpening(card.key))
        f.client.received("mac", reply(f.requestId, path))
        assertEquals(1, f.opened.size)
    }

    @Test fun rejects_arbitrary_destinations_and_invalid_ticket_formats() {
        for (bad in listOf(
            "https://elsewhere.example$path", "//elsewhere.example$path", "$path?redirect=elsewhere",
            "$path/", "$path\n", path.dropLast(1), path.replace("#", "/"), path.replace("#a", "#%"),
            12, JSONObject.NULL,
        )) {
            val f = Fixture()
            f.client.open(card, bridge, f.opened::add)
            assertTrue(f.client.received("mac", reply(f.requestId, bad)))
            assertTrue(f.opened.isEmpty())
            assertEquals("The host sent an invalid task board link.", f.client.error(card.key))
            assertFalse(f.client.isOpening(card.key))
        }
        for (bad in listOf("http://mac.example.ts.net", "https://user@mac.example.ts.net", "${bridge}other", "${bridge}?a=1", "${bridge}#hello")) {
            val f = Fixture()
            f.client.open(card, bad, f.opened::add)
            assertTrue(f.sent.isEmpty())
            assertNotNull(f.client.error(card.key))
        }
    }

    @Test fun leaving_or_updating_the_sheet_or_losing_connection_discards_its_reply() {
        for (end in listOf<(Fixture) -> Unit>(
            { it.client.cancel(card.key) },
            { it.current = false },
            { it.client.clear() },
            { it.client.ended(card.host) },
            { it.timers.single()() },
        )) {
            val f = Fixture()
            f.client.open(card, bridge, f.opened::add)
            end(f)
            f.client.received("mac", reply(f.requestId, path))
            assertFalse(f.client.isOpening(card.key))
            assertTrue(f.opened.isEmpty())
        }
    }

    @Test fun error_response_or_timeout_allow_a_new_tap_and_never_open_the_previous_reply() {
        val f = Fixture()
        f.client.open(card, bridge, f.opened::add)
        assertFalse(f.client.received(card.host, message("error", "of" to "other")))
        assertTrue(f.client.received(card.host, message("error", "of" to f.requestId, "code" to "not_allowed")))
        assertEquals("This device does not have access to the task board.", f.client.error(card.key))
        val first = f.requestId
        f.client.open(card, bridge, f.opened::add)
        assertNull(f.client.error(card.key))
        f.timers.first()()
        f.client.received(card.host, reply(first, path))
        assertTrue(f.opened.isEmpty())
        assertTrue(f.client.isOpening(card.key))
        f.timers.last()()
        assertFalse(f.client.isOpening(card.key))
        assertEquals("The host did not reply. Try again.", f.client.error(card.key))
    }

    @Test fun a_failed_send_explains_the_failure_and_does_not_wait_or_replay() {
        val f = Fixture()
        f.sending = false
        f.client.open(card, bridge, f.opened::add)
        assertNotNull(f.client.error(card.key))
        assertFalse(f.client.isOpening(card.key))
        assertTrue(f.timers.isEmpty())
        f.client.received(card.host, reply(f.requestId, path))
        assertTrue(f.opened.isEmpty())
    }

    @Test fun a_busy_bridge_says_to_try_again_shortly_and_allows_another_tap() {
        val f = Fixture()
        f.client.open(card, bridge, f.opened::add)
        assertTrue(f.client.received(card.host, message("error", "of" to f.requestId, "code" to "busy")))
        assertEquals("The host is busy opening task boards. Try again shortly.", f.client.error(card.key))
        assertFalse(f.client.isOpening(card.key))
        assertTrue(f.opened.isEmpty())
        f.client.open(card, bridge, f.opened::add)
        assertEquals(2, f.sent.size)
        assertTrue(f.client.isOpening(card.key))
        assertNull(f.client.error(card.key))
    }
}
