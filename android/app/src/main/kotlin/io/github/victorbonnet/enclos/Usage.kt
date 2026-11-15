package io.github.victorbonnet.enclos

import org.json.JSONObject
import java.util.Locale

/**
 * What an agent's model calls consumed, as its runtime counts them (docs/spec.md, Usage;
 * enclos's docs/protocol.md, Usage). A count is null when the runtime does not keep it,
 * which is not 0. [input] is what was not read from or written to the provider's cache.
 */
data class Tokens(
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    /** Part of [output], as providers count it. */
    val reasoning: Long? = null,
) {
    /** What the model was sent: input, cache reads and cache writes. */
    val prompt: Long get() = sumKnown(input, cacheRead, cacheWrite)

    /** Every token sent or received. */
    val total: Long get() = sum(prompt, output ?: 0)

    /** The share of the prompt read from the cache, when the runtime counts cache reads. */
    val cacheHit: Double? get() = cacheRead?.takeIf { prompt > 0 }?.let { it.toDouble() / prompt }

    operator fun plus(other: Tokens) = Tokens(
        add(input, other.input),
        add(output, other.output),
        add(cacheRead, other.cacheRead),
        add(cacheWrite, other.cacheWrite),
        add(reasoning, other.reasoning),
    )

    companion object {
        /** The `tokens` object of a usage message; a count that is not a whole number of at least 0 is not counted. */
        fun of(json: JSONObject?): Tokens = Tokens(
            count(json, "input"),
            count(json, "output"),
            count(json, "cache_read"),
            count(json, "cache_write"),
            count(json, "reasoning"),
        )
    }
}

/** Calls to the provider, and their tokens: a turn's, a conversation's, or an agent's over a window. */
data class UsageTotal(val calls: Long = 0, val tokens: Tokens = Tokens()) {
    operator fun plus(other: UsageTotal) = UsageTotal(sum(calls, other.calls), tokens + other.tokens)

    companion object {
        /** A `total` object (`calls`, `tokens`). */
        fun of(json: JSONObject?): UsageTotal = UsageTotal(count(json, "calls") ?: 0, Tokens.of(json?.optJSONObject("tokens")))

        /** One `usage` message's call, which `calls` says may sum a few (absent: one). */
        fun call(message: JSONObject): UsageTotal =
            UsageTotal(if (message.has("calls")) count(message, "calls") ?: 0 else 1, Tokens.of(message.optJSONObject("tokens")))
    }
}

/** The windows the app offers; the bridge sums from the start of the hour that holds the window's start. */
enum class UsageWindow(val label: String, val seconds: Long) {
    Day("24 hours", 24 * 3600),
    Week("7 days", 7 * 24 * 3600),
    Month("30 days", 30 * 24 * 3600),
}

/** What one agent (`sandbox/agent`) used over a window. */
data class AgentUsage(val agent: String, val total: UsageTotal)

/** A host's usage over [window], as its bridge last answered; a late answer to an older request is ignored. */
data class HostUsage(
    val window: UsageWindow = UsageWindow.Week,
    val agents: List<AgentUsage> = emptyList(),
    /** Where the bridge's sums start (Unix seconds), once it answered. */
    val since: Long? = null,
    val loaded: Boolean = false,
    val request: String? = null,
    val error: String? = null,
) {
    fun loading(id: String, window: UsageWindow) = copy(window = window, request = id, error = null)

    /** The answer to [of]: the agents that used the most first. */
    fun received(of: String, report: JSONObject): HostUsage {
        if (request != of) return this
        val list = report.optJSONArray("agents")
        val agents = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val a = list!!.optJSONObject(i) ?: return@mapNotNull null
            val agent = a.optString("agent").ifBlank { return@mapNotNull null }
            AgentUsage(agent, UsageTotal.of(a.optJSONObject("total")))
        }.sortedByDescending { it.total.tokens.total }
        return copy(agents = agents, since = count(report, "since"), loaded = true, request = null, error = null)
    }

    fun failed(of: String, why: String): HostUsage = if (request == of) copy(request = null, error = why) else this
}

/** Counts read at a glance: 950, 83.1k, 1.2M. */
fun tokensText(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> String.format(Locale.ROOT, "%.1fk", n / 1e3)
    n < 1_000_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1e6)
    else -> String.format(Locale.ROOT, "%.1fG", n / 1e9)
}

/**
 * One line for a turn or a conversation: `3 calls · 33.8k in (94% cached) · 96 out`. Input
 * here is all the model was sent, cache included, as that is what grows with the context.
 */
fun usageLine(u: UsageTotal): String = buildList {
    add(if (u.calls == 1L) "1 call" else "${u.calls} calls")
    if (u.tokens.prompt > 0 || u.tokens.input != null) {
        val hit = u.tokens.cacheHit?.takeIf { it >= 0.005 }?.let { " (${(it * 100).toInt()}% cached)" }.orEmpty()
        add("${tokensText(u.tokens.prompt)} in$hit")
    }
    u.tokens.output?.let { add("${tokensText(it)} out") }
}.joinToString(" · ")

/** Each count of [u], labelled, for a detail view; a count the runtime does not keep is said so. */
fun usageDetails(u: UsageTotal): List<Pair<String, String>> {
    val t = u.tokens
    val shown = { n: Long? -> n?.let { "%,d".format(Locale.ROOT, it) } ?: "not counted" }
    return listOf(
        "Calls" to "%,d".format(Locale.ROOT, u.calls),
        "Input" to shown(t.input),
        "Cache read" to shown(t.cacheRead),
        "Cache write" to shown(t.cacheWrite),
        "Output" to shown(t.output),
        "Reasoning" to shown(t.reasoning),
        "Total" to "%,d".format(Locale.ROOT, t.total),
    )
}

private fun sum(vararg n: Long): Long = n.fold(0L) { a, b -> if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b }

private fun sumKnown(vararg n: Long?): Long = sum(*n.filterNotNull().toLongArray())

private fun add(a: Long?, b: Long?): Long? = if (a == null && b == null) null else sum(a ?: 0, b ?: 0)

/**
 * A count the bridge sent: a whole number of at least 0, else not counted. One past what a
 * Long holds (the bridge's sums stop at the largest unsigned 64-bit number) reads as the largest.
 */
private fun count(json: JSONObject?, key: String): Long? {
    if (json == null || !json.has(key) || json.isNull(key)) return null
    return when (val v = json.opt(key)) {
        is Int -> v.toLong().takeIf { it >= 0 }
        is Long -> v.takeIf { it >= 0 }
        is Number -> v.toDouble().takeIf { it >= 0 && it == Math.floor(it) }?.let { if (it >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else it.toLong() }
        else -> null
    }
}
