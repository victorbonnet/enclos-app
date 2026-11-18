package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskBoardLinksTest {
    @Test
    fun an_existing_saved_address_opens_even_when_the_older_bridge_is_disconnected() {
        val saved = LinkDestination("boards.example", "https://boards.example/kanban")
        assertEquals(TaskBoardRoute.Saved(saved), taskBoardRoute(connected = false, supportsBoard = false, saved = saved))
        assertEquals(TaskBoardRoute.Saved(saved), taskBoardRoute(connected = true, supportsBoard = false, saved = saved))
        assertEquals(TaskBoardRoute.Bridge, taskBoardRoute(connected = true, supportsBoard = true, saved = saved))
        assertEquals(TaskBoardRoute.UpdateHost, taskBoardRoute(connected = true, supportsBoard = false, saved = null))
        assertEquals(TaskBoardRoute.Bridge, taskBoardRoute(connected = false, supportsBoard = false, saved = null))
    }

    @Test
    fun saved_links_restore_and_forgetting_is_scoped_to_the_agent_or_host() {
        val stored = mutableMapOf<String, String>()
        fun open() = TaskBoardLinks { changes ->
            changes.forEach { (key, address) -> if (address == null) stored.remove(key) else stored[key] = address }
        }.apply { restore(stored) }

        val director = AgentKey("ubuntu", "org/director")
        val reviewer = AgentKey("ubuntu", "org/reviewer")
        val other = AgentKey("ubuntu-dev", director.name)
        val board = LinkDestination("boards.example", "https://boards.example/kanban")
        val links = open()
        links.set(director, board)
        links.set(reviewer, board)
        links.set(other, board)

        val restored = open()
        assertEquals(board, restored[director])
        restored.forget(director)
        assertNull(restored[director])
        assertNull(open()[director])
        assertEquals(board, open()[reviewer])
        assertEquals(board, open()[other])

        restored.forget("ubuntu")
        assertNull(open()[reviewer])
        assertEquals(board, open()[other])
    }

    @Test
    fun stored_links_are_validated_and_restore_the_ascii_destination() {
        val valid = AgentKey("ubuntu", "org/director")
        val invalid = AgentKey("ubuntu", "org/other")
        val links = TaskBoardLinks {}.apply {
            restore(mapOf(valid.address to "https://bücher.example/kanban", invalid.address to "http://board.example"))
        }
        assertEquals(LinkDestination("xn--bcher-kva.example", "https://xn--bcher-kva.example/kanban"), links[valid])
        assertNull(links[invalid])
    }
}
