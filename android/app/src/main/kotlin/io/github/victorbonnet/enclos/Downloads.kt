package io.github.victorbonnet.enclos

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Saving a received attachment to the phone's shared Downloads folder.
 *
 * This is the only file in the app that writes to Downloads (through
 * [MediaStore.Downloads], from Android 10 / API 29, with no storage
 * permission). On Android 9 (API 28) the action is not offered.
 */
object Downloads {
    /** Whether saving to Downloads is offered on [sdk] (Android 10 / API 29 and later). */
    fun offered(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.Q

    /**
     * The name a file is saved under: [Attachments.fileName] of [name], with
     * no run of spaces (a long run could push an extension out of sight in a
     * file manager) and no leading dot or space, so the saved file is never a
     * hidden one (`.profile`). The strip repeats until nothing changes, since
     * removing a space can expose a dot (`" . .profile"`).
     */
    fun savedName(name: String): String {
        var current = Attachments.fileName(name)
        while (true) {
            val next = current.replace(Regex(" {2,}"), " ").trimStart('.', ' ').trimEnd()
            if (next == current) return current
            current = next
            if (current.isEmpty()) return Attachments.fileName("")
        }
    }

    /**
     * The media type to record for a saved file called [name]: the one its own
     * extension stands for, else `application/octet-stream`. The agent's media
     * type is deliberately not used: MediaStore adds the extension a type
     * implies when the name's does not match it (`report.pdf` sent as an APK
     * type became `report.pdf.apk`), so a type the agent chose would rename the
     * file. A name and a type that agree are never renamed.
     */
    fun mediaTypeFor(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.lastIndex) return OCTET_STREAM
        val ext = name.substring(dot + 1).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: OCTET_STREAM
    }

    private const val OCTET_STREAM = "application/octet-stream"

    /**
     * [name] if it is not in [taken], else the first free `base (n).ext` with
     * n = 1, 2, ... (or `name (n)` when there is no extension). The extension is
     * the part from the last dot when that dot is neither the first nor the last
     * character.
     */
    fun freeName(name: String, taken: Set<String>): String = freeName(name) { it in taken }

    /** As [freeName], for a store that can say whether one name is taken (see [DownloadStore.exists]). */
    fun freeName(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < name.lastIndex
        val base = if (hasExtension) name.substring(0, dot) else name
        val ext = if (hasExtension) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!isTaken(candidate)) return candidate
            n++
        }
    }
}

/** Where a row the store began stands now (see [DownloadStore.state]). */
enum class RowState {
    /** Still the pending row this app began: not published, and this app can remove it. */
    Pending,

    /** Published, and this app can still see and remove it. */
    Done,

    /** Not visible to this app: the platform published it and handed it on, or it is gone. */
    Gone,
}

/**
 * The persistence seam for writing a file into Downloads, so JVM tests can
 * verify naming, streaming, and pending-row cleanup without a real MediaStore.
 */
interface DownloadStore {
    /** The names of this app's own files in Downloads. Another app's are not listed. */
    fun taken(): Set<String>

    /**
     * Whether a file called [name] is there at all, whoever owns it. [taken] cannot
     * see another app's file, but the platform still refuses a second file of that name.
     */
    fun exists(name: String): Boolean = false

    fun begin(name: String, mediaType: String): Uri?
    fun output(uri: Uri): OutputStream?
    fun finish(uri: Uri): Boolean

    /** What became of the row [uri] began; asked when [finish] failed. */
    fun state(uri: Uri): RowState = RowState.Pending

    fun discard(uri: Uri)
}

