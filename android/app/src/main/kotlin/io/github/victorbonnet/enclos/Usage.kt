package io.github.victorbonnet.enclos

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    /** Whether the runtime counts any token at all: one that counts calls alone shows no tokens, not 0. */
    val counted: Boolean get() = listOf(input, output, cacheRead, cacheWrite).any { it != null }

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

/**
 * The windows the app offers; the bridge sums from the start of the hour that holds the
 * window's start. Each has its bars' size: the last day by the hour, a week or a month by
 * the day.
 */
enum class UsageWindow(val label: String, val seconds: Long, val bucket: Long) {
    Day("24 hours", 24 * 3600, 3600),
    Week("7 days", 7 * 24 * 3600, 86_400),
    Month("30 days", 30 * 24 * 3600, 86_400),
}

/** What an agent used in one bucket of a series: its start (Unix seconds), calls and every token, null when the runtime counted none. */
data class UsagePoint(val at: Long, val calls: Long, val tokens: Long?)

/** One agent's usage over time, as its bridge answered: the buckets that saw a call. */
data class UsageSeries(val agent: String, val points: List<UsagePoint>)

/** One bar of the chart: a bucket's start, and what each agent used in it, the chart's order. */
data class UsageBar(val at: Long, val parts: List<Pair<String, Long>>) {
    val tokens: Long get() = parts.sumOf { it.second }
}

/**
 * The bars of [window], one per bucket from the one holding the window's start to the one
 * holding [now], empty buckets too so that time reads evenly; buckets start where the
 * bridge's do, at a multiple of the bucket in the phone's time ([offset] seconds from UTC).
 * Each bar stacks the agents of [series] in their order, those with no use in it left out.
 */
fun usageBars(series: List<UsageSeries>, window: UsageWindow, now: Long, offset: Long): List<UsageBar> {
    val b = window.bucket
    // The bridge keeps usage by the UTC hour: an hour is the hour whatever the offset.
    val shift = if (b >= 86_400) offset else 0
    fun start(t: Long) = Math.floorDiv(t + shift, b) * b - shift
    val last = start(now)
    // From the bucket that holds the window's start, which the bridge's sums include.
    val first = start(now - window.seconds)
    val byAt = series.associate { s -> s.agent to s.points.associateBy { it.at } }
    return generateSequence(first) { it + b }.takeWhile { it <= last }.map { at ->
        UsageBar(at, series.mapNotNull { s -> byAt[s.agent]?.get(at)?.tokens?.takeIf { it > 0 }?.let { s.agent to it } })
    }.toList()
}

/**
 * What a screen reader says of the chart: its window, the bucket that used the most and who
 * used most in it. The rows under the chart give every value.
 */
fun usageSummary(bars: List<UsageBar>, window: UsageWindow, label: (String) -> String, bucketLabel: (Long) -> String): String {
    val peak = bars.maxByOrNull { it.tokens }?.takeIf { it.tokens > 0 }
        ?: return "Tokens by agent over the last ${window.label}: none counted."
    val lead = peak.parts.maxByOrNull { it.second }
    return "Tokens by agent over the last ${window.label}, a bar per ${if (window.bucket < 86_400) "hour" else "day"}. " +
        "The most: ${bucketLabel(peak.at)}, ${tokensText(peak.tokens)}" + (lead?.let { ", mostly ${label(it.first)}." } ?: ".")
}

/**
 * The points of [series] that fall on none of [bars]: what the chart cannot show. None, when
 * the bars are built from the window and offset the report was asked with.
 */
fun usageOffChart(series: List<UsageSeries>, bars: List<UsageBar>): Int {
    val starts = bars.map { it.at }.toSet()
    return series.sumOf { s -> s.points.count { it.at !in starts && (it.tokens ?: 0) > 0 } }
}

/**
 * A bucket's start as the phone said it when it asked: a time for an hour, a weekday and date
 * for a day, at the offset the buckets were made with, so a day before a clock change is still
 * named by its own date.
 */
fun usageBucketLabel(at: Long, window: UsageWindow, offset: Long, locale: Locale = Locale.getDefault()): String {
    val time = Instant.ofEpochSecond(at).atOffset(ZoneOffset.ofTotalSeconds(offset.toInt()))
    return if (window.bucket < 86_400) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
    else DateTimeFormatter.ofPattern("EEE d MMM", locale).format(time)
}

/**
 * The color slot of each agent of [shown]: by its place among [known], the host's agents in
 * name order, so an agent keeps its color whatever the window and whoever else used more. An
 * agent the host no longer lists comes after them all, and one past the slots takes the first
 * free: those are stable only as long as who is shown is. Never more than [slots] in a chart.
 */
fun usageSlots(known: List<String>, shown: List<String>, slots: Int = 8): Map<String, Int> {
    val listed = known.distinct().sorted()
    val order = listed + shown.filter { it !in listed }.distinct().sorted()
    val taken = mutableMapOf<String, Int>()
    shown.forEach { agent -> order.indexOf(agent).takeIf { it in 0 until slots && it !in taken.values }?.let { taken[agent] = it } }
    shown.filter { it !in taken }.forEach { agent -> (0 until slots).firstOrNull { it !in taken.values }?.let { taken[agent] = it } }
    return taken
}

/** What one agent (`sandbox/agent`) used over a window. */
/** What one agent (`sandbox/agent`) used over a window; [outside] when its calls outside the app are counted too. */
data class AgentUsage(val agent: String, val total: UsageTotal, val outside: Boolean = false)

/**
 * A host's usage: the rows of [window], as its bridge last answered, and the window [asked]
 * for while a request is out. The rows always belong to [window]: a request that fails leaves
 * them and their window as they were, and a late answer to an older request is ignored.
 */
