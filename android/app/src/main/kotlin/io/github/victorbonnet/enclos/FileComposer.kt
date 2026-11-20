package io.github.victorbonnet.enclos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.victorbonnet.enclos.ui.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

internal enum class FileSource { Camera, Photos, Files }

/** The private folder a camera writes into; a restored path is used only if it is one of its files. */
internal fun cameraDir(context: Context) = File(context.cacheDir, "outgoing-camera")

internal fun ownedCapture(context: Context, path: String?): File? {
    val file = path?.let(::File) ?: return null
    val dir = cameraDir(context).canonicalFile
    val real = runCatching { file.canonicalFile }.getOrNull() ?: return null
    val name = real.name.removeSuffix(".jpg")
    return real.takeIf { it.parentFile == dir && real.name.endsWith(".jpg") && runCatching { UUID.fromString(name) }.isSuccess }
}

/**
 * The attach control and draft files of [c]. System pickers only: no media
 * or storage permission, no persisted URI grant. Which conversation asked
 * for a picker's result, and the camera's file, are saved state, so a result
 * that comes back after rotation or process death goes to the conversation
 * that asked, or is refused and asked for again; never to another.
 */
@Composable
internal fun FileComposer(model: AppModel, c: Conversation, composer: @Composable (@Composable () -> Unit, Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val state = model.outgoing.files
    val key = FileKey(c.host, c.id)
    val now = state[key]
    // "host\nconversation" of the picker opened from here, until its result comes.
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val mine = "${c.host}\n${c.id}"
    fun owned(): Boolean = (asked == mine).also { asked = null }
    val remaining = (MAX_SEND_FILES - now.draft.size).coerceAtLeast(1)

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, remaining))) {
        if (owned()) model.prepareFiles(c, it, FileSource.Photos)
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (owned()) model.prepareFiles(c, listOfNotNull(it), FileSource.Photos)
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (owned()) model.prepareFiles(c, it, FileSource.Files)
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
        val file = ownedCapture(context, cameraPath)
        cameraPath = null
        if (!owned()) { file?.delete(); return@rememberLauncherForActivityResult }
        when {
            accepted && file != null && file.isFile && file.length() > 0 -> model.prepareFiles(c, listOf(Uri.fromFile(file)), FileSource.Camera, file)
            accepted -> { file?.delete(); state.error(key, "The photo was not kept. Take it again.") }
            else -> file?.delete()
        }
    }
    val phase = now.phase
    Column {
        phase.send()?.let { send ->
            FileOutcome(phase, send.text, send.files.map { it.name },
                onRetry = { model.retryFiles(c)?.let { state.error(key, it) } },
                onDiscard = { state.dismiss(key) })
        }
        FileComposerControls(
            names = now.draft.map { it.name },
            offersFiles = model.offersFiles(c),
            preparing = phase is FilePhase.Preparing,
            sending = phase is FilePhase.Sending || phase is FilePhase.AwaitingAck,
            progress = (phase as? FilePhase.Sending)?.progress,
            blocked = phase !is FilePhase.Draft,
            error = now.error, onRemove = { state.remove(key, it) },
            onCamera = {
                val dir = cameraDir(context).apply { mkdirs() }
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                ownedCapture(context, cameraPath)?.delete()
                asked = mine
                cameraPath = file.path
                runCatching { take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                    .onFailure { file.delete(); cameraPath = null; asked = null; state.error(key, "Camera unavailable.") }
            },
            onPhotos = {
                asked = mine
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                runCatching { if (remaining == 1) photo.launch(request) else photos.launch(request) }
                    .onFailure { asked = null; state.error(key, "Photos unavailable.") }
            },
            onFiles = {
                asked = mine
                runCatching { documents.launch(arrayOf("*/*")) }.onFailure { asked = null; state.error(key, "Files unavailable.") }
            },
        ) { attach, enabled -> composer(attach, enabled) }
    }
}

