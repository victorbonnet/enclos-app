package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationDeletionTest {
    private class Fixture {
        var online = true
        var sending = true
        val sent = mutableListOf<JSONObject>()
        val removed = mutableListOf<ConversationKey>()
        val timers = mutableListOf<() -> Unit>()
        val client = ConversationDeletion({ online }, { _, command -> sent += command; sending }, { removed += it }, { _, callback -> timers += callback })
        val chat = Conversation("mac", "lab/main", "c-1")
        val key = ConversationKey("mac", "c-1")
        fun receipt(type: String, host: String = "mac", of: String = sent.last().getString("id"), code: String = "internal") =
            client.received(host, JSONObject().put("type", type).put("of", of).put("code", code).put("message", "Refused"))
        fun deleted(host: String = "mac", id: String = "c-1") = client.received(host, JSONObject().put("type", "conversation.deleted").put("conversation", id))
    }

    @Test fun only_confirmed_deletion_removes_history_and_replayed_records_are_idempotent() {
        val f = Fixture()
        assertTrue(f.client.delete(f.chat))
        assertEquals("conversation.delete", f.sent.single().getString("type"))
        assertEquals("c-1", f.sent.single().getString("conversation"))
        assertFalse(f.client.delete(f.chat))
        assertTrue(f.removed.isEmpty())
        assertFalse(f.receipt("ack"))
        assertTrue(f.key in f.client.pending)
        f.deleted()
        assertEquals(listOf(f.key), f.removed)
        assertFalse(f.key in f.client.pending)
        assertTrue(f.key in f.client.deleted)
        f.deleted()
        f.timers.single()()
        assertEquals(1, f.removed.size)
        assertFalse(f.key in f.client.errors)
    }

    @Test fun errors_disconnects_and_timeouts_preserve_history_and_allow_explicit_retry() {
        val f = Fixture()
        f.client.delete(f.chat)
        assertFalse(f.receipt("error", host = "pi"))
        assertFalse(f.receipt("error", of = "another-request"))
        assertTrue(f.key in f.client.pending)
        assertTrue(f.receipt("error"))
        assertTrue(f.removed.isEmpty())
        assertEquals("Refused", f.client.errors[f.key])
        f.client.delete(f.chat)
        val current = f.client.pending[f.key]
        f.timers.first()()
        assertEquals(current, f.client.pending[f.key])
        f.client.ended("mac")
        assertFalse(f.key in f.client.pending)
        assertTrue(f.removed.isEmpty())
        f.client.delete(f.chat)
        f.timers.last()()
        assertFalse(f.key in f.client.pending)
        assertTrue(f.client.errors.getValue(f.key).contains("not confirmed"))
        f.deleted()
        assertEquals(listOf(f.key), f.removed)
    }

    @Test fun home_and_offline_actions_are_refused_and_old_bridges_are_explained() {
        val f = Fixture()
        assertFalse(f.client.delete(f.chat.copy(id = "home.lab.main", home = true)))
        f.online = false
        assertFalse(f.client.delete(f.chat))
        assertTrue(f.sent.isEmpty())
        f.online = true
        f.sending = false
        assertFalse(f.client.delete(f.chat))
        assertFalse(f.key in f.client.pending)
        f.sending = true
        f.client.delete(f.chat)
        assertTrue(f.receipt("error", code = "unknown_type"))
        assertTrue(f.client.errors.getValue(f.key).contains("Update"))
        assertTrue(f.removed.isEmpty())
    }

    @Test fun sync_deletes_archived_groups_and_host_scoping_preserves_other_hosts() {
        val f = Fixture()
        val group = f.chat.copy(members = listOf("lab/main", "lab/coder"), archived = true)
        assertTrue(f.client.delete(group))
        f.deleted("pi")
        assertTrue(f.key in f.client.pending)
        assertFalse(f.key in f.client.deleted)
        f.deleted()
        assertTrue(f.key in f.client.deleted)
        assertTrue(ConversationKey("pi", "c-1") in f.client.deleted)
        f.client.forget("pi")
        assertTrue(f.key in f.client.deleted)
        assertFalse(ConversationKey("pi", "c-1") in f.client.deleted)
        assertFalse(f.client.delete(group))
    }
}
