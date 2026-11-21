package io.github.victorbonnet.enclos

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.util.concurrent.Callable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.victorbonnet.enclos.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID

internal enum class FileSource { Camera, Photos, Files }

/** Why an image is not prepared for an agent with `incoming_files` but not `incoming_images`. */
internal const val NO_IMAGES = "this agent takes documents, not images"
/** Under Camera and Photos, disabled, for such an agent. */
internal const val DOCUMENTS_ONLY = "This agent takes documents only: Camera and Photos are off."
/** A picker's result whose conversation is not known (its saved state was lost, or the conversation is gone). */
internal const val PICK_UNMATCHED = "That selection could not be matched to its conversation. Choose the files again."
internal const val PICK_UNMATCHED_PHOTO = "That photo could not be matched to its conversation. Take it again."
internal const val PHOTO_NOT_KEPT = "The photo was not kept. Take it again."
/** The test tag of a draft file's chip. */
internal const val FILE_CHIP = "file-chip"

/** The private folder a camera writes into; a restored path is used only if it is one of its files. */
internal fun cameraDir(context: Context) = File(context.cacheDir, "outgoing-camera")

internal fun ownedCapture(context: Context, path: String?): File? {
    val file = path?.let(::File) ?: return null
    val dir = cameraDir(context).canonicalFile
    val real = runCatching { file.canonicalFile }.getOrNull() ?: return null
    val name = real.name.removeSuffix(".jpg")
    return real.takeIf { it.parentFile == dir && real.name.endsWith(".jpg") && runCatching { UUID.fromString(name) }.isSuccess }
}

/** Camera originals being captured or read in this process, across live models. */
internal object CameraOriginals {
    private val users = mutableMapOf<File, Int>()
    @Synchronized fun retain(file: File) { val path = file.canonicalFile; users[path] = (users[path] ?: 0) + 1 }
    @Synchronized fun release(file: File) {
        val path = file.canonicalFile
        val count = users[path] ?: return
        if (count == 1) users.remove(path) else users[path] = count - 1
    }
    @Synchronized fun sweep(context: Context) {
        val dir = cameraDir(context)
        if (java.nio.file.Files.isSymbolicLink(dir.toPath())) return
        if (dir.canonicalFile.parentFile != context.cacheDir.canonicalFile) return
        dir.listFiles()?.forEach { file ->
            val name = file.name.removeSuffix(".jpg")
            val uuid = runCatching { UUID.fromString(name).toString() }.getOrNull()
            if (file.name == "$uuid.jpg" && file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.parentFile == dir.canonicalFile && file.canonicalFile !in users) file.delete()
        }
    }
}

/** Who asked for a picker's result, as saved state: host and conversation. */
internal fun pickOwner(c: Conversation) = "${c.host}\n${c.id}"

/**
 * The attach control and draft files of [c]. System pickers only: no media
 * or storage permission, no persisted URI grant. Which conversation asked
 * for a picker's result, and the camera's file, are saved state, so a result
 * that comes back after rotation or the process's recreation goes to the
 * conversation that asked ([AppModel.pickedFiles]); one that cannot be
 * matched is said, and asked for again, never put in another.
 */
