package io.github.victorbonnet.enclos

import org.junit.Assert.assertFalse
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
}
