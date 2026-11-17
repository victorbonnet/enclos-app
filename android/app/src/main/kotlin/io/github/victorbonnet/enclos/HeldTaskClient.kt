package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.util.UUID

/** One `held_task.resume`: the card as it was when the person tapped Resume, and the request's id. */
data class HeldTaskRequest(val id: String, val card: HeldTask) {
    /** Whether [incoming], from [host], is the card this request named. */
    fun matches(host: String, incoming: HeldTask): Boolean =
        host == card.host && incoming.key == card.key && incoming.conversation == card.conversation

    /** `held_task.resume` with the card's agent, task and bridge revision, under this request's id. */
    fun command(): JSONObject =
        message("held_task.resume", "agent" to card.agent, "task" to card.task, "revision" to card.revision).put("id", id)
}

/**
 * Held tasks' cards and their one action, Resume (docs/spec.md, Held tasks). Keeps the newest
 * revision of each card, sends a resume only on the person's tap, tracks it by its id, and
 * never queues or sends one again on its own. An outcome it cannot be sure of asks the host
 * for a sync, and Resume waits for it: `unconfirmed`, `conflict`, `internal`, an error code
 * this app does not know, a `held_task.result` it cannot read, no answer in [ANSWER_WAIT_MS],
 * or a connection that ended before the answer.
 */
