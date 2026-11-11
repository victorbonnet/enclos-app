package io.github.victorbonnet.enclos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    /** The unlock prompt: made with the activity, as the screen-lock page it may open requires. */
    private val unlocker = Unlocker(this)

    override fun onStart() {
        super.onStart()
        model.lock.returned()
        model.foreground()
    }

    override fun onResume() {
        super.onResume()
        // Locked: the prompt shows on its own as the app starts or comes back; the lock screen's button asks again.
        if (model.lock.promptNow(unlocker.asking)) unlock()
    }

    override fun onStop() {
        model.lock.stopped(unlocker.asking)
        model.background()
        super.onStop()
    }

    /** Asks the person to unlock the app. */
    private fun unlock() = unlocker.ask("Unlock Enclos") { ok, why -> model.lock.unlocked(ok, why) }

    /**
     * While the lock is on, the window is secure: no screenshot, recording
     * or cast shows the app, nor does the recent apps' picture of it (all
     * versions; the app's dialogs inherit it). From Android 13 the recent
     * apps also keep no picture at all.
     */
    private fun secure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!on)
    }

    /** An `enclos://pair` link the system opened the app with: shown on the pairing screen, which asks first. */
    private fun open(intent: Intent?) {
        val link = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW && it.scheme == "enclos" } ?: return
        model.opened(link.toString())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme(this, model.theme.value)
        // Before the first frame, so none is drawn unsecured.
        secure(model.lock.on.value)
        // Not again when the activity is recreated: the link was handled.
        if (savedInstanceState == null) open(intent)
        // The launch screen stays until what the phone kept for the first
        // screens is read, off the main thread, so the app does not open
        // empty and fill in; at most a second, should the read be slow.
        val content = findViewById<android.view.View>(android.R.id.content)
        val since = android.os.SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!model.ready && android.os.SystemClock.uptimeMillis() - since < 1_000) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
        setContent {
            // The system's light or dark until the person chooses; then theirs.
            val dark = model.theme.value.dark(isSystemInDarkTheme())
            // The status and navigation bars' icons, readable on the theme shown.
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            val lockOn = model.lock.on.value
            LaunchedEffect(lockOn) { secure(lockOn) }
            // Kept while the screens are not composed: the route, the tab, scroll positions.
            val kept = rememberSaveableStateHolder()
            EnclosTheme(dark) {
                // Locked, the lock screen is all that is composed: no screen, dialog, sheet or menu
                // of the app is there to see or reach, by touch, keyboard or accessibility services,
                // and none marks a message read. Unlocked, the screens come back where they were.
                if (model.lock.locked.value) {
                    LockScreen(model.lock, unlock = ::unlock, leave = { moveTaskToBack(true) })
                } else {
                    kept.SaveableStateProvider("screens") { Screens(model, unlocker::ask) }
                }
            }
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
private fun Screens(model: AppModel, ask: Ask) {
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
    // A pairing link opened the app: to the pairing screen, which asks.
    val opened = model.openedLink.value
    LaunchedEffect(opened) { if (opened != null) go(Route.Pair) }

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
                    Route.Tabs -> Tabs(model, tab, onTab = { tab = it }, go = go, ask = ask)
                }
            }
        }
    }
}

/**
 * A full screen arriving (forward) or leaving (back). Forward, Material's
 * shared X axis made short: what is covered fades out first while drifting
 * left, then the new screen fades in on top from a tenth of the width, so
 * the two barely overlap. Back, the screen stays opaque and slides off to
 * the right, uncovering the tabs drifting back into place: a move a finger
 * can drag, as the predictive back gesture does, with nothing showing
 * through.
 */
private fun routeMove(forward: Boolean): ContentTransform =
    if (forward) {
        (slideInHorizontally(Motion.enter()) { it / 10 } + fadeIn(Motion.enter(Motion.MEDIUM - 50, delay = 50))) togetherWith
            (slideOutHorizontally(Motion.enter()) { -it / 20 } + fadeOut(Motion.exit(100)))
    } else {
        // Linear: dragged by a finger, the screen stays under it.
        val drag = tween<IntOffset>(Motion.MEDIUM, easing = LinearEasing)
        slideInHorizontally(drag) { -it / 10 } togetherWith slideOutHorizontally(drag) { it }
    }.apply { targetContentZIndex = if (forward) 1f else -1f }

@Composable
private fun Tabs(model: AppModel, tab: Tab, onTab: (Tab) -> Unit, go: (Route) -> Unit, ask: Ask) {
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
                    Tab.Agents -> AgentsScreen(model, start = { go(Route.Chat(model.newConversation(it))) }, open = { go(Route.Chat(it)) }, pair = pair)
                    Tab.Status -> StatusScreen(model, pair = pair, ask = ask)
                }
            }
        }
        NavBar(tab, onTab)
    }
}
