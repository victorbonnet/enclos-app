package io.github.victorbonnet.enclos

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * The media types another app may be asked to open (`ACTION_VIEW`), each
 * with the file extensions it may carry. The agent chooses both the media
 * type and the name, so a type an app would run (HTML, XHTML, SVG, XML,
 * an APK) is never on it. The receiving app may read the type back from
 * the `FileProvider`, which takes it from the extension: the extension
 * must therefore be one of the declared type's, or another app could be
 * handed `page.xhtml` declared as `text/plain`.
 */
private val Viewable: Map<String, Set<String>> = mapOf(
    "application/pdf" to setOf("pdf"),
    "text/plain" to setOf("txt", "text", "log"),
    "text/csv" to setOf("csv"),
    "text/markdown" to setOf("md", "markdown"),
    "image/png" to setOf("png"),
    "image/jpeg" to setOf("jpg", "jpeg"),
    "image/gif" to setOf("gif"),
    "image/webp" to setOf("webp"),
    "image/heic" to setOf("heic"),
    "image/heif" to setOf("heif"),
    "audio/mpeg" to setOf("mp3"),
    "audio/mp4" to setOf("m4a"),
    "audio/ogg" to setOf("ogg", "oga"),
    "audio/wav" to setOf("wav"),
    "audio/flac" to setOf("flac"),
    "video/mp4" to setOf("mp4", "m4v"),
    "video/webm" to setOf("webm"),
    "application/rtf" to setOf("rtf"),
    "application/msword" to setOf("doc"),
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to setOf("docx"),
    "application/vnd.ms-excel" to setOf("xls"),
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to setOf("xlsx"),
    "application/vnd.ms-powerpoint" to setOf("ppt"),
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" to setOf("pptx"),
    "application/vnd.oasis.opendocument.text" to setOf("odt"),
    "application/vnd.oasis.opendocument.spreadsheet" to setOf("ods"),
    "application/vnd.oasis.opendocument.presentation" to setOf("odp"),
)

/**
 * The type to ask another app to open [fileName] as, or null when the file
 * is offered to Share only: its declared [mediaType] is not on the list,
 * or its extension is not one of that type's.
 */
fun viewIntentType(mediaType: String, fileName: String): String? {
    val type = mediaType.substringBefore(';').trim().lowercase()
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return type.takeIf { Viewable[it]?.contains(extension) == true }
}

/**
 * What a handoff asks of another app: Share (`ACTION_SEND`, the declared type) or Open
 * (`ACTION_VIEW`, a listed type only), with a read grant and never a write or persistable one;
 * null when the file can only be shared. The intent carries the one cached file's URI as its
 * data or stream and as its clip, so the grant covers that file alone (ADR 0008).
 */
data class Handoff(val action: String, val type: String, val flags: Int)

fun handoffOf(attachment: Attachment, fileName: String, sharing: Boolean): Handoff? =
    if (sharing) Handoff(Intent.ACTION_SEND, attachment.mediaType.ifBlank { "application/octet-stream" }, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    else viewIntentType(attachment.mediaType, fileName)?.let { Handoff(Intent.ACTION_VIEW, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

/** Explicit actions from a viewer; only this cached attachment is handed over. */
fun openAttachment(context: Context, attachment: Attachment, file: File) = handoff(context, attachment, file, sharing = false)

fun shareAttachment(context: Context, attachment: Attachment, file: File) = handoff(context, attachment, file, sharing = true)

private fun handoff(context: Context, attachment: Attachment, file: File, sharing: Boolean) {
    val failure = try {
        val handoff = handoffOf(attachment, file.name, sharing)
        if (!file.isFile) {
            "The cached file was cleared. Close the viewer and tap the file to fetch it again."
        } else if (handoff == null) {
            "This file type can only be shared."
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(handoff.action)
            if (sharing) intent.setType(handoff.type).putExtra(Intent.EXTRA_STREAM, uri)
            else intent.setDataAndType(uri, handoff.type)
            intent.addFlags(handoff.flags)
            intent.clipData = ClipData.newRawUri(attachment.shownName, uri)
            // Always a chooser: the person sees which app gets the file,
            // rather than a default handler taking it silently.
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            null
        }
    } catch (_: ActivityNotFoundException) {
        if (sharing) "No app is available to share this file." else "No app is available to open this file type."
    } catch (_: SecurityException) {
        "This file could not be handed to another app."
    } catch (_: IllegalArgumentException) {
        "This cached file cannot be opened. Close the viewer and fetch it again."
    }
    if (failure != null) Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
}