@Composable
internal fun FileComposer(model: AppModel, c: Conversation, composer: @Composable (@Composable () -> Unit, Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val state = model.outgoing.files
    val key = FileKey(c.host, c.id)
    val now = state[key]
    // The conversation (pickOwner) whose picker is open, until its result comes.
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun taken(): String? = asked.also { asked = null }
    SideEffect {
        val owner = model.conversations.firstOrNull { pickOwner(it) == asked }
        if (owner != null) ownedCapture(context, cameraPath)?.let { file ->
            val destination = FileKey(owner.host, owner.id)
            if (state[destination].capture != file) state.capturing(destination, file)
        }
    }
    val remaining = (MAX_SEND_FILES - now.draft.size).coerceAtLeast(1)

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, remaining))) {
        model.pickedFiles(taken(), c, it, FileSource.Photos)
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        model.pickedFiles(taken(), c, listOfNotNull(it), FileSource.Photos)
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        model.pickedFiles(taken(), c, it, FileSource.Files)
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
        // Only a file of the app's own camera folder, restored or not, is ever read or deleted here.
        val file = ownedCapture(context, cameraPath)
        cameraPath = null
        val owner = taken()
        when {
            accepted && file != null && file.isFile && file.length() > 0 ->
                model.pickedFiles(owner, c, listOf(Uri.fromFile(file)), FileSource.Camera, file)
            else -> {
                file?.let { state.captured(it); it.delete() }
                // Cancelled says nothing; accepted without a photo asks for another.
                if (accepted) state.error(key, PHOTO_NOT_KEPT)
            }
        }
    }
    fun launchCamera(owner: Conversation) {
        val dir = cameraDir(context).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        // A capture of this composer whose result never came: its own, so it goes.
        ownedCapture(context, cameraPath)?.let { state.captured(it); it.delete() }
        cameraPath = file.path
        state.capturing(FileKey(owner.host, owner.id), file)
        runCatching { take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
            .onFailure { state.captured(file); file.delete(); cameraPath = null; asked = null; state.error(FileKey(owner.host, owner.id), "Camera unavailable.") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val owner = model.conversations.firstOrNull { pickOwner(it) == asked }
        if (granted && owner != null) launchCamera(owner)
        else {
            asked = null
            owner?.let { state.error(FileKey(it.host, it.id), "Camera permission was not granted.") }
        }
    }
    val phase = now.phase
    // Keyed by each file itself: a removal or a new file never shows another's preview, even for a frame.
    val thumbnails = now.draft.map { file -> androidx.compose.runtime.key(file) { rememberThumbnail(file) } }
    Column {
        phase.outcome()?.let { (text, names) ->
            FileOutcome(phase, text, names,
                onRetry = { model.retryFiles(c)?.let { state.error(key, it) } },
                onDiscard = { state.dismiss(key) })
        }
        FileComposerControls(
            names = now.draft.map { it.name },
            thumbnails = thumbnails,
            offersFiles = model.offersFiles(c),
            offersImages = model.offersImages(c),
            online = model.connected(c.host),
            preparing = phase is FilePhase.Preparing,
            sending = phase is FilePhase.Sending || phase is FilePhase.AwaitingAck,
            progress = (phase as? FilePhase.Sending)?.progress,
            blocked = phase !is FilePhase.Draft,
            error = now.error, onRemove = { state.remove(key, it) },
            onCamera = {
                asked = pickOwner(c)
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    launchCamera(c)
                } else runCatching { permission.launch(android.Manifest.permission.CAMERA) }
                    .onFailure { asked = null; state.error(key, "Camera unavailable.") }
            },
            onPhotos = {
                asked = pickOwner(c)
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                // The picker lets the person choose at most the slots left.
                runCatching { if (remaining == 1) photo.launch(request) else photos.launch(request) }
                    .onFailure { asked = null; state.error(key, "Photos unavailable.") }
            },
            onFiles = {
                asked = pickOwner(c)
                runCatching { documents.launch(arrayOf("*/*")) }.onFailure { asked = null; state.error(key, "Files unavailable.") }
            },
        ) { attach, enabled -> composer(attach, enabled) }
    }
}

/** The words and file names of an operation to show its outcome; none while it has none. */
private fun FilePhase.outcome(): Pair<String, List<String>>? = when (this) {
    is FilePhase.Failed -> text to names
    is FilePhase.Unknown -> send.text to send.files.map { it.name }
    else -> null
}

/**
 * A small preview of a draft image, decoded off the main thread from its
 * prepared bytes (no copy of them), for as long as it is shown; called under
 * `key(file)`. Its bitmap is recycled once it is no longer shown, or as soon
 * as decoded if it is not wanted any more.
 */
@Composable
private fun rememberThumbnail(file: SendFile): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, file) {
        if (!file.mime.startsWith("image/")) return@produceState
        var decoded: Bitmap? = null
        try {
            // Assigned inside, so a decode finished as this is cancelled is still recycled below.
            withContext(Dispatchers.Default) { decoded = thumbnail(file.bytes) }
            value = decoded?.asImageBitmap() ?: return@produceState
            awaitDispose { value = null }
        } finally {
            decoded?.recycle()
        }
    }
    return image
}

