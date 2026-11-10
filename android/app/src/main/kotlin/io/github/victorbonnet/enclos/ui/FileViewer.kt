package io.github.victorbonnet.enclos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.victorbonnet.enclos.Attachment
import io.github.victorbonnet.enclos.TextDocument
import io.github.victorbonnet.enclos.openAttachment
import io.github.victorbonnet.enclos.shareAttachment
import io.github.victorbonnet.enclos.viewIntentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.CharacterCodingException

/**
 * A file opens before either exporting it or handing it to another app.
 * Open in another app is offered only for a type another app may safely
 * be asked to open ([viewIntentType]); any other file is offered to Share.
 */
@Composable
fun FileViewer(attachment: Attachment, file: File, close: () -> Unit) {
    val context = LocalContext.current
    val p = palette
    val exists by produceState<Boolean?>(null, file) { value = withContext(Dispatchers.IO) { file.isFile } }
    val viewable = viewIntentType(attachment.mediaType, file.name) != null
    val elsewhere = if (viewable) "You can still open it in another app or share it." else "You can still share it."
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBars = p.bg.luminance() > 0.5f
        LaunchedEffect(view, lightBars) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightBars
            }
        }
        Column(Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Glyphs.back, "Back", close)
                Text(attachment.shownName, style = Type.small, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(
                    Modifier.height(48.dp).clickable(onClickLabel = "Share the file", role = Role.Button) { shareAttachment(context, attachment, file) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Share", style = Type.label, color = p.accent) }
            }
            Hairline()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (exists) {
                    null -> FileNotice("Opening the file…")
                    false -> FileNotice("The cached file was cleared. Close the viewer and tap the file to fetch it again.")
                    true -> when {
                        attachment.pdf -> PdfPreview(file, elsewhere, Modifier.fillMaxSize())
                        attachment.text -> TextPreview(file, elsewhere)
                        else -> FileNotice("An in-app preview is not available for this file type. $elsewhere")
                    }
                }
            }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(size(attachment.size), style = Type.meta, color = p.textFaint, modifier = Modifier.weight(1f))
                if (viewable) {
                    // At least 48dp tall: the shared button's 44dp gives way to this minimum.
                    ActionButton("Open in another app", Modifier.heightIn(min = 48.dp), enabled = exists == true) { openAttachment(context, attachment, file) }
                }
            }
        }
    }
}

/** Literal text in blocks of a lazy list, so a large file is laid out only where it is seen. */
@Composable
private fun TextPreview(file: File, elsewhere: String) {
    val loaded by produceState<Result<List<String>>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { TextDocument.blocks(TextDocument.read(file)) } }
    }
    val result = loaded
    when {
        result == null -> FileNotice("Opening the text…")
        result.isFailure -> FileNotice(when (val error = result.exceptionOrNull()) {
            is CharacterCodingException -> "This text is not UTF-8. $elsewhere"
            is IllegalArgumentException -> "${error.message ?: "This text cannot be previewed."} $elsewhere"
            else -> "This file cannot be read. Close the viewer and try fetching it again."
        })
        else -> SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                // A block ending a line drops that break: the next block starts on a new line.
                items(result.getOrThrow()) { block ->
                    Text(block.removeSuffix("\n"), style = Type.code, color = palette.text)
                }
            }
        }
    }
}

@Composable
private fun FileNotice(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.body, color = palette.textMuted)
    }
}
