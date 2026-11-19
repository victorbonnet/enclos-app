package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** Component-only fixture; no model, credentials or transport. */
class FileComposerProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EnclosTheme(dark = intent.getBooleanExtra("dark", false)) {
                var text by remember { mutableStateOf("A large draft ".repeat(100)) }
                var menu by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.Bottom) {
                    Composer(text, { text = it }, "Message Director",
                        canSend = !intent.getBooleanExtra("offline", false), hasFiles = true,
                        attachment = {
                            TextButton(onClick = { menu = !menu }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Attach files" }) {
                                Text("+", style = Type.message, color = palette.accent)
                            }
                        }, onSend = {})
                    if (menu) Text("Camera · Photos · Files")
                }
            }
        }
    }
}
