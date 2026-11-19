package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** Synthetic files, actual production controls; no model, credentials or transport. */
class FileComposerProbeActivity : ComponentActivity() {
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
                    var text by remember { mutableStateOf("Please check these files.") }
                    var names by remember { mutableStateOf(listOf("photo.jpg", "notes.pdf")) }
                    var action by remember { mutableStateOf("Synthetic attachment fixture") }
                    Box(Modifier.fillMaxSize().background(palette.bg).safeDrawingPadding()) {
                        Column(Modifier.width(intent.getIntExtra("width", 360).dp).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                            Text(action, style = Type.small, color = palette.textMuted, modifier = Modifier.padding(14.dp))
                            FileComposerControls(
                                names, online = !intent.getBooleanExtra("offline", false),
                                preparing = intent.getBooleanExtra("preparing", false),
                                progress = intent.getIntExtra("progress", -1).takeIf { it >= 0 },
                                error = intent.getStringExtra("error"),
                                onCamera = { action = "Fixture: Camera selected" },
                                onPhotos = { action = "Fixture: Photos selected" },
                                onFiles = { action = "Fixture: Files selected" },
                                onRemove = { index -> names = names.filterIndexed { i, _ -> i != index } },
                            ) { attach, enabled ->
                                Composer(text, { text = it }, "Message Director",
                                    canSend = enabled, hasFiles = names.isNotEmpty(),
                                    notice = if (intent.getBooleanExtra("offline", false)) "Offline fixture" else null,
                                    attachment = attach, onSend = { action = "Fixture: Send selected" })
                            }
                        }
                    }
                }
            }
        }
    }
}
