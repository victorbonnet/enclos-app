package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.util.UUID

/**
 * One request about a held card: `held_task.resume`, or `held_task.answer` when [answer] is set.
 * The card as it was when the person tapped, and the request's id.
 */
data class HeldTaskRequest(val id: String, val card: HeldTask, val answer: HeldTaskAnswer? = null) {
    /** Whether [incoming], from [host], is the card this request named. */
    fun matches(host: String, incoming: HeldTask): Boolean =
        host == card.host && incoming.key == card.key && incoming.conversation == card.conversation

    /** The request with the card's agent, task and bridge revision, under this request's id. */
    fun command(): JSONObject = when (answer) {
        null -> message("held_task.resume", "agent" to card.agent, "task" to card.task, "revision" to card.revision)
        else -> message("held_task.answer", "agent" to card.agent, "task" to card.task, "revision" to card.revision, "answer" to answer.json())
    }.put("id", id)
}

/**
 * A typed answer this app keeps, in memory only, for the Needs you sheet (docs/spec.md, Held
 * tasks): its [text], the [card] it was typed for, the host's connection [generation] then, its
 * [version], and the version a send was tried with ([attempted]), if any.
 */
data class HeldTaskDraft(val text: String, val card: HeldTask, val generation: Long, val version: Long, val attempted: Long? = null)

/** Why kept text no longer belongs to the current request. */
enum class HeldTaskEarlierReason { RequestChanged, ConnectionChanged }

/** Kept text that is not in **Your answer**, and why: the person reviews it before reusing it. */
data class HeldTaskEarlier(val text: String, val why: HeldTaskEarlierReason)

