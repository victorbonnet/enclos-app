package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentUsageTest {
    private fun row(agent: String, tokens: Long, outside: Boolean = false) =
        JSONObject().put("agent", agent).put("outside", outside)
            .put("total", JSONObject().put("calls", 3).put("tokens", JSONObject().put("input", tokens)))

    private fun report(of: String, vararg rows: JSONObject, outside: Boolean = false) =
        JSONObject().put("type", "usage.report").put("of", of).put("since", 0).put("outside", outside)
            .put("agents", JSONArray(rows.toList()))

    @Test fun an_agent_the_host_lists_has_its_day_and_one_it_does_not_used_nothing() {
        val day = RecentUsage().loading("r1", 1_000).received("r1", report("r1", row("lab/main", 1_200_000, outside = true), outside = true))
        assertEquals(1_200_000L, day.of("lab/main")!!.total.tokens.total)
        assertTrue(day.of("lab/main")!!.outside)
        assertTrue(day.outside)
        // The host listed every agent with a call: one it left out made none.
        assertEquals(UsageTotal(), day.of("lab/quiet")!!.total)
        assertEquals(1_000L, day.at)
        assertNull(day.request)
    }

    @Test fun before_an_answer_or_past_a_cut_report_an_agent_is_unknown_not_unused() {
        assertNull(RecentUsage().of("lab/main"))
        assertNull(RecentUsage().loading("r1", 0).of("lab/main"))
        val rows = (0 until MAX_REPORT_AGENTS).map { row("lab/a$it", 10) }.toTypedArray()
        val cut = RecentUsage().loading("r1", 0).received("r1", report("r1", *rows))
        assertTrue(cut.cut)
        assertEquals(10L, cut.of("lab/a1")!!.total.tokens.total)
        assertNull(cut.of("lab/zz"))
    }

    @Test fun a_row_it_cannot_read_is_said_and_makes_the_others_unknown() {
        val day = RecentUsage().loading("r1", 0).received("r1", report("r1", row("lab/main", 5), JSONObject().put("agent", "")))
        assertTrue(day.cut)
        assertEquals("The host sent 1 rows the app could not read.", day.error)
        assertEquals(5L, day.of("lab/main")!!.total.tokens.total)
        assertNull(day.of("lab/other"))
    }

    @Test fun a_late_or_failed_answer_keeps_the_last_day() {
        val day = RecentUsage().loading("r1", 100).received("r1", report("r1", row("lab/main", 5)))
        val asking = day.loading("r2", 300)
        // An answer to another request is not this one's.
        assertEquals(asking, asking.received("r1", report("r1", row("lab/main", 9))))
        val failed = asking.failed("r2", "The connection ended.")
        assertNull(failed.request)
        assertEquals("The connection ended.", failed.error)
        assertEquals(5L, failed.of("lab/main")!!.total.tokens.total)
        // Not a report: said, the last day kept.
        val broken = asking.received("r2", JSONObject().put("type", "usage.report").put("of", "r2"))
        assertEquals(5L, broken.of("lab/main")!!.total.tokens.total)
        assertEquals("The host's usage could not be read.", broken.error)
    }

    @Test fun it_asks_again_once_two_minutes_old_and_never_while_a_request_is_out() {
        assertTrue(RecentUsage().due(0))
        assertFalse(RecentUsage().loading("r1", 0).due(10_000))
        val day = RecentUsage().loading("r1", 1_000).received("r1", report("r1"))
        assertFalse(day.due(1_000 + RECENT_USAGE_FRESH_SECONDS - 1))
        assertTrue(day.due(1_000 + RECENT_USAGE_FRESH_SECONDS))
        // A failed first request is asked again on the next tick.
        assertTrue(RecentUsage().loading("r1", 0).failed("r1", "").due(1))
    }

    @Test fun a_row_reads_tokens_or_calls_and_nothing_for_none() {
        assertEquals("1.2M tokens · 24h", recentUsageText(UsageTotal(3, Tokens(input = 1_200_000))))
        assertEquals("4 calls · 24h", recentUsageText(UsageTotal(4)))
        assertEquals("1 call · 24h", recentUsageText(UsageTotal(1)))
        assertNull(recentUsageText(UsageTotal()))
    }
}