class HeldTaskClient(
    private val send: (HeldTaskRequest) -> Boolean,
    private val connected: (String) -> Boolean,
    /**
     * Whether a card may be kept: of a paired host, and not of a deleted conversation. Its Home
     * need not be known yet: [current] and [unlinked] show it once the Home is.
     */
    private val accepts: (HeldTask) -> Boolean,
    /** A card changed (not from the cache): saved. */
    private val changed: (HeldTask) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
    /** Asks the host for a fresh `sync` over its open connection; false when it could not be sent. */
    private val resync: (String) -> Boolean = { false },
    /** Why a card was not shown (never its text), for the log. */
    private val dropped: (String) -> Unit = {},
) {
    val cards = mutableStateMapOf<HeldTaskKey, HeldTask>()
    /** The resume this phone sent for a card, until its answer or its doubt. */
    val pending = mutableStateMapOf<HeldTaskKey, HeldTaskRequest>()
    /** What the card says of the last resume: its outcome, or why it did not go. */
    val notes = mutableStateMapOf<HeldTaskKey, String>()
    /** Cards whose resume may or may not have happened: no Resume until a sync ends. */
    val uncertain = mutableStateMapOf<HeldTaskKey, Boolean>()
    /** Cards this phone resumed, confirmed by the runtime: the request's id. */
    val receipts = mutableStateMapOf<HeldTaskKey, String>()
    /**
     * Cards the host answered `not_found` for, by the revision then current: the host (or its
     * runtime) no longer knows the task, so no Resume, until the bridge sends a newer card.
     */
    val gone = mutableStateMapOf<HeldTaskKey, Long>()
    private val unsupported = mutableSetOf<HeldTaskKey>()
    private val requests = mutableMapOf<String, HeldTaskRequest>()
    /** By host: whether a sync ended since the connection authenticated, or since this client asked for one. */
    private val fresh = mutableStateMapOf<String, Boolean>()

    /** The card of [task] in [conversation], when it is that agent's Home. */
    fun current(conversation: Conversation, task: String?): HeldTask? =
        task?.let { cards[HeldTaskKey(conversation.host, conversation.agent, it)] }?.takeIf { it.belongsTo(conversation) }

    /**
     * The cards of [conversation], an agent's Home, that no message of it in [linked] (the tasks
     * whose introduction is loaded, [heldTaskIntroductions]) draws: still held, or with a resume
     * whose outcome is still shown. A task held before this phone kept messages' links (an
     * older app) has no linked message, and a sync sends its card, not that message again.
     * Oldest first.
     */
    fun unlinked(conversation: Conversation, linked: Set<String>): List<HeldTask> = cards.values
        .filter { it.belongsTo(conversation) && it.task !in linked }
        .filter { it.state == HeldTaskState.Held || it.key in pending || it.key in uncertain || it.key in notes }
        .sortedWith(compareBy({ it.since }, { it.task }))

    /** Whether the card offers Resume at all: only while held, and not after the host said it has no such task. */
    fun offersResume(card: HeldTask): Boolean = card.state == HeldTaskState.Held && card.resumable && card.key !in gone

    /** What the card says of its state. */
    fun status(card: HeldTask): String = heldTaskStatus(card, here = card.key in receipts, gone = card.key in gone)

    /** Why Resume cannot be tapped now, or null when it can. */
    fun unavailable(card: HeldTask): String? = when {
        card.state == HeldTaskState.Held && !card.resumable -> "This task cannot be resumed from the app."
        !offersResume(card) -> "This task is no longer held."
        card.key in pending -> "Waiting for the runtime to confirm…"
        uncertain[card.key] == true && !connected(card.host) -> "Reconnect to ${card.host} to check whether it resumed."
        uncertain[card.key] == true -> "Checking with ${card.host}…"
        !connected(card.host) -> "Connect to ${card.host} to resume it."
        fresh[card.host] != true -> "Waiting for the host to sync…"
        else -> null
    }

    fun canResume(card: HeldTask): Boolean = offersResume(card) && unavailable(card) == null

    fun merge(incoming: HeldTask, cached: Boolean = false): Boolean {
        if (!accepts(incoming)) {
            dropped("held task ${incoming.task} of ${incoming.agent} from ${incoming.host}: not of a paired host, or of a deleted conversation")
            return false
        }
        val current = cards[incoming.key]
        val selected = newestHeldTask(current, incoming)
        if (selected != current) {
            if (unsupported.remove(incoming.key)) notes.remove(incoming.key)
            cards[incoming.key] = selected
            // A newer card from the bridge replaces what `not_found` said.
            gone.remove(selected.key)
            // A card that left `held` elsewhere: what the last attempt said no longer applies.
            if (current != null && selected.state != current.state && selected.key !in pending) {
                notes.remove(selected.key)
                receipts.remove(selected.key)
            }
            if (!cached) changed(selected)
        }
        return true
    }

    /**
     * Resumes [opening], the card as the person saw it, when it is still the current one and
     * Resume is available. The request's id, or null when nothing was sent.
     */
    fun resume(opening: HeldTask): String? {
        val current = cards[opening.key] ?: return null
        if (current != opening || !canResume(current)) return null
        val request = HeldTaskRequest(UUID.randomUUID().toString(), current)
        requests[request.id] = request
        pending[current.key] = request
        unsupported.remove(current.key)
        notes.remove(current.key)
        receipts.remove(current.key)
        if (!send(request)) {
            finish(request)
            notes[current.key] = "Not sent. Reconnect and try again."
            return null
        }
        // The bridge answers `unconfirmed` after 25 s; this is for an answer that never comes.
        later(ANSWER_WAIT_MS) {
            if (requests[request.id] == request && pending[current.key] == request) {
                // Kept in [requests]: a late answer still settles the card.
                pending.remove(current.key)
                doubt(current.host, current.key, "No answer from ${current.host}. The task may have resumed.")
            }
        }
        return request.id
    }

    /** `held_task.result`: the runtime resumed the task this phone's request [of] named. */
    fun result(host: String, of: String, incoming: HeldTask): Boolean {
        val request = requests[of] ?: return false
        if (!request.matches(host, incoming)) return false
        finish(request)
        merge(incoming)
        uncertain.remove(incoming.key)
        // The card's state line says it: "Resumed from this phone".
        notes.remove(incoming.key)
        if (incoming.state == HeldTaskState.Resumed) receipts[incoming.key] = request.id
        return true
    }

    /** An `error` answering this phone's request [of]: what it means for the card (enclos docs/protocol.md, Held tasks). */
    fun failure(host: String, of: String, code: String, said: String = ""): Boolean {
        val request = requests[of]?.takeIf { it.card.host == host } ?: return false
        finish(request)
        val key = request.card.key
        val quoted = Visible.stripped(said.replace(Regex("\\s+"), " ")).trim().take(200)
        when (code) {
            // Refused before anything was sent to the runtime: certain, and Resume stays.
            "agent_unavailable" -> notes[key] = "The agent is not connected, so nothing was sent. Try again once it is back."
            "not_allowed" -> notes[key] = "Only the host's person can resume held tasks."
            "unsupported" -> {
                unsupported.add(key)
                notes[key] = "This request could not resume the task."
                fresh[host] = false
                if (connected(host)) resync(host)
            }
            "bad_message", "unknown_type" ->
                notes[key] = "The bridge on $host cannot resume held tasks. Update enclos there."
            // The bridge has no such card, or the runtime no such task (the plugin asked it):
            // certain, and nothing left to resume. The card says so until a newer one comes.
            "not_found" -> {
                gone[key] = request.card.revision
                notes.remove(key)
            }
            // The card changed since it was shown: its current state comes with the sync.
            "conflict" -> doubt(host, key, "The task changed since this card was shown.")
            // The task may have resumed: sync before deciding again, never resend.
            "unconfirmed" -> doubt(host, key, "The task may have resumed.")
            "internal" -> doubt(host, key, "The runtime could not confirm the resume" +
                (if (quoted.isNotEmpty()) " ($host said: $quoted)" else "") + ".")
            // A code this app does not know is no proof that nothing happened.
            else -> doubt(host, key, "$host answered ${Visible.stripped(code).take(64).ifEmpty { "an error" }}" +
                (if (quoted.isNotEmpty()) ": $quoted" else "") + ". The task may have resumed.")
        }
        return true
    }

    fun received(host: String, message: JSONObject): Boolean = when (message.optString("type")) {
        "held_task" -> { parsed(host, message)?.let { merge(it) }; true }
        "held_task.result" -> {
            val of = message.optString("of")
            val card = parsed(host, message)
            if (card != null) {
                result(host, of, card)
            } else {
                // An answer to this phone's resume that cannot be read: the outcome is not known.
                requests[of]?.takeIf { it.card.host == host }?.let { request ->
                    finish(request)
                    doubt(host, request.card.key, "The answer from $host could not be read. The task may have resumed.")
                }
            }
            true
        }
        "error" -> failure(host, message.optString("of"), message.optString("code"), message.optString("message"))
        else -> false
    }

    private fun parsed(host: String, message: JSONObject): HeldTask? =
        runCatching { HeldTask.from(host, message.getJSONObject("held_task")) }
            .onFailure { dropped("${message.optString("type")} from $host could not be read: ${it.javaClass.simpleName}") }
            .getOrNull()

    /** A connection to [host] authenticated: its cards are not fresh until its sync ends. */
    fun authenticated(host: String) { fresh[host] = false }

    /** [host]'s sync ended: each card it sent is current, and the doubt of every card of the host is settled by it. */
    fun synced(host: String) {
        fresh[host] = true
        unsupported.filter { it.host == host }.toList().forEach { key ->
            unsupported.remove(key)
            notes.remove(key)
        }
        uncertain.keys.filter { it.host == host }.forEach { key ->
            uncertain.remove(key)
            val card = cards[key] ?: return@forEach
            // A card that left `held` says so in its state line.
            if (offersResume(card)) notes[key] = "Checked: $host still lists it as held." else notes.remove(key)
        }
    }

    /** [host]'s connection ended: a resume still unanswered may have happened, and is not sent again. */
    fun ended(host: String) {
        fresh[host] = false
        requests.values.filter { it.card.host == host }.toList().forEach { request ->
            val waiting = pending[request.card.key] == request
            finish(request)
            if (waiting) {
                uncertain[request.card.key] = true
                notes[request.card.key] = "The connection ended before the runtime confirmed. The task may have resumed; reconnect to check."
            }
        }
    }

    fun forgetConversation(host: String, conversation: String) {
        cards.values.filter { it.host == host && it.conversation == conversation }.map { it.key }.forEach(::forgetCard)
    }

    fun forget(host: String) {
        requests.values.filter { it.card.host == host }.toList().forEach(::finish)
        cards.keys.filter { it.host == host }.forEach(::forgetCard)
        fresh.remove(host)
    }

    private fun forgetCard(key: HeldTaskKey) {
        pending[key]?.let(::finish)
        unsupported.remove(key)
        cards.remove(key); notes.remove(key); uncertain.remove(key); receipts.remove(key); gone.remove(key)
    }

    /**
     * The outcome of [key]'s resume is not known, [what] says why: Resume waits for a sync of
     * [host], asked for now over the open connection, else at the next one.
     */
    private fun doubt(host: String, key: HeldTaskKey, what: String) {
        uncertain[key] = true
        val asked = connected(host) && run {
            fresh[host] = false
            resync(host)
        }
        notes[key] = if (asked) "$what Refreshing…" else "$what Reconnect to $host to check."
    }

    private fun finish(request: HeldTaskRequest) {
        requests.remove(request.id)
        if (pending[request.card.key] == request) pending.remove(request.card.key)
    }

    internal companion object {
        /** How long the app waits for an answer before it doubts the outcome: past the bridge's own 25 s. */
        const val ANSWER_WAIT_MS = 35_000L
    }
}
