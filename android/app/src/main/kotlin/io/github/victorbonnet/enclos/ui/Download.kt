package io.github.victorbonnet.enclos.ui

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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

    private var waiting: Job? = null

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

    private fun busyElsewhere(): Boolean = (active[key] ?: 0) > 0

    val actionLabel: String
        get() = "$label ${attachment.shownName}"

    // A notice is always shown from the main thread, whichever thread the copy ended on.
    private suspend fun notify(text: String) =
        withContext(Dispatchers.Main.immediate) { showDownloadNotice(context, text) }

    fun start(file: File) {
        if (!Downloads.offered() || saving || busyElsewhere()) return
        saving = true
        active[key] = (active[key] ?: 0) + 1
        scope.launch {
            // A cancel that lands once the file is published still comes back as Saved.
            val outcome = try {
                saveToDownloads(context, attachment, file, store)
            } catch (_: CancellationException) {
                DownloadOutcome.Cancelled
            }
            // The notice and the state are not cancellable: a closed viewer still hears how it ended.
            withContext(NonCancellable) {
                if (outcome == DownloadOutcome.Saved) saved = true
                downloadNotice(outcome)?.let { notify(it) }
                saving = false
                val left = (active[key] ?: 1) - 1
                if (left > 0) active[key] = left else active.remove(key)
                if (idle) onIdle?.invoke()
            }
        }
    }

    /**
     * Download, tapped on a file that is not on the phone (not fetched, failed, or
     * cleared from the cache): asks [fetch] for it, then saves it once it comes, or
     * says why it did not. [stateOf] reads the file's state live, since the card
     * that was tapped may be off screen by then. A new tap replaces an earlier wait;
     * a state that is already there when the tap lands (the stale one the fetch
     * replaces) is never taken for the answer.
     */
    fun startWhenFetched(stateOf: () -> FileState?, fetch: () -> Unit) {
        if (!Downloads.offered() || saving || busyElsewhere()) return
        waiting?.cancel()
        val before = stateOf()
        fetch()
        waiting = scope.launch {
            // Each write to the state is its own answer, even an equal one: compared by identity.
            val answer = snapshotFlow { Seen(stateOf()) }
                .map { it.state }
                .first { now -> now !== before && (now is FileState.Ready || now is FileState.Failed) }
            waiting = null
            when (answer) {
                is FileState.Ready -> start(answer.file)
                is FileState.Failed -> notify(downloadFetchFailureNotice(answer.why))
                else -> {}
            }
            if (idle) onIdle?.invoke()
        }
    }

    private class Seen(val state: FileState?)

    private data class SaveKey(val id: String, val store: DownloadStore?)

    private companion object {
        val active = mutableStateMapOf<SaveKey, Int>()
    }
}

/**
 * The saves and the waits for a fetch that outlive the card they started from, one
 * [DownloadController] per attachment, run in the app's own scope. Without it a
 * download stops when its card scrolls out of the chat. Used from the main thread.
 *
 * A visit lasts while any file card is composed: when the last one leaves, idle
 * controllers are dropped (so "Download again" does not outlive the visit); one still
 * saving or waiting for a fetch goes on, and is dropped once it ends.
 */
internal object DownloadHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Key(val id: String, val context: Context, val store: DownloadStore?)
    private val controllers = HashMap<Key, DownloadController>()
    private var cards = 0

    fun controller(attachment: Attachment, context: Context, store: DownloadStore?): DownloadController {
        val app = context.applicationContext
        val key = Key(attachment.id, app, store)
        return controllers.getOrPut(key) {
            DownloadController(attachment, app, scope, store).also { c -> c.onIdle = { dropIfOver(key, c) } }
        }
    }

    fun cardEntered() { cards++ }

    fun cardLeft() {
        cards = (cards - 1).coerceAtLeast(0)
        if (cards == 0) {
            controllers.values.removeAll { it.idle }
        }
    }

    private fun dropIfOver(key: Key, c: DownloadController) {
        if (cards == 0 && controllers[key] === c) controllers.remove(key)
    }
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
    DisposableEffect(Unit) {
        DownloadHub.cardEntered()
        onDispose { DownloadHub.cardLeft() }
    }
    return remember(attachment.id, context, store) { DownloadHub.controller(attachment, context, store) }
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
