package io.github.victorbonnet.enclos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.Motion
import io.github.victorbonnet.enclos.ui.NavBar
import io.github.victorbonnet.enclos.ui.Tab
import io.github.victorbonnet.enclos.ui.applyTheme
import io.github.victorbonnet.enclos.ui.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What fills the window: the three tabs, or a full screen over them. */
private sealed interface Route {
    data object Tabs : Route
    data object Pair : Route
    data class Chat(val id: String) : Route

    /** As saved across a configuration change. */
    fun save(): String = when (this) {
        Tabs -> "tabs"
        Pair -> "pair"
        is Chat -> "chat:$id"
    }

    companion object {
        fun restore(saved: String): Route = when {
            saved == "pair" -> Pair
            saved.startsWith("chat:") -> Chat(saved.removePrefix("chat:"))
            else -> Tabs
        }
    }
}

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
        applyTheme(this, model.theme.value)
        setContent {
            // The system's light or dark until the person chooses; then theirs.
            val dark = model.theme.value.dark(isSystemInDarkTheme())
            // The status and navigation bars' icons, readable on the theme shown.
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            EnclosTheme(dark) { Screens(model) }
        }
    }
}

/**
 * The screens and the moves between them. A full screen arrives from the
 * right over the tabs, which drift left; going back reverses it, and with
 * the back gesture the move follows the finger (predictive back) until it
 * is let go. Tabs fade through each other. Each screen draws its own
 * ground, so two never show through each other mid-move.
 */
@Composable
private fun Screens(model: AppModel) {
    var saved by rememberSaveable { mutableStateOf("tabs") }
    var tab by rememberSaveable { mutableStateOf(Tab.Inbox) }
    val routes = remember { SeekableTransitionState(Route.restore(saved)) }
    val transition = rememberTransition(routes, label = "route")
    val scope = rememberCoroutineScope()
    val go = { to: Route ->
        saved = to.save()
        scope.launch { routes.animateTo(to) }
        Unit
    }

    // Back from a full screen: the screen follows the gesture, then goes
    // or comes back depending on where it was let go. Seeking makes the tabs
    // the target at once, so the handler stays on while the screen is still
    // shown (current), as it does while one is opening (target).
    val onFullScreen = routes.currentState != Route.Tabs || routes.targetState != Route.Tabs
    PredictiveBackHandler(enabled = onFullScreen) { progress ->
        try {
            progress.collect { event -> routes.seekTo(event.progress, Route.Tabs) }
            saved = Route.Tabs.save()
            routes.animateTo(Route.Tabs)
        } catch (_: CancellationException) {
            routes.animateTo(routes.currentState)
        }
    }
    // Back from another tab: to the inbox.
    BackHandler(enabled = !onFullScreen && tab != Tab.Inbox) { tab = Tab.Inbox }

    Box(Modifier.fillMaxSize().background(palette.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        transition.AnimatedContent(transitionSpec = { routeMove(forward = targetState != Route.Tabs) }) { route ->
            val line = palette.line
            // A full screen's left edge is a hairline, so it reads crisply
            // over the tabs while it slides.
            val edge = if (route == Route.Tabs) Modifier else Modifier.drawBehind {
                drawRect(line, size = Size(1.dp.toPx(), size.height))
            }
            Box(Modifier.fillMaxSize().background(palette.bg).then(edge)) {
                when (route) {
                    Route.Pair -> PairScreen(model, back = { go(Route.Tabs) }, done = {
                        tab = Tab.Agents
                        go(Route.Tabs)
                    })
                    is Route.Chat -> ConversationScreen(model, route.id, back = { go(Route.Tabs) })
                    Route.Tabs -> Tabs(model, tab, onTab = { tab = it }, go = go)
                }
            }
        }
    }
}

/**
 * A full screen arriving (forward) or leaving (back), one move played both
 * ways: the screen, opaque, slides in from the right edge over the tabs,
 * which drift a tenth of the width to the left under it; going back it
 * slides off to the right, uncovering them drifting back. Same distance,
 * same 250 ms, same pace, so opening and closing feel alike; linear, so a
 * finger can drag it, as the predictive back gesture does, with nothing
 * showing through.
 */
private fun routeMove(forward: Boolean): ContentTransform {
    val move = tween<IntOffset>(Motion.MEDIUM, easing = LinearEasing)
    return if (forward) {
        slideInHorizontally(move) { it } togetherWith slideOutHorizontally(move) { -it / 10 }
    } else {
        slideInHorizontally(move) { -it / 10 } togetherWith slideOutHorizontally(move) { it }
    }.apply { targetContentZIndex = if (forward) 1f else -1f }
}

@Composable
private fun Tabs(model: AppModel, tab: Tab, onTab: (Tab) -> Unit, go: (Route) -> Unit) {
    val pair = { go(Route.Pair) }
    Column(Modifier.fillMaxSize()) {
        // Fade through: the tab left fades out quickly, the one chosen fades
        // in over it, growing slightly into place.
        AnimatedContent(
            tab,
            Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(Motion.enter(Motion.MEDIUM - 50)) + scaleIn(Motion.enter(Motion.MEDIUM - 50), initialScale = 0.98f)) togetherWith
                    fadeOut(Motion.exit(100))
            },
            label = "tab",
        ) { shown ->
            Box(Modifier.fillMaxSize().background(palette.bg)) {
                when (shown) {
                    Tab.Inbox -> InboxScreen(model, open = { go(Route.Chat(it)) }, newConversation = { onTab(Tab.Agents) }, pair = pair)
                    Tab.Agents -> AgentsScreen(model, start = { go(Route.Chat(model.newConversation(it))) }, pair = pair)
                    Tab.Status -> StatusScreen(model, pair = pair)
                }
            }
        }
        NavBar(tab, onTab)
    }
}
