package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandsTest {
    private val new = AgentCommand("new", "Start a new session", "[name]")
    private val model = AgentCommand("model", "Switch model", "[model]")
    private val reloadMcp = AgentCommand("reload_mcp", "Reload MCP servers", null)
    private val status = AgentCommand("status", "Show session info", null)
    private val all = listOf(new, model, reloadMcp, status)

    @Test
    fun a_slash_alone_offers_every_command_in_order() {
        assertEquals(all, suggest(all, "/"))
    }

    @Test
    fun names_starting_with_what_is_typed_come_before_those_that_have_it_elsewhere() {
        assertEquals(listOf(model), suggest(all, "/mo"))
        assertEquals(listOf(model, reloadMcp), suggest(all, "/M"))
        assertEquals(listOf(status), suggest(all, "/tat"))
    }

    @Test
    fun nothing_once_the_name_is_followed_by_a_space_or_without_a_slash_first() {
        assertEquals(emptyList<AgentCommand>(), suggest(all, "/new "))
        assertEquals(emptyList<AgentCommand>(), suggest(all, "/model gpt"))
        assertEquals(emptyList<AgentCommand>(), suggest(all, "new"))
        assertEquals(emptyList<AgentCommand>(), suggest(all, " /new"))
        assertEquals(emptyList<AgentCommand>(), suggest(all, ""))
        assertEquals(emptyList<AgentCommand>(), suggest(all, "/zzz"))
    }

    @Test
    fun a_name_listed_twice_is_offered_once_the_first() {
        val again = AgentCommand("model", "Another model command", null)
        val twice = all + again
        assertEquals(all, suggest(twice, "/"))
        assertEquals(listOf(model, reloadMcp), suggest(twice, "/m"))
        assertEquals(listOf(model), suggest(twice, "/odel"))
    }

    @Test
    fun picking_one_puts_its_name_and_a_space_in_the_field() {
        assertEquals("/new ", picked(new))
    }
}
