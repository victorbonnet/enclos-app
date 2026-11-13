package io.github.victorbonnet.enclos

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun PolicyRequestRow(request: PolicyRequest, open: () -> Unit) {
    val p = palette
    val agent = request.agent.substringAfterLast('/').ifBlank { null }
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
            if (agent != null) AgentInitial(agent, side = 32.dp)
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
private fun PolicyDecisionButtons(model: AppModel, request: PolicyRequest) {
    val p = palette
    val busy = request.key in model.decidingApprovals || request.state == "applying"
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
    if (request.pending || busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PrimaryButton(if (busy) "Saving decision…" else if (request.restart) "Approve & restart" else "Approve",
                    enabled = live && !busy && request.available) { decide(true) }
            }
            Box(Modifier.weight(1f).height(48.dp).clip(Shapes.control).border(1.dp, p.lineStrong, Shapes.control)
                .clickable(enabled = live && !busy, role = Role.Button) { decide(false) }, contentAlignment = Alignment.Center) {
                Text("Deny", style = Type.label, color = if (live && !busy) p.text else p.textFaint)
            }
        }
        if (!live) Text("Connect to ${request.host} and sync to decide.", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 8.dp))
    } else {
        Text(when (request.state) { "approved" -> "Approved · policy updated"; "denied" -> "Denied · policy unchanged"; "failed" -> "Decision needs review on the host"; else -> request.state }, style = Type.name, color = p.accent)
    }
    model.approvalErrors[request.key]?.let { Text(it, style = Type.small, color = p.textMuted, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
fun PolicyRequestScreen(model: AppModel, key: String, back: () -> Unit, openChat: (String) -> Unit) {
    val p = palette
    val request = model.approvals[key]
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("Sandbox policy", style = Type.name, color = p.text)
        }
        Hairline()
        if (request == null) {
            Text("This request is no longer available. Connect to its host to sync.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(24.dp))
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(request.title, style = Type.heading, color = p.text)
                Gap(12.dp)
                Text("${request.host}/${request.sandbox}", style = Type.code, color = p.textFaint)
                if (request.agent.isNotBlank()) Text("Requested by ${request.agent.substringAfter('/')}", style = Type.small, color = p.textMuted)
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
                val conversation = request.conversation
                if (conversation != null && model.conversations.any { it.host == request.host && it.id == conversation }) {
                    Gap(16.dp)
                    Text("Open conversation →", style = Type.name, color = p.accent, modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { openChat(conversation) }.padding(vertical = 12.dp))
                }
            }
            Column(Modifier.padding(16.dp)) { PolicyDecisionButtons(model, request) }
        }
    }
}