data class HostUsage(
    val window: UsageWindow = UsageWindow.Week,
    val agents: List<AgentUsage> = emptyList(),
    /** Each heavy agent's usage over [window], when the bridge gives series; heaviest first. */
    val series: List<UsageSeries> = emptyList(),
    /** Whether it counts the agents' calls outside the app too: for the host's person. */
    val outside: Boolean = false,
    /** Where the bridge's sums start (Unix seconds), once it answered. */
    val since: Long? = null,
    val loaded: Boolean = false,
    val request: String? = null,
    val asked: UsageWindow? = null,
    /**
     * When the report was asked (Unix seconds) and the phone's offset from UTC then, which its
     * series' buckets follow: the chart is drawn from these, not from the time it is drawn.
     */
    val at: Long = 0,
    val offset: Long = 0,
    private val askedAt: Long = 0,
    private val askedOffset: Long = 0,
    val error: String? = null,
) {
    /** The tab to show selected: the window asked for, while it loads. */
    val selected: UsageWindow get() = asked ?: window

    /** Whether the rows shown are those of the selected tab. */
    val rowsShown: Boolean get() = loaded && selected == window

    fun loading(id: String, window: UsageWindow, at: Long = 0, offset: Long = 0) =
        copy(request = id, asked = window, askedAt = at, askedOffset = offset, error = null)

    /**
     * The answer to [of]: the agents that used the most first, each once. A report that is not
     * one, or rows without an agent, are said, not shown as no usage.
     */
    fun received(of: String, report: JSONObject): HostUsage {
        if (request != of) return this
        val list = report.optJSONArray("agents")
            ?: return failed(of, "The host's usage could not be read.")
        val read = (0 until list.length()).map { i ->
            list.optJSONObject(i)?.let { a ->
                a.optString("agent").ifBlank { null }?.let { AgentUsage(it, UsageTotal.of(a.optJSONObject("total")), a.optBoolean("outside")) }
            }
        }
        val agents = read.filterNotNull().distinctBy { it.agent }.sortedByDescending { it.total.tokens.total }
        val unread = read.count { it == null }
        val (series, unreadSeries) = seriesOf(report)
        val said = listOfNotNull(
            "$unread rows".takeIf { unread > 0 },
            "$unreadSeries chart entries".takeIf { unreadSeries > 0 },
        )
        return copy(
            window = asked ?: window, agents = agents, since = count(report, "since"), loaded = true, request = null, asked = null,
            series = series, outside = report.optBoolean("outside"), at = askedAt, offset = askedOffset,
            error = if (said.isEmpty()) null else "The host sent ${said.joinToString(" and ")} the app could not read.",
        )
    }

    fun failed(of: String, why: String): HostUsage =
        if (request == of) copy(request = null, asked = null, error = why.ifBlank { "The host could not return its usage." }) else this
}

/** The most series a chart draws: its colors. */
const val MAX_CHART_SERIES = 8

/**
 * A report's `series`, and how many of its entries the app could not read: each agent once,
 * at most [MAX_CHART_SERIES], its points with a start and their counts. A series, point or
 * count that is not one is counted, never read as 0; a point's absent `tokens` is the runtime
 * counting none, not an error.
 */
private fun seriesOf(report: JSONObject): Pair<List<UsageSeries>, Int> {
    val list = report.optJSONArray("series") ?: return emptyList<UsageSeries>() to 0
    var unread = 0
    val read = (0 until list.length()).mapNotNull { i ->
        val s = list.optJSONObject(i)
        val agent = s?.optString("agent")?.ifBlank { null }
        if (s == null || agent == null) {
            unread++
            return@mapNotNull null
        }
        val points = s.optJSONArray("points")
        UsageSeries(agent, (0 until (points?.length() ?: 0)).mapNotNull { j ->
            val p = points!!.optJSONObject(j)
            val at = p?.let { count(it, "at") }
            val calls = p?.let { count(it, "calls") }
            val tokens = p?.let { count(it, "tokens") }
            if (p == null || at == null || calls == null || (p.has("tokens") && tokens == null)) {
                unread++
                return@mapNotNull null
            }
            UsagePoint(at, calls, tokens)
        }.sortedBy { it.at })
    }
    val once = read.distinctBy { it.agent }
    unread += read.size - once.size + (once.size - MAX_CHART_SERIES).coerceAtLeast(0)
    return once.take(MAX_CHART_SERIES) to unread
}

/**
 * Where a model call's usage goes. One of a turn under way adds to the turn; a background call
 * (a title, a summary) once the agent answered belongs to that answer, not to the next turn.
 */
fun usageGoesToLastAnswer(working: Boolean, background: Boolean): Boolean = !working && background

/** [lines] with [call] added to the last answer's usage; null when there is no answer yet. */
fun addedToLastAnswer(lines: List<Line>, call: UsageTotal): List<Line>? {
    val last = lines.indexOfLast { it.from == "agent" && it.kind == "reply" }
    if (last < 0) return null
    return lines.toMutableList().also { it[last] = it[last].copy(usage = (it[last].usage ?: UsageTotal()) + call) }
}

/** Counts read at a glance: 950, 83.1k, 1.2M; the unit chosen after rounding, so 999,950 reads 1.0M. */
fun tokensText(n: Long): String {
    if (n < 1_000) return n.toString()
    var value = n.toDouble()
    val units = listOf("k", "M", "G", "T", "P")
    for (unit in units) {
        value /= 1e3
        if (Math.round(value * 10) < 10_000 || unit == units.last()) return String.format(Locale.ROOT, "%.1f%s", value, unit)
    }
    error("the last unit returns")
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
