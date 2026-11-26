package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val PAUSE_CAPABILITY = "pause"
internal const val PAUSE_SUBSET_CAPABILITY = "pause.subset"

/** Closed set of `why` values accepted in `pause.state` sandbox rows. */
internal val PAUSE_WHY_WORDS = setOf(
    "not_covered",
    "released",
    "nothing_to_pause",
    "would_not_stop",
    "would_not_start",
)

/** Closed set of `outcome` values accepted in `pause.result` sandbox rows. */
internal val PAUSE_OUTCOMES = setOf(
    "paused",
    "resumed",
    "nothing_to_pause",
    "nothing_to_resume",
    "left_as_it_was",
    "would_not_stop",
    "would_not_start",
)

/** The two live pause actions this device may send for a single sandbox. */
internal enum class PauseAction { Pause, Resume }

/** One sandbox row inside a host's `pause.state`. */
internal data class SandboxPauseRow(
    val sandbox: String,
    val paused: Boolean,
    val state: String,
    val why: String? = null,
    val unreadable: Boolean = false,
)

/** Latest canonical `pause.state` for one host, kept in memory only. */
internal data class HostPauseState(
    val paused: Boolean,
    val partly: Boolean,
    val waiting: Boolean?,
    val since: String?,
    val by: String?,
    val revision: Long,
    val sandboxes: Map<String, SandboxPauseRow>,
    val unreadable: Boolean = false,
) {
    fun row(sandbox: String): SandboxPauseRow? = sandboxes[sandbox]

    /** Number of other sandboxes currently paused in this snapshot. */
    fun otherPausedCount(sandbox: String): Int =
        sandboxes.values.count { it.sandbox != sandbox && !it.unreadable && it.paused }
}

/** One sandbox outcome inside a correlated `pause.result`. */
internal data class PauseResultRow(val sandbox: String, val outcome: String)

/** Parsed `pause.result`; any unknown outcome makes it unreadable, never success. */
internal data class PauseResult(
    val of: String,
    val paused: Boolean,
    val partly: Boolean,
    val revision: Long,
    val sandboxes: List<PauseResultRow>,
    val unreadable: Boolean = false,
)

/** The single live pause/resume request currently in flight for a host. */
internal data class PauseInFlight(
    val requestId: String,
    val host: String,
    val sandbox: String,
    val action: PauseAction,
)

/** Request-correlated notice or failure for a sandbox's pause block. */
internal sealed interface PauseNotice {
    data object ResumedByThisDevice : PauseNotice
    data object WouldNotStop : PauseNotice
    data object WouldNotStart : PauseNotice
    data object Conflict : PauseNotice
    data object NotFound : PauseNotice
    data object InternalError : PauseNotice
    data object UnreadableResult : PauseNotice
    data class Disconnected(val action: PauseAction) : PauseNotice
}

/** Builds a single-sandbox `pause` request; omits `revision` when none has been seen. */
internal fun pauseRequest(sandbox: String, revision: Long?): JSONObject =
    message("pause", "revision" to revision, "sandboxes" to listOf(sandbox))

/** Builds a single-sandbox `resume` request; omits `revision` when none has been seen. */
internal fun resumeRequest(sandbox: String, revision: Long?): JSONObject =
    message("resume", "revision" to revision, "sandboxes" to listOf(sandbox))

