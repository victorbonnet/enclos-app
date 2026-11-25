package io.github.victorbonnet.enclos

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

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
     * [name] if it is not in [taken], else the first free `base (n).ext` with
     * n = 1, 2, ... (or `name (n)` when there is no extension). The extension is
     * the part from the last dot when that dot is neither the first nor the last
     * character.
     */
    fun freeName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < name.lastIndex
        val base = if (hasExtension) name.substring(0, dot) else name
        val ext = if (hasExtension) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate !in taken) return candidate
            n++
        }
    }
}

/**
 * The persistence seam for writing a file into Downloads, so JVM tests can
 * verify naming, streaming, and pending-row cleanup without a real MediaStore.
 */
interface DownloadStore {
    fun taken(): Set<String>
    fun begin(name: String, mediaType: String): Uri?
    fun output(uri: Uri): OutputStream?
    fun finish(uri: Uri): Boolean
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
 * or cancellation before [DownloadStore.finish] succeeds discards the pending
 * row so nothing partial is left behind. Shows no UI notice itself.
 */
fun saveToDownloads(
    store: DownloadStore,
    name: String,
    mediaType: String,
    file: File,
    isActive: () -> Boolean = { true },
): DownloadOutcome {
    if (!file.isFile) return DownloadOutcome.Missing
    if (!isActive()) return DownloadOutcome.Cancelled

    val cleanName = Attachments.fileName(name)
    val cleanType = mediaType.substringBefore(';').trim().ifBlank { "application/octet-stream" }

    val targetName = try {
        Downloads.freeName(cleanName, store.taken())
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
        file.inputStream().use { input ->
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
        }
        if (!isActive()) return DownloadOutcome.Cancelled
        finished = store.finish(uri)
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
 * Saves [file] for [attachment] to Downloads off the main thread. Uses
 * [MediaStoreDownloadStore] on API 29+ unless [store] is supplied for tests.
 * Shows no UI notice itself.
 */
suspend fun saveToDownloads(
    context: Context,
    attachment: Attachment,
    file: File,
    store: DownloadStore? = null,
): DownloadOutcome = withContext(Dispatchers.IO) {
    val target = store ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStoreDownloadStore(context.contentResolver)
    } else {
        return@withContext DownloadOutcome.Failed
    }
    saveToDownloads(
        store = target,
        name = Attachments.fileName(attachment.name),
        mediaType = attachment.mime,
        file = file,
        isActive = { coroutineContext.isActive },
    )
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
