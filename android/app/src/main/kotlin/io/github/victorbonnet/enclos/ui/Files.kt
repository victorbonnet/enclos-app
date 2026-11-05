package io.github.victorbonnet.enclos.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.github.victorbonnet.enclos.Attachment
import io.github.victorbonnet.enclos.FileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A message's files: an image drawn in the bubble, which opens full screen
 * to share; another file as a line with its name and size, which shares it.
 * Each is fetched from the bridge when it is first shown.
 */
@Composable
fun Files(attachments: List<Attachment>, states: Map<String, FileState>, fetch: (Attachment) -> Unit) {
    var opened by remember { mutableStateOf<Pair<Attachment, File>?>(null) }
    opened?.let { (a, file) -> Viewer(a, file) { opened = null } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { a ->
            val state = states[a.id]
            LaunchedEffect(a.id) { if (state == null) fetch(a) }
            if (a.image) {
                ImageFile(a, state, { fetch(a) }) { opened = a to it }
            } else {
                OtherFile(a, state, { fetch(a) })
            }
        }
    }
}

@Composable
private fun ImageFile(a: Attachment, state: FileState?, retry: () -> Unit, open: (File) -> Unit) {
    val p = palette
    val shape = Shapes.control
    when (state) {
        is FileState.Ready -> {
            val bitmap by produceState<Bitmap?>(null, state.file) { value = decode(state.file, 1080) }
            val image = bitmap
            if (image == null) {
                Placeholder("Opening the image")
            } else {
                Image(
                    image.asImageBitmap(),
                    contentDescription = a.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1))
                        .clip(shape).clickable(onClickLabel = "Open the image", role = Role.Image) { open(state.file) },
                )
            }
        }
        is FileState.Failed -> Placeholder("The image did not come: ${state.why}. Tap to try again.", retry)
        is FileState.Loading -> Placeholder("Loading the image · ${percent(state.done, state.size)}")
        null -> Placeholder("Loading the image")
    }
}

@Composable
private fun Placeholder(text: String, onClick: (() -> Unit)? = null) {
    val p = palette
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(Shapes.control).background(p.bg)
            .border(1.dp, p.line, Shapes.control)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.meta, color = p.textFaint) }
}

/** A file that is not an image: its name and size; tap to share it once it is here. */
@Composable
private fun OtherFile(a: Attachment, state: FileState?, retry: () -> Unit) {
    val p = palette
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(Shapes.control).border(1.dp, p.line, Shapes.control)
            .clickable(role = Role.Button) {
                when (state) {
                    is FileState.Ready -> share(context, a, state.file)
                    is FileState.Failed -> retry()
                    else -> {}
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(Glyphs.compose, p.accent, size = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(a.name, style = Type.small, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (state) {
                    is FileState.Loading -> "${size(a.size)} · ${percent(state.done, state.size)}"
                    is FileState.Failed -> "Did not come: ${state.why}. Tap to try again."
                    is FileState.Ready -> "${size(a.size)} · tap to share"
                    null -> size(a.size)
                },
                style = Type.meta,
                color = p.textFaint,
            )
        }
    }
}

/** An image, full screen on `panel-deep`, with Back and Share. */
@Composable
private fun Viewer(a: Attachment, file: File, close: () -> Unit) {
    val p = palette
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, file) { value = decode(file, 2400) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.panelDeep).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Glyphs.back, "Back", close, tint = p.panelText)
                Text(a.name, style = Type.small, color = p.panelMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(
                    Modifier.height(44.dp).clip(Shapes.control).clickable(onClickLabel = "Share the image", role = Role.Button) { share(context, a, file) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Share", style = Type.label, color = p.lime) }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = a.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Hands the file to another app through the system's share sheet, readable by that app alone. */
fun share(context: Context, a: Attachment, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(a.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(a.name, uri)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

/** The image at [file], decoded at about [longest] pixels on its longest side, off the main thread. */
private suspend fun decode(file: File, longest: Int): Bitmap? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun percent(done: Long, size: Long) = if (size > 0) "${(done * 100 / size).coerceIn(0, 100)} %" else "…"

/** `1.9 MB`, `320 KB`. */
fun size(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
