package io.github.victorbonnet.enclos

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.victorbonnet.enclos.ui.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** System grants only; no media or storage permission and no persisted URI grants. */
@Composable
internal fun FileComposer(model: AppModel, c: Conversation, composer: @Composable (@Composable () -> Unit) -> Unit) {
    val context = LocalContext.current
    val state = model.outgoing.files
    var menu by remember(c.id) { mutableStateOf(false) }
    var preparing by remember(c.id) { mutableStateOf(false) }
    var error by remember(c.id) { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable(c.id) { mutableStateOf<String?>(null) }
    val camera = cameraPath?.let(::File)
    var disposed by remember(c.id) { mutableStateOf(false) }
    DisposableEffect(c.id) { onDispose { disposed = true } }
    val busy = preparing || model.outgoing.waiting(c.id) || c.id in state.progress
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun prepare(uris: List<Uri>, photo: Boolean, temporary: File? = null) {
        if (busy || uris.isEmpty()) { temporary?.delete(); return }
        val before = state.drafts[c.id].orEmpty()
        if (before.size + uris.size > 4) { error = "Choose at most four files."; temporary?.delete(); return }
        val generation = state.generation
        preparing = true
        filePreparation.execute {
            val result = runCatching { uris.map { prepareSendFile(context, it, photo) } }
            temporary?.delete()
            main.post {
                preparing = false
                // Cache clear, deletion, or navigation must not resurrect a discarded draft.
                if (!disposed && generation == state.generation && model.conversations.any { it.id == c.id && it.host == c.host } && state.drafts[c.id].orEmpty() == before) {
                    result.onSuccess { state.drafts[c.id] = before + it }
                        .onFailure { error = it.message ?: "Could not read file." }
                }
            }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { prepare(it, true) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { prepare(it, false) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
        val file = camera
        cameraPath = null
        state.cameras.remove(c.id)
        if (accepted && file != null && file.exists()) prepare(listOf(Uri.fromFile(file)), true, file) else file?.delete()
    }
    Column {
        composer {
            if (model.offersFiles(c)) TextButton(
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Attach files" },
                enabled = !busy && state.drafts[c.id].orEmpty().size < 4,
                onClick = { menu = !menu },
            ) { Text("+", style = Type.message, color = palette.accent) }
        }
        if (menu) {
            TextButton(enabled = !busy, onClick = {
                menu = false
                val dir = File(context.cacheDir, "outgoing-camera").apply { mkdirs() }
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                state.cameras.remove(c.id)?.delete()
                state.cameras[c.id] = file
                cameraPath = file.path
                runCatching { take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                    .onFailure { file.delete(); state.cameras.remove(c.id); cameraPath = null; error = "Camera unavailable." }
            }) { Text("Camera") }
            TextButton(enabled = !busy, onClick = { menu = false; runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { error = "Photos unavailable." } }) { Text("Photos") }
            TextButton(enabled = !busy, onClick = { menu = false; runCatching { documents.launch(arrayOf("*/*")) }.onFailure { error = "Files unavailable." } }) { Text("Files") }
        }
        state.drafts[c.id].orEmpty().forEachIndexed { index, file ->
            AssistChip(enabled = !busy, onClick = { state.remove(c.id, index) }, label = { Text("${file.name} · Remove") })
        }
        if (busy) {
            val percent = state.progress[c.id]
            if (percent != null) LinearProgressIndicator(progress = { percent / 100f })
            Text(when { preparing -> "Preparing files…"; percent != null -> "Sending files: $percent%"; else -> "Waiting for receipt…" }, style = Type.small, color = palette.textMuted)
        }
        error?.let { Text(it) }
    }
}

private val filePreparation = Executors.newSingleThreadExecutor()

internal fun prepareSendFile(context: android.content.Context, uri: Uri, photo: Boolean): SendFile {
    val resolver = context.contentResolver
    val selectedMime = resolver.getType(uri)
    if (photo || selectedMime?.startsWith("image/") == true) {
        // Decode pixels and write a fresh JPEG: no EXIF or other source metadata survives,
        // and HEIC is converted by the platform decoder. Bound decoded memory before allocation.
        val source = if (uri.scheme == "file") ImageDecoder.createSource(File(requireNotNull(uri.path))) else ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bytes = try { BoundedPhotoOutput().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
            out.toByteArray()
        } } finally { bitmap.recycle() }
        require(bytes.size in 1..10_000_000) { "Each file must be 1–10,000,000 bytes." }
        return SendFile("photo.jpg", "image/jpeg", bytes)
    }
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: error("File has no name.")
    val mime = resolver.getType(uri) ?: error("File has no type.")
    require(validSendFile(name, mime, 1)) { "Unsupported file name or type." }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32_768)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= 10_000_000) { "Each file must be 1–10,000,000 bytes." }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    } ?: error("Could not open file.")
    require(validSendFile(name, mime, bytes.size)) { "Each file must be 1–10,000,000 bytes." }
    return SendFile(name, mime, bytes)
}

/** Reject before a growing encoded buffer can exceed the protocol cap. */
private class BoundedPhotoOutput : ByteArrayOutputStream() {
    override fun write(b: ByteArray, off: Int, len: Int) {
        require(count.toLong() + len <= 10_000_000) { "Photo is too large." }
        super.write(b, off, len)
    }
    override fun write(b: Int) {
        require(count < 10_000_000) { "Photo is too large." }
        super.write(b)
    }
}
