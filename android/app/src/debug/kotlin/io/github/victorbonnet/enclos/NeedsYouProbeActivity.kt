package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** The actual request rows/cards/buttons with isolated data; no hosts, model or network. */
class NeedsYouProbeActivity : ComponentActivity() {
    var submitted: QuestionAnswer? = null
    var reply: String? = null
    var closedRequest: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expired = intent.getBooleanExtra("expired", false)
        val runtimeApproval = intent.getBooleanExtra("runtime_approval", false)
        val q = QuestionInfo("Ubuntu", "q", "Inbox filters reset when you return from an agent. Allow a fix and independent testing?",
            if (runtimeApproval) emptyList() else listOf("Fix Inbox filters", "Keep paused"), false, "home", "lab/director",
            if (expired) QuestionState.Expired else QuestionState.Pending, if (expired) 1 else 0,
            approval = if (runtimeApproval) RuntimeApprovalDefinition("printf test", "Run a test", RuntimeApprovalAction.entries, patterns = listOf("printf test")) else null)
        lateinit var client: QuestionClient
        client = QuestionClient(send = { request ->
            submitted = request.answer
            client.result(q.host, request.id, q.copy(state = QuestionState.Answered, revision = q.revision + 1, answer = request.answer, confirmed = true))
            true
        }, connected = { true }, accepts = { true }, changed = {}, later = { _, _ -> },
            closeSupported = { true }, sendClose = { _, id -> closedRequest = id; true })
        client.merge(q); client.authenticated(q.host); client.synced(q.host)
        val rows = listOf(
            OpenItem("Ubuntu", "q", "question", "lab/director", "home", q.text, 3, expired),
            OpenItem("Mac", "held", "held_task", "lab/reviewer", "home-reviewer", "Review is paused until you choose the next step.", 2, false),
            OpenItem("Ubuntu", "approval", "runtime_approval", "lab/engineer", "home-engineer", "Allow the requested test command?", 1, false))
        setContent {
            EnclosTheme(dark = intent.getBooleanExtra("dark", false)) {
                CompositionLocalProvider(LocalDensity provides Density(resources.displayMetrics.density, intent.getFloatExtra("font", 1f))) {
                    var selected by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!selected) {
                            Text("Needs you (3)", style = Type.heading, color = palette.accent)
                            rows.zip(listOf("Director", "Reviewer", "Engineer")).forEach { (item, label) ->
                                NeedsYouRow(item, label, true) { selected = true }
                            }
                            Text("Conversations", style = Type.heading, color = palette.text)
                            Text("Ordinary messages stay below requests.", style = Type.body, color = palette.textMuted)
                        } else {
                            if (runtimeApproval) RuntimeApprovalCard(client, client.questions[q.key]!!)
                            else QuestionCard(client, client.questions[q.key]!!)
                            Text("Suggested replies", style = Type.label, color = palette.textMuted)
                            ReplyChoices(listOf("Fix Inbox filters", "Keep paused"), intent.getBooleanExtra("online", true)) { reply = it }
                        }
                    }
                }
            }
        }
    }
}
