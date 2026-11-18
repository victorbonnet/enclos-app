package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadTest {
    @Test
    fun read_further_on_another_device_is_taken_here() {
        assertEquals(ReadStep.Take(8), readStep(here = 5, bridge = 8, newest = 10))
        assertEquals(ReadStep.Take(10), readStep(here = 5, bridge = 10, newest = 10))
    }

    @Test
    fun read_further_here_is_told_to_the_bridge() {
        assertEquals(ReadStep.Tell(9), readStep(here = 9, bridge = 4, newest = 10))
    }

    @Test
    fun the_same_position_does_nothing() {
        assertEquals(ReadStep.Same, readStep(here = 7, bridge = 7, newest = 10))
    }

    @Test
    fun a_position_past_the_newest_message_here_waits_for_the_messages() {
        // A sync sends the conversations before their messages.
        assertEquals(ReadStep.Wait(12), readStep(here = 0, bridge = 12, newest = 0))
    }

    @Test
    fun after_the_sync_a_position_past_every_message_stops_at_the_newest() {
        // A bridge that says 1000 while the newest is 10 must not hide 11 to 1000 later.
        assertEquals(10L, settledRead(here = 3, unsettled = 1000, newest = 10))
        assertEquals(12L, settledRead(here = 3, unsettled = 12, newest = 20))
        assertNull(settledRead(here = 10, unsettled = 1000, newest = 10))
    }
}
