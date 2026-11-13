package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCapabilitiesTest {
    @Test fun authentication_keeps_every_feature_and_older_hosts_clear_the_set() {
        assertEquals(setOf("audit", "approval.close", "sandboxes"), hostCapabilitiesIn(
            JSONObject("""{"capabilities":["audit","approval.close","sandboxes","audit"]}""")))
        assertTrue(hostCapabilitiesIn(JSONObject("""{"person":"alice"}""")).isEmpty())
        assertTrue(hostCapabilitiesIn(JSONObject("""{"capabilities":null}""")).isEmpty())
    }
}
