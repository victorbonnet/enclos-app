package io.github.victorbonnet.enclos

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.victorbonnet.enclos.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun FileComposer(model: AppModel, c: Conversation, composer: @Composable (@Composable () -> Unit, Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val state = model.outgoing.files
    val preparing = c.id in state.preparing

    var cameraPath by rememberSaveable(c.id) { mutableStateOf<String?>(null) }
    val camera = cameraPath?.let(::File)
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun prepare(uris: List<Uri>, photo: Boolean, temporary: File? = null) {
        if (c.id in state.preparing || model.outgoing.waiting(c.id) || c.id in state.progress || uris.isEmpty()) { temporary?.delete(); return }
        val before = state.drafts[c.id].orEmpty()
        if (before.size + uris.size > 4) { state.errors[c.id] = "Choose at most four files."; temporary?.delete(); return }
        val generation = state.generation
        val token = Any()
        state.preparing[c.id] = token
        state.errors.remove(c.id)
        filePreparation.execute {
            val result = runCatching { uris.map { prepareSendFile(context, it, photo) } }
            temporary?.delete()
            main.post {
                if (state.preparing[c.id] !== token) return@post
                state.preparing.remove(c.id)
                // ViewModel ownership survives rotation; invalidation cannot resurrect discarded drafts.
                if (generation == state.generation && model.conversations.any { it.id == c.id && it.host == c.host } && state.drafts[c.id].orEmpty() == before) {
                    result.onSuccess { state.drafts[c.id] = before + it }
                        .onFailure { state.errors[c.id] = it.message ?: "Could not read file." }
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
        if (accepted && file != null && file.isFile && file.length() > 0) {
            prepare(listOf(Uri.fromFile(file)), true, file)
        } else {
            file?.delete()
            if (accepted) state.errors[c.id] = "Camera photo is no longer available. Take it again."
        }
    }
    FileComposerControls(
        names = state.drafts[c.id].orEmpty().map { it.name },
        offersFiles = model.offersFiles(c), preparing = preparing,
        waiting = model.outgoing.waiting(c.id), progress = state.progress[c.id],
        error = state.errors[c.id], onRemove = { state.remove(c.id, it) },
        onCamera = {
            val dir = File(context.cacheDir, "outgoing-camera").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            state.cameras.remove(c.id)?.delete()
            state.cameras[c.id] = file
            cameraPath = file.path
            runCatching { take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                .onFailure { file.delete(); state.cameras.remove(c.id); cameraPath = null; state.errors[c.id] = "Camera unavailable." }
        },
        onPhotos = { runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { state.errors[c.id] = "Photos unavailable." } },
        onFiles = { runCatching { documents.launch(arrayOf("*/*")) }.onFailure { state.errors[c.id] = "Files unavailable." } },
    ) { attach, enabled -> composer(attach, enabled) }

}

/** Presentation shared by chat and isolated fixtures; no model or transport required. */
@Composable
internal fun FileComposerControls(
    names: List<String>,
    offersFiles: Boolean = true,
    online: Boolean = true,
    preparing: Boolean = false,
    waiting: Boolean = false,
    progress: Int? = null,
    error: String? = null,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onRemove: (Int) -> Unit,
    composer: @Composable (@Composable () -> Unit, Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val busy = preparing || waiting || progress != null
    Column(Modifier.fillMaxWidth().background(palette.bg)) {
        composer({
            if (offersFiles) TextButton(
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Attach files" },
                enabled = !busy && names.size < 4,
                onClick = { menu = !menu },
            ) { Text("+", style = Type.message, color = palette.accent) }
        }, online && !busy)
        // A bounded, scrollable area keeps all actions reachable with large system text.
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            if (menu && offersFiles) {
                listOf("Camera" to onCamera, "Photos" to onPhotos, "Files" to onFiles).forEach { (label, action) ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !busy && names.size < 4,
                        onClick = { menu = false; action() },
                    ) { Text(label, style = Type.message, color = palette.accent) }
                }
            }
            names.forEachIndexed { index, name ->
                TextButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !busy, onClick = { onRemove(index) },
                ) { Text("$name · Remove", style = Type.small, color = palette.text) }
            }
            if (busy) {
                if (progress != null) LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth(),
                )
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when {
                    preparing -> "Preparing files…"
                    progress != null -> "Sending files: $progress%"
                    else -> "Waiting for receipt…"
                }, style = Type.small, color = palette.textMuted)
            }
            error?.let { Text(it, style = Type.small, color = palette.text) }
        }
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
