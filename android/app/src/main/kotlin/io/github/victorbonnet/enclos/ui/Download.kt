package io.github.victorbonnet.enclos.ui

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.victorbonnet.enclos.downloadNotice
import io.github.victorbonnet.enclos.saveToDownloads
import io.github.victorbonnet.enclos.showDownloadNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * A second tap while saving does nothing; leaving composition cancels the copy,
 * discards the pending row, and shows the cancelled notice once.
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

    val label: String
        get() = if (saved) "Download again" else "Download"

    val enabled: Boolean
        get() = Downloads.offered() && !saving

    val actionLabel: String
        get() = "$label ${attachment.shownName}"

    // A notice is always shown from the main thread, whichever thread the copy ended on.
    private suspend fun notify(text: String) =
        withContext(Dispatchers.Main.immediate) { showDownloadNotice(context, text) }

    fun start(file: File) {
        if (!Downloads.offered() || saving) return
        saving = true
        scope.launch {
            try {
                val outcome = saveToDownloads(context, attachment, file, store)
                if (outcome == DownloadOutcome.Saved) saved = true
                downloadNotice(outcome)?.let { notify(it) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    downloadNotice(DownloadOutcome.Cancelled)?.let { notify(it) }
                }
                throw e
            } finally {
                saving = false
            }
        }
    }
}

@Composable
fun rememberDownload(attachment: Attachment): DownloadController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = LocalDownloadStore.current
    return remember(attachment.id, context, scope, store) {
        DownloadController(attachment, context, scope, store)
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
