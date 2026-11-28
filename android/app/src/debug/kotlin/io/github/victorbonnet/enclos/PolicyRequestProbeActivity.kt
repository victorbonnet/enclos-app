package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The policy request screen as production draws it ([PolicyRequestLayout] with [PolicyRequestDetails] and
 * [PolicyDecisions]: its glass top bar, details and decisions) with a synthetic request only: no AppModel,
 * host, store or network. Its controls only record which one was tapped ([tapped]); nothing is decided,
 * closed or asked. Extras: `dark`, `font` (scale), `long` (long text, private-address and restart warnings,
 * a resolved scope and a known conversation), `state` (`pending`, `offline`: pending on a host not
 * connected, with an error; `unavailable`: pending, cannot be approved; `applying`; `failed`, with an error;
 * `approved`), `height` (dp; the layout's height, at the bottom of the window, as a shorter window would give
 * it; 0, the default: the whole window).
 */
class PolicyRequestProbeActivity : ComponentActivity() {
    /** The label of each control tapped, in order. */
    val tapped = CopyOnWriteArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val long = intent.getBooleanExtra("long", false)
        val font = intent.getFloatExtra("font", 1f)
        val state = intent.getStringExtra("state") ?: "pending"
        val height = intent.getIntExtra("height", 0)
        val request = probeRequest(long, state)
        val worker = Agent(request.host, "lab/worker", "hermes", emptyList(), displayName = "Worker")
        val error = if (state == "offline" || state == "failed") "Nothing was sent. The screen lock was cancelled." else null
        setContent {
            EnclosTheme(dark = dark) {
                CompositionLocalProvider(LocalDensity provides Density(resources.displayMetrics.density, font)) {
                    // A shorter window: the layout `height` tall, at the bottom, over the page's ground.
                    Box(Modifier.fillMaxSize().background(palette.bg)
                        .then(if (height > 0) Modifier.wrapContentHeight(Alignment.Bottom).height(height.dp) else Modifier)) {
                        PolicyRequestLayout(back = { tapped += "Back" }, decisions = {
                            PolicyDecisions(request, operation = null, live = state != "offline", closable = true, error = error,
                                decide = { approve -> tapped += if (approve) "Approve" else "Deny" }, close = { tapped += "Close request" })
                        }) {
                            PolicyRequestDetails(request, agentLabel = { if (it == worker.address) "Worker" else it.substringAfterLast('/') },
                                asked = worker, askedWrote = true, ask = { tapped += "Ask Worker why" },
                                openConversation = if (long) ({ tapped += "Open conversation" }) else null)
                        }
                    }
                }
            }
        }
    }
}

/** The synthetic request of the probe: host `test-host`, sandbox `lab`, agent `lab/worker`, nothing real. */
internal fun probeRequest(long: Boolean, state: String): PolicyRequest {
    val short = PolicyRequest(
        "test-host", "fixture-1", "lab", "lab/worker", null, "network", "api.example.test", false,
        "Connect to api.example.test:443", 1_700_000_000, "pending", "Allow network access to api.example.test", false,
        "fixture-1", true, "Fetch a dependency", "api.example.test",
    )
    val request = if (!long) short else short.copy(
        conversation = "fixture-conversation",
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
    return when (state) {
        "pending", "offline" -> request
        "unavailable" -> request.copy(available = false)
        "failed" -> request.copy(state = "failed", reason = if (long) request.reason else "Restart failed after saving policy")
        else -> request.copy(state = state)
    }
}
