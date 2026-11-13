package io.github.victorbonnet.enclos

/**
 * A conversation's live steps after a `tool` message for [call]: a start
 * adds the step once (a repeated start changes nothing); an end marks it
 * done with its outcome, or adds it done when its start was missed.
 */
fun stepped(now: List<Step>, call: String, phase: String, name: String, label: String?, ok: Boolean, durationMs: Long?): List<Step> {
    val at = now.indexOfFirst { it.call == call }
    return if (phase == "end") {
        val ended = { s: Step -> s.copy(done = true, ok = ok, durationMs = durationMs) }
        if (at >= 0) now.toMutableList().also { it[at] = ended(it[at]) } else now + ended(Step(call, name, null))
    } else if (at < 0) {
        now + Step(call, name, label)
    } else {
        now
    }
}

/**
 * The turn ended (`typing` off): a step whose end never came is done, so
 * nothing shows the agent at work any more. The steps stay for an answer
 * that follows; the person's next message starts a turn without them.
 */
fun settled(now: List<Step>): List<Step> = now.map { if (it.done) it else it.copy(done = true) }
