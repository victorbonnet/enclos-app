package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun usage_belongs_to_its_conversation_and_is_left_out_on_a_clash() {
        val mine = Conversation("home", "lab/main", "c-1")
        // Another host keeps a conversation with this id: its usage would land on it.
        assert(leftOutForClash(listOf(mine), "other", "usage", "c-1"))
        assert(!leftOutForClash(listOf(mine), "home", "usage", "c-1"))
    }
}
