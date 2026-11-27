package io.github.victorbonnet.enclos.ui

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.Attachment
import io.github.victorbonnet.enclos.DownloadOutcome
import io.github.victorbonnet.enclos.DownloadStore
import io.github.victorbonnet.enclos.Downloads
import io.github.victorbonnet.enclos.FileState
import io.github.victorbonnet.enclos.downloadFetchFailureNotice
import io.github.victorbonnet.enclos.downloadNotice
import io.github.victorbonnet.enclos.saveToDownloads
import io.github.victorbonnet.enclos.showDownloadNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Optional fake [DownloadStore] for Compose unit tests; null uses the real MediaStore. */
val LocalDownloadStore = staticCompositionLocalOf<DownloadStore?> { null }

/**
 * Whether the Download controls are drawn: the platform predicate by default
 * ([Downloads.offered], Android 10 and later). A test may override it to check
 * an Android 9 screen without that platform's runtime.
 */
val LocalDownloadsOffered = staticCompositionLocalOf { Downloads.offered() }

/**
 * Shared state for saving one [attachment] to Downloads from a viewer or file card.
 * A second tap while saving does nothing. The copy runs in [scope]: a viewer's own
 * (so closing the viewer cancels the copy, discards the pending row and shows the
 * cancelled notice once), or, for a file card in a scrolling list, the app's
 * ([DownloadHub]), which no scroll can end.
 */
@Stable
class DownloadController(
    private val attachment: Attachment,
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: DownloadStore?,
) {
    var saving by mutableStateOf(false)
        private set

    var saved by mutableStateOf(false)
        private set

    private var waiting: Wait? = null

    // The saves in flight, per file, across every controller of it: a card's save in flight
    // keeps the viewer's Download disabled, and the other way round.
    private val key = SaveKey(attachment.id, store)

    /** Called once this controller has neither a save running nor a wait for a fetch. */
    internal var onIdle: (() -> Unit)? = null

    internal val idle: Boolean
        get() = !saving && waiting == null

    val label: String
        get() = if (saved) "Download again" else "Download"

    val enabled: Boolean
        get() = Downloads.offered() && !saving && !busyElsewhere()

    private fun busyElsewhere(): Boolean = active.containsKey(key)

    val actionLabel: String
        get() = "$label ${attachment.shownName}"

    @MainThread
    fun start(file: File) {
        if (!enabled) return
        val previous = waiting
        waiting = null
        previous?.job?.cancel()
        if (!tryStart(file) && previous != null && idle) onIdle?.invoke()
    }

    @MainThread
    @OptIn(DelicateCoroutinesApi::class)
    private fun tryStart(file: File): Boolean {
        if (!enabled) return false
        val reservation = Any()
        active[key] = reservation
        saving = true

        fun release() {
            // Either the coroutine or the launch failure owns cleanup for this save.
            if (active[key] !== reservation) return
            active.remove(key)
            saving = false
            if (idle) onIdle?.invoke()
        }

        try {
            // Enter the finally block even if the scope is cancelled before dispatch.
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    // A cancel that lands once the file is published still comes back as Saved.
                    val outcome = try {
                        ensureActive()
                        saveToDownloads(context, attachment, file, store)
                    } catch (_: CancellationException) {
                        DownloadOutcome.Cancelled
                    } catch (_: Exception) {
                        DownloadOutcome.Failed
                    }
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (outcome == DownloadOutcome.Saved) saved = true
                        downloadNotice(outcome)?.let { showDownloadNotice(context, it) }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { release() }
                }
            }
        } catch (failure: Throwable) {
            // A synchronous launch failure leaves cleanup with the main-thread caller.
            release()
            throw failure
        }
        return true
    }

    /**
     * Download, tapped on a file that is not on the phone (not fetched, failed, or
     * cleared from the cache): asks [fetch] for it, then saves it once it comes, or
     * says why it did not. [stateOf] reads the file's state live, since the card
     * that was tapped may be off screen by then. A new tap replaces an earlier wait;
     * a state that is already there when the tap lands (the stale one the fetch
     * replaces) is never taken for the answer.
     */
    @MainThread
    @OptIn(DelicateCoroutinesApi::class)
    fun startWhenFetched(stateOf: () -> FileState?, fetch: () -> Unit) {
        if (!Downloads.offered() || saving || busyElsewhere()) return
        val previous = waiting
        waiting = null
        previous?.job?.cancel()
        val before = stateOf()
        try {
            fetch()
        } catch (failure: Throwable) {
            if (previous != null && idle) onIdle?.invoke()
            throw failure
        }
        val token = Wait()
        waiting = token
        try {
            val job = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    ensureActive()
                    // Each write to the state is its own answer, even an equal one: compared by identity.
                    val answer = snapshotFlow { Seen(stateOf()) }
                        .map { it.state }
                        .first { now -> now !== before && (now is FileState.Ready || now is FileState.Failed) }
                    withContext(Dispatchers.Main.immediate) {
                        if (waiting !== token) return@withContext
                        waiting = null
                        when (answer) {
                            is FileState.Ready -> if (!tryStart(answer.file) && idle) onIdle?.invoke()
                            is FileState.Failed -> try {
                                showDownloadNotice(context, downloadFetchFailureNotice(answer.why))
                            } finally {
                                if (idle) onIdle?.invoke()
                            }
                            else -> if (idle) onIdle?.invoke()
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (waiting === token) {
                            waiting = null
                            if (idle) onIdle?.invoke()
                        }
                    }
                }
            }
            if (waiting === token) token.job = job else job.cancel()
        } catch (failure: Throwable) {
            if (waiting === token) {
                waiting = null
                if (idle) onIdle?.invoke()
            }
            throw failure
        }
    }

    /** Cancels an in-flight fetch wait without showing a toast, releasing idle state once. */
    @MainThread
    internal fun cancelWait() {
        val current = waiting ?: return
        waiting = null
        current.job?.cancel()
        if (idle) onIdle?.invoke()
    }

    private class Wait(var job: Job? = null)

    private class Seen(val state: FileState?)

    private data class SaveKey(val id: String, val store: DownloadStore?)

    private companion object {
        val active = mutableStateMapOf<SaveKey, Any>()
    }
}

