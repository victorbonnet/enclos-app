package io.github.victorbonnet.enclos

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*
import org.json.JSONObject

/** The host owns a request and its state. A cached row never authorizes a decision. */
data class PolicyRequest(val host: String, val id: String, val sandbox: String, val agent: String,
    val conversation: String?, val kind: String, val target: String, val privateAddress: Boolean,
    val action: String, val time: Long, val state: String, val change: String, val restart: Boolean,
    val revision: String, val available: Boolean, val reason: String?, val scope: String = "") {
    val key get() = "$host/$id"
    val pending get() = state == "pending"
    val attention get() = pending || state == "applying" || state == "failed"
    /** What approval writes to the policy, as the host resolved it; the agent's target until the host says. */
    val shown get() = shownScope(target, scope)
    /** The host resolved the agent's path to another place, through a link. */
    val retargeted get() = scopeLeadsElsewhere(kind, target, scope)
    val title get() = when (kind) { "network" -> "Connect to $shown"; "write" -> "Write to $shown"; "read" -> "Read $shown"; else -> Visible.stripped(action) }
    companion object {
        /** The request [m] describes, or null when a field it needs is missing or malformed: such a request is dropped, never shown. */
        fun parse(host: String, m: JSONObject): PolicyRequest? = runCatching { from(host, m) }.getOrNull()

        fun from(host: String, m: JSONObject): PolicyRequest {
            val a = m.getJSONObject("access")
            return PolicyRequest(host, m.getString("request"), m.getString("sandbox"), m.optString("agent"),
                m.optString("conversation").ifBlank { null }, a.getString("kind"), a.getString("target"), a.optBoolean("private"),
                m.optString("action"), m.getLong("time"), m.getString("state"), m.optString("change"), m.optBoolean("restart"),
                m.optString("revision"), m.optBoolean("available"), m.optString("reason").ifBlank { null }, m.optString("scope"))
        }
    }
}

/** The scope to show: the host's (resolved, normalized) one when it sent it, never the agent's text over it. */
fun shownScope(target: String, scope: String): String = Visible.revealed(scope.ifBlank { target })

/** Whether a write scope is another place than the path the agent named (a symlink resolved to elsewhere). */
fun scopeLeadsElsewhere(kind: String, target: String, scope: String): Boolean =
    kind == "write" && scope.isNotBlank() && scope.trimEnd('/') != target.trimEnd('/')

/**
 * Whom to ask why [request] was needed: the agent that asked, else, as the
 * proxy cannot tell which agent tried, the agent of its sandbox with the
 * newest message on this phone ([lastAt]), or the first listed when none
 * has one. Null when the phone lists none of them, or no longer the agent
 * that asked: no other agent is asked in its place.
 */
fun askWhyAgent(request: PolicyRequest, agents: List<Agent>, lastAt: (String) -> Long?): Agent? {
    val ofSandbox = agents.filter { it.host == request.host && it.sandbox == request.sandbox }
    if (request.agent.isNotBlank()) return ofSandbox.firstOrNull { it.name == request.agent }
    return ofSandbox.maxByOrNull { lastAt(it.address) ?: 0L }
}

/**
 * Whether [request] is one to ask about: pending, and one the phone can
 * approve, so that the person still decides once the answer is in. A failed
 * request was decided already, and an unavailable one can only be closed.
 */
fun askWhyOffered(request: PolicyRequest): Boolean = request.pending && request.available

private val utc = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC)

/**
 * The question to ask about [request], for the person to read, change and
 * send: what the sandbox's policy does not allow, as the host resolved it
 * (and the path the agent named, when that led elsewhere), when the host
 * recorded the request (in UTC, whatever the phone's time zone), for a
 * proxy refusal who tried it, why it is needed, and that the person
 * decides. It is never sent for them.
 */
fun askWhyQuestion(request: PolicyRequest): String {
    val what = when (request.kind) {
        "network" -> "a connection to ${request.shown}"
        "write" -> "writing to ${request.shown}"
        "read" -> "reading ${request.shown}"
        else -> "\"${Visible.stripped(request.action)}\""
    }
    val named = if (request.retargeted) " You named ${Visible.revealed(request.target)}, which leads there." else ""
    val at = utc.format(java.time.Instant.ofEpochSecond(request.time))
    val asked = if (request.agent.isNotBlank()) {
        "The ${request.sandbox} sandbox's policy does not allow $what, which you asked for; the host recorded the request at $at.$named Why do you need it, and what will you do with it?"
    } else {
        "The ${request.sandbox} sandbox's proxy refused $what; the host recorded it at $at and cannot tell which agent tried it.$named Was it you, or do you know who? Why is it needed, and what would it be used for?"
    }
    return "$asked Don't look for another way around it: I'll decide whether to allow it once I know."
}

