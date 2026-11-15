package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    /** The buckets the bridge makes of [hours] (UTC hour starts), as its store does: hours whatever the offset. */
    private fun bridgePoints(hours: List<Long>, bucket: Long, offset: Long): List<UsagePoint> {
        val shift = if (bucket >= day) offset else 0
        return hours.groupBy { Math.floorDiv(it + shift, bucket) * bucket - shift }
            .map { (at, hs) -> UsagePoint(at, hs.size.toLong(), hs.size * 10L) }.sortedBy { it.at }
    }

    @Test fun the_bars_hold_every_token_the_bridge_counted_in_the_window_whatever_the_offset() {
        for (window in UsageWindow.entries) {
            for (offset in listOf(-9 * hour, 0L, 5 * hour + 1_800, 14 * hour)) {
                // A call every hour from the one that holds the window's start, as the bridge sums.
                val since = now - window.seconds
                val hours = generateSequence(since / hour * hour) { it + hour }.takeWhile { it <= now }.toList()
                val series = listOf(UsageSeries("lab/a", bridgePoints(hours, window.bucket, offset)))
                val bars = usageBars(series, window, now, offset)
                assertEquals("$window $offset", hours.size * 10L, bars.sumOf { it.tokens })
                assertEquals("$window $offset", 0, usageOffChart(series, bars))
            }
        }
        assertEquals(31, usageBars(emptyList(), UsageWindow.Month, now, 0).size)
    }

    @Test fun a_point_off_the_chart_is_counted_not_lost() {
        val series = listOf(UsageSeries("lab/a", listOf(UsagePoint(now / hour * hour - 3 * day, 1, 5), UsagePoint(now / hour * hour, 1, null))))
        val bars = usageBars(series, UsageWindow.Day, now, 0)
        assertEquals(1, usageOffChart(series, bars))
        // A point with no counted token draws nothing, and is not off the chart.
        assertTrue(bars.all { it.parts.isEmpty() })
    }

    @Test fun a_day_is_named_by_its_own_date_across_a_clock_change() {
        // Paris after its spring change (+2h): the buckets of the days before it still start at
        // 22:00 UTC, which the old offset would call the day before at 23:00.
        val offset = 2 * hour
        val saturday28March2026 = 1_774_648_800L // 2026-03-27 22:00 UTC
        assertEquals("Sat 28 Mar", usageBucketLabel(saturday28March2026, UsageWindow.Week, offset, Locale.ENGLISH))
        // An hour, in the locale's own short time (its space before PM varies by JDK).
        assertTrue(usageBucketLabel(saturday28March2026, UsageWindow.Day, 0, Locale.ENGLISH).startsWith("10:00"))
    }

    @Test fun an_agent_the_host_no_longer_lists_moves_no_one_elses_color() {
        val known = listOf("lab/b", "lab/c")
        val with = usageSlots(known, listOf("lab/a", "lab/c"))
        val without = usageSlots(known, listOf("lab/c"))
        assertEquals(1, with["lab/c"])
        assertEquals(with["lab/c"], without["lab/c"])
        // The removed agent takes its place after them.
        assertEquals(2, with["lab/a"])
        assertEquals(1, usageSlots(known, listOf("lab/a", "lab/b", "lab/c"))["lab/c"])
    }

    @Test fun series_the_app_cannot_read_or_cannot_draw_are_said() {
        val points = """[{"at":3600,"calls":1,"tokens":5}]"""
        val many = (0 until MAX_CHART_SERIES + 2).joinToString(",") { """{"agent":"lab/a$it","points":$points}""" }
        val report = JSONObject(
            """{"of":"u1","since":0,"agents":[],"series":[$many,{"points":[]},
               {"agent":"lab/x","points":[{"at":-1,"calls":1},{"at":7200,"calls":"one"},{"at":10800,"calls":1,"tokens":-3},{"at":14400,"calls":1}]}]}""",
        )
        val usage = HostUsage().loading("u1", UsageWindow.Week).received("u1", report)
        assertEquals(MAX_CHART_SERIES, usage.series.size)
        // 1 series without an agent, lab/x's 3 bad points, and the 3 series past the colors.
        assertEquals("The host sent 7 chart entries the app could not read.", usage.error)
    }

    @Test fun each_agent_says_whether_its_calls_outside_the_app_are_counted() {
        val report = JSONObject(
            """{"of":"u1","since":0,"outside":true,"agents":[{"agent":"lab/a","outside":true,"total":{"calls":1}},{"agent":"lab/b","total":{"calls":1}}]}""",
        )
        val usage = HostUsage().loading("u1", UsageWindow.Week, at = now, offset = 3_600).received("u1", report)
        assertEquals(listOf(true, false), usage.agents.sortedBy { it.agent }.map { it.outside })
        // The chart follows the request's time and offset, kept with the report.
        assertEquals(now, usage.at)
        assertEquals(3_600L, usage.offset)
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
        assertEquals("Tokens by agent over the last 24 hours: none counted.", usageSummary(emptyList(), UsageWindow.Day, { it }) { "" })
    }
}
