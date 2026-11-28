package io.github.victorbonnet.enclos

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.CanFrost
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.Glass
import io.github.victorbonnet.enclos.ui.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The policy request screen's layout, measured (`docs/spec.md`, §4 "The bars" and the sandbox policy request):
 * the request's details and its decisions are never two layers over each other. On the real
 * [PolicyRequestScreen], with a fixture model and synthetic requests only (no host, no decision sent, no tap
 * on a decision), for every state the screen draws:
 *
 * - The top bar (Back and "Sandbox policy") is not covered by any decision; the title starts below it.
 * - Normally the decisions are pinned: below the details' viewport and above the navigation bar's room. They
 *   take their own, natural height whenever that leaves the details at least four lines of their body text
 *   (at the case's font scale) between the bars, even past half that room; only past it are they bounded to
 *   the rest and scroll on their own, and the details keep those four lines.
 * - When the room between the bars cannot hold both those four lines and one whole decision control (the
 *   tallest the decisions draw, as measured, with the decisions' padding), the page is one scroll instead: the
 *   details, then the same decisions, at their own height, neither scrolling on its own.
 * - No text of the details or the decisions is clipped: each lays out whole in its own bounds, a control's
 *   label included at 200% font.
 * - Each offered control is at least 48 dp tall, can be brought whole into the clear band of the region that
 *   scrolls it, never crosses the navigation bar's room or another control, and is enabled or not as the
 *   request allows. Only in a window shorter than one control is it reached a part at a time (a known limit).
 * - Each key field of the details can be brought fully into the unobscured viewport (below the glass bar,
 *   above pinned decisions or the navigation bar), at default and 200% font scale, with long text; scrolled
 *   to the end and back.
 *
 * Then, on the layout itself with recorded callbacks: resizing across those layouts keeps the details' place
 * and the controls, enabled as before, and calls nothing; a tap reaches its own control alone in each.
 *
 * Bounds are the measured ones, in root coordinates, after layout: the top bar's, the decisions' and the
 * details' (the scroll viewport), never constants taken from the implementation. The rules taken from the
 * spec are the details' least room, four lines of their body text, and one whole control with the decisions'
 * 16 dp padding; each case prints its geometry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
// Real text layout: Robolectric's legacy graphics measure text the same at any font scale.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolicyRequestLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val host = "test-host"
    private val key = "$host/fixture-1"
    private val worker = Agent(host, "lab/worker", "hermes", emptyList(), displayName = "Worker")

    private val short = PolicyRequest(
        host, "fixture-1", "lab", "lab/worker", null, "network", "api.example.test", false,
        "Connect to api.example.test:443", 1_700_000_000, "pending", "Allow network access to api.example.test", false,
        "fixture-1", true, "Fetch a dependency", "api.example.test",
    )

    private val long = short.copy(
        privateAddress = true,
        restart = true,
        scope = "api.example.test:443",
        action = List(4) { i ->
            "Paragraph ${i + 1} of the attempted action: the synthetic agent opened a connection to api.example.test on port 443 " +
                "to fetch a dependency archive, retried it with a longer timeout, then reported the refusal to the person."
        }.joinToString("\n\n"),
        change = "Allow network access to api.example.test on port 443 for every agent of the lab sandbox, " +
            "including its addresses on private networks, saved in the sandbox's policy until it is changed again.",
        reason = List(3) { i ->
            "Reason paragraph ${i + 1}: the build needs the dependency from api.example.test, which no mirror in the sandbox " +
                "provides, and the synthetic agent cannot continue the task without it."
        }.joinToString("\n\n"),
    )

    /** One case: a request (null: none), how it is shown, and the controls the decisions must offer, label to enabled. */
    private data class Case(
        val name: String,
        val request: PolicyRequest?,
        val font: Float = 1f,
        val dark: Boolean = false,
        val navDp: Int = 24,
        val fresh: Boolean = true,
        val operation: PolicyRequestAction? = null,
        val error: String? = null,
        val controls: Map<String, Boolean> = emptyMap(),
        /** Texts of the decisions that are not controls: a status, an explanation, an error. */
        val notes: List<String> = emptyList(),
        /** Texts of the details that must be readable. */
        val fields: List<String> = emptyList(),
        /** Whether the details, then the decisions, must have something to scroll (null: either), so that path is the one tested. */
        val detailsScroll: Boolean? = null,
        val decisionsScroll: Boolean? = null,
        /** Whether the decisions, whole at their own height, must take more than half the room between the bars: the case a half cap bounds. */
        val beyondHalf: Boolean = false,
        /** The layout's height (dp), at the bottom of the window, as a shorter window gives it; null: the whole window. */
        val heightDp: Int? = null,
        /** Whether the page must be one scroll, details then decisions (null: as the room measures). */
        val single: Boolean? = null,
        /** Whether a control is taller than the whole room between the bars, so it can only be seen a part at a time. */
        val tooShort: Boolean = false,
    )

    private val pendingControls = mapOf("Approve" to true, "Deny" to true, "Close request" to true)
    private val longPendingControls = mapOf("Approve & restart" to true, "Deny" to true, "Close request" to true)
    private val offlineControls = mapOf("Approve" to false, "Deny" to false, "Close request" to false)
    private val closeNote = "Closing only removes this request from the Inbox."
    private val offlineNote = "Reconnect to test-host and sync to respond."
    private val errorNote = "Nothing was sent. The screen lock was cancelled."

    private fun shortFields() = listOf("Connect to api.example.test", "test-host/lab", "Attempted action", "Connect to api.example.test:443",
        "What approval changes", "Allow network access to api.example.test", "Approval is saved for the whole sandbox.", "Fetch a dependency")

    private fun longFields() = listOf("Connect to api.example.test:443", "test-host/lab", "Paragraph 1 of the attempted action", "Paragraph 4 of the attempted action",
        "Requested as api.example.test.", "on port 443 for every agent", "Includes private network addresses", "All running work in this sandbox will stop.",
        "Approval is saved for the whole sandbox.", "Reason paragraph 1", "Reason paragraph 3")

    private fun pendingCases(): List<Case> = listOf(
        Case("short pending, light, gesture", short, controls = pendingControls, notes = listOf(closeNote),
            fields = shortFields() + "Ask Worker why →", decisionsScroll = false, single = false),
        Case("long pending, dark, three-button", long, dark = true, navDp = 48,
            controls = longPendingControls, notes = listOf(closeNote),
            fields = longFields() + "Ask Worker why →", detailsScroll = true, decisionsScroll = false, single = false),
        Case("long pending, 200% font, light, three-button", long, font = 2f, navDp = 48,
            controls = longPendingControls, notes = listOf(closeNote),
            fields = longFields() + "Ask Worker why →", detailsScroll = true, decisionsScroll = false, beyondHalf = true, single = false),
        Case("long pending, 200% font, dark, gesture", long, font = 2f, dark = true,
            controls = longPendingControls, notes = listOf(closeNote),
            fields = longFields() + "Ask Worker why →", detailsScroll = true, single = false),
        Case("short pending, 200% font, light, gesture", short, font = 2f, controls = pendingControls, notes = listOf(closeNote),
            fields = shortFields(), single = false),
    )

    private fun stateCases(): List<Case> = listOf(
        Case("pending, cannot approve", short.copy(available = false),
            controls = mapOf("Approve" to false, "Deny" to true, "Close request" to true), notes = listOf(closeNote), fields = shortFields()),
        Case("pending, offline", short, fresh = false,
            controls = offlineControls, notes = listOf(closeNote, offlineNote), fields = shortFields()),
        Case("applying", short.copy(state = "applying"), controls = mapOf("Saving decision…" to false, "Deny" to false), fields = shortFields()),
        Case("failed, with an error", short.copy(state = "failed", reason = "Restart failed after saving policy"),
            error = errorNote,
            controls = mapOf("Close request" to true),
            notes = listOf("Decision needs review on the host", closeNote, errorNote),
            fields = listOf("Connect to api.example.test", "Restart failed after saving policy")),
        Case("failed, long, 200% font", long.copy(state = "failed"), font = 2f, navDp = 48,
            error = errorNote,
            controls = mapOf("Close request" to true),
            notes = listOf("Decision needs review on the host", closeNote, errorNote),
            fields = longFields()),
        Case("approved", short.copy(state = "approved"), notes = listOf("Approved · policy updated"), fields = shortFields()),
        Case("denied", short.copy(state = "denied"), dark = true, notes = listOf("Denied · policy unchanged"), fields = shortFields()),
        Case("closed", short.copy(state = "closed"), notes = listOf("Closed · no new decision"), fields = shortFields()),
        Case("diagnostic", short.copy(state = "diagnostic"), notes = listOf("Diagnostic probe · no approval needed"), fields = shortFields()),
        Case("closing", short, operation = PolicyRequestAction.Close,
            controls = mapOf("Approve" to false, "Deny" to false, "Closing request…" to false), notes = listOf(closeNote), fields = shortFields()),
        Case("no request", null),
        Case("no request, 200% font, three-button", null, font = 2f, navDp = 48),
    )

    // ---- the fixture -------------------------------------------------------------------------------------

    private fun fixture(): AppModel {
        val model = fixtureModel()
        FakeBridge().attach(model, host, files = false)
        model.paired.add(fixtureHost(host))
        model.agents.add(worker)
        return model
    }

    private fun AppModel.show(case: Case) {
        approvals.clear()
        pendingApprovalActions.clear()
        approvalErrors.clear()
        field<MutableMap<String, Set<String>>>("hostCapabilities")[host] = setOf("approval.close")
        approvalsFresh[host] = case.fresh
        case.request?.let { approvals[it.key] = it }
        case.operation?.let { pendingApprovalActions[key] = PendingPolicyRequestAction("fixture-message", it) }
        case.error?.let { approvalErrors[key] = it }
    }

    /** A layout [heightDp] tall at the window's bottom, as a shorter window gives it; null: the whole window. */
    private fun sized(heightDp: Int?) =
        Modifier.fillMaxSize().then(if (heightDp != null) Modifier.wrapContentHeight(Alignment.Bottom).height(heightDp.dp) else Modifier)

    private fun run(cases: List<Case>) {
        val model = fixture()
        try {
            var shown by mutableStateOf(cases.first())
            model.show(shown)
            compose.setContent {
                val case = shown
                key(case.name) {
                    EnclosTheme(dark = case.dark) {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, case.font)) {
                            Box(sized(case.heightDp)) {
                                PolicyRequestScreen(model, key, back = {}, openChat = {}, ask = {}, insets = WindowInsets(bottom = case.navDp.dp))
                            }
                        }
                    }
                }
            }
            for (case in cases) {
                compose.runOnIdle {
                    model.show(case)
                    shown = case
                }
                compose.waitForIdle()
                check(case)
            }
        } finally {
            model.endFixture()
        }
    }

    // ---- the measurements --------------------------------------------------------------------------------

    private fun Rect.show() = "[top=%.1f bottom=%.1f left=%.1f right=%.1f]".format(top, bottom, left, right)

    /** Whether the two share more than half a pixel each way: touching edges do not count. */
    private fun Rect.crosses(other: Rect) = left < other.right - 0.5f && other.left < right - 0.5f && top < other.bottom - 0.5f && other.top < bottom - 0.5f

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** A node's whole rectangle, in root coordinates, unclipped by the scroll that holds it. */
    private fun SemanticsNode.whole(): Rect = Rect(positionInRoot, Size(size.width.toFloat(), size.height.toFloat()))

    private fun wholeOf(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().whole()

    private fun SemanticsNode.label(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

    private fun inDetails(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag(PolicyRequestTags.DETAILS))

    private fun inDecisions(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag(PolicyRequestTags.DECISIONS))

    private val controlMatcher = hasClickAction() and hasAnyAncestor(hasTestTag(PolicyRequestTags.DECISIONS))

    private fun one(matcher: SemanticsMatcher, what: String): SemanticsNodeInteraction {
        val found = compose.onAllNodes(matcher).fetchSemanticsNodes()
        assertTrue("$what: ${found.size} nodes, not one", found.size == 1)
        return compose.onNode(matcher)
    }

    /** Whether the tagged node scrolls at all (has a vertical scroll), whatever its range. */
    private fun scrolls(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.contains(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollRange(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f

    private fun scroll(tag: String, by: Float) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        compose.waitForIdle()
    }

    private fun px(dp: Float) = with(compose.density) { dp.dp.toPx() }

    /** The top of the clear band: below the bar's glass, and below its fade where the phone cannot blur. */
    private fun glassEnd(top: Rect) = top.bottom + if (CanFrost) 0f else px(Glass.fade.value)

    /** The details' least room, four lines of their body text at [font], in px. */
    private fun least(font: Float) = 4 * Density(compose.density.density, font).run { Type.small.lineHeight.toPx() }

    /** A whole decision control: the tallest the decisions draw (at least 48 dp), with the decisions' 16 dp padding above and below. */
    private fun wholeControl(): Float {
        val tallest = compose.onAllNodes(controlMatcher).fetchSemanticsNodes().maxOfOrNull { it.size.height.toFloat() } ?: 0f
        return maxOf(px(48f), tallest) + px(2 * 16f)
    }

    /**
     * Scrolls [node] into the band [clearTop]..[clearBottom] of [scroller], then asserts it lies whole in it;
     * a node taller than the band is checked twice, its top brought to the band's top, then its bottom to its bottom.
     */
    private fun bringIntoClear(name: String, what: String, node: SemanticsNodeInteraction, scroller: String, clearTop: Float, clearBottom: Float) {
        node.performScrollTo()
        compose.waitForIdle()
        val band = clearBottom - clearTop
        var r = node.fetchSemanticsNode().whole()
        if (r.height <= band + 0.5f) {
            if (r.top < clearTop) scroll(scroller, r.top - clearTop) else if (r.bottom > clearBottom) scroll(scroller, r.bottom - clearBottom)
            r = node.fetchSemanticsNode().whole()
            assertTrue("$name: $what ${r.show()} cannot be brought whole between $clearTop and $clearBottom",
                r.top >= clearTop - 0.5f && r.bottom <= clearBottom + 0.5f)
        } else {
            scroll(scroller, r.top - clearTop)
            r = node.fetchSemanticsNode().whole()
            assertTrue("$name: the top of $what ${r.show()} cannot reach the clear band's top $clearTop", kotlin.math.abs(r.top - clearTop) <= 1f)
            scroll(scroller, r.bottom - clearBottom)
            r = node.fetchSemanticsNode().whole()
            assertTrue("$name: the bottom of $what ${r.show()} cannot reach the clear band's bottom $clearBottom", kotlin.math.abs(r.bottom - clearBottom) <= 1f)
        }
    }

    private fun check(case: Case) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val navTop = root.bottom - px(case.navDp.toFloat())
        val top = bounds(PolicyRequestTags.TOP_BAR)
        val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        assertTrue("${case.name}: Back ${back.show()} is outside its bar ${top.show()}", back.top >= top.top - 0.5f && back.bottom <= top.bottom + 0.5f)
        assertTrue("${case.name}: Back is ${back.height} px tall, under 48 dp", back.height >= px(48f) - 0.5f)

        if (case.request == null) {
            assertTrue("${case.name}: with no request the page itself scrolls", !scrolls(PolicyRequestTags.PAGE))
            val text = one(inDetails("This request is no longer available."), "${case.name}: the no-request text")
            bringIntoClear(case.name, "the no-request text", text, PolicyRequestTags.DETAILS, top.bottom, navTop)
            assertTrue("${case.name}: decisions shown with no request", compose.onAllNodes(hasTestTag(PolicyRequestTags.DECISIONS)).fetchSemanticsNodes().isEmpty())
            return
        }

        // ---- which layout: pinned decisions while the room holds the details' four lines and one whole control; else one scroll.
        val glassEnd = glassEnd(top)
        val room = navTop - glassEnd
        val least = least(case.font)
        val control = wholeControl()
        val single = room - least < control
        val decisions = bounds(PolicyRequestTags.DECISIONS)
        val natural = decisions.height + scrollRange(PolicyRequestTags.DECISIONS)
        println(case.name + ": " + "room=%.1f natural=%.1f decisions=%.1f least=%.1f control=%.1f details=%.1f half=%.1f px, one scroll=%b".format(
            room, natural, decisions.height, least, control, decisions.top - glassEnd, room / 2, single))
        case.single?.let { assertEquals("${case.name}: whether the case is one scroll (room $room px, least $least px, a whole control $control px)", it, single) }
        assertEquals("${case.name}: details and decisions in one scroll (room $room px - least $least px < a whole control $control px)",
            single, scrolls(PolicyRequestTags.PAGE))

        // ---- at rest, unscrolled: the bar, the title, the decisions, the navigation bar's room, apart. (The title's
        // bounds as shown, clipped by its scroll: a title taller than the details' viewport is cut by it, not covered.)
        val title = bounds(PolicyRequestTags.TITLE)
        assertTrue("${case.name}: the title ${title.show()} starts under the top bar ${top.show()}", title.top >= top.bottom - 0.5f)
        assertTrue("${case.name}: the decisions ${decisions.show()} cover the status bar or the top bar ${top.show()}", decisions.top >= top.bottom - 0.5f || decisions.height == 0f)
        assertTrue("${case.name}: the decisions ${decisions.show()} cover the title ${title.show()}", !decisions.crosses(title))
        if (!single) {
            val details = bounds(PolicyRequestTags.DETAILS)
            assertTrue("${case.name}: the decisions ${decisions.show()} reach into the navigation bar's room (from $navTop)", decisions.bottom <= navTop + 0.5f)
            assertTrue("${case.name}: the details' viewport ${details.show()} runs under the decisions ${decisions.show()}", details.bottom <= decisions.top + 0.5f)
            case.detailsScroll?.let { assertEquals("${case.name}: the details scroll", it, scrollRange(PolicyRequestTags.DETAILS) > 0f) }
            case.decisionsScroll?.let { assertEquals("${case.name}: the decisions scroll", it, scrollRange(PolicyRequestTags.DECISIONS) > 0f) }

            // ---- the decisions' height: their own, unless that leaves the details less than four lines of their text.
            if (natural <= room - least + 1f) {
                assertEquals("${case.name}: the decisions fit with ${room - natural} px left for the details, yet are not at their own height", natural, decisions.height, 1f)
                assertEquals("${case.name}: the decisions fit, yet scroll", 0f, scrollRange(PolicyRequestTags.DECISIONS), 0.5f)
            } else {
                assertTrue("${case.name}: the decisions ($natural px) do not fit, yet do not scroll", scrollRange(PolicyRequestTags.DECISIONS) > 0f)
                assertEquals("${case.name}: the bounded decisions are not the rest of the room", room - least, decisions.height, 1.5f)
                assertTrue("${case.name}: the details keep ${decisions.top - glassEnd} px, under four lines ($least px)", decisions.top - glassEnd >= least - 1f)
                assertTrue("${case.name}: the bounded decisions (${decisions.height} px) cannot hold one whole control ($control px)", decisions.height >= control - 1f)
            }
            if (case.beyondHalf) {
                assertTrue("${case.name}: the decisions ${decisions.height} px are not past half the room ($room px): the case does not test the natural fit",
                    decisions.height > room / 2 + 1f)
                assertEquals("${case.name}: past half the room, the decisions scroll", 0f, scrollRange(PolicyRequestTags.DECISIONS), 0.5f)
            }
        } else {
            // ---- one scroll: the details, then the same decisions at their own height; only the page scrolls.
            assertTrue("${case.name}: in one scroll the details still scroll on their own", !scrolls(PolicyRequestTags.DETAILS))
            assertTrue("${case.name}: in one scroll the decisions still scroll on their own", !scrolls(PolicyRequestTags.DECISIONS))
            val d = wholeOf(PolicyRequestTags.DETAILS)
            val c = wholeOf(PolicyRequestTags.DECISIONS)
            assertTrue("${case.name}: the decisions ${c.show()} do not follow the details ${d.show()}", c.top >= d.bottom - 0.5f)
            assertTrue("${case.name}: the title ${title.show()} does not start in the clear band, from $glassEnd", title.top >= glassEnd - 0.5f && title.top < navTop)
            assertTrue("${case.name}: one scroll that does not scroll (${d.height + c.height} px in $room px)", scrollRange(PolicyRequestTags.PAGE) > 0f)
        }

        // ---- no text of the details or the decisions is clipped: none needs more height than it was given, none is
        // ellipsized. (Width is wrapped: a paragraph's width is its constraint, so it does not tell a cut line.)
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            (hasAnyAncestor(hasTestTag(PolicyRequestTags.DETAILS)) or hasAnyAncestor(hasTestTag(PolicyRequestTags.DECISIONS))), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("${case.name}: no text found to check", texts.isNotEmpty())
        val clipped = texts.mapNotNull { n ->
            val layout = mutableListOf<TextLayoutResult>().also { n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.firstOrNull()
            layout?.takeIf { l -> l.didOverflowHeight || (0 until l.lineCount).any(l::isLineEllipsized) }?.let { "'${it.layoutInput.text}' (${it.lineCount} lines, ${it.size}, needs ${it.multiParagraph.height})" }
        }
        assertTrue("${case.name}: clipped text: $clipped", clipped.isEmpty())

        // ---- each control the decisions offer: there, reachable, 48 dp, clear of the bars and of each other, enabled as allowed.
        // Pinned, a control is brought into the decisions' own viewport; in one scroll, into the band between the bars.
        val decisionsScroller = if (single) PolicyRequestTags.PAGE else PolicyRequestTags.DECISIONS
        val decisionsTop = if (single) glassEnd else decisions.top
        val decisionsBottom = if (single) navTop else decisions.bottom
        val offered = compose.onAllNodes(controlMatcher).fetchSemanticsNodes().map { it.label() }
        assertEquals("${case.name}: the controls offered", case.controls.keys.sorted(), offered.sorted())
        var tall = false
        for ((label, enabled) in case.controls) {
            val node = one(controlMatcher and hasText(label), "${case.name}: control $label")
            val height = node.fetchSemanticsNode().size.height
            val whole = height <= decisionsBottom - decisionsTop + 0.5f
            // Only in a window shorter than one control is it seen a part at a time: its top, then its bottom.
            assertTrue("${case.name}: $label ($height px) cannot be seen whole in ${decisionsBottom - decisionsTop} px", whole || case.tooShort)
            tall = tall || !whole
            bringIntoClear(case.name, "control $label", node, decisionsScroller, decisionsTop, decisionsBottom)
            val n = node.fetchSemanticsNode()
            val r = n.whole()
            assertTrue("${case.name}: $label ${r.show()} crosses the navigation bar's room (from $navTop)", r.bottom <= navTop + 0.5f)
            if (whole) assertTrue("${case.name}: $label ${r.show()} is under the top bar ${top.show()}", r.top >= top.bottom - 0.5f)
            assertTrue("${case.name}: $label is ${r.height} px tall, under 48 dp", r.height >= px(48f) - 0.5f)
            assertEquals("${case.name}: $label enabled", enabled, !n.config.contains(SemanticsProperties.Disabled))
        }
        assertEquals("${case.name}: whether a control is taller than the room between the bars", case.tooShort, tall)
        // Controls on one row are measured together, at the scroll they share.
        val labels = case.controls.keys.toList()
        for (i in labels.indices) for (j in i + 1 until labels.size) {
            val a = compose.onNode(controlMatcher and hasText(labels[i])).fetchSemanticsNode().whole()
            val b = compose.onNode(controlMatcher and hasText(labels[j])).fetchSemanticsNode().whole()
            assertTrue("${case.name}: ${labels[i]} ${a.show()} and ${labels[j]} ${b.show()} overlap", !a.crosses(b))
        }
        for (note in case.notes) {
            bringIntoClear(case.name, "the note '$note'", one(inDecisions(note), "${case.name}: note $note"), decisionsScroller, decisionsTop, decisionsBottom)
        }

        // ---- each field of the details, brought whole into the unobscured viewport: below the bar, above pinned decisions or the navigation bar.
        val detailsScroller = if (single) PolicyRequestTags.PAGE else PolicyRequestTags.DETAILS
        val clearBottom = if (single) navTop else decisions.top
        for (field in case.fields) {
            bringIntoClear(case.name, "'$field'", compose.onAllNodes(inDetails(field)).fetchSemanticsNodes().firstOrNull()
                ?.let { compose.onNode(inDetails(field) and SemanticsMatcher("id ${it.id}") { n -> n.id == it.id }) }
                ?: throw AssertionError("${case.name}: '$field' not in the details"),
                detailsScroller, glassEnd, clearBottom)
            // Scrolling the details never moves pinned decisions.
            if (!single) assertEquals("${case.name}: the decisions moved while the details scrolled", decisions, bounds(PolicyRequestTags.DECISIONS))
        }

        // ---- to the end and back: the last line rests above the decisions (or, in one scroll, the decisions above the navigation bar).
        scroll(detailsScroller, 1_000_000f)
        if (!single) {
            assertEquals("${case.name}: the decisions moved at the details' end", decisions, bounds(PolicyRequestTags.DECISIONS))
            val details2 = bounds(PolicyRequestTags.DETAILS)
            assertTrue("${case.name}: at the end the details' viewport ${details2.show()} runs under the decisions ${decisions.show()}", details2.bottom <= decisions.top + 0.5f)
        } else {
            val end = wholeOf(PolicyRequestTags.DECISIONS)
            assertEquals("${case.name}: at the page's end the decisions ${end.show()} do not rest on the navigation bar's room ($navTop)", navTop, end.bottom, 1f)
        }
        scroll(detailsScroller, -1_000_000f)
        val titleBack = wholeOf(PolicyRequestTags.TITLE)
        assertTrue("${case.name}: back at the top, the title ${titleBack.show()} does not start in view, under the bar ${top.show()} and above $clearBottom",
            titleBack.top >= glassEnd - 0.5f && titleBack.top < clearBottom)
        // And it can be brought whole into the clear viewport: a title taller than the details' least (large text,
        // a narrow window, bounded decisions) is read by scrolling, like any field.
        bringIntoClear(case.name, "the title", compose.onNodeWithTag(PolicyRequestTags.TITLE), detailsScroller, glassEnd, clearBottom)
    }

    @Test fun pending_requests_short_long_and_large_text_keep_details_and_decisions_apart() = run(pendingCases())

    @Test fun every_state_keeps_details_and_decisions_apart() = run(stateCases())

    /** A phone at 200% font: the decisions take more than half the room between the bars, and show whole. */
    @Test
    @Config(qualifiers = "w360dp-h700dp")
    fun decisions_past_half_the_room_keep_their_own_height_when_the_details_keep_room() = run(listOf(
        Case("phone, short pending, 200% font, gesture", short, font = 2f, controls = pendingControls, notes = listOf(closeNote),
            fields = shortFields() + "Ask Worker why →", decisionsScroll = false, beyondHalf = true, single = false),
        Case("phone, long pending, 200% font, dark, three-button", long, font = 2f, dark = true, navDp = 48,
            controls = longPendingControls, notes = listOf(closeNote),
            fields = longFields() + "Ask Worker why →", detailsScroll = true, decisionsScroll = false, beyondHalf = true, single = false),
    ))

    /** A short window at default font: the same, with the offline note under the controls. */
    @Test
    @Config(qualifiers = "w360dp-h440dp")
    fun a_short_window_at_default_font_keeps_the_decisions_whole() = run(listOf(
        Case("short window, short pending, three-button", short, navDp = 48, controls = pendingControls, notes = listOf(closeNote),
            fields = shortFields() + "Ask Worker why →", decisionsScroll = false, beyondHalf = true, single = false),
        Case("short window, offline, dark", short, fresh = false, dark = true,
            controls = offlineControls, notes = listOf(closeNote, offlineNote), fields = shortFields(), decisionsScroll = false, beyondHalf = true, single = false),
    ))

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun a_small_window_with_large_text_keeps_every_decision_reachable() = run(listOf(
        Case("small, long pending, 200% font, three-button", long, font = 2f, navDp = 48,
            controls = longPendingControls, notes = listOf(closeNote),
            fields = longFields() + "Ask Worker why →", detailsScroll = true, decisionsScroll = true, single = false),
        Case("small, failed, 200% font, dark", long.copy(state = "failed"), font = 2f, dark = true,
            error = errorNote,
            controls = mapOf("Close request" to true),
            notes = listOf("Decision needs review on the host", closeNote, errorNote),
            fields = longFields(), detailsScroll = true, decisionsScroll = true, single = false),
        Case("small, offline, 200% font", short, font = 2f, fresh = false,
            controls = offlineControls, notes = listOf(closeNote, offlineNote), fields = shortFields()),
    ))

    /**
     * Windows too short for both the details' four lines and one whole control (a phone held sideways at large
     * text, split screen): one scroll, the details first, then the same decisions, every state's controls and notes.
     */
    @Test fun a_window_too_short_for_details_and_a_whole_control_scrolls_as_one_page() = run(listOf(
        Case("one scroll, long pending, 200% font, three-button", long, font = 2f, navDp = 48, heightDp = 300,
            controls = longPendingControls, notes = listOf(closeNote), fields = longFields() + "Ask Worker why →", single = true),
        // Room for a 48 dp control with its padding, not for the wrapped "Approve & restart" with its padding: the
        // control's measured height, not 48 dp, decides.
        Case("one scroll, wrapped label, 200% font, three-button", long, font = 2f, navDp = 48, heightDp = 342,
            controls = longPendingControls, notes = listOf(closeNote), fields = longFields(), single = true),
        Case("one scroll, offline, 200% font, dark", short, font = 2f, dark = true, fresh = false, heightDp = 260,
            controls = offlineControls, notes = listOf(closeNote, offlineNote), fields = shortFields(), single = true),
        Case("one scroll, failed, long, 200% font", long.copy(state = "failed"), font = 2f, heightDp = 300, error = errorNote,
            controls = mapOf("Close request" to true), notes = listOf("Decision needs review on the host", closeNote, errorNote),
            fields = longFields(), single = true),
        Case("one scroll, applying, 200% font", short.copy(state = "applying"), font = 2f, navDp = 48, heightDp = 280,
            controls = mapOf("Saving decision…" to false, "Deny" to false), fields = shortFields(), single = true),
        Case("one scroll, cannot approve, default font", short.copy(available = false), heightDp = 240,
            controls = mapOf("Approve" to false, "Deny" to true, "Close request" to true), notes = listOf(closeNote),
            fields = shortFields(), single = true),
        Case("one scroll, short pending, default font", short, heightDp = 240, controls = pendingControls, notes = listOf(closeNote),
            fields = shortFields() + "Ask Worker why →", single = true),
        Case("one scroll, approved, 200% font", short.copy(state = "approved"), font = 2f, heightDp = 250,
            notes = listOf("Approved · policy updated"), fields = shortFields(), single = true),
    ))

    /**
     * A window shorter than one control: no layout shows that control whole. The page is still one scroll, nothing
     * is clipped by the layout, and each part of the control (its top, then its bottom) can be brought into view.
     */
    @Test fun a_window_shorter_than_one_control_is_one_scroll_seen_a_part_at_a_time() = run(listOf(
        Case("too short, long pending, 200% font, three-button", long, font = 2f, navDp = 48, heightDp = 170,
            controls = longPendingControls, notes = listOf(closeNote), fields = longFields(), single = true, tooShort = true),
    ))

    // ---- the layout itself, resized, with recorded callbacks -----------------------------------------------

    private var height by mutableStateOf(640)
    private var live by mutableStateOf(true)
    private val calls = mutableListOf<String>()

    /** The production layout with the long request at 200% font, its controls recording each call; [height] and [live] change it. */
    private fun layout() {
        compose.setContent {
            EnclosTheme(dark = false) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    Box(sized(height)) {
                        PolicyRequestLayout(back = { calls += "Back" }, insets = WindowInsets(bottom = 48.dp), decisions = {
                            PolicyDecisions(long, operation = null, live = live, closable = true, error = null,
                                decide = { calls += if (it) "Approve" else "Deny" }, close = { calls += "Close request" })
                        }) {
                            PolicyRequestDetails(long, agentLabel = { "Worker" }, asked = worker, askedWrote = true, ask = { calls += "Ask" }, openConversation = null)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun resize(dp: Int) {
        compose.runOnIdle { height = dp }
        compose.waitForIdle()
    }

    /** Which layout the page has now, as its scrolls say. */
    private fun shape(): String = when {
        scrolls(PolicyRequestTags.PAGE) -> "one scroll"
        scrollRange(PolicyRequestTags.DECISIONS) > 0f -> "bounded"
        else -> "natural"
    }

    private fun offeredNow(): Map<String, Boolean> =
        compose.onAllNodes(controlMatcher).fetchSemanticsNodes().associate { it.label() to !it.config.contains(SemanticsProperties.Disabled) }

    private val sizes = listOf(640 to "natural", 420 to "bounded", 300 to "one scroll", 170 to "one scroll", 420 to "bounded", 640 to "natural")

    /**
     * Across the layouts, back and forth: the same controls, enabled as before, no call without a tap, and the
     * details where the person left them (the title at the same place under the bar) as long as they can be.
     */
    @Test fun resizing_across_the_layouts_keeps_the_details_place_and_decides_nothing() {
        layout()
        val expected = longPendingControls
        var place: Float? = null
        for ((dp, shape) in sizes) {
            resize(dp)
            assertEquals("at $dp dp: the layout", shape, shape())
            assertEquals("at $dp dp: called without a tap", emptyList<String>(), calls)
            assertEquals("at $dp dp: the controls, and whether each is enabled", expected, offeredNow())
            val at = wholeOf(PolicyRequestTags.TITLE).top - bounds(PolicyRequestTags.TOP_BAR).bottom
            place?.let { assertEquals("at $dp dp ($shape): the details did not keep their place", it, at, 1f) }
            if (place == null && shape == "bounded") {
                // Leave the details part way down, then cross into one scroll and back.
                scroll(PolicyRequestTags.DETAILS, 300f)
                place = wholeOf(PolicyRequestTags.TITLE).top - bounds(PolicyRequestTags.TOP_BAR).bottom
                assertEquals("the details did not scroll", at - 300f, wholeOf(PolicyRequestTags.TITLE).top - bounds(PolicyRequestTags.TOP_BAR).bottom, 1f)
            }
        }
        assertEquals("called without a tap", emptyList<String>(), calls)
    }

    /** In each layout a tap reaches its own control once, where its scroll brings it; offline, no control takes a tap. */
    @Test fun in_every_layout_a_tap_reaches_its_own_control_alone() {
        layout()
        val recorded = mapOf("Approve & restart" to "Approve", "Deny" to "Deny", "Close request" to "Close request")
        for (offline in listOf(false, true)) {
            compose.runOnIdle { live = !offline }
            for ((dp, expected) in sizes.take(4)) {
                resize(dp)
                // Offline, the reconnect note makes the decisions taller: at 640 dp they are bounded, not at their own height.
                val shape = shape()
                if (!offline) assertEquals("at $dp dp: the layout", expected, shape)
                else assertEquals("at $dp dp, offline: one scroll", expected == "one scroll", shape == "one scroll")
                val top = bounds(PolicyRequestTags.TOP_BAR)
                val navTop = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - px(48f)
                for (label in recorded.keys) {
                    val node = one(controlMatcher and hasText(label), "at $dp dp: $label")
                    assertEquals("at $dp dp: $label enabled", !offline, !node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
                    // Bring it clear of the bars (its top, when it is taller than the room), then tap its centre.
                    node.performScrollTo()
                    compose.waitForIdle()
                    val scroller = if (shape == "one scroll") PolicyRequestTags.PAGE else PolicyRequestTags.DECISIONS
                    val clearTop = if (shape == "one scroll") glassEnd(top) else bounds(PolicyRequestTags.DECISIONS).top
                    val clearBottom = if (shape == "one scroll") navTop else bounds(PolicyRequestTags.DECISIONS).bottom
                    val r = node.fetchSemanticsNode().whole()
                    if (r.top < clearTop || r.height > clearBottom - clearTop) scroll(scroller, r.top - clearTop)
                    else if (r.bottom > clearBottom) scroll(scroller, r.bottom - clearBottom)
                    val c = node.fetchSemanticsNode().whole().center
                    assertTrue("at $dp dp: the centre of $label ($c) is not between the bars ($clearTop..$clearBottom)", c.y > clearTop && c.y < clearBottom)
                    calls.clear()
                    node.performClick()
                    compose.waitForIdle()
                    assertEquals("at $dp dp${if (offline) ", offline" else ""}: a tap on $label", listOfNotNull(recorded.getValue(label).takeIf { !offline }), calls)
                }
            }
        }
    }
}
