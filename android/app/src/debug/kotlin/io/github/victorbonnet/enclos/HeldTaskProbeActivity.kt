package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.Bubble
import org.json.JSONObject

/** Isolated instrumentation surface: no AppModel, host, connection or global hooks. */
class HeldTaskProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val card = HeldTask.from("probe", JSONObject(intent.getStringExtra("card")!!))
        val client = HeldTaskClient(
            send = { error("Probe must never send") }, connected = { true }, accepts = { true },
            changed = {}, later = { _, _ -> },
        )
        client.merge(card, cached = true)
        client.authenticated(card.host)
        client.synced(card.host)
        setContent {
            EnclosTheme(dark = intent.getBooleanExtra("dark", false)) {
                CompositionLocalProvider(LocalDensity provides Density(resources.displayMetrics.density, 1.3f)) {
                    val home = Conversation(card.host, card.agent, card.conversation, home = true)
                    val linked = intent.getBooleanExtra("linked", false)
                    val shown = if (linked) client.current(home, card.task) else client.unlinked(home, emptySet()).singleOrNull()
                    shown?.let {
                        Bubble(text = "", time = "12:00", outgoing = false, header = if (linked) "on its own" else null) {
                            HeldTaskCard(client, it)
                        }
                    }
                }
            }
        }
    }
}
