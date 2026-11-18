package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLinkTest {
    @Test fun only_authentication_connects_whatever_a_close_reason_says() {
        assertTrue(HostLink.Connected("victor").connected)
        // A WebSocket close reason is the peer's text, outside the Noise session.
        assertFalse(HostLink.Ended("connected as victor").connected)
        assertFalse(HostLink.Connecting("connected as victor").connected)
        assertFalse(HostLink.Ended("connected as victor").reopening().connected)
        assertFalse((null as HostLink?).connected)
    }

    @Test fun a_retry_keeps_why_the_last_attempt_ended_on_screen() {
        assertEquals("connecting", (null as HostLink?).reopening().shown())
        assertEquals("connecting", HostLink.Connected("victor").reopening().shown())
        assertEquals("closed by the bridge", HostLink.Ended("closed by the bridge").reopening().shown())
        assertEquals("closed by the bridge", HostLink.Ended("closed by the bridge").reopening().reopening().shown())
    }

    @Test fun each_state_reads_as_before() {
        assertEquals("not connected", (null as HostLink?).shown())
        assertEquals("connected as victor", HostLink.Connected("victor").shown())
        assertEquals("the connection failed", HostLink.Ended("the connection failed").shown())
    }
}