/**
 * One conversation visit's card download controllers, keyed per attachment.
 *
 * A visit lives for the duration of a conversation screen, not for individual
 * lazy-list cards: scrolling a card off and back on keeps its "Download again"
 * label, and scrolling away never cancels an active save or fetch wait.
 *
 * When the visit ends:
 * - idle controllers are released immediately;
 * - abandoned fetch waits are cancelled and released;
 * - active saves keep running safely in [scope] and release once idle.
 *
 * Concurrent visits each have their own [DownloadVisit] so they never conflate
 * per-visit state or cleanup, while still sharing [DownloadController]'s
 * process-wide active-save lock.
 */
internal class DownloadVisit(
    private val scope: CoroutineScope = DownloadHub.scope,
) {
    private data class Key(val id: String, val context: Context, val store: DownloadStore?)

    private val controllers = HashMap<Key, DownloadController>()
    private var ended = false

    internal val size: Int
        get() = controllers.size

    @MainThread
    fun controller(attachment: Attachment, context: Context, store: DownloadStore?): DownloadController {
        val app = context.applicationContext
        if (ended) return DownloadController(attachment, app, scope, store)
        val key = Key(attachment.id, app, store)
        return controllers.getOrPut(key) {
            DownloadController(attachment, app, scope, store).also { c ->
                c.onIdle = { dropIfEnded(key, c) }
            }
        }
    }

    @MainThread
    fun end() {
        if (ended) return
        ended = true
        for (c in controllers.values.toList()) {
            c.cancelWait()
        }
        controllers.values.removeAll { it.idle }
    }

    private fun dropIfEnded(key: Key, c: DownloadController) {
        if (ended && controllers[key] === c) controllers.remove(key)
    }
}

/**
 * App-wide scope for card saves and fetch waits that outlive individual lazy-list cards.
 */
internal object DownloadHub {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Fallback visit for standalone file cards composed outside a conversation screen. */
    internal val fallback = DownloadVisit(scope)
}

internal val LocalDownloadVisit = staticCompositionLocalOf<DownloadVisit?> { null }

/** Scopes card download state ("Download again" and fetch waits) to one conversation visit. */
@Composable
internal fun ProvideDownloadVisit(key: Any = Unit, content: @Composable () -> Unit) {
    val visit = remember(key) { DownloadVisit() }
    DisposableEffect(visit) {
        onDispose { visit.end() }
    }
    CompositionLocalProvider(LocalDownloadVisit provides visit, content = content)
}

/** For a viewer: a copy that ends with the viewer. */
@Composable
fun rememberDownload(attachment: Attachment): DownloadController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = LocalDownloadStore.current
    return remember(attachment.id, context, scope, store) {
        DownloadController(attachment, context, scope, store)
    }
}

/** For a file card in a list: a copy that scrolling the card away does not end. */
@Composable
internal fun rememberCardDownload(attachment: Attachment): DownloadController {
    val context = LocalContext.current
    val store = LocalDownloadStore.current
    val visit = LocalDownloadVisit.current ?: DownloadHub.fallback
    return remember(visit, attachment.id, context, store) {
        visit.controller(attachment, context, store)
    }
}

/** Shared bottom-row Download button for document and HTML viewers. */
@Composable
internal fun DownloadActionButton(download: DownloadController, enabled: Boolean, onClick: () -> Unit) {
    ActionButton(
        text = download.label,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = download.actionLabel },
        enabled = enabled && download.enabled,
        onClick = onClick,
    )
}
