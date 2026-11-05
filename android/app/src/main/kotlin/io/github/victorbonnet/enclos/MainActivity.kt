package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.victorbonnet.enclos.core.coreVersion

private sealed interface Screen {
    data object Home : Screen
    data object Pair : Screen
    data class Chat(val conversation: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val model: AppModel = viewModel()
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                Scaffold { padding ->
                    Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                        when (val s = screen) {
                            Screen.Home -> Home(model, { screen = Screen.Pair }, { screen = Screen.Chat(it) })
                            Screen.Pair -> PairScreen(model) { screen = Screen.Home }
                            is Screen.Chat -> Chat(model, s.conversation)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Home(model: AppModel, pair: () -> Unit, open: (String) -> Unit) {
    Text("enclos", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    if (model.paired.isEmpty()) {
        Text("No host yet. On a host, run `enclos app serve`, then `enclos app pair`, and scan its QR code.")
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(model.paired, key = { "host-" + it.name }) { host -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium)
                    Text(model.status[host.name] ?: "", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { model.reconnect(host.name) }) { Text("Reconnect") }
            }
            model.agents.filter { it.host == host.name }.forEach { agent ->
                Text(
                    "  ${agent.name}  (${agent.runtime}) — new conversation",
                    Modifier.fillMaxWidth().clickable { open(model.newConversation(agent)) }.padding(8.dp),
                )
            }
            model.conversations.filter { it.host == host.name }.forEach { c ->
                val last = model.lines[c.id]?.lastOrNull()?.text ?: "(empty)"
                Text(
                    "  ${c.agent}: ${last.take(60)}",
                    Modifier.fillMaxWidth().clickable { open(c.id) }.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
        } }
    }
    Button(onClick = pair, Modifier.fillMaxWidth()) { Text("Pair a host") }
    Text("core ${coreVersion()}", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun ColumnScope.PairScreen(model: AppModel, done: () -> Unit) {
    var link by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { model.pair(it) }
    }
    Text("Pair a host", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text("On the host, run `enclos app pair`, then scan its QR code or paste its link.")
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            scan.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("The QR code of `enclos app pair`")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    // A terminal with a dark theme draws the code light on
                    // dark: read it either way round.
                    .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN),
            )
        },
        Modifier.fillMaxWidth(),
    ) { Text("Scan the QR code") }
    OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("enclos://pair?…") })
    Button(onClick = { model.pair(link) }, Modifier.fillMaxWidth(), enabled = link.isNotBlank()) { Text("Pair") }
    model.pairing.value?.let { Text(it, Modifier.padding(top = 8.dp)) }
    Spacer(Modifier.weight(1f))
    OutlinedButton(onClick = done, Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun ColumnScope.Chat(model: AppModel, id: String) {
    val conversation = model.conversations.firstOrNull { it.id == id } ?: return
    val lines = model.lines[id].orEmpty()
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    Text("${conversation.agent} on ${conversation.host}", style = MaterialTheme.typography.titleMedium)
    LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(lines, key = { it.seq }) { line ->
            val who = if (line.from == "agent") conversation.agent else "you"
            val note = if (line.kind == "notify") " (on its own)" else ""
            Text("$who$note: ${line.text}")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(draft, { draft = it }, Modifier.weight(1f))
        Button(
            onClick = {
                model.send(conversation, draft.trim())
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) { Text("Send") }
    }
}
