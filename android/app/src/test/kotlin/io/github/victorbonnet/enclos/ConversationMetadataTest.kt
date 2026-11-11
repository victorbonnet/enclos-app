package io.github.victorbonnet.enclos

import org.junit.Assert.assertFalse
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
}
