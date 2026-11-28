package io.github.victorbonnet.enclos

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.victorbonnet.enclos.ui.CanFrost
import io.github.victorbonnet.enclos.ui.Glass
import io.github.victorbonnet.enclos.ui.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The policy request screen as production draws it ([PolicyRequestLayout], [PolicyRequestDetails],
 * [PolicyDecisions]) on a device, under the window's real status and navigation bars, through
 * [PolicyRequestProbeActivity] (a synthetic request only: no model, host, store or network; its controls
 * only record a tap, nothing is decided). Light and dark, 100% and 200% font, a short and a long pending
 * request in the whole window; the other states (offline with an error, cannot approve, applying, failed,
 * approved); then shorter windows (the layout given less height, at the window's bottom): one where the
 * decisions, whole, take more than half the room between the bars and must still show whole, three at
 * 200% font where they cannot fit and must be bounded, two too short for both the details' lines and a
 * whole control, which must be one scroll, and one shorter than a control itself:
 *
 * - At rest no decision lies over the status bar, the top bar, the title or the details.
 * - Pinned (the room holds the details' four lines of body text, at the case's font scale, and one whole
 *   control, the tallest measured, with the decisions' 16 dp padding): the decisions are above the
 *   navigation bar and the details' viewport stops at them. They take their own height whenever that leaves
 *   the details those lines; past that they are bounded to the rest and scroll. Else the page is one scroll:
 *   the details, then the same decisions, neither scrolling on its own. No text is clipped.
 * - The decisions offer exactly the case's controls, each enabled or not as the state allows, at least
 *   48 dp tall, brought whole into the region that scrolls them, clear of the navigation bar and of each
 *   other (a part at a time only in a window shorter than the control); a real tap on its visible part
 *   reaches an enabled one alone, and a disabled one, or anything under it, not at all.
 * - Each status, explanation and error of the decisions, and each key field of the details, can be brought
 *   whole into its region's unobscured band; scrolled to the end and back, pinned decisions never move.
 *
 * Bounds are the measured ones, in the Compose root's coordinates; the bars' room is the window's own
 * insets. Screenshots of each case, at rest and at the end, go to the target's external files
 * `policy-layout/`, for `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class PolicyRequestLayoutDeviceTest {
    private val runner = InstrumentationRegistry.getInstrumentation()

    private fun roots(view: View): List<RootForTest> = (if (view is RootForTest) listOf(view) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    private fun own(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

    private fun texts(node: SemanticsNode): String = nodes(node).joinToString(" ") { own(it) }

    private fun SemanticsNode.whole(): Rect = Rect(positionInRoot, Size(size.width.toFloat(), size.height.toFloat()))

    private fun Rect.show() = "[top=%.1f bottom=%.1f left=%.1f right=%.1f]".format(top, bottom, left, right)

    private fun Rect.crosses(other: Rect) = left < other.right - 0.5f && other.left < right - 0.5f && top < other.bottom - 0.5f && other.top < bottom - 0.5f

    /**
     * One case: how the probe shows its request ([PolicyRequestProbeActivity]'s extras), the controls the
     * decisions must offer (label to enabled) and their other texts; [beyondHalf]: the decisions, whole, must
     * take more than half the room between the bars; [bounded]: whether they must be bounded (null: as they
     * measure).
     */
    private data class Case(
        val name: String,
        val dark: Boolean = false,
        val font: Float = 1f,
        val long: Boolean = false,
        val state: String = "pending",
        val heightDp: Int = 0,
        val controls: Map<String, Boolean>,
        val notes: List<String> = emptyList(),
        val beyondHalf: Boolean = false,
        val bounded: Boolean? = null,
        /** Whether the page must be one scroll, details then decisions (null: as the room measures). */
        val single: Boolean? = null,
        /** Whether a control may be taller than the whole room between the bars, seen a part at a time. */
        val mayBeTooShort: Boolean = false,
    ) {
        /** Whether the details offer Ask why: a pending request the phone can approve. */
        val asks get() = state == "pending" || state == "offline"
    }

    /** The case, and where the window's bars are in the Compose root's coordinates. */
    private class Screen(val case: Case, val activity: PolicyRequestProbeActivity, val root: View, val statusBottom: Float, val navTop: Float, val density: Float) {
        val name get() = case.name
        val font get() = case.font
    }

    private fun <T> read(screen: Screen, block: (List<SemanticsNode>) -> T): T {
        var result: Result<T>? = null
        runner.runOnMainSync {
            result = runCatching {
                val root = roots(screen.activity.window.decorView).single()
                root.measureAndLayoutForTest()
                block(nodes(root.semanticsOwner.unmergedRootSemanticsNode))
            }
        }
        return result!!.getOrThrow()
    }

    private fun List<SemanticsNode>.tagged(tag: String) = single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun List<SemanticsNode>.control(label: String) =
        single { it.config.contains(SemanticsActions.OnClick) && label in texts(it) }

    private fun List<SemanticsNode>.text(part: String) = first { part in own(it) }

    private fun settle() {
        runner.waitForIdleSync()
        SystemClock.sleep(400)
        runner.waitForIdleSync()
    }

    private fun scroll(screen: Screen, tag: String, by: Float) {
        read(screen) { all -> all.tagged(tag).config[SemanticsActions.ScrollBy].action!!.invoke(0f, by) }
        settle()
    }

    /** Scrolls the node [find] gives into [clearTop]..[clearBottom] of [scroller], then asserts it lies whole there. */
    private fun bringIntoClear(screen: Screen, what: String, scroller: String, clearTop: Float, clearBottom: Float, find: (List<SemanticsNode>) -> SemanticsNode): Rect {
        val band = clearBottom - clearTop
        var r = read(screen) { find(it).whole() }
        if (r.height > band + 0.5f) {
            // Taller than the band: its top, then its bottom, each brought to the band's edge.
            scroll(screen, scroller, r.top - clearTop)
            r = read(screen) { find(it).whole() }
            assertTrue("${screen.name}: the top of $what ${r.show()} cannot reach $clearTop", abs(r.top - clearTop) <= 1f)
            scroll(screen, scroller, r.bottom - clearBottom)
            r = read(screen) { find(it).whole() }
            assertTrue("${screen.name}: the bottom of $what ${r.show()} cannot reach $clearBottom", abs(r.bottom - clearBottom) <= 1f)
            return r
        }
        repeat(4) {
            val by = when {
                r.top < clearTop - 0.5f -> r.top - clearTop
                r.bottom > clearBottom + 0.5f -> r.bottom - clearBottom
                else -> return@repeat
            }
            scroll(screen, scroller, by)
            r = read(screen) { find(it).whole() }
        }
        assertTrue("${screen.name}: $what ${r.show()} cannot be brought whole between $clearTop and $clearBottom",
            r.top >= clearTop - 0.5f && r.bottom <= clearBottom + 0.5f)
        return r
    }

    /** A real tap (down, up) at the centre of [r], in the root's coordinates. */
    private fun tap(screen: Screen, r: Rect) {
        val at = IntArray(2).also(screen.root::getLocationOnScreen)
        val x = at[0] + r.center.x
        val y = at[1] + r.center.y
        assertTrue("${screen.name}: the tap at ${r.center} is outside the bars' clear band", r.height > 0f)
        val time = SystemClock.uptimeMillis()
        for ((action, t) in listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + 50)) {
            val event = MotionEvent.obtain(time, t, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue("${screen.name}: the tap was not injected", runner.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        settle()
    }

    private fun shot(screen: Screen, stage: String) {
        runner.waitForIdleSync()
        val image = runner.uiAutomation.takeScreenshot() ?: throw AssertionError("${screen.name}: no screenshot")
        try {
            val dir = File(runner.targetContext.getExternalFilesDir(null), "policy-layout").apply { mkdirs() }
            File(dir, "${screen.name}-$stage.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun open(case: Case): Screen {
        val activity = runner.startActivitySync(Intent().setClassName(runner.targetContext, "io.github.victorbonnet.enclos.PolicyRequestProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("dark", case.dark).putExtra("font", case.font).putExtra("long", case.long)
            .putExtra("state", case.state).putExtra("height", case.heightDp)) as PolicyRequestProbeActivity
        settle()
        var screen: Screen? = null
        runner.runOnMainSync {
            val decor = activity.window.decorView
            val root = roots(decor).single() as View
            val insets = WindowInsetsCompat.toWindowInsetsCompat(checkNotNull(decor.rootWindowInsets) { "${case.name}: the window has no insets" }, decor)
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val inWindow = IntArray(2).also(root::getLocationInWindow)
            screen = Screen(case, activity, root, (status - inWindow[1]).toFloat(), (decor.height - nav - inWindow[1]).toFloat(), activity.resources.displayMetrics.density)
        }
        return screen!!
    }

    /** Taps [r]'s centre and asserts what the probe recorded: [expected], or nothing. */
    private fun tapExpecting(screen: Screen, what: String, r: Rect, expected: String?) {
        screen.activity.tapped.clear()
        tap(screen, r)
        assertEquals("${screen.name}: a tap on $what", listOfNotNull(expected), screen.activity.tapped.toList())
    }

    private fun check(screen: Screen) {
        val case = screen.case
        val long = case.long
        val beyondHalf = case.beyondHalf
        val bounded = case.bounded
        val dp = { v: Float -> v * screen.density }
        val top = read(screen) { it.tagged(PolicyRequestTags.TOP_BAR).whole() }
        val decisions = read(screen) { it.tagged(PolicyRequestTags.DECISIONS).boundsInRoot }
        val details = read(screen) { it.tagged(PolicyRequestTags.DETAILS).boundsInRoot }
        val title = read(screen) { it.tagged(PolicyRequestTags.TITLE).boundsInRoot }
        shot(screen, "initial")

        // ---- which layout: pinned decisions while the room holds the details' four lines and one whole control
        // (the tallest the decisions draw, measured, with their 16 dp padding); else one scroll, details then decisions.
        // The clear band starts below the bar's glass, and below its fade where the phone cannot blur.
        val glassEnd = top.bottom + if (CanFrost) 0f else dp(Glass.fade.value)
        val room = screen.navTop - glassEnd
        val least = 4 * Density(screen.density, screen.font).run { Type.small.lineHeight.toPx() }
        val tallest = read(screen) { all -> nodes(all.tagged(PolicyRequestTags.DECISIONS)).filter { it.config.contains(SemanticsActions.OnClick) }.maxOfOrNull { it.size.height.toFloat() } ?: 0f }
        val control = maxOf(dp(48f), tallest) + dp(2 * 16f)
        val single = room - least < control
        val scrollsOnItsOwn = { tag: String -> read(screen) { it.tagged(tag).config.contains(SemanticsProperties.VerticalScrollAxisRange) } }
        val range = read(screen) { it.tagged(PolicyRequestTags.DECISIONS).config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f }
        val natural = decisions.height + range
        Log.i("PolicyRequestLayout", screen.name + ": " + "room=%.1f natural=%.1f decisions=%.1f least=%.1f control=%.1f details=%.1f half=%.1f px, one scroll=%b, sdk=%d frost=%b".format(
            room, natural, decisions.height, least, control, decisions.top - glassEnd, room / 2, single, Build.VERSION.SDK_INT, CanFrost))
        case.single?.let { assertEquals("${screen.name}: whether the case is one scroll (room $room px, least $least px, a whole control $control px)", it, single) }
        assertEquals("${screen.name}: details and decisions in one scroll", single, scrollsOnItsOwn(PolicyRequestTags.PAGE))

        // ---- at rest: status bar, top bar, title, decisions, navigation bar, in that order, apart.
        assertTrue("${screen.name}: the top bar ${top.show()} is under the status bar (to ${screen.statusBottom})", top.top >= screen.statusBottom - 0.5f)
        assertTrue("${screen.name}: the title ${title.show()} starts under the top bar ${top.show()}", title.top >= top.bottom - 0.5f)
        assertTrue("${screen.name}: the decisions ${decisions.show()} cover the status bar or the top bar ${top.show()}", decisions.top >= top.bottom - 0.5f || decisions.height == 0f)
        assertTrue("${screen.name}: the decisions ${decisions.show()} cover the title ${title.show()}", !decisions.crosses(title))
        if (!single) {
            assertTrue("${screen.name}: the decisions ${decisions.show()} reach the navigation bar (from ${screen.navTop})", decisions.bottom <= screen.navTop + 0.5f)
            assertTrue("${screen.name}: the details' viewport ${details.show()} runs under the decisions ${decisions.show()}", details.bottom <= decisions.top + 0.5f)
            // ---- the decisions' height: their own, unless that leaves the details less than four lines of their text.
            val fits = natural <= room - least + 1f
            bounded?.let { assertEquals("${screen.name}: whether the decisions ($natural px) must be bounded in $room px", it, !fits) }
            if (fits) {
                assertEquals("${screen.name}: the decisions fit, yet are not at their own height", natural, decisions.height, 1f)
                assertEquals("${screen.name}: the decisions fit, yet scroll", 0f, range, 0.5f)
            } else {
                assertTrue("${screen.name}: the decisions ($natural px) do not fit, yet do not scroll", range > 0f)
                assertEquals("${screen.name}: the bounded decisions are not the rest of the room", room - least, decisions.height, 1.5f)
                assertTrue("${screen.name}: the details keep ${decisions.top - glassEnd} px, under four lines ($least px)", decisions.top - glassEnd >= least - 1f)
                assertTrue("${screen.name}: the bounded decisions (${decisions.height} px) cannot hold one whole control ($control px)", decisions.height >= control - 1f)
            }
            if (beyondHalf) {
                assertTrue("${screen.name}: the decisions ${decisions.height} px are not past half the room ($room px): the case does not test the natural fit",
                    decisions.height > room / 2 + 1f)
                assertEquals("${screen.name}: past half the room, the decisions scroll", 0f, range, 0.5f)
            }
        } else {
            // ---- one scroll: the details, then the same decisions at their own height; only the page scrolls.
            assertTrue("${screen.name}: in one scroll the details still scroll on their own", !scrollsOnItsOwn(PolicyRequestTags.DETAILS))
            assertTrue("${screen.name}: in one scroll the decisions still scroll on their own", !scrollsOnItsOwn(PolicyRequestTags.DECISIONS))
            val d = read(screen) { it.tagged(PolicyRequestTags.DETAILS).whole() }
            val c = read(screen) { it.tagged(PolicyRequestTags.DECISIONS).whole() }
            assertTrue("${screen.name}: the decisions ${c.show()} do not follow the details ${d.show()}", c.top >= d.bottom - 0.5f)
        }

        // ---- no text of the details or the decisions is clipped: none needs more height than it was given, none is ellipsized.
        val clipped = read(screen) { all ->
            (nodes(all.tagged(PolicyRequestTags.DETAILS)) + nodes(all.tagged(PolicyRequestTags.DECISIONS))).mapNotNull { n ->
                val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
                val layout = mutableListOf<TextLayoutResult>().also { get(it) }.firstOrNull() ?: return@mapNotNull null
                if (layout.didOverflowHeight || (0 until layout.lineCount).any(layout::isLineEllipsized)) {
                    "'${layout.layoutInput.text}' (${layout.lineCount} lines, ${layout.size}, needs ${layout.multiParagraph.height})"
                } else null
            }
        }
        assertTrue("${screen.name}: clipped text: $clipped", clipped.isEmpty())

        // ---- Back: in its bar, 48 dp, and a tap reaches it alone.
        val back = read(screen) { it.control("Back").whole() }
        assertTrue("${screen.name}: Back ${back.show()} outside its bar ${top.show()}", back.top >= top.top - 0.5f && back.bottom <= top.bottom + 0.5f)
        assertTrue("${screen.name}: Back is ${back.height} px, under 48 dp", back.height >= dp(48f) - 0.5f)
        tapExpecting(screen, "Back", back, "Back")

        // ---- the controls the decisions offer: exactly the case's, each enabled or not as the state allows.
        val offered = read(screen) { all ->
            nodes(all.tagged(PolicyRequestTags.DECISIONS)).filter { it.config.contains(SemanticsActions.OnClick) }
                .associate { texts(it).trim() to !it.config.contains(SemanticsProperties.Disabled) }
        }
        assertEquals("${screen.name}: the controls offered, and whether each is enabled", case.controls, offered)

        // ---- each control: brought into the region that scrolls it (the pinned decisions, or the band between the bars
        // in one scroll), 48 dp, clear of the bars and of each other; a tap on its visible part reaches an enabled one
        // alone, and a disabled one (or anything under it) not at all.
        val scroller = if (single) PolicyRequestTags.PAGE else PolicyRequestTags.DECISIONS
        val bandTop = if (single) glassEnd else decisions.top
        val bandBottom = if (single) screen.navTop else decisions.bottom
        val labels = case.controls.keys.toList()
        for (label in labels) {
            val r = bringIntoClear(screen, label, scroller, bandTop, bandBottom) { it.control(label) }
            val whole = r.height <= bandBottom - bandTop + 0.5f
            // Only in a window shorter than one control is it seen a part at a time.
            assertTrue("${screen.name}: $label (${r.height} px) cannot be seen whole in ${bandBottom - bandTop} px", whole || case.mayBeTooShort)
            assertTrue("${screen.name}: $label ${r.show()} crosses the navigation bar (from ${screen.navTop})", r.bottom <= screen.navTop + 0.5f)
            if (whole) assertTrue("${screen.name}: $label ${r.show()} under the top bar ${top.show()}", r.top >= top.bottom - 0.5f)
            assertTrue("${screen.name}: $label is ${r.height} px, under 48 dp", r.height >= dp(48f) - 0.5f)
            val recorded = when {
                case.controls.getValue(label) -> if (label.startsWith("Approve")) "Approve" else label
                else -> null
            }
            tapExpecting(screen, label, Rect(r.left, maxOf(r.top, bandTop), r.right, minOf(r.bottom, bandBottom)), recorded)
        }
        read(screen) { all ->
            val placed = labels.map { it to all.control(it).whole() }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertTrue("${screen.name}: ${placed[i].first} ${placed[i].second.show()} and ${placed[j].first} ${placed[j].second.show()} overlap",
                    !placed[i].second.crosses(placed[j].second))
            }
        }
        for (note in case.notes) {
            bringIntoClear(screen, "the note '$note'", scroller, bandTop, bandBottom) { it.text(note) }
        }
        val detailsScroller = if (single) PolicyRequestTags.PAGE else PolicyRequestTags.DETAILS
        val clearBottom = if (single) screen.navTop else decisions.top
        val pinned = { read(screen) { it.tagged(PolicyRequestTags.DECISIONS).boundsInRoot } }

        // ---- the details' key fields, each whole between the top bar and the decisions.
        val fields = listOf("test-host/lab", "Requested by Worker", "Attempted action",
            if (long) "Paragraph 4 of the attempted action" else "Connect to api.example.test:443",
            "What approval changes", "Approval is saved for the whole sandbox.") +
            (if (long) listOf("Paragraph 1 of the attempted action", "Requested as api.example.test.", "Includes private network addresses",
                "All running work in this sandbox will stop.", "Reason paragraph 1", "Reason paragraph 3")
            else listOf(if (case.state == "failed") "Restart failed after saving policy" else "Fetch a dependency"))
        for (field in fields) {
            bringIntoClear(screen, "'$field'", detailsScroller, glassEnd, clearBottom) { it.text(field) }
            if (!single) assertEquals("${screen.name}: the decisions moved while the details scrolled", decisions, pinned())
        }
        bringIntoClear(screen, "the title", detailsScroller, glassEnd, clearBottom) { it.tagged(PolicyRequestTags.TITLE) }

        // ---- the details' own controls: Ask why while the request is pending and can be approved, the conversation when known.
        val detailControls = read(screen) { all ->
            nodes(all.tagged(PolicyRequestTags.DETAILS)).filter { it.config.contains(SemanticsActions.OnClick) }.map { texts(it).trim() }.sorted()
        }
        val expected = listOfNotNull("Ask Worker why →".takeIf { case.asks }, "Open conversation →".takeIf { long }).sorted()
        assertEquals("${screen.name}: the details' controls", expected, detailControls)
        for (label in expected) {
            val r = bringIntoClear(screen, label, detailsScroller, glassEnd, clearBottom) { it.control(label) }
            assertTrue("${screen.name}: $label is ${r.height} px, under 48 dp", r.height >= dp(48f) - 0.5f)
            tapExpecting(screen, label, Rect(r.left, maxOf(r.top, glassEnd), r.right, minOf(r.bottom, clearBottom)), label.removeSuffix(" →"))
        }

        // ---- the end, then back to the top.
        scroll(screen, detailsScroller, 1_000_000f)
        shot(screen, "end")
        if (!single) {
            assertEquals("${screen.name}: the decisions moved at the details' end", decisions, pinned())
        } else {
            val end = read(screen) { it.tagged(PolicyRequestTags.DECISIONS).whole() }
            assertEquals("${screen.name}: at the page's end the decisions ${end.show()} do not rest on the navigation bar (${screen.navTop})", screen.navTop, end.bottom, 1f)
        }
        scroll(screen, detailsScroller, -1_000_000f)
        val again = read(screen) { it.tagged(PolicyRequestTags.TITLE).whole() }
        assertTrue("${screen.name}: back at the top, the title ${again.show()} does not start in view, under the bar ${top.show()} and above $clearBottom",
            again.top >= glassEnd - 0.5f && again.top < clearBottom)
    }

    private fun run(cases: List<Case>) {
        for (case in cases) {
            val screen = open(case)
            try {
                check(screen)
            } finally {
                runner.runOnMainSync { screen.activity.finish() }
                runner.waitForIdleSync()
            }
        }
    }

    private val closeNote = "Closing only removes this request from the Inbox."
    private val offlineNote = "Reconnect to test-host and sync to respond."
    private val errorNote = "Nothing was sent. The screen lock was cancelled."
    private fun pending(long: Boolean) = mapOf((if (long) "Approve & restart" else "Approve") to true, "Deny" to true, "Close request" to true)
    private fun offline(long: Boolean) = mapOf((if (long) "Approve & restart" else "Approve") to false, "Deny" to false, "Close request" to false)

    private fun name(dark: Boolean, font: Float, long: Boolean, state: String, heightDp: Int = 0) =
        "${if (dark) "dark" else "light"}-font${(font * 100).toInt()}-${if (long) "long" else "short"}-$state" + (if (heightDp > 0) "-h$heightDp" else "")

    @Test fun details_and_decisions_stay_apart_under_the_real_bars_in_both_themes_and_large_text() = run(
        listOf(false, true).flatMap { dark -> listOf(1f, 2f).flatMap { font -> listOf(false, true).map { long ->
            Case(name(dark, font, long, "pending"), dark, font, long, controls = pending(long), notes = listOf(closeNote))
        } } })

    /** Every other state the decisions draw, in the whole window: which controls, enabled or not, and which notes. */
    @Test fun every_state_keeps_details_and_decisions_apart_and_offers_its_own_controls() = run(listOf(
        Case(name(true, 1f, true, "offline"), dark = true, long = true, state = "offline", controls = offline(true),
            notes = listOf(closeNote, offlineNote, errorNote)),
        Case(name(false, 1f, false, "unavailable"), state = "unavailable",
            controls = mapOf("Approve" to false, "Deny" to true, "Close request" to true), notes = listOf(closeNote)),
        Case(name(false, 1f, false, "applying"), state = "applying", controls = mapOf("Saving decision…" to false, "Deny" to false)),
        Case(name(true, 2f, false, "failed"), dark = true, font = 2f, state = "failed", controls = mapOf("Close request" to true),
            notes = listOf("Decision needs review on the host", closeNote, errorNote)),
        Case(name(false, 1f, false, "approved"), state = "approved", controls = emptyMap(), notes = listOf("Approved · policy updated")),
    ))

    /**
     * A window 480 dp tall at default font: the decisions (two rows of controls and the close explanation)
     * take more than half the room between the real bars, and still show whole, not scrolled: no half cap.
     * A phone narrower or with taller bars than the emulator's can move them; the message then says the case
     * no longer tests the natural fit.
     */
    @Test fun decisions_past_half_the_room_keep_their_own_height() = run(listOf(
        Case(name(false, 1f, false, "pending", 480), heightDp = 480, controls = pending(false), notes = listOf(closeNote), beyondHalf = true, bounded = false),
        Case(name(true, 1f, false, "offline", 500), dark = true, state = "offline", heightDp = 500, controls = offline(false),
            notes = listOf(closeNote, offlineNote, errorNote), beyondHalf = true, bounded = false),
    ))

    /**
     * Short windows at 200% font: the decisions cannot fit and are bounded to what the details' four lines leave,
     * and scroll on their own; enabled controls are tapped where that scroll brings them, disabled ones take no tap.
     */
    @Test fun decisions_that_cannot_fit_scroll_and_leave_the_details_four_lines() = run(listOf(
        Case(name(false, 2f, true, "pending", 420), font = 2f, long = true, heightDp = 420, controls = pending(true),
            notes = listOf(closeNote), bounded = true),
        Case(name(true, 2f, true, "offline", 480), dark = true, font = 2f, long = true, state = "offline", heightDp = 480, controls = offline(true),
            notes = listOf(closeNote, offlineNote, errorNote), bounded = true),
        Case(name(false, 2f, true, "failed", 420), font = 2f, long = true, state = "failed", heightDp = 420, controls = mapOf("Close request" to true),
            notes = listOf("Decision needs review on the host", closeNote, errorNote), bounded = true),
    ))

    /**
     * Windows too short for the details' four lines and one whole control at 200% font (300 dp under the real bars):
     * one scroll, the details then the same decisions; each control and note is reached by scrolling the page and
     * tapped there. On a device whose bars leave more room than the emulator's, the message says the case no
     * longer tests one scroll.
     */
    @Test fun a_window_too_short_for_both_scrolls_as_one_page() = run(listOf(
        Case(name(false, 2f, true, "pending", 300), font = 2f, long = true, heightDp = 300, controls = pending(true),
            notes = listOf(closeNote), single = true),
        Case(name(true, 2f, true, "offline", 300), dark = true, font = 2f, long = true, state = "offline", heightDp = 300, controls = offline(true),
            notes = listOf(closeNote, offlineNote, errorNote), single = true),
    ))

    /**
     * A window shorter than one control (190 dp at 200% font): no layout shows that control whole. The page is
     * one scroll; the control is seen a part at a time, and a tap on its visible part reaches it alone. This
     * records the limit; it is not an approved design for that size.
     */
    @Test fun a_window_shorter_than_one_control_is_one_scroll_seen_a_part_at_a_time() = run(listOf(
        Case(name(false, 2f, true, "pending", 190), font = 2f, long = true, heightDp = 190, controls = pending(true),
            notes = listOf(closeNote), single = true, mayBeTooShort = true),
    ))
}
