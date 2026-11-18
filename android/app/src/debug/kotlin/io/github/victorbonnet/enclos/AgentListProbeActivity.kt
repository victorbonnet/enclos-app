package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** Actual agent rows with isolated data: no AppModel, hosts or real requests. */
class AgentListProbeActivity : ComponentActivity() {
    var opened = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        setContent {
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            EnclosTheme(dark = dark) {
                CompositionLocalProvider(LocalDensity provides Density(resources.displayMetrics.density, intent.getFloatExtra("font", 1f))) {
                    Box(Modifier.fillMaxSize().background(palette.bg).safeDrawingPadding()) {
                        Column(Modifier.width(intent.getIntExtra("width", 360).dp)) {
                            AgentListRow(Agent("Ubuntu", "enclos-org/director", "hermes", emptyList(), displayName = "Director"), false,
                                "active just now", "161.2M tokens · 24h", 4) { opened = true }
                            AgentListRow(Agent("Ubuntu", "comptoir/dede", "hermes", emptyList(), displayName = "Dédé"), false,
                                "active 23 hours ago", "377.8k tokens · 24h", 0) {}
                            AgentListRow(Agent("Ubuntu", "enclos-org/reviewer", "hermes", emptyList(), displayName = "Reviewer"), true,
                                "Reviewing the request and its comments", "2.1M tokens · 24h", 2) {}
                            AgentListRow(Agent("Ubuntu", "enclos-org/amelia", "hermes", emptyList(), displayName = "Amelia"), false,
                                "no message yet", null, 0) {}
                        }
                    }
                }
            }
        }
    }
}
