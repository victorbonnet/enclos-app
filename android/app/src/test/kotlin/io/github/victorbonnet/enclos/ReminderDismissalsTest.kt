package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReminderDismissalsTest {
    private class Harness {
        val index = NeedsYou()
        val item = OpenItem("host", "app/t1", "held_task", "lab/lead", "home", "Decision", 1, false, "held:2")
        val sent = mutableListOf<Pair<OpenItem, String>>()
        val waits = mutableListOf<() -> Unit>()
        var online = true
        val client = ReminderDismissals(index, { online }, { true }, { item, id -> sent += item to id; true }, { _, action -> waits += action })
        init { index.received("host", JSONObject().put("revision", 1).put("reset", true).put("complete", true).put("items", JSONArray().put(item.json()))) }
        fun receipt(version: String = item.version) = message("open_item.dismissed", "of" to sent.single().second, "kind" to item.kind, "item" to item.id, "agent" to item.agent, "version" to version)
    }
    @Test fun dismissal_waits_for_an_exact_receipt_and_never_changes_the_native_request() {
        val h = Harness()
        assertTrue(h.client.dismiss(h.item))
        assertFalse(h.client.dismiss(h.item))
        assertEquals(1, h.index.items.size)
        assertFalse(h.client.received("other", h.receipt()))
        h.client.received("host", h.receipt("held:1"))
        assertEquals(1, h.index.items.size)
        h.client.received("host", h.receipt())
        assertTrue(h.index.items.isEmpty())
        assertTrue(h.client.pending.isEmpty())
        assertTrue("host" in h.index.indexedHosts)
        assertEquals(h.item, h.sent.single().first)
    }
    @Test fun offline_stale_and_unconfirmed_dismissals_never_queue_or_resend() {
        val h = Harness()
        h.online = false
        assertFalse(h.client.dismiss(h.item))
        h.online = true
        assertFalse(h.client.dismiss(h.item.copy(version = "old")))
        assertTrue(h.sent.isEmpty())
        h.client.dismiss(h.item)
        h.waits.single()()
        assertEquals(1, h.index.items.size)
        assertEquals(1, h.sent.size)
        assertNotNull(h.client.errors[h.item.key])
        h.index.authenticated("host")
        assertFalse(h.client.dismiss(h.item))
    }
    @Test fun an_older_receipt_cannot_remove_a_new_generation() {
        val h = Harness()
        h.client.dismiss(h.item)
        h.index.items[h.item.key] = h.item.copy(version = "held:3")
        h.client.received("host", h.receipt())
        assertEquals("held:3", h.index.items[h.item.key]?.version)
    }
    @Test fun blank_errors_disconnects_and_snapshots_settle_requests_without_hiding_failures() {
        val h = Harness()
        h.client.dismiss(h.item)
        assertTrue(h.client.received("host", message("error", "of" to h.sent.last().second, "message" to "")))
        assertEquals("Could not dismiss this reminder. Try again.", h.client.errors[h.item.key])
        assertTrue(h.index.items.containsKey(h.item.key))
        h.client.dismiss(h.item)
        h.client.ended("host")
        assertTrue(h.client.pending.isEmpty())
        assertTrue(h.client.errors[h.item.key]!!.contains("Connection ended"))
        h.client.dismiss(h.item)
        h.index.received("host", JSONObject().put("revision", 2).put("reset", true).put("complete", true).put("items", JSONArray()))
        h.client.refreshed("host")
        assertTrue(h.client.pending.isEmpty())
        assertTrue(h.client.errors.isEmpty())
        h.waits.forEach { it() }
        assertTrue(h.client.errors.isEmpty())
    }

    @Test fun dismissal_receipt_persists_the_changed_index_and_unsupported_hosts_never_send() {
        val saved = mutableListOf<List<OpenItem>>()
        val index = NeedsYou { _, rows -> saved += rows }
        val item = Harness().item
        index.received("host", JSONObject().put("revision", 1).put("reset", true).put("complete", true).put("items", JSONArray().put(item.json())))
        val unsupported = ReminderDismissals(index, { true }, { false }, { _, _ -> error("No send") }, { _, _ -> })
        assertFalse(unsupported.dismiss(item))
        index.dismissed(item)
        assertTrue(saved.last().isEmpty())
        val restored = NeedsYou()
        restored.restore(saved.last(), setOf("host"))
        assertTrue(restored.items.isEmpty())
        assertTrue("host" in restored.indexedHosts)
    }

}
