package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSeriesTest {
    private val hour = 3_600L
    private val day = 86_400L

    // 2027-01-15 08:00 UTC.
    private val now = 1_800_000_000L

    @Test fun a_report_with_series_keeps_each_agent_once_and_its_points_in_time() {
        val report = JSONObject(
            """{"of":"u1","since":0,"outside":true,"bucket":86400,"agents":[{"agent":"lab/a","total":{"calls":2}}],
               "series":[{"agent":"lab/a","points":[{"at":172800,"calls":1,"tokens":5},{"at":86400,"calls":1,"tokens":7},{"calls":1}]},
                         {"agent":"lab/a","points":[]},{"points":[]}]}""",
        )
        val usage = HostUsage().loading("u1", UsageWindow.Week).received("u1", report)
        assertTrue(usage.outside)
        assertEquals(1, usage.series.size)
        assertEquals(listOf(86_400L, 172_800L), usage.series[0].points.map { it.at })
        // An older bridge: no series, not "outside".
        val old = HostUsage().loading("u2", UsageWindow.Week).received("u2", JSONObject("""{"of":"u2","since":0,"agents":[]}"""))
        assertTrue(old.series.isEmpty())
        assertFalse(old.outside)
    }

    @Test fun the_bars_cover_every_bucket_of_the_window_stacked_in_the_series_order() {
        val series = listOf(
            UsageSeries("lab/a", listOf(UsagePoint(now / hour * hour, 3, 300), UsagePoint(now / hour * hour - 2 * hour, 1, 100))),
            UsageSeries("lab/b", listOf(UsagePoint(now / hour * hour, 1, 50))),
        )
        val bars = usageBars(series, UsageWindow.Day, now, 0)
        // From the hour that holds the window's start to the one that holds now.
        assertEquals(25, bars.size)
        assertEquals(now / hour * hour, bars.last().at)
        assertEquals(listOf("lab/a" to 300L, "lab/b" to 50L), bars.last().parts)
        assertEquals(350L, bars.last().tokens)
        assertEquals(listOf("lab/a" to 100L), bars[bars.size - 3].parts)
        assertTrue(bars.drop(1).zip(bars).all { (b, a) -> b.at - a.at == hour })
        assertTrue(bars[0].parts.isEmpty())
    }

    @Test fun a_day_starts_at_the_phones_midnight() {
        // A phone 9 hours west of UTC: its days start at 09:00 UTC.
        val offset = -9 * hour
        val bars = usageBars(emptyList(), UsageWindow.Week, now, offset)
        assertTrue(bars.all { Math.floorMod(it.at + offset, day) == 0L })
        assertEquals(8, bars.size)
        // 08:00 UTC is still its yesterday.
        assertTrue(bars.last().at < now && now - bars.last().at < day)
    }

    @Test fun an_agent_keeps_its_color_whoever_else_used_more() {
        val known = listOf("lab/c", "lab/a", "lab/b")
        val week = usageSlots(known, listOf("lab/b", "lab/a"))
        val day = usageSlots(known, listOf("lab/a"))
        assertEquals(week["lab/a"], day["lab/a"])
        assertEquals(mapOf("lab/a" to 0, "lab/b" to 1), week)
        // Past the slots: the first free one, never two agents on one color.
        val many = (0 until 10).map { "lab/a$it" }
        val shown = listOf("lab/a9", "lab/a0", "lab/a8")
        val slots = usageSlots(many, shown)
        assertEquals(3, slots.values.toSet().size)
        assertEquals(0, slots["lab/a0"])
    }

    @Test fun a_screen_reader_hears_the_peak_and_who_led_it() {
        val bars = listOf(UsageBar(0, listOf("lab/a" to 10L)), UsageBar(day, listOf("lab/a" to 100L, "lab/b" to 900L)))
        val said = usageSummary(bars, UsageWindow.Week, { it.substringAfter('/') }) { if (it == day) "Fri 2" else "Thu 1" }
        assertEquals("Tokens by agent over the last 7 days, a bar per day. The most: Fri 2, 1.0k, mostly b.", said)
        assertEquals("Tokens by agent over the last 24 hours: none.", usageSummary(emptyList(), UsageWindow.Day, { it }) { "" })
    }
}
