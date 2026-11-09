package io.github.victorbonnet.enclos

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Explicit actions from a viewer; only this cached attachment is handed over. */
fun openAttachment(context: Context, attachment: Attachment, file: File) = handoff(context, attachment, file, sharing = false)

fun shareAttachment(context: Context, attachment: Attachment, file: File) = handoff(context, attachment, file, sharing = true)

private fun handoff(context: Context, attachment: Attachment, file: File, sharing: Boolean) {
    val failure = try {
        if (!file.isFile) {
            "The cached file was cleared. Close the viewer and tap the file to fetch it again."
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = if (sharing) Intent(Intent.ACTION_SEND)
                .setType(attachment.mediaType.ifBlank { "application/octet-stream" })
                .putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mediaType.ifBlank { "application/octet-stream" })
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(attachment.name, uri)
            context.startActivity(if (sharing) Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else intent)
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
