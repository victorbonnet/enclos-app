package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    @Test fun only_the_hosts_refusal_of_another_person_hides_the_policy_row() {
        assertTrue(policyIsOwnerOnly("Only the host's person can manage sandbox policy."))
        // Passing refusals keep the row: the owner retries after them.
        assertFalse(policyIsOwnerOnly("This device is no longer paired."))
        assertFalse(policyIsOwnerOnly("A policy change is pending. Apply or discard it on the host first."))
        assertFalse(policyIsOwnerOnly(""))
    }

    private val policyJson = """{"type":"sandbox.policy","sandbox":"lab","version":3,"base":"b1","editable":true,"reason":null,
        "fields":[{"key":"write","label":"Writable paths","multiple":true,"editable":true,"choices":[],
          "values":[{"value":"/Users/alice/work","source":"Accepted access","locked":false},
                    {"value":"/Users/alice/notes","source":"Sandbox","locked":false}]},
         {"key":"mode","label":"Network mode","multiple":false,"editable":true,"choices":["public","listed"],
          "values":[{"value":"listed","source":"Inherited","locked":true}]}],"of":""}"""

    private fun policy(json: String = policyJson) = SandboxPolicy.from("pi", JSONObject(json))

    @Test fun a_policy_is_read_with_its_fields_values_and_accepted_count() {
        val read = policy()
        assertEquals("pi/lab", read.address)
        assertEquals(3, read.version)
        assertEquals("b1", read.base)
        assertTrue(read.editable)
        assertNull(read.reason)
        assertEquals(listOf("write", "mode"), read.fields.map { it.key })
        assertEquals(listOf("public", "listed"), read.fields[1].choices)
        assertTrue(read.fields[1].values.single().locked)
        assertEquals(1, read.accepted)
    }

    @Test fun a_policy_with_no_reason_or_a_blank_one_has_none_and_a_reason_is_kept() {
        assertNull(policy(policyJson.replace("\"reason\":null,", "")).reason)
        assertNull(policy(policyJson.replace("\"reason\":null", "\"reason\":\"\"")).reason)
        assertEquals("Shared with you", policy(policyJson.replace("\"reason\":null", "\"reason\":\"Shared with you\"")).reason)
    }

    private val reviewJson = """{"type":"sandbox.policy.preview","sandbox":"lab","base":"b1","revision":"r2",
        "change":{"field":"write","before":null,"after":"/Users/alice/new"},"changes":["Add writable path /Users/alice/new"],"restart":true,"of":"x"}"""

    @Test fun a_review_reads_an_addition_with_no_before() {
        val review = PolicyReview.from(JSONObject(reviewJson))
        assertEquals("b1", review.base)
        assertEquals("r2", review.revision)
        assertEquals(PolicyEdit("write", null, "/Users/alice/new"), review.edit)
        assertEquals(listOf("Add writable path /Users/alice/new"), review.changes)
        assertTrue(review.restart)
    }

    @Test fun a_review_reads_a_removal_with_no_after_and_a_missing_before_as_none() {
        val removal = PolicyReview.from(JSONObject(reviewJson.replace("\"before\":null,\"after\":\"/Users/alice/new\"", "\"before\":\"/Users/alice/old\",\"after\":null")))
        assertEquals(PolicyEdit("write", "/Users/alice/old", null), removal.edit)
        val noBefore = PolicyReview.from(JSONObject(reviewJson.replace("\"before\":null,", "")))
        assertNull(noBefore.edit.before)
    }

    @Test fun an_edit_is_the_trimmed_value_and_nothing_when_it_changes_nothing() {
        assertEquals(PolicyEdit("write", "/Users/alice/a", "/Users/alice/b"), policyEdit("write", "/Users/alice/a", " /Users/alice/b "))
        // Only a space apart: the edit would send a `before` equal to its `after`.
        assertNull(policyEdit("write", "/Users/alice/a", "/Users/alice/a "))
        assertNull(policyEdit("write", null, "   "))
        assertEquals(PolicyEdit("mode", "listed", "public"), policyEdit("mode", "listed", "public"))
    }

    @Test fun a_typed_value_with_a_hidden_character_is_refused_before_any_preview() {
        assertNull(policyEdit("write", null, "/Users/alice/a\u202Etxt.exe"))
        assertNull(policyEdit("allow", null, "api.example.com\u200B"))
        assertNull(policyEdit("write", null, "/Users/alice/work\uDB40\uDC41"))
    }

    @Test fun values_show_their_hidden_characters_written_out() {
        assertEquals("/Users/alice/a\u27E8U+202E\u27E9txt", policyValueLabel("write", "/Users/alice/a\u202Etxt"))
        assertEquals("Public web and listed destinations", policyValueLabel("mode", "public"))
        assertEquals("Blocked", policyValueLabel("listen", "false"))
    }

    @Test fun an_edit_needs_a_live_fresh_editable_policy_and_no_request_waiting() {
        val read = policy()
        assertTrue(policyEditable(live = true, policy = read, fresh = true, busy = false))
        assertFalse(policyEditable(live = false, policy = read, fresh = true, busy = false))
        assertFalse(policyEditable(live = true, policy = read, fresh = false, busy = false))
        assertFalse(policyEditable(live = true, policy = read, fresh = true, busy = true))
        assertFalse(policyEditable(live = true, policy = null, fresh = true, busy = false))
        assertFalse(policyEditable(live = true, policy = read.copy(editable = false), fresh = true, busy = false))
    }

    @Test fun a_review_is_saved_only_against_the_current_base() {
        val read = policy()
        val review = PolicyReview.from(JSONObject(reviewJson))
        assertTrue(policyReviewSavable(review, read, live = true, fresh = true, busy = false))
        assertFalse(policyReviewSavable(review, read.copy(base = "b2"), live = true, fresh = true, busy = false))
        assertFalse(policyReviewSavable(review, read, live = true, fresh = false, busy = false))
        assertFalse(policyReviewSavable(review, read, live = true, fresh = true, busy = true))
        assertFalse(policyReviewSavable(review, null, live = true, fresh = true, busy = false))
    }
}
