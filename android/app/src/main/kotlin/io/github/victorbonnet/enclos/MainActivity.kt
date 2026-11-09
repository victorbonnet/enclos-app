package io.github.victorbonnet.enclos

import android.content.Intent
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import io.github.victorbonnet.enclos.ui.LocalAgentPictures
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.IconButton
import io.github.victorbonnet.enclos.ui.Glyphs
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.Motion
import io.github.victorbonnet.enclos.ui.NavBar
import io.github.victorbonnet.enclos.ui.Tab
import io.github.victorbonnet.enclos.ui.applyTheme
import io.github.victorbonnet.enclos.ui.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What fills the window: the first screen (its two views), or a full screen over it. */
private sealed interface Route {
    data object Tabs : Route
    data object Pair : Route
    data object Settings : Route
    data object NewChat : Route
    data class ApprovalPage(val key: String) : Route
    data class Chat(val id: String) : Route
    /** An agent's page (`host/sandbox/agent`), opened from its sandbox's page when [inSandbox]. */
    data class AgentPage(val address: String, val inSandbox: Boolean = false) : Route
    data class RoutinesPage(val address: String, val fromAgent: Boolean = false, val inSandbox: Boolean = false) : Route

    /** A sandbox's page: `host/sandbox`. */
    data class SandboxPage(val address: String) : Route
    data class PolicyPage(val address: String) : Route

    /** Where back leads: an agent opened from its sandbox's page goes back to it; the rest, to the tabs. */
    val up: Route get() = when {
        this is PolicyPage -> SandboxPage(address)
        this is RoutinesPage && fromAgent -> AgentPage(address, inSandbox)
        this is AgentPage && inSandbox -> SandboxPage(address.substringBeforeLast('/'))
        else -> Tabs
    }

    /** How deep it is: a move to a deeper route goes forward. */
    val depth: Int get() = when {
        this == Tabs -> 0
        else -> up.depth + 1
    }

    /** As saved across a configuration change. */
    fun save(): String = when (this) {
        Tabs -> "tabs"
        Pair -> "pair"
        Settings -> "settings"
        NewChat -> "new"
        is ApprovalPage -> "approval:$key"
        is Chat -> "chat:$id"
        is AgentPage -> if (inSandbox) "sandbox-agent:$address" else "agent:$address"
        is RoutinesPage -> (if (inSandbox) "sandbox-agent-routines:" else if (fromAgent) "agent-routines:" else "routines:") + address
        is SandboxPage -> "sandbox:$address"
        is PolicyPage -> "policy:$address"
    }

    companion object {
        fun restore(saved: String): Route = when {
            saved == "pair" -> Pair
            saved == "settings" -> Settings
            saved == "new" -> NewChat
            saved.startsWith("approval:") -> ApprovalPage(saved.removePrefix("approval:"))
            saved.startsWith("chat:") -> Chat(saved.removePrefix("chat:"))
            saved.startsWith("agent:") -> AgentPage(saved.removePrefix("agent:"))
            saved.startsWith("sandbox-agent:") -> AgentPage(saved.removePrefix("sandbox-agent:"), inSandbox = true)
            saved.startsWith("sandbox-agent-routines:") -> RoutinesPage(saved.removePrefix("sandbox-agent-routines:"), fromAgent = true, inSandbox = true)
            saved.startsWith("agent-routines:") -> RoutinesPage(saved.removePrefix("agent-routines:"), fromAgent = true)
            saved.startsWith("routines:") -> RoutinesPage(saved.removePrefix("routines:"))
            saved.startsWith("policy:") -> PolicyPage(saved.removePrefix("policy:"))
            saved.startsWith("sandbox:") -> SandboxPage(saved.removePrefix("sandbox:"))
            else -> Tabs
        }
    }
}

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    /** The unlock prompt: made with the activity, as the screen-lock page it may open requires. */
    val unlocker = Unlocker(this)

    override fun onStart() {
        super.onStart()
        model.returned(android.os.SystemClock.elapsedRealtime())
        model.foreground()
    }

    override fun onResume() {
        super.onResume()
        // Locked: the prompt shows on its own, once; the lock screen's button asks again.
        if (model.locked.value && model.promptDue) {
            model.promptDue = false
            unlock()
        }
    }

    override fun onStop() {
        // The screen-lock page the prompt opened is not the person leaving.
        if (!unlocker.asking) model.left(android.os.SystemClock.elapsedRealtime())
        model.background()
        super.onStop()
    }

    /** Asks the person to unlock the app. */
    fun unlock() = unlocker.ask("Unlock Enclos") { ok, why -> model.unlocked(ok, why) }

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
            // While the lock is on, the recent apps show no picture of the app (Android 13 and later).
            val lockOn = model.lockOn.value
            LaunchedEffect(lockOn) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!lockOn)
            }
            EnclosTheme(dark) {
                CompositionLocalProvider(LocalAgentPictures provides model.agentImages.pictures) {
                    val locked = model.locked.value
                    Box(Modifier.fillMaxSize()) {
                        // Kept under the lock, so the screen the person was on is there once unlocked;
                        // hidden from accessibility services meanwhile.
                        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { Screens(model) }
                        if (locked) LockScreen(model, unlock = ::unlock, leave = { moveTaskToBack(true) })
                    }
                }
            }
        }
    }
}