/** Real Downloads store backed by [MediaStore.Downloads] (API 29+). */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreDownloadStore(private val resolver: ContentResolver) : DownloadStore {
    override fun taken(): Set<String> {
        val names = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(Environment.DIRECTORY_DOWNLOADS, "${Environment.DIRECTORY_DOWNLOADS}/")
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            val col = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (col >= 0) {
                while (cursor.moveToNext()) {
                    cursor.getString(col)?.let(names::add)
                }
            }
        }
        return names
    }

    // The folder answers for a name another app owns, though it does not list it.
    @Suppress("DEPRECATION")
    override fun exists(name: String): Boolean =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).exists()

    override fun begin(name: String, mediaType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    override fun output(uri: Uri): OutputStream? = resolver.openOutputStream(uri)

    override fun finish(uri: Uri): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        return resolver.update(uri, values, null, null) > 0
    }

    override fun state(uri: Uri): RowState = try {
        val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val extras = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
            resolver.query(uri, projection, extras, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(uri), projection, null, null, null)
        }
        cursor?.use { if (!it.moveToFirst()) RowState.Gone else if (it.getInt(0) == 1) RowState.Pending else RowState.Done }
            ?: RowState.Gone
    } catch (_: Throwable) {
        // Unknown is not "published": the caller removes the row and says nothing was saved.
        RowState.Pending
    }

    override fun discard(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }
}

/** Result of writing a cached attachment to Downloads. */
sealed interface DownloadOutcome {
    data object Saved : DownloadOutcome
    data object NoRoom : DownloadOutcome
    data object Failed : DownloadOutcome
    data object Missing : DownloadOutcome
    data object Cancelled : DownloadOutcome
}

private const val COPY_CHUNK_BYTES = 64 * 1024

/**
 * Streams [file] unchanged into [store] under a free sanitised name. Any failure
 * or cancellation before the file is published discards the pending row so
 * nothing partial is left behind. Shows no UI notice itself. The agent's
 * [mediaType] is not used to type the saved file (see [Downloads.mediaTypeFor]);
 * the name's own extension decides.
 */
@Suppress("UNUSED_PARAMETER")
fun saveToDownloads(
    store: DownloadStore,
    name: String,
    mediaType: String,
    file: File,
    isActive: () -> Boolean = { true },
): DownloadOutcome {
    if (!file.isFile) return DownloadOutcome.Missing
    if (!isActive()) return DownloadOutcome.Cancelled

    // The source is opened before the store is touched: a file that cannot be read
    // leaves no pending row, and no output stream is open to leak.
    val input = try {
        file.inputStream()
    } catch (e: FileNotFoundException) {
        return if (!file.isFile) DownloadOutcome.Missing else failureOf(e)
    } catch (e: Throwable) {
        return failureOf(e)
    }
    return input.use { writeThrough(store, Downloads.savedName(name), input, file, isActive) }
}

private fun writeThrough(
    store: DownloadStore,
    cleanName: String,
    input: InputStream,
    file: File,
    isActive: () -> Boolean,
): DownloadOutcome {
    // The agent's media type is not recorded: see Downloads.mediaTypeFor.
    val cleanType = Downloads.mediaTypeFor(cleanName)

    val targetName = try {
        // Names this app lists, and names another app owns that only the folder answers for.
        val taken = store.taken()
        Downloads.freeName(cleanName) { it in taken || store.exists(it) }
    } catch (e: Throwable) {
        return failureOf(e)
    }
    if (!isActive()) return DownloadOutcome.Cancelled

    val uri = try {
        store.begin(targetName, cleanType)
    } catch (e: Throwable) {
        return failureOf(e)
    } ?: return DownloadOutcome.Failed

    var finished = false
    try {
        if (!isActive()) return DownloadOutcome.Cancelled
        val out = store.output(uri) ?: return DownloadOutcome.Failed
        val buffer = ByteArray(COPY_CHUNK_BYTES)
        out.use { output ->
            while (true) {
                if (!isActive()) return DownloadOutcome.Cancelled
                val read = input.read(buffer)
                if (read < 0) break
                if (!isActive()) return DownloadOutcome.Cancelled
                output.write(buffer, 0, read)
            }
            output.flush()
        }
        if (!isActive()) return DownloadOutcome.Cancelled
        finished = publish(store, uri)
        return if (finished) DownloadOutcome.Saved else DownloadOutcome.Failed
    } catch (_: CancellationException) {
        return DownloadOutcome.Cancelled
    } catch (e: FileNotFoundException) {
        return if (!file.isFile) DownloadOutcome.Missing else failureOf(e)
    } catch (e: Throwable) {
        return failureOf(e)
    } finally {
        if (!finished) {
            runCatching { store.discard(uri) }
        }
    }
}

