package io.github.victorbonnet.enclos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.NavBar
import io.github.victorbonnet.enclos.ui.Tab
import io.github.victorbonnet.enclos.ui.palette

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onStart() {
        super.onStart()
        model.foreground()
    }

    override fun onStop() {
        model.background()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnclosTheme {
                // The three destinations, and the full screens over them.
                var tab by rememberSaveable { mutableStateOf(Tab.Inbox) }
                var conversation by rememberSaveable { mutableStateOf<String?>(null) }
                var pairing by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = conversation != null || pairing || tab != Tab.Inbox) {
                    when {
                        pairing -> pairing = false
                        conversation != null -> conversation = null
                        else -> tab = Tab.Inbox
                    }
                }
                val pair = { pairing = true }
                Box(Modifier.fillMaxSize().background(palette.bg).windowInsetsPadding(WindowInsets.statusBars)) {
                    val open = conversation
                    when {
                        pairing -> PairScreen(model, back = { pairing = false }, done = {
                            pairing = false
                            tab = Tab.Agents
                        })
                        open != null -> ConversationScreen(model, open, back = { conversation = null })
                        else -> Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    Tab.Inbox -> InboxScreen(model, open = { conversation = it }, newConversation = { tab = Tab.Agents }, pair = pair)
                                    Tab.Agents -> AgentsScreen(model, start = { conversation = model.newConversation(it) }, pair = pair)
                                    Tab.Status -> StatusScreen(model, pair = pair)
                                }
                            }
                            NavBar(tab) { tab = it }
                        }
                    }
                }
            }
        }
    }
}
