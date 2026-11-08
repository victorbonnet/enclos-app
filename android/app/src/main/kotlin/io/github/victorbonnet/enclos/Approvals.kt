package io.github.victorbonnet.enclos

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*
import org.json.JSONObject

/** The host owns a request and its state. A cached row never authorizes a decision. */
data class PolicyRequest(val host: String, val id: String, val sandbox: String, val agent: String,
    val conversation: String?, val kind: String, val target: String, val privateAddress: Boolean,
    val action: String, val time: Long, val state: String, val change: String, val restart: Boolean,
    val revision: String, val available: Boolean, val reason: String?) {
    val key get() = "$host/$id"
    val pending get() = state == "pending"
    val attention get() = pending || state == "applying" || state == "failed"
    val title get() = when (kind) { "network" -> "Connect to $target"; "write" -> "Write to $target"; "read" -> "Read $target"; else -> action }
    companion object {
        fun from(host: String, m: JSONObject): PolicyRequest {
            val a = m.getJSONObject("access")
            return PolicyRequest(host, m.getString("request"), m.getString("sandbox"), m.optString("agent"),
                m.optString("conversation").ifBlank { null }, a.getString("kind"), a.getString("target"), a.optBoolean("private"),
                m.optString("action"), m.getLong("time"), m.getString("state"), m.optString("change"), m.optBoolean("restart"),
                m.optString("revision"), m.optBoolean("available"), m.optString("reason").ifBlank { null })
        }
    }
}

@Composable
fun PolicyRequestRow(model: AppModel, request: PolicyRequest, open: () -> Unit) {
    val p = palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        .clip(Shapes.control).border(1.dp, p.line, Shapes.control).background(p.surface)) {
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Review attempted action", onClick = open).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Glyph(Glyphs.settings, p.accent, size = 22.dp)
                Text(request.title, style = Type.name, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
            }
            Gap(6.dp)
            Text("${request.sandbox} · ${request.host}" + if (request.agent.isBlank()) " · sandbox request" else " · ${request.agent.substringAfter('/')}" , style = Type.code, color = p.textFaint)
            Gap(6.dp)
            Text(if (request.available) request.change else request.reason ?: "This scope needs review on the host.", style = Type.small, color = p.textMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { PolicyDecisionButtons(model, request) }
    }
}

@Composable
private fun PolicyDecisionButtons(model: AppModel, request: PolicyRequest) {
    val p = palette
    val busy = request.key in model.decidingApprovals || request.state == "applying"
    val live = model.connected(request.host) && model.approvalsFresh[request.host] == true
    if (request.pending || busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PrimaryButton(if (busy) "Saving decision…" else if (request.restart) "Approve & restart" else "Approve",
                    enabled = live && !busy && request.available) { model.decideApproval(request, true) }
            }
            Box(Modifier.weight(1f).height(48.dp).clip(Shapes.control).border(1.dp, p.lineStrong, Shapes.control)
                .clickable(enabled = live && !busy, role = Role.Button) { model.decideApproval(request, false) }, contentAlignment = Alignment.Center) {
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
                SelectionContainer { Text(request.action, style = Type.small, color = p.textMuted) }
                Gap(12.dp)
                SelectionContainer { Text(request.target, style = Type.code, color = p.accent) }
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