/** An operation's outcome. Sending and waiting show progress only; a refusal is final; unknown offers Retry. */
@Composable
internal fun FileOutcome(phase: FilePhase, text: String, names: List<String>, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val (line, retry) = when (phase) {
        is FilePhase.Failed -> "Not sent: ${phase.reason}" to false
        is FilePhase.Unknown -> "Delivery unknown. Check history before sending it again." to true
        else -> return
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text(listOf(text, names.joinToString()).filter { it.isNotBlank() }.joinToString(" · "), style = Type.small, color = palette.text)
        Text(line, style = Type.small, color = palette.text)
        Column {
            if (retry) TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onRetry) {
                Text("Retry original message", color = palette.accent)
            }
            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onDiscard) {
                Text(if (retry) "Discard" else "Dismiss", color = palette.accent)
            }
        }
    }
}

/** The attach control, its sheet and the draft's chips; no model or transport needed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileComposerControls(
    names: List<String>,
    offersFiles: Boolean = true,
    online: Boolean = true,
    preparing: Boolean = false,
    sending: Boolean = false,
    progress: Int? = null,
    blocked: Boolean = preparing || sending,
    error: String? = null,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onRemove: (Int) -> Unit,
    composer: @Composable (@Composable () -> Unit, Boolean) -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val p = palette
    val canAttach = offersFiles && online && !blocked && names.size < MAX_SEND_FILES
    Column(Modifier.fillMaxWidth().background(p.bg)) {
        composer({
            if (offersFiles) IconButton(
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Attach files" },
                enabled = canAttach,
                onClick = { menu = true },
            ) { Icon(Glyphs.plus, contentDescription = null, tint = if (canAttach) p.accent else p.textFaint) }
        }, !preparing && !sending)
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            names.forEachIndexed { index, name ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The name is only shown: removing takes its own button.
                    Text(name, Modifier.weight(1f), style = Type.small, color = p.text)
                    IconButton(
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Remove $name" },
                        enabled = !blocked, onClick = { onRemove(index) },
                    ) { Icon(Glyphs.clear, contentDescription = null, tint = p.textMuted) }
                }
            }
            if (preparing || sending) {
                if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when {
                    preparing -> "Preparing files…"
                    progress != null -> "Sending files: $progress%"
                    else -> "Waiting for receipt…"
                }, style = Type.small, color = p.textMuted)
            }
            error?.let { Text(it, style = Type.small, color = p.text) }
        }
    }
    if (menu && canAttach) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { menu = false }, sheetState = sheet, containerColor = p.bg, shape = Shapes.bubble, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                listOf("Camera" to onCamera, "Photos" to onPhotos, "Files" to onFiles).forEach { (label, action) ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
                        onClick = { menu = false; action() },
                    ) { Text(label, Modifier.fillMaxWidth(), style = Type.message, color = p.accent) }
                }
            }
        }
    }
}

internal class PreparedFiles(val files: List<SendFile>, val refused: List<String>)

internal const val GIF_FROM_PHOTOS = "a GIF from Photos or the camera keeps its source details; choose it in Files to send it as it is"

/**
 * Prepares each pick. One refused (its type, its size, a name the protocol
 * refuses, the budget) is named with why, in words of the app's own; the
 * others are kept. Their bytes are reserved in [budget] as they are read;
 * [alive] is called before each read and throws to stop (a cancelled
 * preparation), and then everything reserved here is released.
 */
internal fun prepareSendFiles(context: Context, picks: List<Uri>, source: FileSource, budget: PreparedBudget = PreparedBudget.process, alive: () -> Unit = {}): PreparedFiles {
    val files = mutableListOf<SendFile>()
    val refused = mutableListOf<String>()
    try {
        picks.forEachIndexed { index, uri ->
            alive()
            val shown = displayName(context, uri)
            try {
                files += prepareSendFile(context, uri, source, shown, index, picks.size, budget, alive)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: PrepareRefused) {
                refused += "${safeFileName(shown)} (${e.safe})"
            } catch (e: Exception) {
                refused += "${safeFileName(shown)} (could not be read)"
            }
        }
    } catch (e: Throwable) {
        files.forEach { budget.release(it.bytes.size) }
        throw e
    }
    return PreparedFiles(unique(files), refused)
}

private fun displayName(context: Context, uri: Uri): String? =
    if (uri.scheme == "file") uri.lastPathSegment
    else runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

