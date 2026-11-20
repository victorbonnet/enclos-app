// OkHttp's platform hook, compared by identity before and after: internal to OkHttp, used by tests only.
@file:OptIn(okhttp3.internal.OkHttpInternalApi::class)

package io.github.victorbonnet.enclos

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import okhttp3.internal.platform.Platform
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #82's capture support: the production Needs you sheet of a held task, its
 * exact conversation and the way back, on the real [MainActivity] and its own
 * [AppModel] (read back from the Activity's ViewModelStore, never a second
 * one, never seeded), connected through the production transport to two
 * [FakeBridge]s in this process ([ReplacementFixture]). Driven and observed
 * through the platform's accessibility nodes ([UiAutomation]), no UI library.
 *
 * It records the existing behaviour; it decides none. Whether the unsent
 * answer and the folded Details survive the trip to the conversation and back
 * is written as an observation (`observations.txt`), never asserted.
 *
 * What a failure means, by its first word:
 * - `SETUP`: the fixture, the target or the platform did not allow the run: no product result.
 * - `REFUSED`: the target is not empty: nothing was taken, nothing changed.
 * - `C82 FAIL`: a native observation contradicted the existing, specified behaviour.
 *
 * Evidence (ordered PNGs, each with its accessibility hierarchy, a transcript,
 * `metadata.txt` and `observations.txt`) goes to the target's external files,
 * `/sdcard/Android/data/io.github.victorbonnet.enclos/files/c82/<run>/`, for
 * `adb pull`. It holds synthetic names only: no key, no secret.
 *
 * Disposable emulator only: it refuses, read only and before anything is
 * taken, a target with any app store, device key, a process hook a run sets,
 * or push configured ([AppStores.occupancy]).
 */