private fun thumbnail(bytes: ByteArray, side: Int = 96): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FileComposerControls(
    names: List<String>,
    thumbnails: List<ImageBitmap?> = emptyList(),
    offersFiles: Boolean = true,
    offersImages: Boolean = true,
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
            if (names.isNotEmpty()) FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A draft's names are distinct (prepareSendFiles): each chip keeps its own place in composition.
                names.forEachIndexed { index, name ->
                    androidx.compose.runtime.key(name) { FileChip(name, thumbnails.getOrNull(index), enabled = !blocked) { onRemove(index) } }
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
                listOf(Triple("Camera", onCamera, offersImages), Triple("Photos", onPhotos, offersImages), Triple("Files", onFiles, true))
                    .forEach { (label, action, on) ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
                            enabled = on,
                            onClick = { menu = false; action() },
                        ) { Text(label, Modifier.fillMaxWidth(), style = Type.message, color = if (on) p.accent else p.textFaint) }
                    }
                if (!offersImages) Text(DOCUMENTS_ONLY, Modifier.padding(horizontal = 26.dp, vertical = 8.dp), style = Type.small, color = p.textMuted)
            }
        }
    }
}

/**
 * A draft file: its preview (an image's thumbnail, else a file mark) and its
 * full name, wrapped, which does nothing when tapped; removing it takes its
 * own 48 dp button.
 */
@Composable
private fun FileChip(name: String, thumbnail: ImageBitmap?, enabled: Boolean, onRemove: () -> Unit) {
    val p = palette
    Row(
        Modifier.testTag(FILE_CHIP).clip(Shapes.control).background(p.surface).border(1.dp, p.line, Shapes.control).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) Image(thumbnail, contentDescription = null, Modifier.size(32.dp).clip(Shapes.control), contentScale = ContentScale.Crop)
        else Glyph(Glyphs.compose, p.textMuted, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(name, Modifier.weight(1f, fill = false).padding(vertical = 8.dp), style = Type.small, color = p.text)
        IconButton(
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Remove $name" },
            enabled = enabled, onClick = onRemove,
        ) { Icon(Glyphs.clear, contentDescription = null, tint = p.textMuted) }
    }
}

internal class PreparedFiles(val files: List<SendFile>, val refused: List<String>)

internal const val GIF_FROM_PHOTOS = "a GIF from Photos or the camera keeps its source details; choose it in Files to send it as it is"

/**
 * Prepares each pick. One refused (its type, its size, a name the protocol
 * refuses, an image for an agent without [images], the budget) is named
 * with why, in words of the app's own; the others are kept. Their bytes are
 * reserved in [budget] as they are read; [alive] is called before each read
 * and throws to stop (a cancelled preparation), and then everything
 * reserved here is released. Names stay distinct from each other and from
 * [taken] (the draft's): each is final before its file exists, so one file
 * has one owner, never a renamed second over the same bytes.
 */
internal fun prepareSendFiles(context: Context, picks: List<Uri>, source: FileSource, budget: PreparedBudget = PreparedBudget.process, images: Boolean = true, taken: Collection<String> = emptyList(), alive: () -> Unit = {}): PreparedFiles {
    val files = mutableListOf<SendFile>()
    val refused = mutableListOf<String>()
    val seen = taken.mapTo(mutableSetOf()) { it.lowercase() }
    try {
        picks.forEachIndexed { index, uri ->
            alive()
            // The camera's file is named by the app; it goes as a photo.
            var shown: String? = if (source != FileSource.Files) "photo.jpg" else "a selected file"
            try {
                validatePickUri(context, uri, source)
                if (source == FileSource.Files) shown = displayName(context, uri)
                files += prepareSendFile(context, uri, source, shown, index, picks.size, budget, alive, images) { distinct(it, seen) }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: PrepareRefused) {
                refused += "${safeFileName(shown)} (${e.safe})"
            } catch (e: Exception) {
                refused += "${safeFileName(shown)} (could not be read)"
            }
        }
    } catch (e: Throwable) {
        files.forEach { it.releaseFrom(budget) }
        throw e
    }
    return PreparedFiles(files, refused)
}

internal fun validatePickUri(context: Context, uri: Uri, source: FileSource) {
    val valid = if (source == FileSource.Camera) {
        uri.scheme == "file" && ownedCapture(context, uri.path)?.let { Uri.fromFile(it) == uri } == true
    } else {
        val authority = uri.authority
        // Accept only literal provider authorities, without encoded aliases, user ids or ports.
        uri.scheme == "content" && !authority.isNullOrEmpty() &&
            uri.encodedAuthority == authority &&
            authority.split('.').all { part ->
                part.isNotEmpty() && part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
            } &&
            !authority.equals("${context.packageName}.files", ignoreCase = true)
    }
    if (!valid) throw PrepareRefused("choose it again from the picker")
}

