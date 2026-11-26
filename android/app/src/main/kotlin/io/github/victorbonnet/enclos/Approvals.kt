package io.github.victorbonnet.enclos

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    val p = palette
    val operation = model.pendingApprovalActions[request.key]?.action
    val closing = operation == PolicyRequestAction.Close
    val busy = operation != null || request.state == "applying"
    val deciding = busy && !closing
    val live = model.connected(request.host) && model.approvalsFresh[request.host] == true
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
    if (request.pending || deciding) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
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
            enabled = live && !busy && model.hostSupports(request.host, "approval.close"),
            modifier = Modifier.fillMaxWidth()) {
            if (model.closeApproval(request)) onCloseStarted()
        }
        Text("Closing only removes this request from the Inbox. It does not approve or deny access, undo earlier changes, or resume work.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp))
        val unavailable = when {
            !live -> "Reconnect to ${request.host} and sync to respond."
            !model.hostSupports(request.host, "approval.close") -> "This host cannot close requests yet."
            else -> null
        }
        unavailable?.let { Text(it, style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 8.dp)) }
    } else if (deciding && !live) {
        Text("Reconnect to ${request.host} and sync to check the decision.", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 8.dp))
    }
    model.approvalErrors[request.key]?.let { Text(it, style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun PolicyOutlineButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val p = palette
    Box(modifier.height(48.dp).clip(Shapes.control).border(1.dp, p.lineStrong, Shapes.control)
        .clickable(enabled = enabled, role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
        Text(label, style = Type.label, color = if (enabled) p.text else p.textFaint)
    }
}

@Composable
fun PolicyRequestScreen(model: AppModel, key: String, back: () -> Unit, openChat: (String) -> Unit, ask: (agent: String) -> Unit = {}) {
    val p = palette
    val request = model.approvals[key]
    var closing by rememberSaveable(key) { mutableStateOf(false) }
    val operation = model.pendingApprovalActions[key]?.action
    LaunchedEffect(closing, request?.state, operation) {
        if (closing && request?.state == "closed") back()
        else if (closing && operation != PolicyRequestAction.Close) closing = false
    }
    GlassScaffold(Modifier.windowInsetsPadding(WindowInsets.navigationBars), top = {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("Sandbox policy", style = Type.name, color = p.text)
        }
    }) { room ->
        if (request == null) {
            Text("This request is no longer available. Connect to its host to sync.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(room).padding(24.dp))
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(room).padding(20.dp)) {
                Text(request.title, style = Type.heading, color = p.text)
                Gap(12.dp)
                Text("${request.host}/${request.sandbox}", style = Type.code, color = p.textFaint)
                if (request.agent.isNotBlank()) Text("Requested by ${model.agentLabel("${request.host}/${request.agent}")}", style = Type.small, color = p.textMuted)
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
                val asked = if (offered) askWhyAgent(request, model.agents) { model.lastAt(it) } else null
                if (asked != null) {
                    Gap(16.dp)
                    Text("Ask ${model.agentLabel(asked.address)} why →", style = Type.name, color = p.accent, modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { ask(asked.address) }.padding(vertical = 12.dp))
                    if (request.agent.isBlank()) {
                        val hint = if (model.lastAt(asked.address) != null) "This asks the agent of ${request.sandbox} with the newest message here; you can pick another before sending."
                        else "No agent of ${request.sandbox} has a message here yet: this asks the first listed; you can pick another before sending."
                        Text(hint, style = Type.small, color = p.textFaint)
                    }
                } else if (offered) {
                    Gap(16.dp)
                    val missing = if (request.agent.isNotBlank()) "To ask ${model.agentLabel("${request.host}/${request.agent}")} why, this phone needs to list it: it was removed, or its host has not listed it yet."
                    else "To ask why, this phone needs to list an agent of ${request.sandbox}."
                    Text(missing, style = Type.small, color = p.textFaint)
                }
                val conversation = request.conversation
                if (conversation != null && model.conversations.any { it.host == request.host && it.id == conversation }) {
                    Gap(if (asked != null) 4.dp else 16.dp)
                    Text("Open conversation →", style = Type.name, color = p.accent, modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { openChat(conversation) }.padding(vertical = 12.dp))
                }
            }
            Column(Modifier.padding(16.dp)) { PolicyDecisionButtons(model, request) { closing = true } }
        }
    }
}
