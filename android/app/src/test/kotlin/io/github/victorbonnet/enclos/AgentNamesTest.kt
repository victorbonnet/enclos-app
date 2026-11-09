package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class AgentNamesTest {
    private fun agent(host: String = "mac", name: String? = null, revision: Long = 0, allowed: Boolean = true) =
        Agent(host, "lab/comptoir-dev", "hermes", listOf("typing"), displayName = name, nameRevision = revision, canEditName = allowed, supportsNames = true)

    @Test fun unicode_names_are_trimmed_without_changing_canonical_identity() {
        val named = agent(name = normalizeAgentName("  Gégé Commit  "), revision = 1)
        assertEquals("Gégé Commit", named.label)
        assertEquals("comptoir-dev", named.agent)
        assertEquals("lab/comptoir-dev", named.name)
        assertEquals("mac/lab/comptoir-dev", named.address)
        assertEquals("😀".repeat(100), normalizeAgentName("😀".repeat(100)))
        assertNotNull(agentNameProblem("😀".repeat(101)))
    }

    @Test fun avatar_initials_keep_a_complete_unicode_character() {
        assertEquals("G", firstAgentCharacter("Gégé Commit"))
        assertEquals("😀", firstAgentCharacter("😀 Commit"))
        assertEquals("?", firstAgentCharacter(""))
    }

    @Test fun invalid_names_are_not_silently_cleared_or_normalized_into_valid_names() {
        for (value in listOf("", "  ", "\nGégé", "Gégé\tCommit", "Gégé\u2028Commit", "Gégé\u2029Commit", "\u0000Gégé", "\uD800")) {
            assertNotNull(value, agentNameProblem(value))
        }
    }

    @Test fun current_names_are_scoped_by_full_host_and_agent_identity() {
        val mac = agent(name = "Gégé Commit", revision = 3)
        val pi = agent(host = "pi", name = "Comptoir", revision = 1)
        assertEquals("Gégé Commit", resolveAgentLabel(listOf(mac, pi), mac.address))
        assertEquals("Comptoir", resolveAgentLabel(listOf(mac, pi), pi.address))
        assertEquals("comptoir-dev", resolveAgentLabel(listOf(mac, pi), "other/lab/comptoir-dev"))
    }

    @Test fun older_lists_keep_the_name_but_never_restore_authority_or_capability_flags() {
        val current = agent(name = "Gégé Commit", revision = 3)
        val oldHost = agent(revision = 0, allowed = false).copy(supportsNames = false)
        val merged = mergeAgentName(current, oldHost)
        assertEquals("Gégé Commit", merged.label)
        assertEquals(3L, merged.nameRevision)
        assertFalse(merged.canEditName)
        assertFalse(merged.supportsNames)
        assertEquals("comptoir-dev", mergeAgentName(current, agent(host = "pi")).label)
    }

    @Test fun clearing_is_a_newer_name_state_and_cannot_be_undone_by_a_delayed_broadcast() {
        val named = SharedAgentName("mac/lab/comptoir-dev", "Gégé Commit", 4)
        val cleared = named.copy(displayName = null, revision = 5)
        assertEquals(cleared, newestAgentName(named, cleared))
        assertEquals(cleared, newestAgentName(cleared, named))
        assertEquals("comptoir-dev", mergeAgentName(agent(name = named.displayName, revision = 4), agent(revision = 5)).label)
    }

    @Test fun only_the_exact_host_agent_and_request_acknowledge_a_save() {
        val request = AgentNameRequest("request", "mac/lab/comptoir-dev", true, "Gégé Commit")
        assertTrue(request.matches("mac", "request", "lab/comptoir-dev"))
        assertFalse(request.matches("pi", "request", "lab/comptoir-dev"))
        assertFalse(request.matches("mac", "", "lab/comptoir-dev"))
        assertFalse(request.matches("mac", "request", "lab/other"))
        assertFalse(request.matches("mac", "old-request", "lab/comptoir-dev"))
    }
}