private fun displayName(context: Context, uri: Uri): String? =
    if (uri.scheme == "file") uri.lastPathSegment
    else runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

/**
 * [name] as picked, or, when [seen] has it already (case aside), with a
 * number before its extension; [seen] then has the one chosen. A duplicate
 * that cannot be numbered within the filename bounds is refused, never given another chip's identity.
 */
private fun distinct(name: String, seen: MutableSet<String>): String {
    var candidate = name
    var n = 2
    while (candidate.lowercase() in seen) candidate = "${name.substringBeforeLast('.')}-${n++}.${name.substringAfterLast('.')}"
    if (!validSendFileName(candidate)) throw PrepareRefused("its repeated name is too long; rename it and choose it again")
    seen += candidate.lowercase()
    return candidate
}

/** What an image's first bytes say it is, whatever its provider calls it. */
private enum class ImageHead { Png, Gif, Other }

private fun imageHead(input: InputStream): ImageHead {
    val head = ByteArray(8)
    var n = 0
    while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r }
    return when {
        n >= 8 && head.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> ImageHead.Png
        n >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "GIF8" -> ImageHead.Gif
        else -> ImageHead.Other
    }
}

/** One pick prepared; [named] gives its final name once its bytes are read, just before its one [SendFile] is made. */
internal fun prepareSendFile(context: Context, uri: Uri, source: FileSource, shown: String?, index: Int, count: Int, budget: PreparedBudget, alive: () -> Unit = {}, images: Boolean = true, named: (String) -> String = { it }): SendFile {
    validatePickUri(context, uri, source)
    val resolver = context.contentResolver
    fun open(): InputStream = (if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream() else resolver.openInputStream(uri))
        ?: throw PrepareRefused("could not be opened")
    if (source == FileSource.Files) {
        // A document keeps its name and exact bytes (a PNG's transparency, a GIF's frames);
        // its type is the protocol's for its extension, whatever the provider calls it.
        val name = shown ?: throw PrepareRefused("it has no name")
        if (!validSendFileName(name)) throw PrepareRefused("its name has characters an agent does not take")
        val mime = sendFileType(name) ?: throw PrepareRefused("not a type an agent takes")
        if (!images && mime.startsWith("image/")) throw PrepareRefused(NO_IMAGES)
        val finalName = named(name)
        val bytes = open().use { readBounded(it, budget, alive) }
        return SendFile(finalName, mime, bytes)
    }
    if (!images) throw PrepareRefused(NO_IMAGES)
    // Camera and Photos: decoded and written again, so no source metadata (place, device, time) goes.
    // What the image is comes from its own first bytes, not from what its provider calls it.
    val head = open().use(::imageHead)
    // A GIF's frames cannot be written again here without losing its animation, and its own
    // bytes may carry source details: refused rather than sent as it is.
    if (head == ImageHead.Gif) throw PrepareRefused(GIF_FROM_PHOTOS)
    val base = "photo"
    alive()
    val decoder = when {
        uri.scheme == "file" -> ImageDecoder.createSource(File(requireNotNull(uri.path)))
        // Opened as it is, not as "image/*": a provider calling it something generic still serves it.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ImageDecoder.createSource(Callable<AssetFileDescriptor> { resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException() })
        else -> ImageDecoder.createSource(resolver, uri)
    }
    val bitmap = try {
        ImageDecoder.decodeBitmap(decoder) { d, info, _ ->
            val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
            d.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) { throw PrepareRefused("not an image the phone reads") }
    // A PNG, or any image with transparency, stays PNG so its transparency is kept; the rest, HEIC included, is JPEG.
    val png = head == ImageHead.Png || bitmap.hasAlpha()
    val bytes = try {
        Reserving(budget, alive).use { out ->
            check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out))
            out.keep()
        }
    } finally { bitmap.recycle() }
    return try {
        if (png) SendFile(named("$base.png"), "image/png", bytes) else SendFile(named("$base.jpg"), "image/jpeg", bytes)
    } catch (e: Throwable) {
        budget.release(bytes.size)
        throw e
    }
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
        // The buffer itself when it is exactly full, else one copy of its used part; this stream is dropped after.
        return if (buf.size == count) buf else buf.copyOf(count)
    }
    override fun close() { if (!kept) budget.release(reserved) }
}