/**
 * The screens and the moves between them. A full screen arrives from the
 * right over the tabs, which drift left; going back reverses it, and with
 * the back gesture the move follows the finger (predictive back) until it
 * is let go. The two home views slide with a horizontal swipe. Each screen
 * draws its own ground, so two never show through each other mid-move.
 */
@Composable
private fun Screens(model: AppModel) {
    var saved by rememberSaveable { mutableStateOf("tabs") }
    val views = rememberPagerState(pageCount = { View.entries.size })
    val routes = remember { SeekableTransitionState(Route.restore(saved)) }
    val transition = rememberTransition(routes, label = "route")
    val scope = rememberCoroutineScope()
    val onView = { to: View ->
        scope.launch { views.animateScrollToPage(to.ordinal, animationSpec = Motion.change(Motion.MEDIUM)) }
        Unit
    }
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
        val up = routes.currentState.up
        try {
            progress.collect { event -> routes.seekTo(event.progress, up) }
            saved = up.save()
            routes.animateTo(up)
        } catch (_: CancellationException) {
            routes.animateTo(routes.currentState)
        }
    }
    // A pairing link opened the app: to the pairing screen, which asks.
    val opened = model.openedLink.value
    LaunchedEffect(opened) { if (opened != null) go(Route.Pair) }

    // Back from the agents: to the conversations.
    BackHandler(enabled = !onFullScreen && views.currentPage != View.Conversations.ordinal) { onView(View.Conversations) }

    Box(Modifier.fillMaxSize().background(palette.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        transition.AnimatedContent(transitionSpec = { routeMove(forward = targetState.depth > initialState.depth) }) { route ->
            val line = palette.line
            // A full screen's left edge is a hairline, so it reads crisply
            // over the tabs while it slides.
            val edge = if (route == Route.Tabs) Modifier else Modifier.drawBehind {
                drawRect(line, size = Size(1.dp.toPx(), size.height))
            }
            Box(Modifier.fillMaxSize().background(palette.bg).then(edge)) {
                val start = { agent: Agent -> go(Route.Chat(model.newConversation(agent))) }
                when (route) {
                    Route.Pair -> PairScreen(model, back = { go(Route.Tabs) }, done = {
                        onView(View.Agents)
                        go(Route.Tabs)
                    })
                    is Route.ApprovalPage -> PolicyRequestScreen(model, route.key, back = { go(Route.Tabs) }, openChat = { go(Route.Chat(it)) })
                    is Route.Chat -> ConversationScreen(model, route.id, back = { go(Route.Tabs) })
                    Route.Settings -> Column(Modifier.fillMaxSize()) {
                        AppBar { IconButton(Glyphs.back, "Back", { go(Route.Tabs) }) }
                        SettingsScreen(model, pair = { go(Route.Pair) })
                    }
                    Route.NewChat -> NewChatScreen(model, back = { go(Route.Tabs) }, opened = { go(Route.Chat(it)) })
                    is Route.AgentPage -> AgentScreen(model, route.address, back = { go(route.up) }, openChat = { go(Route.Chat(it)) }, start = start, openRoutines = { go(Route.RoutinesPage(route.address, fromAgent = true, inSandbox = route.inSandbox)) })
                    is Route.RoutinesPage -> RoutinesScreen(model, route.address, back = { go(route.up) })
                    is Route.PolicyPage -> SandboxPolicyScreen(model, route.address, back = { go(route.up) })
                    is Route.SandboxPage -> SandboxScreen(
                        model,
                        route.address,
                        back = { go(Route.Tabs) },
                        openAgent = { go(Route.AgentPage(it.address, inSandbox = true)) },
                        openPolicy = { go(Route.PolicyPage(route.address)) },
                        openChat = { go(Route.Chat(it)) },
                    )
                    Route.Tabs -> HomeShell(
                        model,
                        views,
                        onView = onView,
                        openChat = { go(Route.Chat(it)) },
                        openAgent = { go(Route.AgentPage(it.address)) },
                        openRoutines = { go(Route.RoutinesPage(it.address)) },
                        openSandbox = { go(Route.SandboxPage(it)) },
                        openApproval = { go(Route.ApprovalPage(it)) },
                        start = start,
                        settings = { go(Route.Settings) },
                        compose = { go(Route.NewChat) },
                        pair = { go(Route.Pair) },
                    )
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
