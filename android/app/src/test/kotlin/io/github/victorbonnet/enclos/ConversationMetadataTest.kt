package io.github.victorbonnet.enclos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMetadataTest {
    private val original = Conversation("mac", "lab/main", "c-1", title = "Weather")

    @Test
    fun submitting_an_older_rename_dialog_preserves_a_newer_archive_and_revision() {
        val dialog = original
        val newer = original.copy(archived = true, revision = 7)
        val conversations = mutableListOf(newer)
        renameConversationLocally(conversations, dialog.id, "New name")
        assertEquals(newer.copy(title = "New name", titledBy = "person"), conversations.single())
        renameConversationLocally(conversations, dialog.id, "")
        assertEquals(newer.copy(title = null, titledBy = null), conversations.single())
    }

    @Test
    fun another_hosts_conversation_with_the_same_id_never_replaces_this_hosts() {
        // Two hosts with a sandbox `lab` and an agent `main` both have `home.lab.main`.
        val mac = Conversation("mac", "lab/main", "home.lab.main", title = "Home", home = true, revision = 1)
        val pi = mac.copy(host = "pi", archived = true, revision = 9)
        assertFalse(acceptsConversationMetadata(mac, pi))
        assertTrue(heldByAnotherHost(listOf(mac), "pi", "home.lab.main"))
        assertFalse(heldByAnotherHost(listOf(mac), "mac", "home.lab.main"))
        assertFalse(heldByAnotherHost(listOf(mac), "pi", "home.lab.coder"))
    }

    @Test
    fun delayed_archive_and_old_sync_cannot_hide_a_restored_conversation() {
        val archived = original.copy(archived = true, revision = 1)
        val restored = archived.copy(archived = false, revision = 2)
        assertTrue(acceptsConversationMetadata(original, archived))
        assertTrue(acceptsConversationMetadata(archived, restored))
        assertFalse(acceptsConversationMetadata(restored, archived))
        assertFalse(acceptsConversationMetadata(restored, original))
    }

    @Test
    fun delayed_rename_read_or_sync_metadata_cannot_replace_newer_archive_state() {
        val renamed = original.copy(title = "New name", revision = 3)
        val archived = renamed.copy(archived = true, revision = 4)
        assertFalse(acceptsConversationMetadata(archived, renamed))
        assertFalse(acceptsConversationMetadata(archived, original.copy(revision = 2)))
        assertTrue(acceptsConversationMetadata(archived, archived.copy(revision = 5)))
        assertTrue(acceptsConversationMetadata(archived, archived))
    }

    @Test
    fun legacy_zero_is_accepted_before_new_revisions_and_counters_are_host_scoped() {
        assertTrue(acceptsConversationMetadata(null, original))
        assertTrue(acceptsConversationMetadata(original, original.copy(title = "Legacy name")))
        // Another host's row with the same id is refused, not compared: see another_hosts_conversation_….
        assertFalse(acceptsConversationMetadata(original.copy(revision = 9), original.copy(host = "pi")))
        assertFalse(acceptsConversationMetadata(original, original.copy(revision = -1)))
    }

    @Test
    fun every_message_about_a_clashing_conversation_is_left_out_not_only_its_lines() {
        val mac = listOf(Conversation("mac", "lab/main", "home.lab.main", title = "Home", home = true, revision = 1))
        // Host pi's typing, tool steps, usage and group state would otherwise land on mac's Home.
        listOf("conversation", "message", "typing", "tool", "usage", "group.state").forEach {
            assertTrue(it, leftOutForClash(mac, "pi", it, "home.lab.main"))
        }
        assertFalse(leftOutForClash(mac, "mac", "typing", "home.lab.main"))
        assertFalse(leftOutForClash(mac, "pi", "typing", "home.lab.coder"))
        assertFalse(leftOutForClash(mac, "pi", "typing", null))
        // A policy request naming that conversation is about the sandbox: it still reaches the inbox.
        assertFalse(leftOutForClash(mac, "pi", "approval", "home.lab.main"))
    }

    @Test
    fun held_by_another_host_row_returns_the_holding_row_only_for_other_hosts() {
        val mac = Conversation("mac", "lab/main", "home.lab.main", title = "Home", home = true, revision = 1)
        assertEquals("mac", heldByAnotherHostRow(listOf(mac), "pi", "home.lab.main")?.host)
        assertNull(heldByAnotherHostRow(listOf(mac), "mac", "home.lab.main"))
        assertNull(heldByAnotherHostRow(listOf(mac), "pi", "home.lab.coder"))
    }

    @Test
    fun clash_notice_names_the_holding_host_and_agents() {
        assertEquals(
            "1 conversation of this host not shown: atelier also has comptoir/comptoir-dev, with the same sandbox and name.",
            clashNoticeText(1, listOf(ClashHolder("atelier", 1, listOf("comptoir/comptoir-dev")))),
        )
        assertEquals(
            "2 conversations of this host not shown: atelier also has comptoir/comptoir-dev, with the same sandbox and name.",
            clashNoticeText(2, listOf(ClashHolder("atelier", 2, listOf("comptoir/comptoir-dev")))),
        )
        assertEquals(
            "3 conversations of this host not shown: atelier also has comptoir/comptoir-dev and comptoir/comptoir-qa, with the same sandbox and name.",
            clashNoticeText(3, listOf(ClashHolder("atelier", 3, listOf("comptoir/comptoir-dev", "comptoir/comptoir-qa")))),
        )
        assertEquals(
            "3 conversations of this host not shown: atelier also has comptoir/a, comptoir/b and comptoir/c, with the same sandbox and name.",
            clashNoticeText(3, listOf(ClashHolder("atelier", 3, listOf("comptoir/a", "comptoir/b", "comptoir/c")))),
        )
    }

    @Test
    fun clash_notice_orders_hosts_by_count_then_name_and_caps_at_two() {
        assertEquals(
            "4 conversations of this host not shown: atelier also has comptoir/comptoir-dev, and boutique also has lab/runner, with the same sandbox and name.",
            clashNoticeText(
                4,
                listOf(
                    ClashHolder("boutique", 2, listOf("lab/runner")),
                    ClashHolder("atelier", 2, listOf("comptoir/comptoir-dev")),
                ),
            ),
        )
        assertEquals(
            "5 conversations of this host not shown: atelier also has comptoir/comptoir-dev, and boutique also has lab/runner, and 1 more host, with the same sandbox and name.",
            clashNoticeText(
                5,
                listOf(
                    ClashHolder("cellar", 1, listOf("db/backup")),
                    ClashHolder("boutique", 2, listOf("lab/runner")),
                    ClashHolder("atelier", 2, listOf("comptoir/comptoir-dev")),
                ),
            ),
        )
        assertEquals(
            "7 conversations of this host not shown: atelier also has comptoir/comptoir-dev, and boutique also has lab/runner, and 2 more hosts, with the same sandbox and name.",
            clashNoticeText(
                7,
                listOf(
                    ClashHolder("depot", 1, listOf("ops/deploy")),
                    ClashHolder("boutique", 2, listOf("lab/runner")),
                    ClashHolder("cellar", 1, listOf("db/backup")),
                    ClashHolder("atelier", 4, listOf("comptoir/comptoir-dev")),
                ),
            ),
        )
    }

    @Test
    fun clash_notice_and_home_note_fall_back_when_no_holder_can_be_named() {
        assertEquals(
            "2 conversations of this host not shown: another paired host has an agent with the same sandbox and name, whose conversations use the same ids.",
            clashNoticeText(2, emptyList()),
        )
        assertEquals(
            "2 conversations of this host not shown: another paired host has an agent with the same sandbox and name, whose conversations use the same ids.",
            clashNoticeText(2, listOf(ClashHolder("atelier", 2, emptyList()))),
        )
        assertEquals(
            "This agent's Home is not shown here: another paired host has an agent named comptoir/comptoir-dev too, and this phone keeps the first one's.",
            clashHomeNoteText("comptoir/comptoir-dev", emptyList()),
        )
        assertEquals(
            "This agent's Home is not shown here: another paired host has an agent named comptoir/comptoir-dev too, and this phone keeps the first one's.",
            clashHomeNoteText("comptoir/comptoir-dev", listOf(ClashHolder("atelier", 1, emptyList()))),
        )
    }

    @Test
    fun clash_home_note_names_the_holding_host_and_agent() {
        assertEquals(
            "This agent's Home is not shown here: atelier also has comptoir/comptoir-dev, with the same sandbox and name.",
            clashHomeNoteText("comptoir/comptoir-dev", listOf(ClashHolder("atelier", 1, listOf("comptoir/comptoir-dev")))),
        )
    }
}