@RunWith(AndroidJUnit4::class)
class ConnectedNeedsYouCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target: Context = instrumentation.targetContext

    /** What the app must never send in these runs: an answer, a resume, a dismissal, a message, a board. */
    private val forbidden = setOf(HELD_TASK_ANSWER, "held_task.resume", "open_item.dismiss", "message.send", "conversation.new", HELD_TASK_BOARD)

    /** The synthetic fixture, named afresh for the run. */
    private class Synthetic(val nonce: String) {
        val h1 = "c82-1-$nonce"
        val h2 = "c82-2-$nonce"
        val agent = "lab/lead"
        val label = "Synthetic lead"
        val home1 = "c-$nonce-h1-home"
        val home2 = "c-$nonce-h2-home"
        val absent = "c-$nonce-h1-absent"
        /** The same task id on both hosts: only the host tells them apart. */
        val task = "t-$nonce-1"
        val noAsk = "t-$nonce-2"
        val noConversation = "t-$nonce-3"
        val decision = "Which synthetic window should $nonce use?"
        val blocked = "The synthetic rollout $nonce waits for a window."
        val choiceWait = "Wait for the next synthetic window"
        val choiceNow = "Use the current synthetic window"
        /** The same summary on H1's and H2's rows: the duplicate-label control. */
        val summary = "Synthetic decision $nonce"
        val summaryNoAsk = "Synthetic no-ask $nonce"
        val summaryAbsent = "Synthetic absent conversation $nonce"
        val titleNoAsk = "Synthetic no-ask task $nonce"
        val decisionAbsent = "Which synthetic path should $nonce take?"
        val titleH2 = "Wrong-target task $nonce"
        val marker1 = "History marker $nonce on H1"
        val marker2 = "History marker $nonce on H2"
        val draft = "UNSENT-$nonce something else, never sent"
        fun key(host: String, task: String) = "$host/held_task/$agent/$task"
    }

    // ---- the scenario ----------------------------------------------------------------------------

    @Test
    fun held_task_sheet_exact_conversation_return() {
        val run = Run.start(target, "sheet-return")
        val s = Synthetic(run.nonce)
        val hooks = Hooks.now()
        val wrapBefore = AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS)
        // Refused before anything is taken: read only.
        refuseOccupied(AppStores(target), run)
        run.metadata(instrumentation, "held_task_sheet_exact_conversation_return", s.nonce, listOf(s.h1, s.h2))
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = true)
        val owner = ActivityOwner(fixture, run)
        val native = Native(instrumentation.uiAutomation, target.packageName, run)
        // The flags are restored in the finally even when open() itself failed half way.
        var primary: Throwable? = null
        try {
            native.open()
            owner.around { scenario(fixture, owner, native, run, s) }
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            val notRestored = runCatching { native.close() }.exceptionOrNull()
            if (notRestored != null) primary?.addSuppressed(notRestored) ?: throw notRestored
        }
        assertGivenBack(owner, fixture, hooks, wrapBefore, listOf(s.h1, s.h2), run, bridges = 2)
        assertTrue("SETUP: UiAutomation's flags not restored", native.restored())
        run.finish("PASS")
    }

    private fun scripts(s: Synthetic): Pair<BridgeScript, BridgeScript> {
        val now = System.currentTimeMillis() / 1000
        fun agents() = listOf(JSONObject().put("agent", s.agent).put("runtime", "hermes").put("capabilities", JSONArray(listOf("typing"))).put("display_name", s.label))
        fun conversation(id: String) = message("conversation", "conversation" to id, "agent" to s.agent, "home" to true, "revision" to 1)
        fun line(conversation: String, seq: Long, text: String, kind: String, held: String? = null) = message(
            "message", "conversation" to conversation, "agent" to s.agent, "seq" to seq, "from" to "agent", "text" to text, "kind" to kind,
            "time" to now - 900 + seq, "held_task" to held,
        )
        fun card(task: String, conversation: String, title: String, revision: Long, ask: JSONObject?) = message(
            "held_task",
            "held_task" to JSONObject().put("agent", s.agent).put("conversation", conversation).put("task", task).put("title", title)
                .put("reason", "Synthetic reason for $task").put("since", now - 600).put("resume", "Runs the synthetic task again.")
                .put("state", "held").put("revision", revision).put("resumable", true).apply { ask?.let { put("ask", it) } },
        )
        fun item(task: String, conversation: String, summary: String, since: Long, revision: Long) = JSONObject().put("id", task).put("kind", "held_task")
            .put("agent", s.agent).put("conversation", conversation).put("summary", summary).put("since", since).put("expired", false).put("version", "held:$revision")
        fun index(vararg items: JSONObject) = message("open_items", "revision" to 1, "reset" to true, "complete" to true, "items" to JSONArray(items.toList()))
        val ask = JSONObject().put("decision", s.decision).put("blocked", s.blocked).put("choices", JSONArray(listOf(s.choiceWait, s.choiceNow))).put("recommended", 1)
        val askAbsent = JSONObject().put("decision", s.decisionAbsent).put("blocked", "Synthetic path ${s.nonce}").put("choices", JSONArray(listOf("Left", "Right"))).put("recommended", 0)
        val h1 = BridgeScript(
            capabilities = listOf("open_items", HELD_TASK_ANSWER),
            agents = agents(),
            sync = listOf(
                conversation(s.home1),
                line(s.home1, 1, s.marker1, "reply"),
                line(s.home1, 2, "The synthetic task is held (${s.nonce}).", "notify", held = s.task),
                card(s.task, s.home1, "Synthetic held task ${s.nonce}", 3, ask),
                card(s.noAsk, s.home1, s.titleNoAsk, 1, null),
                card(s.noConversation, s.absent, "Synthetic task without its conversation ${s.nonce}", 2, askAbsent),
                index(
                    item(s.task, s.home1, s.summary, now - 60, 3),
                    item(s.noAsk, s.home1, s.summaryNoAsk, now - 120, 1),
                    item(s.noConversation, s.absent, s.summaryAbsent, now - 180, 2),
                ),
            ),
            sentinelAgent = s.agent,
        )
        // An older bridge (no held_task.answer), same agent, same label, same task id and summary: only the host differs.
        val h2 = BridgeScript(
            capabilities = listOf("open_items"),
            agents = agents(),
            sync = listOf(
                conversation(s.home2),
                line(s.home2, 1, s.marker2, "reply"),
                card(s.task, s.home2, s.titleH2, 3, JSONObject().put("decision", "Wrong-target decision ${s.nonce}").put("blocked", "")),
                index(item(s.task, s.home2, s.summary, now - 90, 3)),
            ),
            sentinelAgent = s.agent,
        )
        return h1 to h2
    }

    private fun scenario(fixture: ReplacementFixture, owner: ActivityOwner, native: Native, run: Run, s: Synthetic) {
        run.note("scenario starts")
        fixture.acquire()
        val (script1, script2) = scripts(s)
        val b1 = fixture.pair(s.h1, script1)
        val b2 = fixture.pair(s.h2, script2)
        fixture.beforeModel()
        // Registered before the Activity exists: whatever stops the launch, the owner finishes it.
        fixture.holdModel(owner::endActivity)
        val activity = owner.launch(instrumentation)
        val model = owner.model!!
        run.note("activity ${owner.activityId} model ${owner.modelId} pid ${Process.myPid()}")

        // ---- connected and synced: each bridge's sentinel, sent after its sync, is in the model
        val s1 = b1.nextSession() ?: setupFail("${s.h1}'s bridge authenticated no connection: ${b1.problems}")
        val s2 = b2.nextSession() ?: setupFail("${s.h2}'s bridge authenticated no connection: ${b2.problems}")
        awaitSentinel(model, "${s.h1}/${s.agent}", "sync-1")
        awaitSentinel(model, "${s.h2}/${s.agent}", "sync-1")
        setup(onMain { model.connected(s.h1) && model.connected(s.h2) }) { "not both connected: ${onMain { model.links.toMap() }}" }
        val live = mutableListOf(s1 to s.h1, s2 to s.h2)
        fun barrier(label: String) = processingBarrier(model, live, b1, b2, label, run)

        // ---- the Activity's own model holds exactly what was synced
        onMain {
            setup(model.hostSupports(s.h1, HELD_TASK_ANSWER) && model.heldTasks.answersOn(s.h1)) { "${s.h1} does not take answers in the model" }
            setup(!model.heldTasks.answersOn(s.h2)) { "${s.h2}, an older bridge, takes answers in the model" }
            val card = model.heldTasks.cards[HeldTaskKey(s.h1, s.agent, s.task)] ?: setupFail("no card for ${s.task} on ${s.h1}")
            val ask = card.ask ?: setupFail("the card has no ask: $card")
            setup(card.conversation == s.home1 && card.revision == 3L && ask.decision == s.decision) { "the card is not the one synced: $card" }
            setup(ask.choices == listOf(s.choiceWait, s.choiceNow) && ask.recommended == 1) { "the ask is not the one synced: $ask" }
            val item = model.attentionItems.firstOrNull { it.key == s.key(s.h1, s.task) } ?: setupFail("no Needs you row for ${s.task} on ${s.h1}")
            setup(item.conversation == s.home1 && item.version == "held:3" && model.needsYou.freshHosts[s.h1] == true) { "the row is not the complete index's: $item" }
            setup(model.attentionItems.count { it.id == s.task } == 2) { "the wrong-target row is missing: ${model.attentionItems.map { it.key }}" }
            setup(model.conversations.single { it.id == s.home1 }.let { it.host == s.h1 && it.agent == s.agent && it.home }) { "${s.home1} is not ${s.h1}'s Home" }
            setup(model.agentLabel("${s.h1}/${s.agent}") == s.label && model.agentLabel("${s.h2}/${s.agent}") == s.label) { "the two agents' labels differ" }
        }
        run.note("synced: sessions ${s1.number} ${s2.number}; model holds the card, its row and both Homes")
        barrier("synced")

        // ---- 1. the Inbox: two rows with the same label and summary, one per host
        native.waitNode("H1's row") { row(s.h1, s.summary)(it) && it.visible }
        val labels = native.nodes().filter { it.visible && (row(s.h1, s.summary)(it) || row(s.h2, s.summary)(it)) }.map { it.clickLabel }
        assertEquals("C82 FAIL: the two rows' labels ($labels)", listOf("Review request from ${s.label}", "Review request from ${s.label}"), labels)
        native.capture("inbox")

        // ---- 2. the sheet of H1's task, opened from its own row
        native.clickUnique("H1's row") { row(s.h1, s.summary)(it) }
        val sheet = native.waitNode("the sheet's decision") { it.text == s.decision && it.visible }.window
        assertEquals("C82 FAIL: the sheet opened another request", s.key(s.h1, s.task), onMain { model.focusedRequest?.key })
        assertFalse("C82 FAIL: the wrong-target task's sheet", native.nodes().any { it.visible && it.text == s.titleH2 })
        native.waitNode("the header") { it.window == sheet && it.text == s.label }
        native.waitNode("Blocked") { it.window == sheet && it.text == "Blocked: ${s.blocked}" }
        val now = native.waitNode("the recommended choice", match = choice(sheet, s.choiceNow, s.choiceWait))
        val wait = native.waitNode("the other choice", match = choice(sheet, s.choiceWait, s.choiceNow))
        assertTrue("C82 FAIL: the recommended choice not marked: ${now.texts}", "Recommended" in now.texts)
        assertFalse("C82 FAIL: the other choice marked: ${wait.texts}", "Recommended" in wait.texts)
        assertTrue("C82 FAIL: the recommended choice not first", now.bounds.top < wait.bounds.top)
        assertTrue("C82 FAIL: the choices are not enabled while connected", now.enabled && wait.enabled)
        native.capture("sheet-initial")

        // ---- 3. an answer typed in Something else…, never sent
        native.scrollTo("Something else…", sheet, ::answerField)
        native.setText("Something else…", s.draft) { it.window == sheet && it.editable && answerField(it) }
        native.waitNode("the draft in the field") { it.window == sheet && answerField(it) && it.text == s.draft }
        run.observe("draft_send_enabled", native.nodes().firstOrNull { it.window == sheet && it.clickable && "Send" in it.texts }?.enabled)
        native.capture("draft-typed")
        barrier("draft")

        // ---- 4. Details, then the real View conversation
        native.scrollTo("Details", sheet) { it.clickable && it.text == "Details" }
        native.click("Details") { it.window == sheet && it.clickable && it.text == "Details" }
        native.waitNode("Details open") { it.window == sheet && it.text == "Hide details" }
        native.waitNode("the task's id") { it.window == sheet && it.text == "Task ${s.task}" }
        native.scrollTo("View conversation", sheet) { it.clickable && "View conversation" in it.texts }
        native.capture("details-expanded")
        native.click("View conversation") { it.window == sheet && it.clickable && "View conversation" in it.texts }
        native.waitNode("H1's Home history") { it.text == s.marker1 && it.visible }
        native.waitGone("the sheet") { it.text == s.decision }
        assertFalse("C82 FAIL: H2's history shown", native.nodes().any { it.text == s.marker2 })
        assertTrue("C82 FAIL: the conversation has no Back", native.nodes().any { it.visible && it.clickable && "Back" in it.texts })
        onMain {
            assertEquals("C82 FAIL: the sheet stayed focused", null, model.focusedRequest)
            val c = model.conversations.single { it.id == s.home1 }
            assertTrue("C82 FAIL: the conversation opened is not H1's Home: $c", c.host == s.h1 && c.agent == s.agent && c.home)
            assertTrue("C82 FAIL: H1's Home does not hold its marker", model.lines[s.home1].orEmpty().any { it.text == s.marker1 })
        }
        native.capture("conversation")
        barrier("conversation")

        // ---- 5. the system Back, then the same row again: observed, not decided
        native.back("from the conversation")
        native.waitGone("H1's Home history") { it.text == s.marker1 }
        native.waitNode("the Inbox") { row(s.h1, s.summary)(it) && it.visible }
        val sheetAfterBack = native.nodes().any { it.visible && it.text == s.decision }
        run.observe("after_back_inbox_shown", true)
        run.observe("after_back_sheet_shown", sheetAfterBack)
        native.capture("after-back")
        if (!sheetAfterBack) native.clickUnique("H1's row, again") { row(s.h1, s.summary)(it) }
        val reopened = native.waitNode("the sheet's decision, again") { it.text == s.decision && it.visible }.window
        assertEquals("C82 FAIL: the sheet reopened another request", s.key(s.h1, s.task), onMain { model.focusedRequest?.key })
        native.scrollTo("Something else…, again", reopened, ::answerField)
        val field = native.waitNode("Something else…, again") { it.window == reopened && answerField(it) }
        run.observe("reopened_draft_kept", field.text == s.draft)
        run.observe("reopened_draft_text", field.text ?: "")
        run.observe("reopened_details_open", native.nodes().any { it.window == reopened && it.text == "Hide details" })
        native.capture("reopened")
        barrier("reopened")
        closeSheet(native, model, s.decision)

        // ---- 6. a card no agent wrote: the answer layout's Ask the agent
        native.clickUnique("H1's no-ask row") { row(s.h1, s.summaryNoAsk)(it) }
        val noAsk = native.waitNode("the no-ask heading") { it.text == s.titleNoAsk && it.visible }.window
        assertEquals("C82 FAIL: the no-ask sheet is another request", s.key(s.h1, s.noAsk), onMain { model.focusedRequest?.key })
        native.waitNode("Ask the agent") { it.window == noAsk && it.clickable && "Ask the agent" in it.texts }
        assertFalse("C82 FAIL: a choice on a card no agent wrote", native.nodes().any { it.window == noAsk && s.choiceNow in it.texts })
        native.capture("no-ask")
        closeSheet(native, model, s.titleNoAsk)

        // ---- 7. the wrong-target row: H2, an older bridge, its own sheet as before answers
        native.clickUnique("H2's row") { row(s.h2, s.summary)(it) }
        val older = native.waitNode("H2's title") { it.text == s.titleH2 && it.visible }.window
        assertEquals("C82 FAIL: H2's row opened another request", s.key(s.h2, s.task), onMain { model.focusedRequest?.key })
        native.waitNode("H2's Resume task") { it.window == older && it.clickable && "Resume task" in it.texts }
        assertFalse("C82 FAIL: H1's decision on H2's sheet", native.nodes().any { it.visible && it.text == s.decision })
        assertFalse("C82 FAIL: an answer field from an older bridge", native.nodes().any { it.window == older && answerField(it) })
        native.capture("older-capability-h2")
        closeSheet(native, model, s.titleH2)

        // ---- 8. a task whose exact conversation is not kept: no View conversation, nothing in its place
        native.clickUnique("H1's absent-conversation row") { row(s.h1, s.summaryAbsent)(it) }
        val absent = native.waitNode("its decision") { it.text == s.decisionAbsent && it.visible }.window
        assertEquals("C82 FAIL: the absent-conversation sheet is another request", s.key(s.h1, s.noConversation), onMain { model.focusedRequest?.key })
        native.scrollTo("Details", absent) { it.clickable && it.text == "Details" }
        native.click("Details") { it.window == absent && it.clickable && it.text == "Details" }
        native.waitNode("its task's id") { it.window == absent && it.text == "Task ${s.noConversation}" }
        native.scrollTo("Dismiss reminder", absent) { "Dismiss reminder" in it.texts }
        assertFalse("C82 FAIL: a View conversation for a conversation not kept", native.nodes().any { it.window == absent && "View conversation" in it.texts })
        native.capture("absent-conversation")
        closeSheet(native, model, s.decisionAbsent)
        barrier("fallbacks")

        // ---- 9. a controlled disconnect: H1's cached answer controls, disabled
        b1.refusing = true
        live.removeAll { it.first === s1 }
        s1.closeFromBridge("controlled disconnect")
        setup(s1.gone.await(20, TimeUnit.SECONDS)) { "H1's connection did not end" }
        pollUntil("${s.h1} disconnected in the model") { onMain { !model.connected(s.h1) } }
        native.clickUnique("H1's offline row") { row(s.h1, s.summary, offline = true)(it) }
        val offline = native.waitNode("the decision, offline") { it.text == s.decision && it.visible }.window
        assertEquals("C82 FAIL: the offline sheet is another request", s.key(s.h1, s.task), onMain { model.focusedRequest?.key })
        val nowOff = native.waitNode("the recommended choice, offline", match = choice(offline, s.choiceNow, s.choiceWait))
        val waitOff = native.waitNode("the other choice, offline", match = choice(offline, s.choiceWait, s.choiceNow))
        assertFalse("C82 FAIL: a choice enabled while ${s.h1} is offline", nowOff.enabled || waitOff.enabled)
        native.scrollTo("Something else…, offline", offline, ::answerField)
        val fieldOff = native.waitNode("Something else…, offline") { it.window == offline && answerField(it) }
        assertFalse("C82 FAIL: the answer field enabled while ${s.h1} is offline", fieldOff.enabled)
        run.observe("offline_draft_kept", fieldOff.text == s.draft)
        run.observe("offline_reason_shown", native.nodes().firstOrNull { it.window == offline && it.text?.startsWith("Connect to ${s.h1}") == true }?.text ?: "none")
        native.capture("offline-cached-disabled")
        closeSheet(native, model, s.decision)
        barrier("offline")

        // ---- the same process, Activity and model throughout; nothing answered, resumed, dismissed or sent
        assertSame("C82 FAIL: the Activity changed", activity, owner.resumed())
        assertSame("C82 FAIL: the Activity's model changed", model, owner.activityModel(activity))
        run.note("received by ${s.h1}: ${b1.receivedAll().map { it.optString("type") }}; by ${s.h2}: ${b2.receivedAll().map { it.optString("type") }}")
        run.note("same pid ${Process.myPid()}, activity ${owner.activityId}, model ${owner.modelId}")
    }

    /** A Needs you row: its click label, and its host's line naming [host] then [summary], as the Inbox writes it. */
    private fun row(host: String, summary: String, offline: Boolean = false): (Node) -> Boolean = { n ->
        n.clickable && n.clickLabel?.startsWith("Review request from ") == true &&
            n.texts.any { it == "$host · " + (if (offline) "Offline, cached request · " else "") + summary }
    }

    /** One choice button of the sheet in [window]: the control naming [label], not a container that also names [other]. */
    private fun choice(window: Int, label: String, other: String): (Node) -> Boolean =
        { it.window == window && it.clickable && label in it.texts && other !in it.texts }

    /** The typed answer's field: its description is the field's label (FormField); disabled, it may not report itself editable. */
    private fun answerField(n: Node) = n.desc == "Something else…"

    /** Closes a sheet with the system Back, a second Back only when the first did not (a keyboard over it), said in the transcript. */
    private fun closeSheet(native: Native, model: AppModel, anchor: String) {
        native.back("closes the sheet")
        if (!native.goneWithin(5) { it.text == anchor }) native.back("closes the sheet: the first Back did not")
        native.waitGone("the sheet") { it.text == anchor }
        pollUntil("the sheet unfocused") { onMain { model.focusedRequest == null } }
    }

    /**
     * A deterministic processing barrier on each live connection: the main
     * thread idled (a tap's send has run), the app-to-bridge round trip
     * ([FakeBridge.Session.roundTrip]: all the app queued is at the bridge),
     * then a sentinel the model holds (all the bridge sent is read). Then
     * nothing [forbidden] was received over any connection of either bridge.
     */
    private fun processingBarrier(model: AppModel, live: List<Pair<FakeBridge.Session, String>>, b1: FakeBridge, b2: FakeBridge, label: String, run: Run) {
        instrumentation.waitForIdleSync()
        onMain { }
        live.forEach { (session, host) ->
            session.roundTrip(label)
            session.sentinel("barrier-$label")
            awaitSentinel(model, "$host/lab/lead", "barrier-$label")
        }
        val sent = (b1.receivedAll() + b2.receivedAll()).map { it.optString("type") }
        run.note("barrier $label: received ${sent.groupingBy { it }.eachCount()}")
        val actions = sent.filter { it in forbidden }
        assertTrue("C82 FAIL: the app sent $actions by $label", actions.isEmpty())
    }

    // ---- focused safety controls -----------------------------------------------------------------

    /**
     * Every store path, one at a time, then each process hook a run sets: an
     * isolated copy (a prefix of its own beside the app's) is refused before
     * anything is taken; its bytes, the app's own, the device keys and the
     * hooks' identities are as before, and the fixture owns nothing.
     */
    @Test
    fun each_occupied_store_and_hook_is_refused_with_bytes_and_hooks_unchanged() {
        val run = Run.start(target, "occupied")
        refuseOccupied(AppStores(target), run)
        val stores = AppStores(target, "c82-occ-${run.nonce}-")
        try {
            stores.paths().forEach { path ->
                setup(!path.exists()) { "${path.name} exists already: not this check's" }
                if (path.parentFile == target.cacheDir) {
                    path.mkdirs()
                    File(path, "sentinel.bin").writeBytes(randomBytes())
                } else {
                    path.parentFile?.mkdirs()
                    path.writeBytes(randomBytes())
                }
                assertRefused(stores, run, "store ${path.name}")
                setup(path.deleteRecursively() && !path.exists()) { "the sentinel ${path.name} not removed" }
            }
            val listener: (String) -> Unit = {}
            PushTokens.changed = listener
            try {
                assertRefused(stores, run, "a live model's push-token listener")
                assertSame("the listener changed by the refusal", listener, PushTokens.changed)
            } finally {
                PushTokens.changed = null
            }
            val original = Platform.get()
            val leaked = LoopbackCleartext(original)
            Platform.resetForTests(leaked)
            try {
                assertRefused(stores, run, "a leaked platform hook")
                assertSame("the hook changed by the refusal", leaked, Platform.get())
            } finally {
                Platform.resetForTests(original)
            }
        } finally {
            stores.paths().forEach { it.deleteRecursively() }
        }
        assertTrue("sentinels left", stores.paths().none(File::exists))
        run.finish("PASS")
    }

    /** A device key no paired host names (AppModel's sweep would delete it) is refused too, the key kept. */
    @Test
    fun an_unpaired_device_key_is_refused_and_kept() {
        val run = Run.start(target, "key-refusal")
        refuseOccupied(AppStores(target), run)
        val alias = "${DEVICE_KEY_PREFIX}c82-occupied-${run.nonce}"
        // Only a key this check made is its own to delete: not one found under the alias before.
        var ownsKey = false
        try {
            setup(!AppStores.keyStore().containsAlias(alias)) { "$alias exists already: not this check's" }
            ownsKey = true
            Keys.newDeviceKey(alias)
            assertRefused(AppStores(target), run, "an unpaired device key")
            assertTrue("the key was not kept", AppStores.keyStore().containsAlias(alias))
        } finally {
            if (ownsKey) AppStores.keyStore().run { if (containsAlias(alias)) deleteEntry(alias) }
        }
        assertFalse("the check's own key was not deleted", AppStores.keyStore().containsAlias(alias))
        run.finish("PASS")
    }

    /** A setup stopped half way (H2's bridge, after H1 was paired): what was made is given back, only that; the failure is the one thrown. */
    @Test
    fun a_partial_acquisition_is_given_back() {
        val run = Run.start(target, "partial")
        val hooks = Hooks.now()
        val wrapBefore = AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS)
        refuseOccupied(AppStores(target), run)
        val s = Synthetic(run.nonce)
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = true)
        fixture.beforeStep = { if (it == "bridge ${s.h2}") throw Injected(it) }
        val owner = ActivityOwner(fixture, run)
        val (script1, script2) = scripts(s)
        val thrown = runCatching {
            owner.around {
                fixture.acquire()
                fixture.pair(s.h1, script1)
                fixture.pair(s.h2, script2)
            }
        }.exceptionOrNull()
        assertTrue("not the injected failure: $thrown", thrown is Injected)
        assertEquals("teardown failures carried", 0, thrown!!.suppressed.size)
        assertEquals("hosts left", emptyList<String>(), storedHostNames())
        assertGivenBack(owner, fixture, hooks, wrapBefore, listOf(s.h1, s.h2), run, bridges = 1)
        run.finish("PASS")
    }

    /**
     * The model's cleanup failing on every attempt of the owner (injected,
     * before the Activity is finished): the bridges are closed and the hook
     * restored, but the hosts, keys and stores the live model may use stay
     * held, untouched, and the Activity live. A later retry, once the failure
     * stops, ends the Activity, drains, then gives them back; a release after
     * that does nothing.
     */
    @Test
    fun a_persistent_model_cleanup_failure_keeps_storage_until_a_later_retry() {
        val run = Run.start(target, "model-held")
        val hooks = Hooks.now()
        val wrapBefore = AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS)
        refuseOccupied(AppStores(target), run)
        val s = Synthetic(run.nonce)
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = true)
        val owner = ActivityOwner(fixture, run)
        val attempts = mutableListOf<String>()
        var failing = true
        fixture.beforeCleanup = { label ->
            attempts += label
            if (label == "model" && failing) throw Injected(label)
        }
        val thrown = runCatching {
            owner.around {
                fixture.acquire()
                val b = fixture.pair(s.h1, scripts(s).first)
                fixture.beforeModel()
                fixture.holdModel(owner::endActivity)
                owner.launch(instrumentation)
                setup(b.nextSession() != null) { "no connection: ${b.problems}" }
                awaitSentinel(owner.model!!, "${s.h1}/${s.agent}", "sync-1")
                throw AssertionError("deliberate failure while connected")
            }
        }.exceptionOrNull()
        laterRetry(fixture, run) {
            run.note("cleanup attempts: $attempts; held ${fixture.held}")
            assertEquals("deliberate failure while connected", thrown?.message)
            assertTrue("the model's failure not carried: ${thrown!!.suppressed.toList()}", thrown.suppressed.any { it is Injected })
            assertEquals("model steps attempted: $attempts", 2, attempts.count { it == "model" })
            assertTrue(
                "storage released while the model was held: $attempts",
                attempts.none { it.startsWith("host ") || it.startsWith("key ") || it.startsWith("preferences ") || it.startsWith("database ") || it.startsWith("cache ") },
            )
            val held = fixture.held
            assertTrue("not held: $held", "model" in held && "host ${s.h1}" in held && "key ${ReplacementFixture.aliasOf(s.h1)}" in held && "database conversations.db" in held)
            assertFalse("the bridge or the hook still held: $held", held.any { it.startsWith("bridge ") || it == "platform" })
            assertTrue("the pairing not kept", s.h1 in storedHostNames())
            assertTrue("the key not kept", AppStores.keyStore().containsAlias(ReplacementFixture.aliasOf(s.h1)))
            assertEquals("the Activity not kept live", 1, owner.liveActivities().size)
            assertSame("the hook not restored while the model was held", hooks.platform, Platform.get())
        }
        assertTrue("storage not released after the model ended: ${fixture.released}", fixture.released.any { it.startsWith("host ") })
        assertGivenBack(owner, fixture, hooks, wrapBefore, listOf(s.h1), run, bridges = 1)
        val count = fixture.released.size
        fixture.release()
        assertEquals("a second release changed something: ${fixture.released}", count, fixture.released.size)
        run.finish("PASS")
    }

    /**
     * A bridge's close failing on every attempt of the owner (H1's, injected):
     * H2's host and key are given back, independent of it; H1's host and key,
     * the wrapping key and the stores stay held; the hook is restored. A later
     * retry closes it and gives the rest back; a release after that does
     * nothing.
     */
    @Test
    fun a_persistent_bridge_cleanup_failure_keeps_its_dependencies_until_a_later_retry() {
        val run = Run.start(target, "bridge-held")
        val hooks = Hooks.now()
        val wrapBefore = AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS)
        refuseOccupied(AppStores(target), run)
        val s = Synthetic(run.nonce)
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = true)
        var failing = true
        fixture.beforeCleanup = { if (it == "bridge ${s.h1}" && failing) throw Injected(it) }
        val owner = ActivityOwner(fixture, run)
        val (script1, script2) = scripts(s)
        val thrown = runCatching {
            owner.around {
                fixture.acquire()
                fixture.pair(s.h1, script1)
                fixture.pair(s.h2, script2)
                throw AssertionError("deliberate failure before the launch")
            }
        }.exceptionOrNull()
        laterRetry(fixture, run) {
            assertEquals("deliberate failure before the launch", thrown?.message)
            assertTrue("the bridge's failure not carried: ${thrown!!.suppressed.toList()}", thrown.suppressed.any { it is Injected })
            val held = fixture.held
            assertTrue("H1 not held: $held", "bridge ${s.h1}" in held && "host ${s.h1}" in held && "key ${ReplacementFixture.aliasOf(s.h1)}" in held)
            assertTrue("the stores not held: $held", "database conversations.db" in held && "preferences hosts" in held)
            assertFalse("H2 or the hook still held: $held", held.any { it.contains(s.h2) } || "platform" in held)
            assertEquals("pairings left", listOf(s.h1), storedHostNames())
            assertTrue("H1's key not kept", AppStores.keyStore().containsAlias(ReplacementFixture.aliasOf(s.h1)))
            assertFalse("H2's key kept", AppStores.keyStore().containsAlias(ReplacementFixture.aliasOf(s.h2)))
            assertTrue("H1's bridge not kept listening", owner.bridgesMade.first { it.name == s.h1 }.listening)
            assertSame("the hook not restored", hooks.platform, Platform.get())
            failing = false
        }
        assertGivenBack(owner, fixture, hooks, wrapBefore, listOf(s.h1, s.h2), run, bridges = 2)
        val count = fixture.released.size
        fixture.release()
        assertEquals("a second release changed something: ${fixture.released}", count, fixture.released.size)
        run.finish("PASS")
    }

    /** Runs [checks]; then, whatever they did, stops the injected failure's effect by a later release; a failure of either is thrown, the other carried. */
    private fun laterRetry(fixture: ReplacementFixture, run: Run, checks: () -> Unit) {
        val checked = runCatching(checks).exceptionOrNull()
        fixture.beforeCleanup = {}
        val retried = runCatching { fixture.release() }.exceptionOrNull()
        run.note("later retry: ${retried ?: "given back"}; released ${fixture.released}")
        if (checked != null) {
            retried?.let(checked::addSuppressed)
            throw checked
        }
        retried?.let { throw it }
    }

    private fun assertRefused(stores: AppStores, run: Run, what: String) {
        val before = stores.bytes()
        val own = AppStores(target).bytes()
        val aliases = AppStores.deviceAliases()
        val hooks = Hooks.now()
        val fixture = ReplacementFixture(stores, modelAllowed = false)
        val refused = runCatching { fixture.acquire() }.exceptionOrNull()
        assertTrue("$what not refused: $refused", refused is AssertionError && refused.message.orEmpty().startsWith("REFUSED"))
        assertFalse("$what: the fixture owns something", fixture.owned)
        assertTrue("$what: the fixture holds ${fixture.held}", fixture.held.isEmpty())
        fixture.release()
        assertTrue("$what: a release after a refusal removed ${fixture.released}", fixture.released.isEmpty())
        assertEquals("$what: the isolated bytes changed by the refusal", before, stores.bytes())
        assertEquals("$what: the app's own bytes changed by the refusal", own, AppStores(target).bytes())
        assertEquals("$what: the device keys changed by the refusal", aliases, AppStores.deviceAliases())
        assertSame("$what: the platform hook changed by the refusal", hooks.platform, Platform.get())
        assertSame("$what: the push-token listener changed by the refusal", hooks.push, PushTokens.changed)
        run.note("refused, unchanged: $what (${before.size} paths)")
    }

    /** After teardown: no MainActivity, its model cleared, the hooks the original ones, nothing held, bridges closed, keys gone, the target empty. */
    private fun assertGivenBack(owner: ActivityOwner, fixture: ReplacementFixture, hooks: Hooks, wrapBefore: Boolean, hosts: List<String>, run: Run, bridges: Int) {
        assertTrue("SETUP: a MainActivity outlived the run", owner.liveActivities().isEmpty())
        assertTrue("SETUP: the Activity's model was not cleared", owner.model == null || owner.modelCleared)
        assertSame("SETUP: OkHttp's platform hook not restored", hooks.platform, Platform.get())
        assertSame("SETUP: the push-token listener not restored", hooks.push, PushTokens.changed)
        assertTrue("SETUP: the fixture still holds ${fixture.held}", fixture.held.isEmpty())
        assertFalse("SETUP: the fixture still owns", fixture.owned)
        assertEquals("SETUP: bridges made", bridges, owner.bridgesMade.size)
        owner.bridgesMade.forEach { b ->
            assertFalse("SETUP: ${b.name} still listening", b.listening)
            assertTrue("SETUP: ${b.name}'s threads alive: ${b.threadsAlive}", b.threadsAlive.isEmpty())
            val open = b.accepted.filter { !it.socketClosed || it.worker?.isAlive == true }.map { "connection ${it.index} (${it.phase})" }
            assertTrue("SETUP: ${b.name}'s connections not closed: $open", open.isEmpty())
        }
        val left = hosts.map(ReplacementFixture::aliasOf).filter { AppStores.keyStore().containsAlias(it) }
        assertTrue("SETUP: synthetic keys left: $left", left.isEmpty())
        assertEquals("SETUP: the wrapping key's presence changed", wrapBefore, AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS))
        // Read twice, a second apart: a write the app queued after the fixture's deletion fails here, not as the next run's refusal.
        instrumentation.waitForIdleSync()
        val first = AppStores(target).occupancy()
        SystemClock.sleep(1_000)
        val second = AppStores(target).occupancy()
        run.note("given back: occupancy ${first.ifEmpty { listOf("empty") }}, then ${second.ifEmpty { listOf("empty") }}; released ${fixture.released}")
        assertTrue("SETUP: the run's own data left after teardown: $first, then $second", first.isEmpty() && second.isEmpty())
    }

    // ---- the pieces ------------------------------------------------------------------------------

    private class Injected(at: String) : RuntimeException("injected failure at $at")

    /** The process hooks a run sets, by identity. */
    private class Hooks(val platform: Platform, val push: Any?) {
        companion object {
            fun now() = Hooks(Platform.get(), PushTokens.changed)
        }
    }

    /**
     * Owns the Activity and the fixture: [around] runs a check, then gives
     * back everything whatever stopped it, the Activity first (finished,
     * destroyed, its model cleared, its writers drained) through the
     * fixture's model step, then the rest ([ReplacementFixture.release]). A
     * cleanup that failed is tried once more; what still fails stays held and
     * is thrown, carried in the check's own failure when there is one.
     */
    private inner class ActivityOwner(private val fixture: ReplacementFixture, private val run: Run) {
        var model: AppModel? = null
            private set
        var activityId = "none"
            private set
        var modelId = "none"
            private set
        private var cleared: CountDownLatch? = null
        val modelCleared: Boolean get() = cleared?.count == 0L

        /** Every bridge the fixture made, kept for the checks after teardown (the fixture forgets those it closed). */
        var bridgesMade: List<FakeBridge> = emptyList()
            private set

        fun around(check: () -> Unit) {
            var primary: Throwable? = null
            try {
                check()
            } catch (t: Throwable) {
                primary = t
                run.note("check failed: $t")
            }
            // Before any release: the fixture holds every bridge it made until it closed it.
            bridgesMade = fixture.bridges.toList()
            val teardown = finish()
            if (primary != null) {
                teardown?.let(primary::addSuppressed)
                run.finish("FAIL")
                throw primary
            }
            if (teardown != null) {
                run.finish("TEARDOWN FAIL")
                throw teardown
            }
        }

        /** Releases, once more if anything is still held; null when all was given back at the first attempt. */
        private fun finish(): Throwable? {
            val first = runCatching { fixture.release() }.exceptionOrNull() ?: return null
            run.note("not all given back: $first; held ${fixture.held}; retried")
            val second = runCatching { fixture.release() }.exceptionOrNull()
            if (second == null && fixture.held.isEmpty()) {
                // Given back, but the failure is still reported: never hidden by the retry.
                run.note("given back on retry: ${fixture.released}")
                return first
            }
            second?.let(first::addSuppressed)
            run.note("still held after the retry: ${fixture.held}")
            return first
        }

        /** Launches MainActivity as the system does, and reads back the model it made (never makes one). */
        fun launch(instrumentation: android.app.Instrumentation): MainActivity {
            val intent = Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            baseline = Thread.getAllStackTraces().keys
            val activity = instrumentation.startActivitySync(intent) as MainActivity
            observePool()
            activityId = "${activity.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(activity))}"
            val m = activityModel(activity)
            model = m
            modelId = "AppModel@${Integer.toHexString(System.identityHashCode(m))}"
            val latch = CountDownLatch(1)
            cleared = latch
            onMain { m.addCloseable(AutoCloseable { latch.countDown() }) }
            return activity
        }

        /** The Activity's own AppModel, from its ViewModelStore; a factory that refuses proves none is made here. */
        fun activityModel(activity: MainActivity): AppModel = onMain {
            val refuse = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    throw AssertionError("SETUP: the Activity had no AppModel; none is made by the test")
            }
            ViewModelProvider(activity.viewModelStore, refuse, activity.defaultViewModelCreationExtras)[AppModel::class.java]
        }

        fun resumed(): Activity? = onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity } }

        fun liveActivities(): List<Activity> = onMain {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }.filter { it is MainActivity }
        }

        /** The fixture's model step: every MainActivity finished and destroyed, its model cleared, its writers drained; throws when not, kept held. */
        fun endActivity() {
            val live = liveActivities()
            run.note("ending activities $live")
            observePool()
            onMain { live.forEach(Activity::finish) }
            val until = SystemClock.uptimeMillis() + 20_000
            while (liveActivities().isNotEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
            val left = liveActivities()
            if (left.isNotEmpty()) throw IllegalStateException("activities not destroyed within 20 s: $left")
            cleared?.let { latch ->
                if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("the Activity's AppModel was not cleared within 20 s")
            }
            // Main idle: ViewModel.clear() ran onCleared, which shut the executors down, after the closeable's latch.
            instrumentation.waitForIdleSync()
            if (model != null) awaitPoolThreads()
            run.note("activity destroyed, model cleared=$modelCleared, new pool threads ended: ${poolSeen.map { it.name }}")
        }

        /** Live threads before [launch]: never waited on. */
        private var baseline: Set<Thread> = emptySet()

        /** The default thread factory's names (`Executors.defaultThreadFactory`). */
        private val poolName = Regex("pool-\\d+-thread-\\d+")

        /** Every new pool thread observed since [launch], kept by identity (a name can be reused). */
        private val poolSeen = mutableSetOf<Thread>()

        private fun observePool() {
            poolSeen += Thread.getAllStackTraces().keys.filter { it !in baseline && poolName.matches(it.name) }
        }

        /**
         * The cleared model's queued work ended: onCleared shuts its executors
         * down (AppModel's `saving`, `work`, `disk`, AgentImages' `io`), but
         * what is queued still runs and would write after the fixture deleted
         * the stores. They use the default thread factory (`pool-N-thread-M`,
         * ending once shut down and drained): every such thread new since
         * [launch] is joined, never interrupted, until none is live; one live
         * after 20 s (the model's or not: ambiguous) throws, and the stores
         * stay held. Queued `apply` writes are drained by the fixture's commit
         * barrier, per preferences, before each deletion.
         */
        private fun awaitPoolThreads() {
            val until = SystemClock.uptimeMillis() + 20_000
            while (true) {
                observePool()
                val live = poolSeen.filter(Thread::isAlive)
                if (live.isEmpty()) return
                val left = until - SystemClock.uptimeMillis()
                if (left <= 0) throw IllegalStateException("new pool thread(s) still live 20 s after the model was cleared: ${live.map { it.name }}")
                live.first().join(left)
            }
        }
    }

    /** A node as the platform's accessibility API reports it; [texts] are its own text and description, then its descendants', in order. */
    data class Node(
        val text: String?, val desc: String?, val state: String?, val className: String?,
        val checkable: Boolean, val checked: Boolean, val clickable: Boolean, val enabled: Boolean, val heading: Boolean, val editable: Boolean,
        val visible: Boolean, val clickLabel: String?, val bounds: Rect, val depth: Int, val window: Int, val texts: List<String>,
    )

    /**
     * The app's screens through [UiAutomation]: the accessibility nodes of
     * every window of the app (a sheet is a window of its own), clicks and
     * text by node actions, Back by the system's global action.
     */
    private inner class Native(private val ui: UiAutomation, private val pkg: String, private val run: Run) {
        private var shot = 0
        private var flagsBefore: Int? = null
        private var restoring = false

        /** Asks for every window's nodes, for this UiAutomation connection only; [close] restores the flags. */
        fun open() {
            val info = ui.serviceInfo
            flagsBefore = info.flags
            // Owed from here, before the change: a set that throws half way is still undone by close().
            restoring = true
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
        }

        /** Restores the flags, tried twice; still owed (and thrown, both failures) when both fail. */
        fun close() {
            if (!restoring) return
            val flags = flagsBefore ?: return
            val first = runCatching { ui.serviceInfo = ui.serviceInfo.also { it.flags = flags } }.exceptionOrNull()
            if (first != null) {
                run.note("UiAutomation flags not restored: $first; retried")
                runCatching { ui.serviceInfo = ui.serviceInfo.also { it.flags = flags } }.exceptionOrNull()?.let {
                    first.addSuppressed(it)
                    throw first
                }
            }
            restoring = false
            run.note("UiAutomation flags restored: $flags")
        }

        fun restored(): Boolean = !restoring && flagsBefore.let { it == null || ui.serviceInfo.flags == it }

        /** Compose leaves stale nodes in the connection's cache: each read starts from it emptied (API 34+), or nodes refreshed one by one. */
        private fun freshen() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !ui.clearCache()) run.note("UiAutomation.clearCache answered false")
        }

        private fun current(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || n.refresh()) n else null

        private fun roots(): List<Pair<Int, AccessibilityNodeInfo>> {
            freshen()
            val fromWindows = ui.windows.mapNotNull { w -> w.root?.let(::current)?.takeIf { it.packageName?.toString() == pkg }?.let { w.id to it } }
            return fromWindows.ifEmpty { listOfNotNull(ui.rootInActiveWindow?.let(::current)?.takeIf { it.packageName?.toString() == pkg }?.let { -1 to it }) }
        }

        private fun walk(visit: (AccessibilityNodeInfo, Node) -> Unit) {
            roots().forEach { (window, root) ->
                fun go(n: AccessibilityNodeInfo, depth: Int): List<String> {
                    val own = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                    val below = (0 until n.childCount).mapNotNull { n.getChild(it)?.let(::current) }.flatMap { go(it, depth + 1) }
                    visit(n, read(n, depth, window, own + below))
                    return own + below
                }
                go(root, 0)
            }
        }

        private val order = compareBy<Node>({ it.bounds.top }, { it.depth })

        // isChecked: deprecated from API 36 for a tri-state; the boolean is still set, and is all API 28 has.
        @Suppress("DEPRECATION")
        private fun read(n: AccessibilityNodeInfo, depth: Int, window: Int, texts: List<String>): Node = Node(
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) n.stateDescription?.toString() else null,
            className = n.className?.toString(),
            checkable = n.isCheckable, checked = n.isChecked, clickable = n.isClickable, enabled = n.isEnabled,
            heading = n.isHeading, editable = n.isEditable, visible = n.isVisibleToUser,
            clickLabel = n.actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label?.toString(),
            bounds = Rect().also(n::getBoundsInScreen), depth = depth, window = window, texts = texts,
        )

        fun nodes(): List<Node> = mutableListOf<Node>().also { list -> walk { _, node -> list += node } }.sortedWith(order)

        /** The visible nodes that [match], the deepest first: a control before a container that also names its words. */
        private fun liveAll(match: (Node) -> Boolean): List<AccessibilityNodeInfo> =
            mutableListOf<Pair<AccessibilityNodeInfo, Int>>().also { found -> walk { info, node -> if (node.visible && match(node)) found += info to node.depth } }
                .sortedByDescending { it.second }.map { it.first }

        fun waitNode(what: String, seconds: Long = 20, match: (Node) -> Boolean): Node = poll(what, seconds) { nodes().firstOrNull(match) }

        fun waitGone(what: String, seconds: Long = 20, match: (Node) -> Boolean) {
            poll("$what gone", seconds) { if (nodes().none { it.visible && match(it) }) Unit else null }
        }

        /** Whether no visible node [match]es within [seconds]; never fails. */
        fun goneWithin(seconds: Long, match: (Node) -> Boolean): Boolean {
            val until = SystemClock.uptimeMillis() + seconds * 1000
            while (SystemClock.uptimeMillis() < until) {
                if (nodes().none { it.visible && match(it) }) return true
                SystemClock.sleep(100)
            }
            return false
        }

        fun click(what: String, match: (Node) -> Boolean) {
            val acted = poll("$what to click", 20) { liveAll(match).firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)?.takeIf { it } }
            run.note("clicked $what: $acted")
        }

        /** Clicks the one visible node that [match]es; two or more is an identity failure, never a guess. */
        fun clickUnique(what: String, match: (Node) -> Boolean) {
            val acted = poll("$what to click", 20) {
                val all = liveAll(match)
                if (all.size > 1) throw AssertionError("C82 FAIL: ${all.size} nodes match $what")
                all.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)?.takeIf { it }
            }
            run.note("clicked $what (unique): $acted")
        }

        fun setText(what: String, value: String, match: (Node) -> Boolean) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            poll("$what to take the text", 20) { liveAll(match).firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)?.takeIf { it } }
            run.note("typed into $what: $value")
        }

        fun back(why: String) {
            setup(ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "the system Back was refused ($why)" }
            run.note("system Back: $why")
        }

        /** Scrolls the tallest scrollable of [window] forward, then back, until a visible node of it [match]es; twelve scrolls each way at most. */
        fun scrollTo(what: String, window: Int, match: (Node) -> Boolean) {
            for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                repeat(12) {
                    if (nodes().any { it.window == window && it.visible && match(it) }) return
                    val scrollables = mutableListOf<Pair<AccessibilityNodeInfo, Node>>()
                    walk { info, node -> if (node.window == window && node.visible && info.actionList.any { it.id == action }) scrollables += info to node }
                    val scroller = scrollables.maxByOrNull { it.second.bounds.height() }?.first ?: return@repeat
                    if (!scroller.performAction(action)) return@repeat
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(200)
                }
            }
            setup(nodes().any { it.window == window && it.visible && match(it) }) { "$what not on the screen after scrolling" }
        }

        /** The screen as a PNG and its hierarchy, labelled with the state, numbered in order. */
        fun capture(state: String) {
            instrumentation.waitForIdleSync()
            val label = "%02d-%s".format(++shot, state.replace(Regex("[^A-Za-z0-9-]"), "-"))
            // Required evidence: a state with no PNG is a failure, never a silent pass.
            val png = File(run.dir, "$label.png")
            val bitmap = ui.takeScreenshot() ?: setupFail("required evidence missing: no screenshot for $label")
            val written = png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            setup(written && png.length() > 0) { "required evidence missing: $label.png not written" }
            File(run.dir, "$label-hierarchy.txt").writeText(
                nodes().joinToString("\n") { n ->
                    "  ".repeat(n.depth) + listOfNotNull(
                        n.className, n.text?.let { "text=\"$it\"" }, n.desc?.let { "desc=\"$it\"" }, n.state?.let { "state=\"$it\"" },
                        if (n.checkable) "checked=${n.checked}" else null, if (n.clickable) "click=\"${n.clickLabel}\"" else null,
                        if (!n.enabled) "disabled" else null, if (n.heading) "heading" else null, if (n.editable) "editable" else null,
                        if (!n.visible) "hidden" else null, "w${n.window}", n.bounds.toShortString(),
                    ).joinToString(" ")
                },
            )
            run.note("state $label pid ${Process.myPid()}")
        }

        private fun <T : Any> poll(what: String, seconds: Long, attempt: () -> T?): T {
            val until = SystemClock.uptimeMillis() + seconds * 1000
            var last: Throwable? = null
            while (SystemClock.uptimeMillis() < until) {
                try {
                    attempt()?.let { return it }
                } catch (t: IllegalStateException) {
                    // A node gone between reading and acting: read again.
                    last = t
                }
                SystemClock.sleep(100)
            }
            val timeout = AssertionError("C82 FAIL: not seen within $seconds s: $what${last?.let { " ($it)" }.orEmpty()}")
            // The evidence of the timeout, missing, is carried in it: never hides the timeout itself.
            runCatching { capture("timeout-${what.take(40)}") }.exceptionOrNull()?.let(timeout::addSuppressed)
            throw timeout
        }
    }

    /**
     * One run's evidence: logcat (tag `C82`) from the start; its folder in the
     * target's external files only once [begin] is called, after the target
     * was found empty, so a refusal writes nothing at all.
     */
    private class Run(private val target: Context, val name: String, val nonce: String) {
        private val pending = mutableListOf<String>()
        private var folder: File? = null
        val dir: File get() = folder ?: throw AssertionError("SETUP: evidence written before the target was found empty")

        /** Opens `<external files>/c82/<name>-<nonce>` and writes what was noted so far. */
        fun begin() {
            if (folder != null) return
            val base = target.getExternalFilesDir(null) ?: throw AssertionError("SETUP: no external files directory")
            val made = File(base, "c82/$name-$nonce").apply { mkdirs() }
            folder = made
            File(made, "transcript.txt").appendText(pending.joinToString("") { "$it\n" })
            pending.clear()
            note("evidence ${made.absolutePath}")
        }

        fun note(text: String) {
            val line = "${SystemClock.uptimeMillis()} pid=${Process.myPid()} $text"
            Log.i("C82", line)
            folder?.let { File(it, "transcript.txt").appendText(line + "\n") } ?: pending.add(line)
        }

        /** An observation of existing behaviour, never an assertion: `observations.txt`, in order. */
        fun observe(key: String, value: Any?) {
            File(dir, "observations.txt").appendText("$key=$value\n")
            note("observed $key=$value")
        }

        /** What the evidence was taken on: the platform, the viewport, the app and test packages, the fixture's names. */
        fun metadata(instrumentation: android.app.Instrumentation, test: String, nonce: String, hosts: List<String>) {
            val metrics = target.resources.displayMetrics
            val config = target.resources.configuration
            @Suppress("DEPRECATION")
            val app = target.packageManager.getPackageInfo(target.packageName, 0)
            val night = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            File(dir, "metadata.txt").writeText(
                listOf(
                    "test=${ConnectedNeedsYouCaptureTest::class.java.name}#$test", "nonce=$nonce", "hosts=${hosts.joinToString()}",
                    "device=${Build.MANUFACTURER} ${Build.MODEL}", "fingerprint=${Build.FINGERPRINT}", "sdk=${Build.VERSION.SDK_INT}",
                    "viewport_px=${metrics.widthPixels}x${metrics.heightPixels}", "density_dpi=${metrics.densityDpi}", "density=${metrics.density}",
                    "font_scale=${config.fontScale}", "night=$night", "locale=${config.locales[0]}",
                    "app=${target.packageName} ${app.versionName} (${app.longVersionCode})", "test_package=${instrumentation.context.packageName}",
                    "pid=${Process.myPid()}",
                ).joinToString("\n", postfix = "\n"),
            )
        }

        fun finish(outcome: String) = note("outcome $outcome; evidence ${folder?.absolutePath ?: "logcat only (tag C82)"}")

        companion object {
            fun start(target: Context, name: String): Run {
                val nonce = ByteArray(4).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                return Run(target, name, nonce).also { it.note("run $name-$nonce, ${Build.MODEL} API ${Build.VERSION.SDK_INT}") }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private fun refuseOccupied(stores: AppStores, run: Run) {
        val reasons = stores.occupancy()
        run.note("occupancy: ${reasons.ifEmpty { listOf("empty") }}")
        if (reasons.isNotEmpty()) {
            run.finish("REFUSED")
            throw AssertionError("REFUSED (nothing taken, nothing changed): this test runs on an empty disposable emulator only: $reasons")
        }
        // Empty: only now is anything written, the evidence folder first.
        run.begin()
    }

    private fun awaitSentinel(model: AppModel, agent: String, name: String) {
        pollUntil("$agent's sentinel $name") { onMain { model.commands[agent].orEmpty().any { it.name == name } } }
    }

    private fun pollUntil(what: String, seconds: Long = 20, done: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + seconds * 1000
        while (SystemClock.uptimeMillis() < until) {
            if (done()) return
            SystemClock.sleep(20)
        }
        setupFail("timed out waiting for $what")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun storedHostNames(): List<String> =
        decodeHosts(target.getSharedPreferences("hosts", Context.MODE_PRIVATE).getString("hosts", null) ?: "[]") { it }.first.map { it.name }

    private fun randomBytes() = ByteArray(64).also(SecureRandom()::nextBytes)

    private fun setup(condition: Boolean, why: () -> String) {
        if (!condition) setupFail(why())
    }

    private fun setupFail(why: String): Nothing = throw AssertionError("SETUP (no product result): $why")
}
