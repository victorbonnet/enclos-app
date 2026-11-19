package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCostTest {
    private val day = 86_400L

    // 2027-01-15 08:00 UTC.
    private val now = 1_800_000_000L

    @Test fun a_cost_reads_in_dollars_and_cents_and_says_what_it_leaves_out() {
        assertEquals("~$1.23", costText(UsageCost(1_234_567)))
        assertEquals("~$1.24", costText(UsageCost(1_235_000)))
        assertEquals("~$1.23", costText(UsageCost(1_234_567, included = 10)))
        assertEquals("≥~$1.23", costText(UsageCost(1_234_567, unpriced = 10)))
        assertEquals("included", costText(UsageCost(included = 10)))
        assertEquals("?", costText(UsageCost(included = 10, unpriced = 3)))
        assertEquals("?", costText(UsageCost(unpriced = 3)))
        assertEquals("~$0.00", costText(UsageCost()))
        assertEquals("~<$0.01", costText(UsageCost(4_999)))
        assertEquals("~$0.01", costText(UsageCost(5_000)))
        assertEquals("$1,234.57", dollarsText(1_234_567_890))
    }

    @Test fun a_cost_object_is_read_whole_or_not_at_all() {
        assertEquals(UsageCost(1_250_000, 0, 12), UsageCost.of(JSONObject("""{"usd_micros":1250000,"unpriced":12}""")))
        assertEquals(UsageCost(included = 3), UsageCost.of(JSONObject("""{"included":3}""")))
        assertNull(UsageCost.of(null))
        // A part that is not a count: the whole cost is unread, never 0.
        assertNull(UsageCost.of(JSONObject("""{"usd_micros":-1}""")))
        assertNull(UsageCost.of(JSONObject("""{"usd_micros":"12"}""")))
        assertEquals(UsageCost(3, 4, 5), UsageCost(1, 2, 3) + UsageCost(2, 2, 2))
        assertEquals(Long.MAX_VALUE, (UsageCost(Long.MAX_VALUE) + UsageCost(1)).usdMicros)
    }

    @Test fun a_report_with_costs_keeps_each_agents_and_sandboxes_cost_and_a_scopes_sum() {
        val report = JSONObject(
            """{"of":"u1","since":0,"bucket":86400,
               "agents":[{"agent":"lab/main","total":{"calls":2,"tokens":{"input":100}},"cost":{"usd_micros":1250000}},
                         {"agent":"lab/scout","total":{"calls":1}}],
               "sandboxes":[{"sandbox":"lab","total":{"calls":3,"tokens":{"input":100}},"cost":{"usd_micros":1250000,"unpriced":9}},
                            {"sandbox":"bad","total":{"calls":1}},{"total":{"calls":1},"cost":{}}],
               "sum":[{"at":172800,"calls":1,"tokens":40,"cost":{"usd_micros":500000}},
                      {"at":86400,"calls":2,"tokens":60,"cost":{"usd_micros":750000,"unpriced":9}},
                      {"at":259200,"calls":1,"cost":{"usd_micros":-5}}]}""",
        )
        val usage = HostUsage().loading("u1", UsageWindow.Week).received("u1", report)
        assertEquals(UsageCost(1_250_000), usage.agents.first { it.agent == "lab/main" }.cost)
        assertNull(usage.agents.first { it.agent == "lab/scout" }.cost)
        assertEquals(listOf("lab"), usage.sandboxes.map { it.sandbox })
        assertEquals(UsageCost(1_250_000, unpriced = 9), usage.sandboxes[0].cost)
        assertEquals(listOf(86_400L, 172_800L), usage.sum.map { it.at })
        assertEquals(UsageCost(1_250_000, unpriced = 9), sumCost(usage.sum))
        // Two sandbox rows and one point it could not read are said, not dropped silently.
        assertTrue(usage.error!!, usage.error!!.contains("2 sandbox rows") && usage.error!!.contains("1 chart entries"))
        // An older bridge: none of them.
        val old = HostUsage().loading("u2", UsageWindow.Week).received("u2", JSONObject("""{"of":"u2","since":0,"agents":[]}"""))
        assertTrue(old.sandboxes.isEmpty() && old.sum.isEmpty())
        assertNull(sumCost(old.sum))
    }

    @Test fun a_scopes_bars_are_its_tokens_or_its_dollars_a_bucket() {
        val today = now / day * day
        val sum = listOf(
            UsagePoint(today - day, 2, 60, UsageCost(750_000, unpriced = 9)),
            UsagePoint(today, 1, 40, UsageCost(included = 40)),
        )
        val dollars = sumBars(sum, "h/lab", cost = true, UsageWindow.Week, now, 0)
        assertEquals(8, dollars.size)
        assertEquals(750_000L, dollars[dollars.size - 2].tokens)
        // A bucket all on a subscription has no dollars: no bar.
        assertEquals(0L, dollars.last().tokens)
        val tokens = sumBars(sum, "h/lab", cost = false, UsageWindow.Week, now, 0)
        assertEquals(listOf(60L, 40L), tokens.takeLast(2).map { it.tokens })
        assertEquals(listOf("h/lab"), tokens.last().parts.map { it.first })
        val summary = sumSummary(dollars, UsageWindow.Week, cost = true) { "day $it" }
        assertTrue(summary, summary.startsWith("Estimated cost over the last 7 days, a bar per day. The most: day ${today - day}, $0.75."))
        assertEquals("Tokens over the last 7 days: none.", sumSummary(emptyList(), UsageWindow.Week, cost = false) { "" })
    }

    @Test fun the_last_day_of_an_agent_carries_its_cost() {
        val state = RecentUsage().loading("r1", now).received(
            "r1",
            JSONObject("""{"of":"r1","since":0,"agents":[{"agent":"lab/main","total":{"calls":1},"cost":{"included":5}}]}"""),
        )
        assertEquals(UsageCost(included = 5), state.of("lab/main")?.cost)
    }
}