/**
 * Publishes the written row. When the platform refuses with an error (another
 * app's file of the same name, say) it may still have finished the file itself
 * under a numbered name and handed it on, which this app can no longer see or
 * remove. A file that is there in the person's Downloads is saved, whatever the
 * call said, so that counts as published: the notice must not say nothing was
 * saved while one was. A row this app can still see is not published, and is
 * removed by the caller. A plain refusal (no row updated) is a failure.
 */
private fun publish(store: DownloadStore, uri: Uri): Boolean {
    try {
        return store.finish(uri)
    } catch (_: Exception) {
        return store.state(uri) == RowState.Gone
    }
}

/**
 * Saves [file] for [attachment] to Downloads off the main thread. Uses
 * [MediaStoreDownloadStore] on API 29+ unless [store] is supplied for tests.
 * Shows no UI notice itself. The copy stops at the next chunk when the caller
 * is cancelled. A cancel that lands after the file was published does not turn
 * Saved into a cancellation: Saved is returned, since the file is there.
 */
suspend fun saveToDownloads(
    context: Context,
    attachment: Attachment,
    file: File,
    store: DownloadStore? = null,
): DownloadOutcome {
    val target = store ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStoreDownloadStore(context.contentResolver)
    } else {
        return DownloadOutcome.Failed
    }
    val caller = coroutineContext[Job]
    val result = AtomicReference<DownloadOutcome?>(null)
    try {
        withContext(Dispatchers.IO) {
            result.set(
                saveToDownloads(
                    store = target,
                    name = attachment.name,
                    mediaType = attachment.mime,
                    file = file,
                    isActive = { caller?.isActive != false },
                ),
            )
        }
    } catch (e: CancellationException) {
        // withContext rethrows when the caller was cancelled, even with the work done.
        if (result.get() == DownloadOutcome.Saved) return DownloadOutcome.Saved
        throw e
    }
    return result.get() ?: DownloadOutcome.Failed
}

/** Exact notice text for a [DownloadOutcome]. */
fun downloadNotice(outcome: DownloadOutcome): String? = when (outcome) {
    DownloadOutcome.Saved -> "Saved to Downloads"
    DownloadOutcome.NoRoom -> "The phone has no room to save this file. Nothing was saved."
    DownloadOutcome.Failed -> "The file could not be saved. Nothing was saved."
    DownloadOutcome.Missing -> "The file was cleared from the phone. Tap it to fetch it again."
    DownloadOutcome.Cancelled -> "Cancelled. Nothing was saved."
}

/** Exact notice text when a fetch requested by Download fails. */
fun downloadFetchFailureNotice(why: String): String =
    if (why.contains("not_allowed", ignoreCase = true) || why.contains("changed", ignoreCase = true)) {
        "The host refused it. It may have changed on the host."
    } else {
        "The transfer stopped. Nothing was saved."
    }

/** Shows a short Toast notice for a download outcome. */
fun showDownloadNotice(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun failureOf(error: Throwable): DownloadOutcome =
    if (isNoSpace(error)) DownloadOutcome.NoRoom else DownloadOutcome.Failed

private fun isNoSpace(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 16) {
        if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
        if (current is IOException) {
            val message = current.message?.lowercase().orEmpty()
            if ("enospc" in message || "no space" in message) return true
        }
        current = current.cause
        depth++
    }
    return false
}