/**
 * Held tasks' cards and their actions: Resume and, where the bridge takes it, the person's answer
 * (docs/spec.md, Held tasks). Keeps the newest revision of each card, sends a request only on the
 * person's tap, tracks it by its id, and never queues or sends one again on its own. An outcome
 * it cannot be sure of asks the host for a sync, and the card's actions wait for it:
 * `unconfirmed`, `conflict`, `internal`, an error code this app does not know, a
 * `held_task.result` it cannot read, no answer in [ANSWER_WAIT_MS], or a connection that ended
 * before the answer.
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
    /**
     * By host: whether its last `authenticated` listed `held_task.answer`. Kept when the
     * connection ends, so the answer layout stays (its controls disabled, saying why) until the
     * next connection says otherwise; forgotten with the host.
     */
    private val answerHosts = mutableStateMapOf<String, Boolean>()
    val cards = mutableStateMapOf<HeldTaskKey, HeldTask>()
    /** The request this phone sent for a card, until its answer or its doubt. */
    val pending = mutableStateMapOf<HeldTaskKey, HeldTaskRequest>()
    /** What the card says of the last request: its outcome, or why it did not go. */
    val notes = mutableStateMapOf<HeldTaskKey, String>()
    /** Cards whose last request may or may not have happened: no action until a sync ends. */
    val uncertain = mutableStateMapOf<HeldTaskKey, Boolean>()
    /** Cards this phone resumed, confirmed by the runtime: the request's id. */
    val receipts = mutableStateMapOf<HeldTaskKey, String>()
    /** Cards this phone answered, confirmed by the host: the request's id. */
    val answered = mutableStateMapOf<HeldTaskKey, String>()
    /**
     * Cards the host answered `not_found` for, by the revision then current: the host (or its
     * runtime) no longer knows the task, so no action, until the bridge sends a newer card.
     */
    val gone = mutableStateMapOf<HeldTaskKey, Long>()
    /**
     * Cards the host sent a newer revision of that this app could not read, by that revision: the
     * kept card is stale, so no action (an answer would only meet `conflict`) until a readable
     * card of that revision or a newer one comes.
     */
    val unreadable = mutableStateMapOf<HeldTaskKey, Long>()
    /** Certain refusals awaiting sync: whether this connection sent the refresh. */
    private val unsupported = mutableStateMapOf<HeldTaskKey, Boolean>()
    /** Cards whose last request was an answer rather than a resume: what a doubt says. */
    private val lastAnswer = mutableStateMapOf<HeldTaskKey, Boolean>()
    private val requests = mutableMapOf<String, HeldTaskRequest>()
    /** By host: whether a sync ended since the connection authenticated, or since this client asked for one. */
    private val fresh = mutableStateMapOf<String, Boolean>()

    /**
     * By card: the text in **Your answer**, kept for the card and connection it was typed for.
     * Plain memory, never saved: it ends with this client (the app's model), and is never logged.
     */
    private val drafts = mutableStateMapOf<HeldTaskKey, HeldTaskDraft>()
    /**
     * By card: kept text whose request or connection changed since it was typed, oldest first;
     * shown one at a time, the newest, until the person uses or discards it.
     */
    private val earlierTexts = mutableStateMapOf<HeldTaskKey, List<HeldTaskDraft>>()
    /** By host: its connection's generation, advanced at each `authenticated`. */
    private val generations = mutableStateMapOf<String, Long>()
    private var lastGeneration = 0L
    private var lastVersion = 0L
    /** By request id: the version of the kept text that request sent. */
    private val sentDrafts = mutableMapOf<String, Long>()

    /**
     * The text in **Your answer** for [card]: kept only for this exact card (every field, as
     * [answer] compares it) on the host's current connection; else empty.
     */
    fun draftFor(card: HeldTask): String = drafts[card.key]?.takeIf { attached(it, card) }?.text.orEmpty()

    /** Whether a send was tried with the text in **Your answer** for [card], as it reads now. */
    fun draftAttempted(card: HeldTask): Boolean =
        drafts[card.key]?.takeIf { attached(it, card) }?.let { it.attempted == it.version } == true

    /**
     * The person typed [text] into **Your answer** for [card], the current card: kept for this
     * card and connection; empty text removes it. Earlier text stays apart. Sends nothing.
     */
    fun edit(card: HeldTask, text: String) {
        if (cards[card.key] != card) return
        drafts[card.key]?.takeIf { !attached(it, card) }?.let { keepEarlier(card.key, it) }
        if (text.isEmpty()) drafts.remove(card.key)
        else drafts[card.key] = HeldTaskDraft(text, card, generationOf(card.host), ++lastVersion)
    }

    /** The kept text of [card]'s task that is not in **Your answer**, and why; null when none. */
    fun earlier(card: HeldTask): HeldTaskEarlier? {
        val kept = earlierTexts[card.key]?.lastOrNull() ?: return null
        val why = if (kept.card != card) HeldTaskEarlierReason.RequestChanged else HeldTaskEarlierReason.ConnectionChanged
        return HeldTaskEarlier(kept.text, why)
    }

    /**
     * Puts the earlier text of [card] into its empty **Your answer**, only while [card] is the
     * current card and can be answered now; sends nothing. Whether it did.
     */
    fun useEarlier(card: HeldTask): Boolean {
        if (cards[card.key] != card || !canAnswer(card) || draftFor(card).isNotEmpty()) return false
        val list = earlierTexts[card.key] ?: return false
        val used = list.lastOrNull() ?: return false
        setEarlier(card.key, list.dropLast(1))
        drafts[card.key] = HeldTaskDraft(used.text, card, generationOf(card.host), ++lastVersion)
        return true
    }

    /** Drops the earlier text of [card] shown now. */
    fun discardEarlier(card: HeldTask) {
        earlierTexts[card.key]?.let { setEarlier(card.key, it.dropLast(1)) }
    }

    private fun generationOf(host: String): Long = generations[host] ?: 0L

    private fun attached(draft: HeldTaskDraft, card: HeldTask): Boolean =
        draft.card == card && cards[card.key] == card && draft.generation == generationOf(card.host)

    /** Moves [draft] apart, as earlier text; the same text kept twice is kept once. */
    private fun keepEarlier(key: HeldTaskKey, draft: HeldTaskDraft) {
        if (drafts[key] == draft) drafts.remove(key)
        setEarlier(key, earlierTexts[key].orEmpty().filter { it.text != draft.text } + draft)
    }

    private fun setEarlier(key: HeldTaskKey, list: List<HeldTaskDraft>) {
        if (list.isEmpty()) earlierTexts.remove(key) else earlierTexts[key] = list
    }

    /** The text in **Your answer** of [key], when it no longer belongs to the current card or connection: apart, as earlier text. */
    private fun detach(key: HeldTaskKey) {
        val draft = drafts[key] ?: return
        val card = cards[key]
        if (card == null || !attached(draft, card)) keepEarlier(key, draft)
    }

    /** Drops the kept text, in the field or earlier, of [key] that [version] sent. */
    private fun clearSent(key: HeldTaskKey, version: Long) {
        if (drafts[key]?.version == version) drafts.remove(key)
        earlierTexts[key]?.let { list -> setEarlier(key, list.filter { it.version != version }) }
    }

    /** The card of [task] in [conversation], when it is that agent's Home. */
    fun current(conversation: Conversation, task: String?): HeldTask? =
        task?.let { cards[HeldTaskKey(conversation.host, conversation.agent, it)] }?.takeIf { it.belongsTo(conversation) }

    /**
     * The cards of [conversation], an agent's Home, that no message of it in [linked] (the tasks
     * whose introduction is loaded, [heldTaskIntroductions]) draws: still held or answered, or
     * with a request whose outcome is still shown. A task held before this phone kept messages'
     * links (an older app) has no linked message, and a sync sends its card, not that message
     * again. Oldest first.
     */
    fun unlinked(conversation: Conversation, linked: Set<String>): List<HeldTask> = cards.values
        .filter { it.belongsTo(conversation) && it.task !in linked }
        .filter {
            it.state == HeldTaskState.Held || it.state == HeldTaskState.Answered ||
                it.key in pending || it.key in uncertain || it.key in notes
        }
        .sortedWith(compareBy({ it.since }, { it.task }))

    /** Whether the card offers Resume at all: only while held, and not after the host said it has no such task. */
    fun offersResume(card: HeldTask): Boolean = card.state == HeldTaskState.Held && card.resumable && card.key !in gone

    /**
     * Whether the card offers an answer at all (enclos ADR 0073): only while held, from a bridge
     * that takes one, and not after the host said it has no such task.
     */
    fun offersAnswer(card: HeldTask): Boolean =
        card.state == HeldTaskState.Held && card.key !in gone && answersOn(card.host)

    /** Whether [host]'s bridge, as its last connection said, takes a person's answer to a held card. */
    fun answersOn(host: String): Boolean = answerHosts[host] == true

    /**
     * Whether a Needs you sheet of [key] stays open once its row left the list: this phone's
     * answer is waiting, in doubt, or taken (the bridge sends the answered card and its index
     * before `held_task.result`), so the sheet can say what was answered.
     */
    fun keepsSheet(key: HeldTaskKey): Boolean =
        key in answered || (lastAnswer[key] == true && (key in pending || key in uncertain || cards[key]?.state == HeldTaskState.Answered))

    /** What the card says of its state. */
    fun status(card: HeldTask): String = heldTaskStatus(card, here = card.key in receipts, gone = card.key in gone)

    /** Why Resume cannot be tapped now, or null when it can. */
    fun unavailable(card: HeldTask): String? = when {
        card.state == HeldTaskState.Held && !card.resumable -> "This task cannot be resumed from the app."
        !offersResume(card) -> "This task is no longer held."
        else -> busy(card, "resume it")
    }

    /** Why the answer controls cannot be used now, or null when they can. */
    fun answerUnavailable(card: HeldTask): String? = when {
        !offersAnswer(card) -> "This task no longer waits for you."
        else -> busy(card, "answer")
    }

    /** What keeps either action from being tapped now: a request waiting, a doubt, the connection or the sync. */
    private fun busy(card: HeldTask, action: String): String? = when {
        card.key in unreadable -> "${card.host} sent a newer version of this task that this app cannot read. Update the app to $action."
        card.key in pending -> if (pending[card.key]?.answer != null) "Sending your answer…" else "Waiting for the runtime to confirm…"
        uncertain[card.key] == true && !connected(card.host) ->
            if (lastAnswer[card.key] == true) "Reconnect to ${card.host} to check whether your answer arrived."
            else "Reconnect to ${card.host} to check whether it resumed."
        uncertain[card.key] == true -> "Checking with ${card.host}…"
        card.key in unsupported && (!connected(card.host) || unsupported[card.key] != true) ->
            "Reconnect to ${card.host} to check the task."
        card.key in unsupported -> "Checking with ${card.host}…"
        !connected(card.host) -> "Connect to ${card.host} to $action."
        fresh[card.host] != true -> "Waiting for the host to sync…"
        else -> null
    }

    fun canResume(card: HeldTask): Boolean = offersResume(card) && unavailable(card) == null

    fun canAnswer(card: HeldTask): Boolean = offersAnswer(card) && answerUnavailable(card) == null

    fun merge(incoming: HeldTask, cached: Boolean = false): Boolean {
        if (!accepts(incoming)) {
            dropped("held task ${incoming.task} of ${incoming.agent} from ${incoming.host}: not of a paired host, or of a deleted conversation")
            return false
        }
        val current = cards[incoming.key]
        val selected = newestHeldTask(current, incoming)
        unreadable[selected.key]?.let { if (selected.revision >= it) unreadable.remove(selected.key) }
        if (selected != current) {
            if (unsupported.remove(incoming.key) != null) notes.remove(incoming.key)
            cards[incoming.key] = selected
            // Text typed for the card before is not this card's answer: kept apart, as earlier text.
            detach(selected.key)
            // A newer card from the bridge replaces what `not_found` said.
            gone.remove(selected.key)
            // A card that changed state elsewhere: what the last attempt said no longer applies.
            // A newer card: what the last attempt said was about the older one. A doubt keeps
            // its note until the sync ends and says what it found.
            if (current != null && selected.key !in pending && selected.key !in uncertain) notes.remove(selected.key)
            if (current != null && selected.state != current.state && selected.key !in pending) {
                notes.remove(selected.key)
                receipts.remove(selected.key)
                answered.remove(selected.key)
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
        return dispatch(current, null)
    }

    /**
     * Sends [answer] for [opening], the card as the person saw it, when it is still the current
     * one and its answer controls are available (enclos ADR 0073): a choice its ask offers, a
     * typed text the bridge takes ([heldAnswerText], sent trimmed), or the request to the agent
     * to look into it. The request's id, or null when nothing was sent.
     */
    fun answer(opening: HeldTask, answer: HeldTaskAnswer): String? {
        val current = cards[opening.key] ?: return null
        if (current != opening || !canAnswer(current)) return null
        val checked = when (answer) {
            is HeldTaskAnswer.Choice -> answer.takeIf { current.ask?.choices?.indices?.contains(it.index) == true }
            is HeldTaskAnswer.Text -> heldAnswerText(answer.text)?.let { HeldTaskAnswer.Text(it) }
            HeldTaskAnswer.Agent -> answer
        } ?: return null
        val id = dispatch(current, checked) ?: return null
        // The kept text this sends: a matching result clears that version only, never a newer one.
        if (checked is HeldTaskAnswer.Text) {
            drafts[current.key]?.takeIf { attached(it, current) && heldAnswerText(it.text) == checked.text }?.let {
                drafts[current.key] = it.copy(attempted = it.version)
                sentDrafts[id] = it.version
            }
        }
        return id
    }

    private fun dispatch(current: HeldTask, answer: HeldTaskAnswer?): String? {
        val request = HeldTaskRequest(UUID.randomUUID().toString(), current, answer)
        requests[request.id] = request
        pending[current.key] = request
        lastAnswer[current.key] = answer != null
        unsupported.remove(current.key)
        notes.remove(current.key)
        receipts.remove(current.key)
        answered.remove(current.key)
        if (!send(request)) {
            finish(request)
            notes[current.key] = "Not sent. Reconnect and try again."
            return null
        }
        // The bridge answers a resume `unconfirmed` after 25 s; this is for an answer that never comes.
        later(ANSWER_WAIT_MS) {
            if (requests[request.id] == request && pending[current.key] == request) {
                // Kept in [requests]: a late answer still settles the card.
                pending.remove(current.key)
                doubt(current.host, current.key, "No answer from ${current.host}. " + mayHave(request))
            }
        }
        return request.id
    }

    /** `held_task.result`: the runtime resumed the task, or the host took the answer, that this phone's request [of] named. */
    fun result(host: String, of: String, incoming: HeldTask): Boolean {
        val request = requests[of] ?: return false
        if (!request.matches(host, incoming)) return false
        val sentVersion = sentDrafts[request.id]
        finish(request)
        merge(incoming)
        uncertain.remove(incoming.key)
        // The card's state line says it: "Resumed from this phone", "Answered: …".
        notes.remove(incoming.key)
        if (request.answer == null && incoming.state == HeldTaskState.Resumed) receipts[incoming.key] = request.id
        if (request.answer != null && incoming.state == HeldTaskState.Answered) answered[incoming.key] = request.id
        // The host took the typed text: that text, unless edited since, is no longer kept.
        if (request.answer is HeldTaskAnswer.Text && incoming.state == HeldTaskState.Answered && sentVersion != null) {
            clearSent(incoming.key, sentVersion)
        }
        return true
    }

    /** An `error` answering this phone's request [of]: what it means for the card (enclos docs/protocol.md, Held tasks). */
    fun failure(host: String, of: String, code: String, said: String = ""): Boolean {
        val request = requests[of]?.takeIf { it.card.host == host } ?: return false
        finish(request)
        val quoted = Visible.stripped(said.replace(Regex("\\s+"), " ")).trim().take(200)
        if (request.answer != null) answerFailure(host, request, code, quoted) else resumeFailure(host, request, code, quoted)
        return true
    }

    private fun resumeFailure(host: String, request: HeldTaskRequest, code: String, quoted: String) {
        val key = request.card.key
        when (code) {
            // Refused before anything was sent to the runtime: certain, and Resume stays.
            "agent_unavailable" -> notes[key] = "The agent is not connected, so nothing was sent. Try again once it is back."
            "not_allowed" -> notes[key] = "Only the host's person can resume held tasks."
            "unsupported" -> {
                notes[key] = "This request could not resume the task."
                fresh[host] = false
                unsupported[key] = connected(host) && resync(host)
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
    }

    /**
     * An answer's refusal (enclos docs/protocol.md, Held tasks, Answer). The bridge checks who
     * asks, the card, its revision and the answer before it logs or stores anything, so those
     * refusals are certain; any other may come after the person's message was stored.
     */
    private fun answerFailure(host: String, request: HeldTaskRequest, code: String, quoted: String) {
        val key = request.card.key
        when (code) {
            "not_allowed" -> notes[key] = "Only the host's person can answer held tasks."
            "not_found" -> {
                gone[key] = request.card.revision
                notes.remove(key)
            }
            // The card changed, or was answered, since it was shown: refresh, never resend.
            "conflict" -> doubt(host, key, "The task changed since this card was shown.")
            "bad_message" -> notes[key] = "$host did not take this answer, so nothing was sent."
            "unknown_type", "unsupported" -> notes[key] = "The bridge on $host cannot take answers to held tasks. Update enclos there."
            "internal" -> doubt(host, key, "$host could not confirm the answer" +
                (if (quoted.isNotEmpty()) " ($host said: $quoted)" else "") + ". " + mayHave(request))
            // A code this app does not know is no proof that nothing happened.
            else -> doubt(host, key, "$host answered ${Visible.stripped(code).take(64).ifEmpty { "an error" }}" +
                (if (quoted.isNotEmpty()) ": $quoted" else "") + ". " + mayHave(request))
        }
    }

    fun received(host: String, message: JSONObject): Boolean = when (message.optString("type")) {
        "held_task" -> { parsed(host, message)?.let { merge(it) }; true }
        "held_task.result" -> {
            val of = message.optString("of")
            val card = parsed(host, message)
            if (card != null) {
                // A result for no request of this phone's still brings the card.
                if (!result(host, of, card)) merge(card)
            } else {
                // An answer to this phone's request that cannot be read: the outcome is not known.
                requests[of]?.takeIf { it.card.host == host }?.let { request ->
                    finish(request)
                    doubt(host, request.card.key, "The answer from $host could not be read. " + mayHave(request))
                }
            }
            true
        }
        "error" -> failure(host, message.optString("of"), message.optString("code"), message.optString("message"))
        else -> false
    }

    /**
     * The card [message] carries, or null when it cannot be read. A card newer than the one kept
     * is then marked [unreadable], so the stale one offers no action; the log names the task and
     * revision and the exception's class, never the card's text.
     */
    private fun parsed(host: String, message: JSONObject): HeldTask? =
        runCatching { HeldTask.from(host, message.getJSONObject("held_task")) }
            .onFailure { why ->
                val info = message.optJSONObject("held_task")
                val agent = info?.opt("agent") as? String
                val task = info?.opt("task") as? String
                val revision = (info?.opt("revision") as? Number)?.toLong()
                dropped("${message.optString("type")} from $host for task ${task?.take(128)} at revision $revision could not be read: ${why.javaClass.simpleName}")
                if (agent != null && task != null && revision != null) {
                    val key = HeldTaskKey(host, agent, task)
                    val kept = cards[key]
                    if (kept != null && revision > kept.revision && revision > (unreadable[key] ?: -1)) unreadable[key] = revision
                }
            }
            .getOrNull()

    /**
     * A connection to [host] authenticated: its cards are not fresh until its sync ends. [answers]
     * when its `authenticated` lists `held_task.answer`.
     */
    fun authenticated(host: String, answers: Boolean = false) {
        fresh[host] = false
        answerHosts[host] = answers
        // A new connection: text typed over the last one is reviewed before it is reused.
        generations[host] = ++lastGeneration
        drafts.keys.filter { it.host == host }.forEach(::detach)
    }

    /**
     * [host]'s sync ended: each card it sent is current, and the doubt of every card of the host
     * is settled by it. A sync sends every card still held or answered, so one this phone's
     * answer was in doubt for is `answered` here if the answer was taken, and still `held`, waiting
     * for the person, if it was not.
     */
    fun synced(host: String) {
        fresh[host] = true
        unsupported.keys.filter { it.host == host }.toList().forEach { key ->
            unsupported.remove(key)
            notes.remove(key)
        }
        uncertain.keys.filter { it.host == host }.forEach { key ->
            uncertain.remove(key)
            val card = cards[key] ?: return@forEach
            val note = when {
                // A card that left `held` says so in its state line.
                // The host has a newer card than this app could read: the actions say so.
                card.state != HeldTaskState.Held || key in gone || key in unreadable -> null
                lastAnswer[key] == true -> "Checked: $host still lists it as waiting for you."
                offersResume(card) -> "Checked: $host still lists it as held."
                else -> null
            }
            if (note != null) notes[key] = note else notes.remove(key)
        }
    }

    /** [host]'s connection ended: a request still unanswered may have happened, and is not sent again. */
    fun ended(host: String) {
        fresh[host] = false
        unsupported.keys.filter { it.host == host }.forEach { unsupported[it] = false }
        requests.values.filter { it.card.host == host }.toList().forEach { request ->
            val waiting = pending[request.card.key] == request
            finish(request)
            if (waiting) {
                uncertain[request.card.key] = true
                notes[request.card.key] = if (request.answer != null)
                    "The connection ended before $host confirmed. Your answer may have reached the agent; reconnect to check."
                else "The connection ended before the runtime confirmed. The task may have resumed; reconnect to check."
            }
        }
    }

    fun forgetConversation(host: String, conversation: String) {
        cards.values.filter { it.host == host && it.conversation == conversation }.map { it.key }.forEach(::forgetCard)
        val of = { draft: HeldTaskDraft -> draft.card.host == host && draft.card.conversation == conversation }
        drafts.filterValues(of).keys.forEach { drafts.remove(it) }
        earlierTexts.filterValues { it.any(of) }.keys.forEach { earlierTexts.remove(it) }
    }

    /** Forgets all of [host]: its forgetting, its cache cleared, or its pairing replaced. */
    fun forget(host: String) {
        requests.values.filter { it.card.host == host }.toList().forEach(::finish)
        cards.keys.filter { it.host == host }.forEach(::forgetCard)
        drafts.keys.filter { it.host == host }.forEach { drafts.remove(it) }
        earlierTexts.keys.filter { it.host == host }.forEach { earlierTexts.remove(it) }
        fresh.remove(host)
        answerHosts.remove(host)
        generations.remove(host)
    }

    private fun forgetCard(key: HeldTaskKey) {
        pending[key]?.let(::finish)
        unsupported.remove(key)
        cards.remove(key); notes.remove(key); uncertain.remove(key); receipts.remove(key); gone.remove(key); unreadable.remove(key)
        answered.remove(key); lastAnswer.remove(key)
        drafts.remove(key); earlierTexts.remove(key)
    }

    /** What a doubt says may have happened: the request's own action. */
    private fun mayHave(request: HeldTaskRequest): String =
        if (request.answer != null) "Your answer may have reached the agent." else "The task may have resumed."

    /**
     * The outcome of [key]'s request is not known, [what] says why: the card's actions wait for
     * a sync of [host], asked for now over the open connection, else at the next one.
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
        sentDrafts.remove(request.id)
        if (pending[request.card.key] == request) pending.remove(request.card.key)
    }

    internal companion object {
        /** How long the app waits for an answer before it doubts the outcome: past the bridge's own 25 s. */
        const val ANSWER_WAIT_MS = 35_000L
    }
}
