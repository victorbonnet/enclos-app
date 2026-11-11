package io.github.victorbonnet.enclos.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import io.github.victorbonnet.enclos.Attachment
import io.github.victorbonnet.enclos.Attachments
import io.github.victorbonnet.enclos.FileState
import io.github.victorbonnet.enclos.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A message's files: an image drawn in the bubble, which opens full screen
 * to share; another file as a line with its name and size, which shares it.
 * Each is fetched from the bridge when it is first shown, or when tapped
 * once larger than [Attachments.FETCH_ON_TAP].
 */
@Composable
fun Files(attachments: List<Attachment>, states: Map<String, FileState>, fetch: (Attachment) -> Unit) {
    var opened by remember { mutableStateOf<Pair<Attachment, File>?>(null) }
    opened?.let { (a, file) -> Viewer(a, file) { opened = null } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { a ->
            val state = states[a.id]
            // Fetched when first shown, and again when the system cleared
            // the cache since it was here: each time the message is shown.
            // A large one waits for a tap, so an agent cannot make the
            // phone pull megabytes over mobile data unasked.
            LaunchedEffect(a.id, state) {
                val gone = state is FileState.Ready && !withContext(Dispatchers.IO) { state.file.isFile }
                if (a.size <= Attachments.FETCH_ON_TAP && (state == null || gone)) fetch(a)
            }
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
            val decoded by produceState<Decoded>(Decoded.Pending, state.file) {
                value = decode(state.file, 1080)?.let { Decoded.Drawn(it) } ?: Decoded.Unreadable
            }
            when (val d = decoded) {
                Decoded.Pending -> Placeholder("Opening the image")
                // Not an image Android can draw (an SVG chart, a damaged file),
                // or cleared since: a file like any other, to share or fetch again.
                Decoded.Unreadable -> OtherFile(a, state, retry)
                is Decoded.Drawn -> Image(
                    d.bitmap.asImageBitmap(),
                    contentDescription = a.name,
                    contentScale = ContentScale.Fit,
                    // Its own shape within 1:3 and 3:1: a thin, tall image is
                    // drawn inside that box, never taller than three widths.
                    modifier = Modifier.fillMaxWidth().aspectRatio(Images.ratio(d.bitmap.width, d.bitmap.height))
                        .clip(shape).clickable(onClickLabel = "Open the image", role = Role.Image) { open(state.file) },
                )
            }
        }
        is FileState.Failed -> Placeholder("The image did not come: ${state.why}. Tap to try again.", retry)
        is FileState.Loading -> Placeholder("Loading the image · ${percent(state.done, state.size)}")
        null -> if (a.size > Attachments.FETCH_ON_TAP) Placeholder("Image · ${size(a.size)} · tap to load it", retry) else Placeholder("Loading the image")
    }
}

/** An image's bitmap for the screen: being decoded, drawn, or not one Android can draw. */
private sealed interface Decoded {
    data object Pending : Decoded
    data object Unreadable : Decoded
    data class Drawn(val bitmap: Bitmap) : Decoded
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
                    // Cleared by the system since: fetched again, not shared missing.
                    is FileState.Ready -> if (state.file.isFile) share(context, a, state.file) else retry()
                    is FileState.Failed -> retry()
                    // A large file, not fetched until asked.
                    null -> retry()
                    is FileState.Loading -> {}
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
                    null -> if (a.size > Attachments.FETCH_ON_TAP) "${size(a.size)} · tap to load it" else size(a.size)
                },
                style = Type.meta,
                color = p.textFaint,
            )
        }
    }
}

/** The viewer's ground: near black in both themes, as photo viewers have it, so the image is the only colour. */
private val ViewerGround = Color(0xFF0B0C0B)
private val ViewerText = Color(0xFFF2F2EF)

/**
 * An image, full screen on near black, edge to edge: Back, its name and
 * Share float over it on a shade, and a tap on the image hides or shows
 * them. Pinch to zoom (up to 6×) around the fingers, drag to move while
 * zoomed, double-tap to zoom on a point or back to the whole image.
 */
@Composable
private fun Viewer(a: Attachment, file: File, close: () -> Unit) {
    val context = LocalContext.current
    // Decoded larger than in the bubble, to stay sharp when zoomed.
    val bitmap by produceState<Bitmap?>(null, file) { value = decode(file, 4096) }
    var chrome by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog's own window: light status bar icons over the dark ground.
        val view = LocalView.current
        LaunchedEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
        Box(Modifier.fillMaxSize().background(ViewerGround)) {
            bitmap?.let { Zoomable(it, a.name) { chrome = !chrome } }
            AnimatedVisibility(
                chrome,
                enter = fadeIn(Motion.enter(Motion.SHORT)),
                exit = fadeOut(Motion.exit()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .height(64.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(Glyphs.back, "Back", close, tint = ViewerText)
                    Text(a.name, style = Type.small, color = ViewerText.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.height(44.dp).clip(Shapes.control).clickable(onClickLabel = "Share the image", role = Role.Button) { share(context, a, file) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Share", style = Type.label, color = ViewerText) }
                }
            }
        }
    }
}

/**
 * [bitmap] fitted to the screen, zoomable: pinch between 1× and 6×, keeping
 * the point between the fingers where it is; drag while zoomed, the image's
 * edges held at the screen's; double-tap zooms 2.5× on that point, or back.
 */
@Composable
private fun Zoomable(bitmap: Bitmap, description: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val center = { Offset(box.width / 2f, box.height / 2f) }
    // How far the image may move at scale s: by how much it overflows the screen, halved.
    val clamp = { o: Offset, s: Float ->
        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val (w, h) = if (box.height == 0 || box.width.toFloat() / box.height > ratio) {
            box.height * ratio to box.height.toFloat()
        } else {
            box.width.toFloat() to box.width / ratio
        }
        val x = ((w * s - box.width) / 2).coerceAtLeast(0f)
        val y = ((h * s - box.height) / 2).coerceAtLeast(0f)
        Offset(o.x.coerceIn(-x, x), o.y.coerceIn(-y, y))
    }
    val animateTo = { s: Float, o: Offset ->
        val s0 = scale
        val o0 = offset
        scope.launch {
            animate(0f, 1f, animationSpec = Motion.change(Motion.MEDIUM)) { v, _ ->
                scale = s0 + (s - s0) * v
                offset = o0 + (o - o0) * v
            }
        }
        Unit
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        if (scale > 1.01f) {
                            animateTo(1f, Offset.Zero)
                        } else {
                            val s = 2.5f
                            animateTo(s, clamp((center() - at) * (s - 1), s))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 6f)
                    // The point under the fingers stays under them.
                    val from = centroid - center()
                    offset = clamp(from - (from - offset) * (s / scale) + pan, s)
                    scale = s
                }
            },
    ) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
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

/**
 * The image at [file], off the main thread: at most [longest] pixels on its
 * longest side and [Images.MOST_BYTES] in memory, whatever the file says
 * ([Images.sampleSize]); null when Android cannot decode it, or it would
 * still be too large to draw.
 */
private suspend fun decode(file: File, longest: Int): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val sample = Images.sampleSize(bounds.outWidth, bounds.outHeight, longest)
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap != null && !Images.drawable(bitmap.allocationByteCount.toLong())) {
            bitmap.recycle()
            null
        } else {
            bitmap
        }
    } catch (e: OutOfMemoryError) {
        null
    }
}

private fun percent(done: Long, size: Long) = if (size > 0) "${(done * 100 / size).coerceIn(0, 100)} %" else "…"

/** `1.9 MB`, `320 KB`. */
fun size(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