/**
 * Puts [question] in the field of [request]'s own conversation and returns
 * its id: the direct, unarchived conversation of the agent that asked, as
 * [conversations] hold it on the request's host. A draft the person wrote
 * there is kept, and the field says the question was not added
 * ([Outgoing.prefill]). Null when there is none, so a new conversation
 * asks: always for a proxy refusal, which has no conversation.
 */
internal fun askInConversation(request: PolicyRequest, question: String, conversations: List<Conversation>, outgoing: Outgoing): String? {
    if (request.agent.isBlank()) return null
    val id = request.conversation ?: return null
    val conversation = conversations.firstOrNull { it.host == request.host && it.id == id } ?: return null
    if (conversation.group || conversation.archived || conversation.agent != request.agent) return null
    outgoing.prefill(id, question)
    return id
}

fun AppModel.askInConversation(request: PolicyRequest, question: String): String? =
    askInConversation(request, question, conversations, outgoing)

@Composable
fun PolicyRequestRow(request: PolicyRequest, agentLabel: (String) -> String = { it.substringAfterLast('/') }, open: () -> Unit) {
    val p = palette
    val agent = request.agent.takeIf { it.isNotBlank() }?.let { agentLabel("${request.host}/$it") }
    val title = when (request.kind) {
        "write" -> "Write to ${request.shown.trimEnd('/').substringAfterLast('/').ifBlank { "/" }}"
        "read" -> "Read ${request.shown.trimEnd('/').substringAfterLast('/').ifBlank { "/" }}"
        else -> request.title
    }
    val status = when {
        request.state == "applying" -> "Saving decision…"
        request.state == "failed" -> "Needs review"
        !request.available -> "Cannot approve"
        else -> null
    }
    val context = listOfNotNull(status, agent ?: "Agent unknown", request.sandbox, request.host).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "Review attempted action", role = Role.Button, onClick = open)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(32.dp).semantics { contentDescription = agent?.let { "Requested by $it" } ?: "Requesting agent unknown" },
            contentAlignment = Alignment.Center,
        ) {
            if (agent != null) AgentInitial(agent, side = 32.dp, photo = PhotoKey(request.host, request.agent))
            else SandboxMark(live = false, side = 32.dp, tint = p.textFaint)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
            }
            Text(context, style = Type.small, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PolicyDecisionButtons(model: AppModel, request: PolicyRequest, onCloseStarted: () -> Unit) {
    val context = LocalContext.current
    // Each decision asks for the biometric or screen lock first, whatever
    // the app-lock setting; cancelled, nothing is sent.
    val decide = { approve: Boolean ->
        model.approvalErrors.remove(request.key)
        (context as? MainActivity)?.unlocker?.let { unlocker ->
            Confirm.decide(
                if (approve) "Approve the request" else "Deny the request",
                screenLock = AppLock.available(context),
                ask = unlocker::ask,
                send = { model.decideApproval(request, approve) },
                held = { why -> model.approvalErrors[request.key] = listOfNotNull("Nothing was sent.", why).joinToString(" ") },
            )
        }
        Unit
    }
    PolicyDecisions(
        request,
        operation = model.pendingApprovalActions[request.key]?.action,
        live = model.connected(request.host) && model.approvalsFresh[request.host] == true,
        closable = model.hostSupports(request.host, "approval.close"),
        error = model.approvalErrors[request.key],
        decide = decide,
    ) { if (model.closeApproval(request)) onCloseStarted() }
}

/**
 * The decisions of [request], as the page shows them under its details: Approve and Deny while it is pending (or
 * a decision is being saved), else its outcome; Close request with its explanation while it is pending or failed;
 * why a control is unavailable; then [error]. [operation] is the one this phone has in flight, [live] whether its
 * host is connected and freshly synced, [closable] whether that host can close requests. [decide] and [close] are
 * only called from an enabled control.
 */
@Composable
internal fun PolicyDecisions(
    request: PolicyRequest,
    operation: PolicyRequestAction?,
    live: Boolean,
    closable: Boolean,
    error: String?,
    decide: (approve: Boolean) -> Unit,
    close: () -> Unit,
) {
    val p = palette
    val closing = operation == PolicyRequestAction.Close
    val busy = operation != null || request.state == "applying"
    val deciding = busy && !closing
    if (request.pending || deciding) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).decisionControl()) {
                PrimaryButton(if (deciding) "Saving decision…" else if (request.restart) "Approve & restart" else "Approve",
                    enabled = live && !busy && request.available) { decide(true) }
            }
            PolicyOutlineButton("Deny", live && !busy, Modifier.weight(1f)) { decide(false) }
        }
    } else {
        Text(when (request.state) { "approved" -> "Approved · policy updated"; "denied" -> "Denied · policy unchanged"; "closed" -> "Closed · no new decision"; "diagnostic" -> "Diagnostic probe · no approval needed"; "failed" -> "Decision needs review on the host"; else -> request.state }, style = Type.name, color = if (request.state == "closed") p.textMuted else p.accent)
    }
    if (request.pending || request.state == "failed" || closing) {
        Gap(12.dp)
        PolicyOutlineButton(if (closing) "Closing request…" else "Close request",
            enabled = live && !busy && closable,
            modifier = Modifier.fillMaxWidth()) {
            close()
        }
        Text("Closing only removes this request from the Inbox. It does not approve or deny access, undo earlier changes, or resume work.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp))
        val unavailable = when {
            !live -> "Reconnect to ${request.host} and sync to respond."
            !closable -> "This host cannot close requests yet."
            else -> null
        }
        unavailable?.let { Text(it, style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 8.dp)) }
    } else if (deciding && !live) {
        Text("Reconnect to ${request.host} and sync to check the decision.", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 8.dp))
    }
    error?.let { Text(it, style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
internal fun PolicyOutlineButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val p = palette
    // At least 48 dp; a label wrapped by large text makes it taller rather than being cut.
    Box(modifier.decisionControl().heightIn(min = 48.dp).clip(Shapes.control).border(1.dp, p.lineStrong, Shapes.control)
        .clickable(enabled = enabled, role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
        Text(label, style = Type.label, color = if (enabled) p.text else p.textFaint, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Test tags of the policy request screen's layout regions, for its geometry tests. */
internal object PolicyRequestTags {
    const val TOP_BAR = "policy.top"
    const val DETAILS = "policy.details"
    const val DECISIONS = "policy.decisions"
    const val TITLE = "policy.title"
    const val PAGE = "policy.page"
}

@Composable
fun PolicyRequestScreen(model: AppModel, key: String, back: () -> Unit, openChat: (String) -> Unit, ask: (agent: String) -> Unit = {},
    insets: WindowInsets = WindowInsets.navigationBars) {
    val p = palette
    val request = model.approvals[key]
    var closing by rememberSaveable(key) { mutableStateOf(false) }
    val operation = model.pendingApprovalActions[key]?.action
    LaunchedEffect(closing, request?.state, operation) {
        if (closing && request?.state == "closed") back()
        else if (closing && operation != PolicyRequestAction.Close) closing = false
    }
    if (request == null) {
        PolicyRequestLayout(back, insets) {
            Text("This request is no longer available. Connect to its host to sync.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(4.dp))
        }
    } else {
        PolicyRequestLayout(back, insets, decisions = { PolicyDecisionButtons(model, request) { closing = true } }) {
            val asked = if (askWhyOffered(request)) askWhyAgent(request, model.agents) { model.lastAt(it) } else null
            val conversation = request.conversation
            PolicyRequestDetails(
                request,
                agentLabel = model::agentLabel,
                asked = asked,
                askedWrote = asked != null && model.lastAt(asked.address) != null,
                ask = ask,
                openConversation = if (conversation != null && model.conversations.any { it.host == request.host && it.id == conversation }) {
                    { openChat(conversation) }
                } else null,
            )
        }
    }
}

/**
 * The details of [request], as its page shows them above the decisions. [agentLabel] names an agent by its
 * address; [asked] is the agent **Ask why** asks (null: the phone lists none to ask), [askedWrote] whether it
 * has a message on this phone; [openConversation] opens the request's conversation, null when the phone does
 * not hold it.
 */
@Composable
internal fun PolicyRequestDetails(
    request: PolicyRequest,
    agentLabel: (String) -> String,
    asked: Agent?,
    askedWrote: Boolean,
    ask: (agent: String) -> Unit,
    openConversation: (() -> Unit)?,
) {
    val p = palette
    Text(request.title, style = Type.heading, color = p.text, modifier = Modifier.testTag(PolicyRequestTags.TITLE))
    Gap(12.dp)
    Text("${request.host}/${request.sandbox}", style = Type.code, color = p.textFaint)
    if (request.agent.isNotBlank()) Text("Requested by ${agentLabel("${request.host}/${request.agent}")}", style = Type.small, color = p.textMuted)
    else Text("The proxy records this for the sandbox; it cannot identify which agent tried it.", style = Type.small, color = p.textMuted)
    Gap(24.dp)
    Text("Attempted action", style = Type.name, color = p.text)
    Gap(8.dp)
    SelectionContainer { Text(Visible.stripped(request.action), style = Type.small, color = p.textMuted) }
    Gap(12.dp)
    // The scope approval writes leads; the agent's own text is only the request.
    SelectionContainer { Text(request.shown, style = Type.code, color = p.accent) }
    if (request.retargeted) {
        Text("Requested as ${Visible.revealed(request.target)}, a link to the folder above: approval grants that folder, not the link.",
            style = Type.small, color = p.text, modifier = Modifier.padding(top = 8.dp))
    } else if (request.scope.isNotBlank() && request.scope != request.target) {
        Text("Requested as ${Visible.revealed(request.target)}.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp))
    }
    Gap(24.dp)
    Text("What approval changes", style = Type.name, color = p.text)
    Gap(8.dp)
    Text(request.change.ifBlank { "This scope cannot be approved from the phone." }, style = Type.small, color = p.textMuted)
    if (request.privateAddress) Text("Includes private network addresses for this destination.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp))
    if (request.restart) Text("All running work in this sandbox will stop. Its services restart under the updated policy.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp))
    Gap(12.dp)
    Text("Approval is saved for the whole sandbox. Every agent learns its active policy for later work. The attempted action is not replayed automatically.", style = Type.small, color = p.textMuted)
    request.reason?.let { Gap(12.dp); Text(it, style = Type.small, color = p.textMuted) }
    // Asking decides nothing: the request stays, to approve or deny once the answer is in.
    val offered = askWhyOffered(request)
    val asking = asked.takeIf { offered }
    if (asking != null) {
        Gap(16.dp)
        Text("Ask ${agentLabel(asking.address)} why →", style = Type.name, color = p.accent, modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { ask(asking.address) }.padding(vertical = 12.dp))
        if (request.agent.isBlank()) {
            val hint = if (askedWrote) "This asks the agent of ${request.sandbox} with the newest message here; you can pick another before sending."
            else "No agent of ${request.sandbox} has a message here yet: this asks the first listed; you can pick another before sending."
            Text(hint, style = Type.small, color = p.textFaint)
        }
    } else if (offered) {
        Gap(16.dp)
        val missing = if (request.agent.isNotBlank()) "To ask ${agentLabel("${request.host}/${request.agent}")} why, this phone needs to list it: it was removed, or its host has not listed it yet."
        else "To ask why, this phone needs to list an agent of ${request.sandbox}."
        Text(missing, style = Type.small, color = p.textFaint)
    }
    if (openConversation != null) {
        Gap(if (asking != null) 4.dp else 16.dp)
        Text("Open conversation →", style = Type.name, color = p.accent, modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = openConversation).padding(vertical = 12.dp))
    }
}

/** The lines of the details' body text ([Type.small], at the phone's font scale) the details keep in view beside pinned decisions. */
internal const val POLICY_DETAILS_LINES = 4

/** The decisions' padding, around their controls and notes. */
private val DecisionsPadding = 16.dp

/**
 * The height of each decision control the page draws, as last measured, so that its layout knows the
 * tallest: a label wrapped by large text makes a control taller than its 48 dp.
 */
private class DecisionControls {
    private val heights = mutableStateMapOf<Any, Int>()
    val tallest: Int get() = heights.values.maxOrNull() ?: 0
    fun set(control: Any, height: Int) { if (heights[control] != height) heights[control] = height }
    fun remove(control: Any) { heights.remove(control) }
}

private val LocalDecisionControls = staticCompositionLocalOf<DecisionControls?> { null }

/** Marks a decision control: the policy request page measures it ([DecisionControls]). Elsewhere, nothing. */
private fun Modifier.decisionControl(): Modifier = this then DecisionControlElement

private data object DecisionControlElement : ModifierNodeElement<DecisionControlNode>() {
    override fun create() = DecisionControlNode()
    override fun update(node: DecisionControlNode) = Unit
}

private class DecisionControlNode : Modifier.Node(), LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    /** Where this control's height was last recorded, to take it out again when the control goes. */
    private var into: DecisionControls? = null
    override fun onRemeasured(size: IntSize) {
        val now = currentValueOf(LocalDecisionControls)
        if (now !== into) into?.remove(this)
        into = now
        now?.set(this, size.height)
    }
    override fun onDetach() { into?.remove(this); into = null }
}

/**
 * The policy request screen's one layout: the glass top bar, then the request's [details], then its
 * [decisions], one above the other, never one over the other, in the same order and words in every window.
 *
 * - Normally the decisions are pinned: the details scroll under the top bar and stop at the decisions,
 *   which sit at the bottom, above the navigation bar ([insets]), at their own height.
 * - When that would leave the details less than [POLICY_DETAILS_LINES] lines of their text between the
 *   bars (large text, a short window), the decisions are bounded to the rest and scroll on their own, so
 *   every control and explanation stays reachable.
 * - When the room between the bars cannot hold both those lines and one whole decision control (the
 *   tallest the decisions draw, measured, with their padding), the page is one scroll instead: the
 *   details, then the same decisions at their own height, below them. Nothing is pinned, added or removed,
 *   and the details' scroll is the page's, so the person's place in the details is kept across the change.
 * - A window shorter than that control itself cannot show it whole in any layout; it stays one scroll, and
 *   the control is seen a part at a time. No design covers that size yet (`docs/spec.md`, To verify); the
 *   layout never acts on its own.
 *
 * With no [decisions], the details take the whole height and, scrolled, pass under the navigation bar.
 */
@Composable
internal fun PolicyRequestLayout(
    back: () -> Unit,
    insets: WindowInsets = WindowInsets.navigationBars,
    decisions: (@Composable ColumnScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit,
) {
    val p = palette
    val controls = remember { DecisionControls() }
    // The details' scroll, and the whole page's when it is one: the same state, so the place is kept.
    val page = rememberScrollState()
    val own = rememberScrollState()
    GlassScaffold(insets = insets, top = {
        Box(Modifier.testTag(PolicyRequestTags.TOP_BAR)) {
            AppBar {
                IconButton(Glyphs.back, "Back", back)
                Text("Sandbox policy", style = Type.name, color = p.text)
            }
        }
    }) { room ->
        val top = room.calculateTopPadding()
        val bottom = room.calculateBottomPadding()
        val density = LocalDensity.current
        // The least the details keep: lines of their own text, at the phone's font scale. One line is
        // scaled, then counted: large text scales non-linearly, so 4 × 20 sp is not four lines of 20 sp.
        val least = with(density) { Type.small.lineHeight.toDp() } * POLICY_DETAILS_LINES
        // One whole control: the tallest the decisions draw (48 dp until measured), with the decisions' padding.
        val control = maxOf(48.dp, with(density) { controls.tallest.toDp() }) + DecisionsPadding * 2
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val between = (maxHeight - top - bottom).coerceAtLeast(0.dp)
            // The decisions' most when pinned: all the room the details do not keep. Within it they take their
            // own height, and scroll only past it; when it cannot hold one whole control, the page is one scroll.
            val most = between - least
            val single = decisions != null && most < control
            // Only modifiers change between the layouts: no part of the page is composed again or moved.
            CompositionLocalProvider(LocalDecisionControls provides controls) {
                Column(
                    Modifier.fillMaxSize().testTag(PolicyRequestTags.PAGE)
                        .then(if (single) Modifier.verticalScroll(page).padding(top = top, bottom = bottom) else Modifier),
                ) {
                    Column(
                        (if (single) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth()).testTag(PolicyRequestTags.DETAILS)
                            .then(if (single) Modifier else Modifier.verticalScroll(page).padding(top = top, bottom = if (decisions == null) bottom else 0.dp))
                            .padding(20.dp),
                        content = details,
                    )
                    if (decisions != null) {
                        Column(
                            Modifier.fillMaxWidth().then(if (single) Modifier else Modifier.heightIn(max = most))
                                .testTag(PolicyRequestTags.DECISIONS).then(if (single) Modifier else Modifier.verticalScroll(own))
                                .padding(DecisionsPadding),
                            content = decisions,
                        )
                        Spacer(Modifier.height(if (single) 0.dp else bottom))
                    }
                }
            }
        }
    }
}
