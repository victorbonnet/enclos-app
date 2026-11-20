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
import android.util.Xml
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.xmlpull.v1.XmlPullParser
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
 * taken, a target with any app store, device key, the Noise wrapping key, a
 * process hook a run sets, or push configured ([AppStores.occupancy]).
 */
@RunWith(AndroidJUnit4::class)
class ConnectedNeedsYouCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target: Context = instrumentation.targetContext

    /** The run the test started ([start]), concluded by [evidence]. */
    private var current: Run? = null

    /**
     * Concludes the test's run ([Run.conclude]) whatever stopped the test:
     * its outcome noted; an evidence write that failed fails the test,
     * carried in the test's own failure when there is one, never in its place.
     */
    @get:Rule
    val evidence = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val failed = runCatching { base.evaluate() }.exceptionOrNull()
                val run = current
                if (run != null) run.conclude(failed) else throwAll(failed, emptyList())
            }
        }
    }

    private fun start(name: String): Run = Run.start(target, name).also { current = it }

    /**
     * What the app may send in these runs: reads, sync, a conversation's read
     * position, and the `error` it answers the barrier with. Every frame the
     * app sends is built by `message(type, …)` (Bridge.kt).
     */
    private val reads = setOf(
        "agents", "sync", "conversation.read", "error", "agent.avatar.get", "agent.name.get", "usage.get", "audit", "sandboxes", "runtimes",
        "sandbox.policy.get", "attachment.get",
    )

    /**
     * The production frames that act on a host, each one a `C82 FAIL` here: an
     * answer, a resume, a dismissal, a message (`message.send`, a new
     * conversation's `conversation.new` first), a board, and every other
     * change. Any type in neither set fails too, as unclassified: never let
     * through unnoticed.
     */
    private val writes = setOf(
        HELD_TASK_ANSWER, "held_task.resume", HELD_TASK_BOARD, "open_item.dismiss", "message.send", "conversation.new", "conversation.rename",
        "conversation.archive", "conversation.delete", "group.new", "group.stop", "question.answer", "question.close", "approval.decide",
        "approval.close", "agent.forget", "agents.removed", "device.forget", "agent.name.set", "agent.avatar.set", "sandbox.policy.apply", "sandbox.create", "runtime.install", "model.request", "routine.request", "push.token", "pair.request",
    )

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
        val run = start("sheet-return")
        val s = Synthetic(run.nonce)
        val hooks = Hooks.now()
        // Refused before anything is taken: read only.
        refuseOccupied(AppStores(target), run)
        run.metadata(instrumentation, "held_task_sheet_exact_conversation_return", s.nonce, listOf(s.h1, s.h2))
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = true)
        val owner = ActivityOwner(fixture, run)
        val native = Native(instrumentation.uiAutomation, target.packageName, run)
        withNative(native, run) { owner.around { scenario(fixture, owner, native, run, s) } }
        assertGivenBack(owner, fixture, hooks, listOf(s.h1, s.h2), run, bridges = 2)
        assertTrue("SETUP: UiAutomation's flags not restored", native.restored())
    }

    /**
     * Runs [body] with [native] open, then restores its flags whatever
     * stopped it (open() itself half way included), after the body's own
     * teardown and independent of it and of the evidence. The body's failure
     * is thrown, the restoration's and [run]'s failed writes carried in it,
     * or the restoration's alone, the failed writes in it ([Run.raise]): a
     * scenario's, a fixture's, an evidence write's and the flags' failures
     * together, each the original object.
     */
    private fun withNative(native: Native, run: Run, body: () -> Unit) {
        val failed = runCatching {
            native.open()
            body()
        }.exceptionOrNull()
        val notRestored = runCatching { native.close() }.exceptionOrNull()
        run.raise(failed, listOfNotNull(notRestored))
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

    /** Synthetic-only parser evidence, after transport sentinels and before the unchanged setup check. */
    private fun parserDiagnostics(script: BridgeScript, host: String, model: AppModel, run: Run) {
        run.note("parser runtime: SDK ${Build.VERSION.SDK_INT}; Pattern loader=${java.util.regex.Pattern::class.java.classLoader}")
        fun probe(label: String, parse: () -> String) {
            try {
                run.note("parser $label: ${parse()}")
            } catch (t: Exception) {
                // The input is exclusively scripts(Synthetic). Retain the actual exception and
                // stack, which HeldTaskClient deliberately omits from production logs.
                run.note("parser $label failed:\n${t.stackTraceToString()}")
            }
        }
        probe("unicode-regex") { Regex("(?U)\\s+").split("synthetic whitespace").toString() }
        script.sync.filter { it.optString("type") == "held_task" }.forEachIndexed { index, frame ->
            val payload = frame.getJSONObject("held_task")
            probe("$host frame $index ask=${payload.has("ask")}") {
                val card = HeldTask.from(host, payload)
                val present = onMain { model.heldTasks.cards.containsKey(card.key) }
                "parsed task=${card.task} choices=${card.ask?.choices?.size} recommended=${card.ask?.recommended} Activity-present=$present"
            }
            if (payload.has("ask")) {
                probe("$host frame $index without-ask control") {
                    val control = JSONObject(payload.toString()).apply { remove("ask") }
                    "parsed task=${HeldTask.from(host, control).task}"
                }
            }
        }
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

        // Diagnostic replay only: these are the same finite synthetic frames already sent.
        // Never merge the replay into the Activity model or replace its setup assertion.
        parserDiagnostics(script1, s.h1, model, run)
        parserDiagnostics(script2, s.h2, model, run)

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
        native.waitNode("what is blocked") { it.window == sheet && it.text == s.blocked }
        val now = native.waitNode("the recommended choice", match = choice(sheet, s.choiceNow, s.choiceWait))
        val wait = native.waitNode("the other choice", match = choice(sheet, s.choiceWait, s.choiceNow))
        assertTrue("C82 FAIL: the recommended choice not marked: ${now.texts}", "Recommended" in now.texts)
        assertFalse("C82 FAIL: the other choice marked: ${wait.texts}", "Recommended" in wait.texts)
        assertTrue("C82 FAIL: the recommended choice not first", now.bounds.top < wait.bounds.top)
        assertTrue("C82 FAIL: the choices are not enabled while connected", now.enabled && wait.enabled)
        native.capture("sheet-initial")

        // ---- 3. an answer typed in Your answer, never sent
        exposeAnswerField(native, run, "Your answer", sheet, "draft_disclosure_opened")
        native.setText("Your answer", s.draft) { it.window == sheet && it.editable && answerField(it) }
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
        exposeAnswerField(native, run, "Your answer, again", reopened, "reopened_disclosure_opened")
        val field = native.waitNode("Your answer, again") { it.window == reopened && answerField(it) }
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
        native.waitNode("Ask the agent for choices") { it.window == noAsk && it.clickable && "Ask the agent for choices" in it.texts }
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
        exposeAnswerField(native, run, "Your answer, offline", offline, "offline_disclosure_opened")
        val fieldOff = native.waitNode("Your answer, offline") { it.window == offline && answerField(it) }
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
    private fun answerField(n: Node) = n.desc == "Your answer"

    /** The fold before the typed answer (HeldTaskAnswerPanel): the control naming exactly Answer in your own words. */
    private fun answerDisclosure(n: Node) = n.clickable && "Answer in your own words" in n.texts

    /**
     * The answer field of the sheet in [window], on the screen. The sheet folds it behind
     * Answer in your own words until it is written in or a draft is kept: either is found by
     * scrolling, and the fold is opened (in [window], the one control) only when the field is
     * absent from the window, not merely off the screen. Whether it was opened is [observed],
     * never decided; nothing is typed, sent or set in the model here. Required after: a field
     * not there is a failure, never skipped.
     */
    private fun exposeAnswerField(native: Native, run: Run, what: String, window: Int, observed: String) {
        native.scrollTo("$what or Answer in your own words", window) { answerField(it) || answerDisclosure(it) }
        val absent = native.nodes().none { it.window == window && answerField(it) }
        run.observe(observed, absent)
        if (absent) {
            native.clickUnique("Answer in your own words, for $what") { it.window == window && answerDisclosure(it) }
            native.waitNode("$what, opened") { it.window == window && answerField(it) }
        }
        native.scrollTo(what, window, ::answerField)
    }

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
     * every type received over any connection of either bridge is one of
     * [reads]: one of [writes] is a `C82 FAIL`, any other an unclassified
     * `SETUP` failure.
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
        val actions = sent.filter { it in writes }
        assertTrue("C82 FAIL: the app sent $actions by $label", actions.isEmpty())
        val unclassified = sent.filter { it !in reads }
        setup(unclassified.isEmpty()) { "the app sent $unclassified by $label: neither a read nor a known write; classify it" }
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
        val run = start("occupied")
        refuseOccupied(AppStores(target), run)
        val stores = AppStores(target, "c82-occ-${run.nonce}-")
        refuseEachStore(stores, run)
        assertTrue("sentinels left", stores.paths().none(File::exists))
        val listener: (String) -> Unit = {}
        val originalListener = PushTokens.changed
        PushTokens.changed = listener
        val listenerChecked = runCatching {
            assertRefused(stores, run, "a live model's push-token listener")
            assertSame("the listener changed by the refusal", listener, PushTokens.changed)
        }.exceptionOrNull()
        attemptAll(listenerChecked, listOf { PushTokens.changed = originalListener })
        val original = Platform.get()
        val leaked = LoopbackCleartext(original)
        Platform.resetForTests(leaked)
        val hookChecked = runCatching {
            assertRefused(stores, run, "a leaked platform hook")
            assertSame("the hook changed by the refusal", leaked, Platform.get())
        }.exceptionOrNull()
        attemptAll(hookChecked, listOf { Platform.resetForTests(original) })
    }

    /**
     * A sentinel path found present (one this control made first, as another
     * owner would have) stops [refuseEachStore] as a `SETUP` failure: that
     * path is kept, byte for byte, and only the sentinels the check made are
     * gone.
     */
    @Test
    fun a_store_path_found_present_is_kept_by_the_sentinel_check() {
        val run = start("occupied-present")
        refuseOccupied(AppStores(target), run)
        val stores = AppStores(target, "c82-pre-${run.nonce}-")
        // The first cache path (its folder, the app's cache folder, always exists): every preference and database path before it is made, refused and removed first.
        val found = stores.paths().first { it.parentFile == target.cacheDir }
        setup(!found.exists()) { "${found.name} exists already: not this control's" }
        val bytes = randomBytes()
        val failed = runCatching {
            found.writeBytes(bytes)
            val thrown = runCatching { refuseEachStore(stores, run) }.exceptionOrNull()
            assertTrue("not stopped at ${found.name}: $thrown", thrown is AssertionError && thrown.message.orEmpty().startsWith("SETUP") && found.name in thrown.message.orEmpty())
            assertTrue("the path found present not kept", found.readBytes().contentEquals(bytes))
            assertTrue("the check's own sentinels left", stores.paths().filter { it != found }.none(File::exists))
        }.exceptionOrNull()
        // Made by this control after it was found absent: its own to remove, and only it.
        attemptAll(failed, listOf { setup(found.delete() || !found.exists()) { "${found.name} not removed" } })
    }

    /** A device key no paired host names (AppModel's sweep would delete it) is refused too, the key kept. */
    @Test
    fun an_unpaired_device_key_is_refused_and_kept() {
        val run = start("key-refusal")
        refuseOccupied(AppStores(target), run)
        val alias = "${DEVICE_KEY_PREFIX}c82-occupied-${run.nonce}"
        // Only a key this check made is its own to delete: not one found under the alias before.
        var ownsKey = false
        val failed = runCatching {
            setup(!AppStores.keyStore().containsAlias(alias)) { "$alias exists already: not this check's" }
            ownsKey = true
            Keys.newDeviceKey(alias)
            assertRefused(AppStores(target), run, "an unpaired device key")
            assertTrue("the key was not kept", AppStores.keyStore().containsAlias(alias))
        }.exceptionOrNull()
        attemptAll(failed, listOf { if (ownsKey) AppStores.keyStore().run { if (containsAlias(alias)) deleteEntry(alias) } })
        assertFalse("the check's own key was not deleted", AppStores.keyStore().containsAlias(alias))
    }

    /**
     * The Noise wrapping key present (the app's, made here by the app's own
     * Keys.wrap) is refused before anything is taken: the same key is kept,
     * proved by unwrapping what it wrapped before the refusal.
     */
    @Test
    fun an_existing_wrapping_key_is_refused_and_kept() {
        val run = start("wrap-refusal")
        // Found absent here (the occupancy check): only a key this check made is its own to delete.
        refuseOccupied(AppStores(target), run)
        var ownsKey = false
        val failed = runCatching {
            setup(!AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS)) { "$NOISE_WRAP_ALIAS exists already: not this check's" }
            ownsKey = true
            val plain = randomBytes()
            val wrapped = Keys.wrap(plain)
            val created = AppStores.keyStore().getCreationDate(NOISE_WRAP_ALIAS)
            assertRefused(AppStores(target), run, "an existing wrapping key")
            assertEquals("the wrapping key replaced", created, AppStores.keyStore().getCreationDate(NOISE_WRAP_ALIAS))
            assertTrue("the wrapping key changed: what it wrapped no longer unwraps", Keys.unwrap(wrapped).contentEquals(plain))
        }.exceptionOrNull()
        attemptAll(failed, listOf { if (ownsKey) AppStores.keyStore().run { if (containsAlias(NOISE_WRAP_ALIAS)) deleteEntry(NOISE_WRAP_ALIAS) } })
        assertFalse("the check's own wrapping key was not deleted", AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS))
    }

    /** A setup stopped half way (H2's bridge, after H1 was paired): what was made is given back, only that; the failure is the one thrown. */
    @Test
    fun a_partial_acquisition_is_given_back() {
        val run = start("partial")
        val hooks = Hooks.now()
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
        assertEquals("hosts left", emptyList<String>(), pairedHostNames())
        assertGivenBack(owner, fixture, hooks, listOf(s.h1, s.h2), run, bridges = 1)
    }

    /**
     * The model's cleanup failing on every attempt of the owner (injected,
     * before the Activity is finished): the bridges are closed and the hook
     * restored, but the hosts, keys and stores the live model may use stay
     * held, untouched, and the Activity live. A release with that failure
     * stopped but the model's Store close failing (injected) ends the
     * Activity and clears the model, and still keeps the model step and the
     * storage held. A later retry, once that failure stops too, closes the
     * Store, then gives them back; a release after that does nothing.
     */
    @Test
    fun a_persistent_model_cleanup_failure_keeps_storage_until_a_later_retry() {
        val run = start("model-held")
        val hooks = Hooks.now()
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
            assertTrue("the pairing not kept", s.h1 in pairedHostNames())
            assertTrue("the key not kept", AppStores.keyStore().containsAlias(ReplacementFixture.aliasOf(s.h1)))
            assertEquals("the Activity not kept live", 1, owner.liveActivities().size)
            assertSame("the hook not restored while the model was held", hooks.platform, Platform.get())
            // The model step let through, its Store's close failing: the model ends, the step and the storage stay held.
            failing = false
            owner.beforeStoreClose = { throw Injected("store close") }
            val closeFailed = try {
                runCatching { fixture.release() }.exceptionOrNull()
            } finally {
                owner.beforeStoreClose = {}
            }
            run.note("store close injected: $closeFailed; held ${fixture.held}")
            assertTrue("not the injected close failure: $closeFailed", closeFailed is IllegalStateException && closeFailed.cause is Injected)
            assertFalse("the Store reported closed", owner.storeClosed)
            assertTrue("the model not ended before its Store's close", owner.modelCleared && owner.liveActivities().isEmpty())
            val stillHeld = fixture.held
            assertTrue("not held after the failed close: $stillHeld", "model" in stillHeld && "host ${s.h1}" in stillHeld && "key ${ReplacementFixture.aliasOf(s.h1)}" in stillHeld && "database conversations.db" in stillHeld)
            assertTrue(
                "storage released after the failed close: $attempts",
                attempts.none { it.startsWith("host ") || it.startsWith("key ") || it.startsWith("preferences ") || it.startsWith("database ") || it.startsWith("cache ") },
            )
            assertTrue("the pairing not kept after the failed close", s.h1 in pairedHostNames())
            assertTrue("the database not kept after the failed close", AppStores(target).databaseFiles().first().exists())
        }
        assertTrue("the Store not closed by the later retry", owner.storeClosed)
        assertTrue("storage not released after the model ended: ${fixture.released}", fixture.released.any { it.startsWith("host ") })
        assertGivenBack(owner, fixture, hooks, listOf(s.h1), run, bridges = 1)
        val count = fixture.released.size
        fixture.release()
        assertEquals("a second release changed something: ${fixture.released}", count, fixture.released.size)
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
        val run = start("bridge-held")
        val hooks = Hooks.now()
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
            assertEquals("pairings left", listOf(s.h1), pairedHostNames())
            assertTrue("H1's key not kept", AppStores.keyStore().containsAlias(ReplacementFixture.aliasOf(s.h1)))
            assertFalse("H2's key kept", AppStores.keyStore().containsAlias(ReplacementFixture.aliasOf(s.h2)))
            assertTrue("H1's bridge not kept listening", owner.bridgesMade.first { it.name == s.h1 }.listening)
            assertSame("the hook not restored", hooks.platform, Platform.get())
            failing = false
        }
        assertGivenBack(owner, fixture, hooks, listOf(s.h1, s.h2), run, bridges = 2)
        val count = fixture.released.size
        fixture.release()
        assertEquals("a second release changed something: ${fixture.released}", count, fixture.released.size)
    }

    /**
     * Every evidence write of the owner refused ([blockedRun]: real I/O)
     * while the check fails, connected, and H1's bridge's first close fails
     * (injected): the model step ends the Activity, its notes before the
     * finish and after the Store's close failing; the bridge is closed on
     * the retry, then the storage given back. The check's own failure is the
     * one thrown, the bridge's and every failed write carried in it, each the
     * original object. Concluded, that run throws the same failure, its
     * outcome's own failed write in it; with no failure, its first failed
     * write: never a pass.
     */
    @Test
    fun a_failed_evidence_write_stops_no_release_or_retry_and_fails_the_run() {
        val run = start("evidence-failure")
        val hooks = Hooks.now()
        refuseOccupied(AppStores(target), run)
        val s = Synthetic(run.nonce)
        val blocked = blockedRun(run)
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = true)
        val owner = ActivityOwner(fixture, blocked)
        val attempts = mutableListOf<String>()
        val injected = mutableListOf<Throwable>()
        fixture.beforeCleanup = { label ->
            attempts += label
            if (label == "bridge ${s.h1}" && injected.isEmpty()) throw Injected(label).also { injected += it }
        }
        val primary = AssertionError("deliberate failure while connected")
        val thrown = runCatching {
            owner.around {
                fixture.acquire()
                val b = fixture.pair(s.h1, scripts(s).first)
                fixture.beforeModel()
                fixture.holdModel(owner::endActivity)
                owner.launch(instrumentation)
                setup(b.nextSession() != null) { "no connection: ${b.problems}" }
                awaitSentinel(owner.model!!, "${s.h1}/${s.agent}", "sync-1")
                throw primary
            }
        }.exceptionOrNull()
        laterRetry(fixture, run) {
            run.note("blocked writes: ${blocked.writesFailed.size}; cleanup attempts: $attempts; held ${fixture.held}")
            assertSame("not the check's own failure: $thrown", primary, thrown)
            assertEquals("the bridge's close not failed once: $injected", 1, injected.size)
            assertTrue("the bridge's failure not carried: ${primary.suppressed.toList()}", primary.reaches(injected.single()))
            assertTrue("no write failed after the probe: ${blocked.writesFailed}", blocked.writesFailed.size > 1)
            assertTrue("a failed write not carried", blocked.writesFailed.all(primary::reaches))
            assertEquals("model steps attempted: $attempts", 1, attempts.count { it == "model" })
            assertEquals("bridge closes attempted: $attempts", 2, attempts.count { it == "bridge ${s.h1}" })
            assertTrue("the model step not done: Activity, model or Store", owner.liveActivities().isEmpty() && owner.modelCleared && owner.storeClosed)
            assertTrue("not given back on the retry: ${fixture.held}", fixture.held.isEmpty() && !fixture.owned)
            val before = blocked.writesFailed
            val concluded = runCatching { blocked.conclude(primary) }.exceptionOrNull()
            assertSame("the conclusion replaced the check's failure: $concluded", primary, concluded)
            assertEquals("the outcome's own write did not fail", before.size + 1, blocked.writesFailed.size)
            assertTrue("a failed write not carried by the conclusion", blocked.writesFailed.all(primary::reaches))
            val alone = runCatching { blocked.conclude(null) }.exceptionOrNull()
            assertSame("a run with failed writes alone not failed by its first: $alone", blocked.writesFailed.first(), alone)
            assertTrue("a failed write not carried by the first", blocked.writesFailed.all { alone!!.reaches(it) })
        }
        assertGivenBack(owner, fixture, hooks, listOf(s.h1), run, bridges = 1)
    }

    /**
     * UiAutomation's flags with every evidence write refused: the check, the
     * fixture's first release (its platform step, injected) and the flags'
     * first try (injected) failing together, the check's failure is the one
     * thrown, the fixture's, the flags' and the failed writes in it; the
     * fixture is given back and the flags restored by their retries, no
     * longer owed (a later close tries nothing). Both tries failing, the
     * first try's own failure is thrown, the second in it, the flags still
     * owed until a later close restores them.
     */
    @Test
    fun the_ui_flags_are_restored_and_reported_whatever_the_evidence_or_the_fixture_did() {
        val run = start("ui-flags")
        val hooks = Hooks.now()
        refuseOccupied(AppStores(target), run)
        val blocked = blockedRun(run)
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = false)
        val owner = ActivityOwner(fixture, blocked)
        val native = Native(instrumentation.uiAutomation, target.packageName, blocked)
        val released = mutableListOf<Throwable>()
        fixture.beforeCleanup = { if (it == "platform" && released.isEmpty()) throw Injected(it).also { e -> released += e } }
        var tries = 0
        var failing = 1
        val ui = mutableListOf<Throwable>()
        native.beforeRestore = {
            tries++
            if (failing > 0) {
                failing--
                throw Injected("UiAutomation flags, try $tries").also { ui += it }
            }
        }
        val primary = AssertionError("deliberate failure with the flags changed")
        val checked = runCatching {
            val thrown = runCatching { withNative(native, blocked) { owner.around { fixture.acquire(); throw primary } } }.exceptionOrNull()
            run.note("together: $thrown; carried ${thrown?.suppressed?.toList()}")
            assertSame("not the check's own failure: $thrown", primary, thrown)
            assertTrue("the fixture's or the flags' failure not carried", released.size == 1 && ui.size == 1 && primary.reaches(released[0]) && primary.reaches(ui[0]))
            assertTrue("a failed write not carried", blocked.writesFailed.size > 1 && blocked.writesFailed.all(primary::reaches))
            assertTrue("the fixture not given back on its retry: ${fixture.held}", fixture.held.isEmpty() && !fixture.owned)
            assertSame("the platform hook not restored", hooks.platform, Platform.get())
            assertTrue("the flags not restored by the retry: $tries tries", tries == 2 && native.restored())
            native.close()
            assertEquals("a close after the flags were restored tried again", 2, tries)
            failing = 2
            val bothFailed = runCatching { withNative(native, blocked) {} }.exceptionOrNull()
            run.note("both tries failed: $bothFailed; carried ${bothFailed?.suppressed?.toList()}")
            assertTrue("not the first try's own failure, the second in it: $bothFailed", ui.size == 3 && bothFailed === ui[1] && ui[1].reaches(ui[2]))
            assertFalse("restored after both tries failed", native.restored())
            native.close()
            assertTrue("the flags not restored by a later close: $tries tries", tries == 5 && native.restored())
        }.exceptionOrNull()
        // Whatever the checks did: the flags restored, the fixture given back.
        native.beforeRestore = {}
        attemptAll(checked, listOf({ native.close() }, { fixture.beforeCleanup = {}; fixture.release() }))
        assertGivenBack(owner, fixture, hooks, emptyList(), run, bridges = 0)
        assertTrue("SETUP: UiAutomation's flags not restored", native.restored())
    }

    /**
     * A check's failure that can carry nothing (its suppression disabled)
     * loses no other: with every evidence write refused, the fixture's
     * release and its retry (the platform step, injected) and both of the
     * flags' tries (injected) failing too, the one thrown is a [Carried],
     * its cause the check's own failure, the raw object, holding nothing,
     * and every other failure, each the original object, reachable from it.
     * Concluded, the run throws that same [Carried], its outcome's own failed
     * write in it. A later close and release restore the flags and give the
     * fixture back.
     */
    @Test
    fun a_failure_that_carries_nothing_loses_no_other() {
        val run = start("uncarried")
        val hooks = Hooks.now()
        refuseOccupied(AppStores(target), run)
        val primary = CarriesNothing("deliberate failure that carries nothing")
        primary.addSuppressed(Injected("suppression probe"))
        setup(primary.suppressed.isEmpty()) { "the failure's suppression is not disabled: ${primary.suppressed.toList()}" }
        assertSame("a lone failure not thrown as itself", primary, runCatching { throwAll(primary, emptyList()) }.exceptionOrNull())
        val blocked = blockedRun(run)
        val fixture = ReplacementFixture(AppStores(target), modelAllowed = false)
        val owner = ActivityOwner(fixture, blocked)
        val native = Native(instrumentation.uiAutomation, target.packageName, blocked)
        val released = mutableListOf<Throwable>()
        fixture.beforeCleanup = { if (it == "platform") throw Injected("$it, release ${released.size + 1}").also { e -> released += e } }
        val ui = mutableListOf<Throwable>()
        native.beforeRestore = { throw Injected("UiAutomation flags, try ${ui.size + 1}").also { ui += it } }
        val checked = runCatching {
            val thrown = runCatching { withNative(native, blocked) { owner.around { fixture.acquire(); throw primary } } }.exceptionOrNull()
            run.note("carries nothing: $thrown; carried ${thrown?.suppressed?.toList()}")
            assertTrue("not a Carried: $thrown", thrown is Carried)
            val carried = thrown as Carried
            assertSame("the check's own failure not its cause", primary, carried.cause)
            assertTrue("the check's own failure holds something: ${primary.suppressed.toList()}", primary.suppressed.isEmpty())
            assertEquals("releases failed: $released", 2, released.size)
            assertEquals("the flags' tries failed: $ui", 2, ui.size)
            assertTrue("no write failed after the probe: ${blocked.writesFailed}", blocked.writesFailed.size > 1)
            val others = released + ui + blocked.writesFailed
            assertTrue("a failure lost: ${others.filterNot(carried::reaches)}", others.all(carried::reaches))
            assertTrue("the fixture not held after both releases failed: ${fixture.held}", "platform" in fixture.held)
            assertFalse("the flags restored after both tries failed", native.restored())
            val before = blocked.writesFailed.size
            val concluded = runCatching { blocked.conclude(carried) }.exceptionOrNull()
            assertSame("the conclusion replaced the Carried: $concluded", carried, concluded)
            assertEquals("the outcome's own write did not fail", before + 1, blocked.writesFailed.size)
            assertTrue("a failed write not carried by the conclusion", blocked.writesFailed.all(carried::reaches))
        }.exceptionOrNull()
        // Whatever the checks did: the flags restored, the fixture given back.
        native.beforeRestore = {}
        attemptAll(checked, listOf({ native.close() }, { fixture.beforeCleanup = {}; fixture.release() }))
        assertGivenBack(owner, fixture, hooks, emptyList(), run, bridges = 0)
        assertTrue("SETUP: UiAutomation's flags not restored", native.restored())
    }

    /**
     * A second run of [run]'s test whose every transcript write fails: its
     * transcript is a folder (a real write failure, in its own evidence
     * folder beside [run]'s, nothing else changed). Checked: a note fails.
     */
    private fun blockedRun(run: Run): Run {
        val blocked = Run(target, "${run.name}-blocked", run.nonce)
        blocked.begin()
        val transcript = File(blocked.dir, "transcript.txt")
        setup(transcript.delete() && transcript.mkdir()) { "the blocked run's transcript not replaced by a folder" }
        blocked.note("a write that must fail")
        setup(blocked.writesFailed.size == 1) { "a write to a folder did not fail: ${blocked.writesFailed}" }
        return blocked
    }

    /**
     * Runs [checks]; then, whatever they did, stops the injected failure's
     * effect by a later release, attempted whatever the checks or the
     * evidence did. The checks' failure is thrown, the release's carried in
     * it, or the release's alone, with the run's failed writes ([Run.raise]).
     */
    private fun laterRetry(fixture: ReplacementFixture, run: Run, checks: () -> Unit) {
        val checked = runCatching(checks).exceptionOrNull()
        fixture.beforeCleanup = {}
        val retried = runCatching { fixture.release() }.exceptionOrNull()
        run.note("later retry: ${retried ?: "given back"}; released ${fixture.released}")
        run.raise(checked, listOfNotNull(retried))
    }

    /**
     * A sentinel at each store path of [stores], one at a time, each refused
     * ([assertRefused]). A path, or the folder made for it, is this check's
     * only once found absent, recorded before it is made; only those are
     * removed, each checked, every removal attempted, the failures carried
     * together. A path found present stops the check (`SETUP`), and is kept.
     */
    private fun refuseEachStore(stores: AppStores, run: Run) {
        // Owned roots, in the order made: removed in reverse, a folder after what was made in it.
        val owned = mutableListOf<File>()
        fun remove(root: File) {
            // A folder made for a sentinel is removed only when empty (never recursively); a sentinel cache folder holds only its own file.
            if (root.isDirectory && root.parentFile != target.cacheDir) root.delete() else root.deleteRecursively()
            setup(!root.exists()) { "${root.name}, the check's own, not removed" }
            owned -= root
        }
        val failed = runCatching {
            stores.paths().forEach { path ->
                setup(!path.exists()) { "${path.name} exists already: not this check's, kept" }
                val folder = path.parentFile ?: setupFail("${path.name} has no folder")
                if (!folder.exists()) {
                    setup(folder.parentFile?.exists() == true) { "${folder.name}'s own folder is missing" }
                    owned += folder
                    setup(folder.mkdir()) { "${folder.name} not made" }
                }
                owned += path
                if (folder == target.cacheDir) {
                    setup(path.mkdir()) { "${path.name} not made" }
                    File(path, "sentinel.bin").writeBytes(randomBytes())
                } else {
                    path.writeBytes(randomBytes())
                }
                assertRefused(stores, run, "store ${path.name}")
                remove(path)
            }
        }.exceptionOrNull()
        attemptAll(failed, owned.reversed().map { root -> { remove(root) } })
    }

    /**
     * Runs every one of [steps], each attempted whatever [primary] or the
     * others did; then throws [primary], their failures carried in it, or,
     * with none, the first of them, the others in it ([throwAll]); returns
     * when nothing failed. Called after what [primary] stopped, never in a
     * `finally`: what it throws replaces nothing.
     */
    private fun attemptAll(primary: Throwable?, steps: List<() -> Unit>) =
        throwAll(primary, steps.mapNotNull { runCatching(it).exceptionOrNull() })

    private fun assertRefused(stores: AppStores, run: Run, what: String) {
        val before = stores.bytes()
        val own = AppStores(target).bytes()
        val aliases = AppStores.deviceAliases()
        val wrapCreated = AppStores.keyStore().run { if (containsAlias(NOISE_WRAP_ALIAS)) getCreationDate(NOISE_WRAP_ALIAS) else null }
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
        assertEquals(
            "$what: the wrapping key changed by the refusal", wrapCreated,
            AppStores.keyStore().run { if (containsAlias(NOISE_WRAP_ALIAS)) getCreationDate(NOISE_WRAP_ALIAS) else null },
        )
        assertSame("$what: the platform hook changed by the refusal", hooks.platform, Platform.get())
        assertSame("$what: the push-token listener changed by the refusal", hooks.push, PushTokens.changed)
        run.note("refused, unchanged: $what (${before.size} paths)")
    }

    /** After teardown: no MainActivity, its model cleared and its Store closed, the hooks the original ones, nothing held, bridges closed, keys gone, the target empty. */
    private fun assertGivenBack(owner: ActivityOwner, fixture: ReplacementFixture, hooks: Hooks, hosts: List<String>, run: Run, bridges: Int) {
        assertTrue("SETUP: a MainActivity outlived the run", owner.liveActivities().isEmpty())
        assertTrue("SETUP: the Activity's model was not cleared, or its Store not closed", !owner.modelSeen || (owner.modelCleared && owner.storeClosed))
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
        assertFalse("SETUP: the run's wrapping key left", AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS))
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

    /** A failure whose suppression is disabled, as some the platform throws are: addSuppressed on it does nothing. */
    private class CarriesNothing(message: String) : RuntimeException(message, null, false, true)

    /** What the refusing factory throws: the Activity's ViewModelStore holds no AppModel. */
    private class NoModel : IllegalStateException("the Activity's ViewModelStore holds no AppModel")

    /** The process hooks a run sets, by identity. */
    private class Hooks(val platform: Platform, val push: Any?) {
        companion object {
            fun now() = Hooks(Platform.get(), PushTokens.changed)
        }
    }

    /**
     * Owns the Activity and the fixture: [around] runs a check, then gives
     * back everything whatever stopped it, the Activity first (finished,
     * destroyed, its model cleared, its writers drained, the model's Store
     * closed) through the
     * fixture's model step, then the rest ([ReplacementFixture.release]). A
     * cleanup that failed is tried once more; what still fails stays held and
     * is thrown, carried in the check's own failure when there is one. A note
     * never throws ([Run.note]): no evidence write stops a release or a retry.
     */
    private inner class ActivityOwner(private val fixture: ReplacementFixture, private val run: Run) {
        /** The Activity's model, read back, never made. */
        var model: AppModel? = null
            private set
        /** The model's own Store, read back with it ([storeOf]): closed by the model step once its writers drained. */
        private var store: Store? = null
        var activityId = "none"
            private set
        var modelId = "none"
            private set
        private var cleared: CountDownLatch? = null
        /** Whether a model was read back from an Activity: its clear and its Store's close must then be proved. */
        val modelSeen: Boolean get() = model != null
        val modelCleared: Boolean get() = cleared?.count == 0L
        /** Whether the model step closed the model's Store, its connection to the database. */
        var storeClosed = false
            private set

        /** Run just before the Store's close: a control injects a failed close here. */
        var beforeStoreClose: () -> Unit = {}

        /** From just before startActivitySync: an Activity, and a model, may exist from then on. */
        private var launchAttempted = false
        private var launchReturned = false

        /** Why the model's end cannot be proved, once known: sticky, so the model step, and the storage behind it, stays held. */
        private var unprovable: String? = null

        /** Every bridge the fixture made, kept for the checks after teardown (the fixture forgets those it closed). */
        var bridgesMade: List<FakeBridge> = emptyList()
            private set

        /**
         * Runs [check], then [finish] whatever stopped it. Throws the check's
         * failure, the teardown's and the run's failed writes carried in it,
         * or the teardown's, the failed writes in it ([Run.raise]), each the
         * original object. Failed writes alone are left to the run's
         * conclusion ([Run.conclude]): the checks after the teardown still run.
         */
        fun around(check: () -> Unit) {
            val failed = runCatching(check).exceptionOrNull()
            failed?.let { run.note("check failed: $it") }
            // Before any release: the fixture holds every bridge it made until it closed it.
            bridgesMade = fixture.bridges.toList()
            val teardown = finish()
            teardown?.let { run.note("teardown failed: $it") }
            if (failed != null || teardown != null) run.raise(failed, listOfNotNull(teardown))
        }

        /**
         * Releases, once more if anything is still held; null when all was
         * given back at the first attempt. Otherwise the first failure, the
         * retry's carried in it ([carrying]): reported even when the retry
         * gave all back, never hidden by it.
         */
        private fun finish(): Throwable? {
            val first = runCatching { fixture.release() }.exceptionOrNull() ?: return null
            run.note("not all given back: $first; held ${fixture.held}; retried")
            val second = runCatching { fixture.release() }.exceptionOrNull()
            run.note(if (fixture.held.isEmpty()) "given back on retry: ${fixture.released}" else "still held after the retry: ${fixture.held}")
            return carrying(first, listOfNotNull(second))
        }

        /** Launches MainActivity as the system does, and reads back the model it made (never makes one). */
        fun launch(instrumentation: android.app.Instrumentation): MainActivity {
            val intent = Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            baseline = Thread.getAllStackTraces().keys
            launchAttempted = true
            val activity = instrumentation.startActivitySync(intent) as MainActivity
            launchReturned = true
            observePool()
            activityId = "${activity.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(activity))}"
            readBack(activity)
            setup(model != null) { "the Activity had no AppModel; none is made by the test" }
            return activity
        }

        /** The Activity's own AppModel, from its ViewModelStore; a factory that refuses ([NoModel]) proves none is made here. */
        fun activityModel(activity: MainActivity): AppModel = onMain {
            val refuse = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = throw NoModel()
            }
            ViewModelProvider(activity.viewModelStore, refuse, activity.defaultViewModelCreationExtras)[AppModel::class.java]
        }

        /**
         * Reads back [activity]'s model (never makes one), reads its Store
         * ([storeOf]) and registers its clear latch, before it is kept: a
         * read or a registration that fails leaves it to the next read, and
         * the step stops before any finish. The one already kept is done; a
         * second one beside it is ambiguous, and throws. None in a finishing
         * Activity's store, with none kept, may be one cleared already,
         * unseen: sticky, unprovable.
         */
        private fun readBack(activity: MainActivity) {
            val m = try {
                activityModel(activity)
            } catch (_: NoModel) {
                if (activity.isFinishing && model == null) {
                    unprovable = "a finishing Activity's store held no AppModel, and none was read before: whether one was made, and is gone, is unknown"
                }
                return
            }
            if (model === m) return
            check(model == null) { "a second AppModel ($m) beside the one read back: which one writes is ambiguous" }
            val s = storeOf(m)
            val latch = CountDownLatch(1)
            onMain { m.addCloseable(AutoCloseable { latch.countDown() }) }
            cleared = latch
            store = s
            model = m
            modelId = "AppModel@${Integer.toHexString(System.identityHashCode(m))}"
        }

        /**
         * [m]'s own Store: AppModel's private `store` field, read only (never
         * set), test-only, the exact field of exactly [Store]'s type. Its
         * field missing, of another type, unreadable or holding no Store, it
         * throws: the model is not kept, and the step stays held.
         */
        private fun storeOf(m: AppModel): Store {
            val held = try {
                val field = AppModel::class.java.getDeclaredField("store")
                check(field.type == Store::class.java) { "AppModel.store is a ${field.type.name}, not a Store" }
                field.isAccessible = true
                field.get(m)
            } catch (e: Exception) {
                throw IllegalStateException("the model's Store could not be read back: its close cannot be proved", e)
            }
            return held as? Store ?: throw IllegalStateException("AppModel.store holds no Store: its close cannot be proved")
        }

        fun resumed(): Activity? = onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity } }

        fun liveActivities(): List<Activity> = onMain {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }.filter { it is MainActivity }
        }

        /**
         * The fixture's model step: each live MainActivity's model read back
         * and its clear latch registered before that Activity is finished;
         * every one destroyed; the model cleared, no model's push-token
         * listener left, the writers drained (whenever a launch was
         * attempted, a model read back or not), then the model's Store
         * closed ([closeStore]). Throws when any of it is not proved: the
         * step, and the storage behind it, stays held.
         */
        fun endActivity() {
            unprovable?.let { throw IllegalStateException(it) }
            if (launchAttempted && !launchReturned && model == null && liveActivities().isEmpty()) {
                unprovable = "the launch threw before its Activity's model was read back, and no Activity is live: whether a model was made, and is gone, is unknown"
            }
            val until = SystemClock.uptimeMillis() + 20_000
            while (true) {
                val live = liveActivities()
                if (live.isEmpty()) break
                if (SystemClock.uptimeMillis() >= until) throw IllegalStateException("activities not destroyed within 20 s: $live")
                // A note never throws (Run.note): a failed write neither keeps the Activity live nor the step held.
                run.note("ending activities $live")
                // Read back before it is finished: a model is never let go unseen.
                live.forEach { readBack(it as MainActivity) }
                observePool()
                onMain { live.filterNot(Activity::isFinishing).forEach(Activity::finish) }
                SystemClock.sleep(50)
            }
            unprovable?.let { throw IllegalStateException(it) }
            cleared?.let { latch ->
                if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("the Activity's AppModel was not cleared within 20 s")
            }
            // A model's init sets it, its onCleared resets it: one still set is a model still live, read back or not.
            if (PushTokens.changed != null) throw IllegalStateException("an AppModel's push-token listener is still set: a model is live in this process")
            // Main idle: ViewModel.clear() ran onCleared, which shut the executors down, after the closeable's latch.
            instrumentation.waitForIdleSync()
            if (launchAttempted) awaitPoolThreads()
            closeStore()
            run.note("activity destroyed, model cleared=$modelCleared, store closed=$storeClosed, new pool threads ended: ${poolSeen.map { it.name }}")
            poolSeen.clear()
        }

        /**
         * Closes the model's Store (SQLiteOpenHelper's own close), only once
         * the model is cleared and its writers drained: every use of the
         * Store runs on the model's executors, shut down by onCleared, so
         * nothing reopens it. The ReplacementFixture's database step comes
         * after this step: never a database deleted under an open connection.
         * A close that fails throws; the step, and the storage behind it,
         * stays held. No model read back: no Store to close.
         */
        private fun closeStore() {
            if (model == null || storeClosed) return
            val s = store ?: throw IllegalStateException("the model's Store was not read back: its close cannot be proved")
            try {
                beforeStoreClose()
                s.close()
            } catch (t: Throwable) {
                throw IllegalStateException("the model's Store not closed: its connection may still be open", t)
            }
            storeClosed = true
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

        /** Run just before each restoration try ([restore]): a control injects a failed try here. */
        var beforeRestore: () -> Unit = {}

        /**
         * Restores the flags, tried twice. Restored by either try, they are
         * no longer owed: a later close does nothing. A failed first try is
         * never hidden: its raw failure is thrown even when the retry
         * restored them, the retry's carried in it when it failed too, the
         * flags then still owed (and the next close tries again).
         */
        fun close() {
            if (!restoring) return
            val flags = flagsBefore ?: return
            val first = restore(flags)
            if (first == null) {
                restoring = false
                run.note("UiAutomation flags restored: $flags")
                return
            }
            run.note("UiAutomation flags not restored: $first; retried")
            val second = restore(flags)
            if (second == null) restoring = false
            run.note(if (second == null) "UiAutomation flags restored on retry: $flags; the first failure is still thrown" else "UiAutomation flags still owed: $second")
            throw carrying(first, listOfNotNull(second))
        }

        /** One restoration try: its failure, or null once the flags were set back. */
        private fun restore(flags: Int): Throwable? = runCatching {
            beforeRestore()
            ui.serviceInfo = ui.serviceInfo.also { it.flags = flags }
        }.exceptionOrNull()

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
            throw carrying(timeout, listOfNotNull(runCatching { capture("timeout-${what.take(40)}") }.exceptionOrNull()))
        }
    }

    /**
     * One run's evidence: logcat (tag `C82`) from the start; its folder in the
     * target's external files only once [begin] is called, after the target
     * was found empty, so a refusal writes nothing at all.
     *
     * A note never throws: a write that failed is kept, the failure itself
     * ([writesFailed]), so no release, restoration or retry depends on the
     * evidence being written. Every failed write fails the run, carried in
     * whatever is thrown at a boundary ([raise]) and at its end ([conclude]).
     */
    private class Run(private val target: Context, val name: String, val nonce: String) {
        private val pending = mutableListOf<String>()
        private var folder: File? = null
        val dir: File get() = folder ?: throw AssertionError("SETUP: evidence written before the target was found empty")

        private val failedWrites = mutableListOf<Throwable>()

        /** Every note that failed to be written, its own failure, each once, in order. */
        val writesFailed: List<Throwable> get() = failedWrites.toList()

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

        /** Notes [text] in logcat and the transcript; never throws: a failed write is kept ([writesFailed]). */
        fun note(text: String) {
            try {
                val line = "${SystemClock.uptimeMillis()} pid=${Process.myPid()} $text"
                Log.i("C82", line)
                folder?.let { File(it, "transcript.txt").appendText(line + "\n") } ?: pending.add(line)
            } catch (t: Throwable) {
                if (failedWrites.none { it === t }) failedWrites += t
            }
        }

        /**
         * Throws [first], or with none the first of [others], every other one
         * and every failed write carried in it ([throwAll]); returns when
         * neither is given: failed writes alone are left to [conclude].
         */
        fun raise(first: Throwable?, others: List<Throwable>) {
            if (first != null || others.isNotEmpty()) throwAll(first, others + writesFailed)
        }

        /**
         * Ends the run: its outcome noted, then [failure] (the test's own)
         * thrown, every failed write carried in it, the outcome's own
         * included; with no failure, the first failed write, the others in
         * it. Missing evidence is never a pass, nor in a failure's place.
         */
        fun conclude(failure: Throwable?) {
            // A Carried stands for its cause: a refusal is still one.
            val origin = if (failure is Carried) failure.cause else failure
            val outcome = when {
                origin != null && origin.message.orEmpty().startsWith("REFUSED") -> "REFUSED"
                failure != null -> "FAIL"
                writesFailed.isNotEmpty() -> "FAIL (evidence not written)"
                else -> "PASS"
            }
            note("outcome $outcome; evidence ${folder?.absolutePath ?: "logcat only (tag C82)"}")
            throwAll(failure, writesFailed)
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
            // Its outcome, REFUSED, is noted as the run concludes (Run.conclude).
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

    /**
     * The hosts paired on disk, read from the `hosts` preferences' file as it
     * is (its backup first, as SharedPreferences reads it), never through a
     * SharedPreferences instance: a read makes no instance and writes nothing.
     */
    private fun pairedHostNames(): List<String> {
        val file = AppStores(target).preferenceFiles("hosts").let { (main, backup) -> if (backup.exists()) backup else main }
        if (!file.exists()) return emptyList()
        val encoded = file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, null) }
            var found: String? = null
            while (found == null && parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string" && parser.getAttributeValue(null, "name") == "hosts") found = parser.nextText()
            }
            found
        }
        return decodeHosts(encoded ?: "[]") { it }.first.map { it.name }
    }

    private fun randomBytes() = ByteArray(64).also(SecureRandom()::nextBytes)

    private fun setup(condition: Boolean, why: () -> String) {
        if (!condition) setupFail(why())
    }

    private fun setupFail(why: String): Nothing = throw AssertionError("SETUP (no product result): $why")
}
