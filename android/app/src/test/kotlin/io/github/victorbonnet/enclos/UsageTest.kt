package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    @Test fun a_call_adds_to_the_turn_and_a_count_not_kept_stays_not_counted() {
        val first = UsageTotal.call(JSONObject("""{"conversation":"c","tokens":{"input":2104,"output":96,"cache_read":31744}}"""))
        val second = UsageTotal.call(JSONObject("""{"conversation":"c","calls":2,"background":true,"tokens":{"input":10,"reasoning":4}}"""))
        val turn = UsageTotal() + first + second
        assertEquals(3L, turn.calls)
        assertEquals(2114L, turn.tokens.input)
        assertEquals(96L, turn.tokens.output)
        assertEquals(4L, turn.tokens.reasoning)
        assertNull(turn.tokens.cacheWrite)
        assertEquals(2114L + 31744, turn.tokens.prompt)
        assertEquals(2114L + 31744 + 96, turn.tokens.total)
    }

    @Test fun a_count_that_is_not_a_whole_number_of_at_least_0_is_not_counted_and_a_huge_one_saturates() {
        val tokens = Tokens.of(JSONObject("""{"input":-1,"output":"3","cache_read":1.5,"cache_write":18446744073709551615,"reasoning":true}"""))
        assertNull(tokens.input)
        assertNull(tokens.output)
        assertNull(tokens.cacheRead)
        assertNull(tokens.reasoning)
        assertEquals(Long.MAX_VALUE, tokens.cacheWrite)
        // A sum past what a Long holds stops there.
        assertEquals(Long.MAX_VALUE, (tokens + tokens).cacheWrite)
        assertEquals(Long.MAX_VALUE, (tokens + tokens).total)
    }

    @Test fun a_line_reads_at_a_glance_with_the_cache_share_of_what_was_sent() {
        val u = UsageTotal(26, Tokens(input = 83_087, output = 6_084, cacheRead = 762_752, cacheWrite = 0))
        assertEquals("26 calls · 845.8k in (90% cached) · 6.1k out", usageLine(u))
        assertEquals("1 call · 950 in · 12 out", usageLine(UsageTotal(1, Tokens(input = 950, output = 12))))
        // A runtime that counts nothing still says how many calls.
        assertEquals("2 calls", usageLine(UsageTotal(2, Tokens())))
        assertEquals("1.2M", tokensText(1_234_567))
        // Rounding up to the next unit takes that unit.
        assertEquals("999.9k", tokensText(999_949))
        assertEquals("1.0M", tokensText(999_950))
        assertEquals("1.0G", tokensText(999_950_000))
        assertEquals("9223.4P", tokensText(Long.MAX_VALUE))
        val details = usageDetails(UsageTotal(1, Tokens(input = 1_000)))
        assertEquals("1,000", details.first { it.first == "Input" }.second)
        assertEquals("not counted", details.first { it.first == "Reasoning" }.second)
    }

    @Test fun a_window_keeps_its_own_answer_most_used_first_and_ignores_a_late_one() {
        val waiting = HostUsage().loading("u2", UsageWindow.Day)
        val report = JSONObject(
            """{"of":"u2","since":1799996400,"agents":[
                {"agent":"lab/quiet","total":{"calls":1,"tokens":{"input":5}}},
                {"agent":"lab/busy","total":{"calls":26,"tokens":{"input":83087,"cache_read":762752}}},
                {"total":{"calls":1}}]}""",
        )
        assertEquals(waiting, waiting.received("u1", report))
        val done = waiting.received("u2", report)
        assertEquals(listOf("lab/busy", "lab/quiet"), done.agents.map { it.agent })
        assertEquals(1_799_996_400L, done.since)
        assertEquals(UsageWindow.Day, done.window)
        assertNull(done.request)
        assertEquals(done, done.failed("u2", "late timeout"))
    }

    @Test fun a_failed_request_says_why_and_keeps_the_rows_it_had() {
        val week = HostUsage().loading("u1", UsageWindow.Week).received("u1", report("u1", "lab/main"))
        val failed = week.loading("u2", UsageWindow.Week).failed("u2", "gone")
        assertEquals("gone", failed.error)
        assertNull(failed.request)
        assertEquals(week.agents, failed.agents)
        // An error with no words still says something.
        assertEquals("The host could not return its usage.", week.loading("u3", UsageWindow.Week).failed("u3", "").error)
        // The timeout of an earlier request leaves the one under way alone.
        val waiting = week.loading("u4", UsageWindow.Day)
        assertEquals(waiting, waiting.failed("u2", "late timeout"))
    }

    @Test fun rows_always_belong_to_the_window_shown_and_a_failed_switch_goes_back() {
        val week = HostUsage().loading("u1", UsageWindow.Week).received("u1", report("u1", "lab/main"))
        assertTrue(week.rowsShown)
        val switching = week.loading("u2", UsageWindow.Day)
        // The new tab is selected; the week's rows are not shown under it.
        assertEquals(UsageWindow.Day, switching.selected)
        assertFalse(switching.rowsShown)
        val back = switching.failed("u2", "timed out")
        assertEquals(UsageWindow.Week, back.selected)
        assertTrue(back.rowsShown)
        val day = switching.received("u2", report("u2"))
        assertEquals(UsageWindow.Day, day.window)
        assertTrue(day.rowsShown && day.agents.isEmpty())
    }

    @Test fun a_report_that_is_not_one_is_said_and_an_agent_listed_twice_shows_once() {
        val waiting = HostUsage().loading("u1", UsageWindow.Week)
        val broken = waiting.received("u1", JSONObject("""{"of":"u1","since":0}"""))
        assertEquals("The host's usage could not be read.", broken.error)
        assertFalse(broken.loaded)
        val twice = waiting.received(
            "u1",
            JSONObject("""{"of":"u1","since":0,"agents":[{"agent":"lab/a","total":{"calls":1}},{"agent":"lab/a","total":{"calls":2}},{"total":{}}]}"""),
        )
        assertEquals(listOf("lab/a"), twice.agents.map { it.agent })
        assertEquals("The host sent 1 rows the app could not read.", twice.error)
    }

    @Test fun a_runtime_that_counts_calls_alone_has_no_tokens_rather_than_0() {
        assertFalse(Tokens().counted)
        assertTrue(Tokens(output = 0).counted)
    }

    @Test fun a_background_call_once_the_agent_answered_goes_to_that_answer() {
        assertTrue(usageGoesToLastAnswer(working = false, background = true))
        assertFalse(usageGoesToLastAnswer(working = true, background = true))
        assertFalse(usageGoesToLastAnswer(working = false, background = false))
        val lines = listOf(
            Line(1, "person", "hi", "reply", 0),
            Line(2, "agent", "hello", "reply", 0, usage = UsageTotal(2, Tokens(input = 10))),
            Line(3, "agent", "cron", "notify", 0),
        )
        val title = UsageTotal(1, Tokens(input = 5))
        val after = addedToLastAnswer(lines, title)!!
        assertEquals(UsageTotal(3, Tokens(input = 15)), after[1].usage)
        assertNull(after[2].usage)
        assertNull(addedToLastAnswer(lines.take(1), title))
    }

    private fun report(of: String, vararg agents: String) = JSONObject(
        """{"of":"$of","since":0,"agents":[${agents.joinToString(",") { """{"agent":"$it","total":{"calls":1,"tokens":{"input":5}}}""" }}]}""",
    )
}