/** Names stay those picked; a repeat gets a number before its extension. */
private fun unique(files: List<SendFile>): List<SendFile> {
    val seen = mutableSetOf<String>()
    return files.map { f ->
        var name = f.name
        var n = 2
        while (!seen.add(name.lowercase())) name = "${f.name.substringBeforeLast('.')}-${n++}.${f.name.substringAfterLast('.')}"
        if (name == f.name || !validSendFileName(name)) f else SendFile(name, f.mime, f.bytes)
    }
}

internal fun prepareSendFile(context: Context, uri: Uri, source: FileSource, shown: String?, index: Int, count: Int, budget: PreparedBudget, alive: () -> Unit = {}): SendFile {
    val resolver = context.contentResolver
    fun open(): InputStream = (if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream() else resolver.openInputStream(uri))
        ?: throw PrepareRefused("could not be opened")
    if (source == FileSource.Files) {
        // A document keeps its name and exact bytes (a PNG's transparency, a GIF's frames);
        // its type is the protocol's for its extension, whatever the provider calls it.
        val name = shown ?: throw PrepareRefused("it has no name")
        if (!validSendFileName(name)) throw PrepareRefused("its name has characters an agent does not take")
        val mime = sendFileType(name) ?: throw PrepareRefused("not a type an agent takes")
        return SendFile(name, mime, open().use { readBounded(it, budget, alive) })
    }
    // Camera and Photos: decoded and written again, so no source metadata (place, device, time) goes.
    // A PNG keeps its transparency; HEIC and the rest become JPEG.
    val type = resolver.getType(uri)?.lowercase() ?: if (source == FileSource.Camera) "image/jpeg" else null
    // A GIF's frames cannot be written again here without losing its animation, and its own
    // bytes may carry source details: refused rather than sent as it is.
    // Its first bytes decide, whatever the provider calls it.
    val gif = type == "image/gif" || open().use { s -> val h = ByteArray(4); s.read(h) == 4 && String(h, Charsets.US_ASCII) == "GIF8" }
    if (gif) throw PrepareRefused(GIF_FROM_PHOTOS)
    val base = shown?.substringBeforeLast('.')?.takeIf { validSendFileName("$it.jpg") }
        ?: if (count == 1) "photo" else "photo-${index + 1}"
    alive()
    val png = type == "image/png" || type == "image/webp"
    val decoder = if (uri.scheme == "file") ImageDecoder.createSource(File(requireNotNull(uri.path))) else ImageDecoder.createSource(resolver, uri)
    val bitmap = try {
        ImageDecoder.decodeBitmap(decoder) { d, info, _ ->
            val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
            d.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) { throw PrepareRefused("not an image the phone reads") }
    val bytes = try {
        Reserving(budget, alive).use { out ->
            check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out))
            out.keep()
        }
    } finally { bitmap.recycle() }
    return if (png) SendFile("$base.png", "image/png", bytes) else SendFile("$base.jpg", "image/jpeg", bytes)
}

/** Reads at most one file's bytes, reserving each chunk in [budget] before keeping it. */
internal fun readBounded(input: InputStream, budget: PreparedBudget, alive: () -> Unit = {}): ByteArray = Reserving(budget, alive).use { out ->
    val buffer = ByteArray(32_768)
    while (true) {
        alive()
        val count = input.read(buffer)
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    out.keep()
}

/**
 * Bytes reserved in the budget as they are written, at most one file's
 * worth; closed without [keep], it releases them all (a failed preparation).
 */
private class Reserving(private val budget: PreparedBudget, private val alive: () -> Unit = {}) : ByteArrayOutputStream() {
    private var reserved = 0
    private var kept = false
    override fun write(b: ByteArray, off: Int, len: Int) {
        alive()
        if (count.toLong() + len > MAX_SEND_FILE_BYTES) throw PrepareRefused("over 10 MB")
        if (!budget.reserve(len)) throw PrepareRefused("files waiting to send already fill the phone's room; send or remove some first")
        reserved += len
        super.write(b, off, len)
    }
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    fun keep(): ByteArray {
        if (count == 0) throw PrepareRefused("empty")
        kept = true
        return toByteArray()
    }
    override fun close() { if (!kept) budget.release(reserved) }
}