private fun wholeLong(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

private fun unreadableHostState() = HostPauseState(
    paused = false,
    partly = false,
    waiting = null,
    since = null,
    by = null,
    revision = 0L,
    sandboxes = emptyMap(),
    unreadable = true,
)

/** Parses a `pause.state` message; an unknown `why` marks that row unreadable. */
internal fun parsePauseState(m: JSONObject): HostPauseState {
    val paused = m.opt("paused") as? Boolean ?: return unreadableHostState()
    val partly = m.opt("partly") as? Boolean ?: return unreadableHostState()
    val revision = wholeLong(m.opt("revision")) ?: return unreadableHostState()
    val waiting = when {
        !m.has("waiting") || m.isNull("waiting") -> null
        m.opt("waiting") is Boolean -> m.getBoolean("waiting")
        else -> return unreadableHostState()
    }
    val since = if (m.has("since") && !m.isNull("since")) m.opt("since") as? String else null
    val by = if (m.has("by") && !m.isNull("by")) m.opt("by") as? String else null
    val list = m.opt("sandboxes") as? JSONArray ?: return unreadableHostState()
    val rows = linkedMapOf<String, SandboxPauseRow>()
    for (i in 0 until list.length()) {
        val item = list.opt(i) as? JSONObject ?: return unreadableHostState()
        val name = (item.opt("sandbox") as? String)?.takeIf { it.isNotBlank() } ?: return unreadableHostState()
        val rowPaused = item.opt("paused") as? Boolean
        val rowState = (item.opt("state") as? String)?.takeIf { it.isNotBlank() }
        val rawWhy = if (!item.has("why") || item.isNull("why")) null else item.opt("why")
        val whyValid = rawWhy == null || (rawWhy is String && rawWhy in PAUSE_WHY_WORDS)
        rows[name] = if (rowPaused == null || rowState == null || !whyValid) {
            SandboxPauseRow(sandbox = name, paused = false, state = "unreadable", why = null, unreadable = true)
        } else {
            SandboxPauseRow(sandbox = name, paused = rowPaused, state = rowState, why = rawWhy as? String)
        }
    }
    return HostPauseState(
        paused = paused,
        partly = partly,
        waiting = waiting,
        since = since,
        by = by,
        revision = revision,
        sandboxes = rows,
    )
}

/** Parses a `pause.result` message; any unknown `outcome` marks the result unreadable. */
internal fun parsePauseResult(m: JSONObject): PauseResult {
    val of = (m.opt("of") as? String)?.takeIf { it.isNotBlank() } ?: ""
    val paused = m.opt("paused") as? Boolean
    val partly = m.opt("partly") as? Boolean
    val revision = wholeLong(m.opt("revision"))
    val list = m.opt("sandboxes") as? JSONArray
    if (of.isBlank() || paused == null || partly == null || revision == null || list == null) {
        return PauseResult(of = of, paused = false, partly = false, revision = 0L, sandboxes = emptyList(), unreadable = true)
    }
    val rows = mutableListOf<PauseResultRow>()
    for (i in 0 until list.length()) {
        val item = list.opt(i) as? JSONObject
        val name = (item?.opt("sandbox") as? String)?.takeIf { it.isNotBlank() }
        val outcome = item?.opt("outcome") as? String
        if (name == null || outcome == null || outcome !in PAUSE_OUTCOMES) {
            return PauseResult(of = of, paused = paused, partly = partly, revision = revision, sandboxes = emptyList(), unreadable = true)
        }
        rows += PauseResultRow(sandbox = name, outcome = outcome)
    }
    return PauseResult(of = of, paused = paused, partly = partly, revision = revision, sandboxes = rows)
}

/**
 * In-memory per-host pause state and single-sandbox pause/resume request tracking.
 * Dropped on disconnect, host removal, or when capabilities change.
 */
internal class PauseClient(
    private val connected: (String) -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
    private val refreshSandboxes: (String) -> Unit = {},
    private val later: (Long, () -> Unit) -> Unit = { _, _ -> },
) {
    val states = mutableStateMapOf<String, HostPauseState>()
    private val hostCapabilities = mutableStateMapOf<String, Set<String>>()
    val inFlight = mutableStateMapOf<String, PauseInFlight>()
    val notices = mutableStateMapOf<String, PauseNotice>()
    val notAllowed = mutableStateMapOf<String, Boolean>()

    fun hasBothCapabilities(host: String): Boolean {
        val caps = hostCapabilities[host].orEmpty()
        return PAUSE_CAPABILITY in caps && PAUSE_SUBSET_CAPABILITY in caps
    }

    fun canControl(host: String): Boolean =
        connected(host) && hasBothCapabilities(host) && notAllowed[host] != true

    fun isPaused(host: String, sandbox: String): Boolean {
        val state = states[host] ?: return false
        if (state.unreadable) return false
        val row = state.row(sandbox) ?: return false
        return !row.unreadable && row.paused
    }

    fun isUnreadable(host: String, sandbox: String): Boolean {
        val state = states[host] ?: return false
        return state.unreadable || state.row(sandbox)?.unreadable == true
    }

    fun authenticated(host: String, capabilities: Set<String>) {
        inFlight.remove(host)
        states.remove(host)
        notAllowed.remove(host)
        hostCapabilities[host] = capabilities
        if (PAUSE_CAPABILITY !in capabilities) {
            clearNotices(host)
        }
    }

    fun ended(host: String) {
        hostCapabilities.remove(host)
        states.remove(host)
        notAllowed.remove(host)
        val pending = inFlight.remove(host)
        clearNotices(host)
        if (pending != null) {
            notices["${pending.host}/${pending.sandbox}"] = PauseNotice.Disconnected(pending.action)
        }
    }

    fun forget(host: String) {
        hostCapabilities.remove(host)
        states.remove(host)
        inFlight.remove(host)
        notAllowed.remove(host)
        clearNotices(host)
    }

    fun pause(host: String, sandbox: String): Boolean = submit(host, sandbox, PauseAction.Pause)

    fun resume(host: String, sandbox: String): Boolean = submit(host, sandbox, PauseAction.Resume)

    private fun submit(host: String, sandbox: String, action: PauseAction): Boolean {
        if (!canControl(host)) return false
        if (inFlight[host] != null) return false
        val current = states[host]
        if (current != null && (current.unreadable || current.row(sandbox)?.unreadable == true)) return false
        val revision = current?.revision
        val request = if (action == PauseAction.Pause) pauseRequest(sandbox, revision) else resumeRequest(sandbox, revision)
        val id = request.getString("id")
        val key = "$host/$sandbox"
        notices.remove(key)
        val pending = PauseInFlight(requestId = id, host = host, sandbox = sandbox, action = action)
        inFlight[host] = pending
        if (!send(host, request)) {
            inFlight.remove(host)
            notices[key] = PauseNotice.Disconnected(action)
            return false
        }
        later(PAUSE_TIMEOUT_MS) {
            if (inFlight[host]?.requestId == id) {
                inFlight.remove(host)
                notices[key] = PauseNotice.Disconnected(action)
            }
        }
        return true
    }

    /** Consumes `pause.state`, `pause.result`, and correlated `error` frames. */
    fun received(host: String, m: JSONObject): Boolean = when (m.optString("type")) {
        "pause.state" -> {
            val parsed = parsePauseState(m)
            states[host] = parsed
            val prefix = "$host/"
            notices.entries.filter { it.key.startsWith(prefix) }.toList().forEach { (key, notice) ->
                val sandbox = key.removePrefix(prefix)
                val row = parsed.row(sandbox)
                val clear = when (notice) {
                    is PauseNotice.Conflict,
                    is PauseNotice.Disconnected,
                    is PauseNotice.InternalError,
                    -> true
                    is PauseNotice.ResumedByThisDevice -> row?.paused == true
                    is PauseNotice.WouldNotStop -> row?.paused == true
                    is PauseNotice.WouldNotStart -> row != null && !row.unreadable && !row.paused && row.state == "running"
                    is PauseNotice.NotFound,
                    is PauseNotice.UnreadableResult,
                    -> false
                }
                if (clear) notices.remove(key)
            }
            true
        }
        "pause.result" -> {
            val of = m.optString("of")
            val pending = inFlight[host]
            if (pending == null || pending.requestId != of) return true
            inFlight.remove(host)
            val parsed = parsePauseResult(m)
            val key = "$host/${pending.sandbox}"
            val row = parsed.sandboxes.firstOrNull { it.sandbox == pending.sandbox }
            if (parsed.unreadable || row == null) {
                notices[key] = PauseNotice.UnreadableResult
            } else {
                when (row.outcome) {
                    "would_not_stop" -> notices[key] = PauseNotice.WouldNotStop
                    "would_not_start" -> notices[key] = PauseNotice.WouldNotStart
                    "resumed" -> notices[key] = PauseNotice.ResumedByThisDevice
                    "paused", "nothing_to_pause", "nothing_to_resume", "left_as_it_was" -> notices.remove(key)
                    else -> notices[key] = PauseNotice.UnreadableResult
                }
            }
            true
        }
        "error" -> {
            val of = m.optString("of")
            val pending = inFlight[host]
            if (pending == null || pending.requestId != of) return false
            inFlight.remove(host)
            val key = "$host/${pending.sandbox}"
            when (m.optString("code")) {
                "conflict" -> notices[key] = PauseNotice.Conflict
                "not_found" -> {
                    notices[key] = PauseNotice.NotFound
                    refreshSandboxes(host)
                }
                "not_allowed" -> {
                    notAllowed[host] = true
                    notices.remove(key)
                }
                "operation_failed" -> {
                    notices[key] = if (pending.action == PauseAction.Pause) {
                        PauseNotice.WouldNotStop
                    } else {
                        PauseNotice.WouldNotStart
                    }
                }
                else -> notices[key] = PauseNotice.InternalError
            }
            true
        }
        else -> false
    }

    private fun clearNotices(host: String) {
        val prefix = "$host/"
        notices.keys.filter { it.startsWith(prefix) }.forEach { notices.remove(it) }
    }

    companion object {
        internal const val PAUSE_TIMEOUT_MS = 30_000L
    }
}

/** Button spec for the sandbox pause block. */
internal data class PauseBlockButton(
    val label: String,
    val enabled: Boolean,
    val action: PauseAction,
)

/** Pure view-model for the sandbox page's pause block. Null means render nothing new. */
internal data class SandboxPauseBlockState(
    val heading: String,
    val headingDescription: String,
    val stateLine: String,
    val button: PauseBlockButton? = null,
    val notice: String? = null,
)

/**
 * Pure projection of the sandbox pause block for every state in the approved table.
 */
internal fun sandboxPauseBlock(
    host: String,
    sandbox: String,
    connected: Boolean,
    hasBothCapabilities: Boolean,
    notAllowed: Boolean,
    hostState: HostPauseState?,
    listedState: String?,
    hasRuntime: Boolean,
    inFlight: PauseInFlight?,
    notice: PauseNotice?,
): SandboxPauseBlockState? {
    val heading = "PAUSE ${sandbox.uppercase(Locale.ROOT)}"
    val headingDescription = "Pause $sandbox"
    val canControl = connected && hasBothCapabilities && !notAllowed
    val row = hostState?.row(sandbox)
    val thisInFlight = inFlight?.takeIf { it.sandbox == sandbox }
    val otherInFlight = inFlight != null && inFlight.sandbox != sandbox

    if (!connected) {
        val disconnectNotice = (notice as? PauseNotice.Disconnected)?.let {
            "The ${if (it.action == PauseAction.Pause) "pause" else "resume"} may have reached $host. Refreshing…"
        }
        return SandboxPauseBlockState(
            heading = heading,
            headingDescription = headingDescription,
            stateLine = "Connect to $host to see whether $sandbox is paused.",
            button = null,
            notice = disconnectNotice,
        )
    }

    if (hostState?.unreadable == true || row?.unreadable == true ||
        notice is PauseNotice.InternalError || notice is PauseNotice.UnreadableResult
    ) {
        return SandboxPauseBlockState(
            heading = heading,
            headingDescription = headingDescription,
            stateLine = "$host could not tell whether $sandbox is paused. Nothing was changed.",
            button = null,
            notice = null,
        )
    }

    val isPaused = row?.paused ?: (listedState == "paused")

    if (!hasBothCapabilities && !notAllowed) {
        return if (isPaused) {
            SandboxPauseBlockState(
                heading = heading,
                headingDescription = headingDescription,
                stateLine = "$sandbox is paused. Its runtime is stopped.",
                button = null,
                notice = null,
            )
        } else {
            null
        }
    }

    val effectiveState = row?.state ?: listedState
    val isStopped = !isPaused && (!hasRuntime || (effectiveState != null && effectiveState != "running"))

    if (thisInFlight == null && (notice is PauseNotice.WouldNotStop || (!isPaused && row?.why == "would_not_stop"))) {
        return SandboxPauseBlockState(
            heading = heading,
            headingDescription = headingDescription,
            stateLine = "Still running · $sandbox could not be paused.",
            button = if (canControl) PauseBlockButton("Pause every agent in $sandbox", enabled = !otherInFlight, action = PauseAction.Pause) else null,
            notice = "$host could not stop $sandbox. Pausing again tries it. The rest of $host is unaffected.",
        )
    }

    if (thisInFlight == null && (notice is PauseNotice.WouldNotStart || (isPaused && row?.why == "would_not_start"))) {
        return SandboxPauseBlockState(
            heading = heading,
            headingDescription = headingDescription,
            stateLine = "Still stopped · $sandbox did not start.",
            button = if (canControl) PauseBlockButton("Resume every agent in $sandbox", enabled = !otherInFlight, action = PauseAction.Resume) else null,
            notice = "Resuming again tries it. The rest of $host stays paused.",
        )
    }

    if (isPaused) {
        val button = if (!canControl) {
            null
        } else {
            when (thisInFlight?.action) {
                PauseAction.Resume -> PauseBlockButton("Resuming…", enabled = false, action = PauseAction.Resume)
                PauseAction.Pause -> PauseBlockButton("Pausing…", enabled = false, action = PauseAction.Pause)
                null -> PauseBlockButton("Resume every agent in $sandbox", enabled = !otherInFlight, action = PauseAction.Resume)
            }
        }
        val noticeText = if (thisInFlight != null) {
            null
        } else {
            when (notice) {
                is PauseNotice.Conflict -> "$host's state changed since you looked. Refreshing…"
                is PauseNotice.NotFound -> "$sandbox is no longer listed on $host."
                is PauseNotice.Disconnected -> "The ${if (notice.action == PauseAction.Pause) "pause" else "resume"} may have reached $host. Refreshing…"
                else -> when {
                    hostState?.paused == true -> "The rest of $host stays paused; the host's page resumes all of it."
                    hostState?.partly == true -> "The rest of $host keeps running either way."
                    else -> null
                }
            }
        }
        return SandboxPauseBlockState(
            heading = heading,
            headingDescription = headingDescription,
            stateLine = "$sandbox is paused. Its runtime is stopped.",
            button = button,
            notice = noticeText,
        )
    }

    if (isStopped) {
        return SandboxPauseBlockState(
            heading = heading,
            headingDescription = headingDescription,
            stateLine = "$sandbox is stopped. There is nothing running to pause.",
            button = null,
            notice = "The rest of $host is unaffected.",
        )
    }

    val button = if (!canControl) {
        null
    } else {
        when (thisInFlight?.action) {
            PauseAction.Pause -> PauseBlockButton("Pausing…", enabled = false, action = PauseAction.Pause)
            PauseAction.Resume -> PauseBlockButton("Resuming…", enabled = false, action = PauseAction.Resume)
            null -> PauseBlockButton("Pause every agent in $sandbox", enabled = !otherInFlight, action = PauseAction.Pause)
        }
    }
    val noticeText = if (thisInFlight != null) {
        null
    } else {
        when (notice) {
            is PauseNotice.Conflict -> "$host's state changed since you looked. Refreshing…"
            is PauseNotice.NotFound -> "$sandbox is no longer listed on $host."
            is PauseNotice.Disconnected -> "The ${if (notice.action == PauseAction.Pause) "pause" else "resume"} may have reached $host. Refreshing…"
            is PauseNotice.ResumedByThisDevice -> {
                val others = hostState?.otherPausedCount(sandbox) ?: 0
                if (others > 0) {
                    val suffix = if (others == 1) "1 sandbox stays paused." else "$others sandboxes stay paused."
                    "Resumed · $sandbox runs again, $suffix"
                } else {
                    null
                }
            }
            else -> null
        }
    }
    return SandboxPauseBlockState(
        heading = heading,
        headingDescription = headingDescription,
        stateLine = "Running · nothing of $sandbox is paused.",
        button = button,
        notice = noticeText,
    )
}

/** Formats a sandbox's last-day usage for the pause confirmation dialog; null when none or zero. */
internal fun sandboxLastDayUsageText(recent: RecentUsage?, sandbox: String): String? {
    if (recent == null) return null
    val prefix = "$sandbox/"
    val rows = recent.agents.filterKeys { it.startsWith(prefix) }.values
    if (rows.isEmpty()) return null
    val sum = rows.fold(UsageTotal()) { acc, row -> acc + row.total }
    return when {
        sum.tokens.counted && sum.tokens.total > 0L -> "${tokensText(sum.tokens.total)} tokens"
        sum.calls > 0L -> if (sum.calls == 1L) "1 call" else "${sum.calls} calls"
        else -> null
    }
}

/** Builds the paragraphs of the pause confirmation dialog in the approved order. */
internal fun pauseConfirmParagraphs(
    host: String,
    sandbox: String,
    agentCount: Int,
    lastDayUsage: String?,
    waitingQuestions: Int,
    waitingHeldTasks: Int,
): List<String> {
    val agentsClause = if (agentCount == 1) "1 agent runs" else "$agentCount agents run"
    val usageClause = if (lastDayUsage != null) ", and they used $lastDayUsage in the last 24 hours" else ""
    val paragraphs = mutableListOf(
        "$agentsClause in $sandbox$usageClause. Pausing stops the runtime of $sandbox now, including any answer being written. The rest of $host keeps running and keeps spending.",
        "Its files stay on $host: a pause does not write into any sandbox home. A question an agent is waiting on stops with it.",
        "Until the next update, $host can still start $sandbox again.",
    )
    val waitingParts = listOfNotNull(
        waitingQuestions.takeIf { it > 0 }?.let { if (it == 1) "1 question" else "$it questions" },
        waitingHeldTasks.takeIf { it > 0 }?.let { if (it == 1) "1 held task" else "$it held tasks" },
    )
    if (waitingParts.isNotEmpty()) {
        paragraphs += "Waiting for you in $sandbox: ${waitingParts.joinToString(" and ")}. Pausing does not answer them."
    }
    return paragraphs
}

/** Sandbox-level status override for an agent row in the Agents list and sandbox page. */
internal fun sandboxAgentRowStatus(
    hostState: HostPauseState?,
    listedState: String?,
    sandbox: String,
): String? {
    if (hostState?.unreadable == true) return "not known"
    val row = hostState?.row(sandbox)
    if (row != null) {
        return when {
            row.unreadable -> "not known"
            row.paused -> "paused"
            row.state != "running" -> "stopped"
            else -> null
        }
    }
    return when {
        listedState == "paused" -> "paused"
        listedState != null && listedState != "running" -> "stopped"
        else -> null
    }
}

/** Sandbox heading's note in the Agents list: `paused · <runtime version>` when paused. */
internal fun sandboxHeadingNote(sandbox: Sandbox, paused: Boolean): String {
    val runtime = runtimeLine(sandbox)
        ?.let { if (sandbox.update != null) "$it · update available" else it }
    return when {
        paused && runtime != null -> "paused · $runtime"
        paused -> "paused"
        else -> runtime ?: "No runtime installed"
    }
}

/** Notice shown on an agent's page when its sandbox is paused. */
internal fun agentPausedNotice(sandbox: String): String =
    "$sandbox is paused. Its runtime is stopped."
