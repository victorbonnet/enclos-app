package io.github.victorbonnet.enclos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

/** TEST APK only. Serves generated fixture bytes from its own private directory, never target-app paths. */
class NativeFixtureProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "io.github.victorbonnet.enclos.test.nativefixtures"
        private val names = setOf("IMG_1.jpg", "sticker.bin", "moving.gif", "clear.png",
            "sunrise.jpg", "report.pdf", "example.py")
        fun serve(name: String, bytes: ByteArray): Uri {
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            return Uri.parse(checkNotNull(resolver.call(Uri.parse("content://$AUTHORITY"), "serve", name,
                Bundle().apply { putByteArray("bytes", bytes) })).getString("uri")!!)
        }
        fun clear() {
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                Uri.parse("content://$AUTHORITY"), "clear", null, null)
        }
    }
    private val root get() = File(context!!.cacheDir, "native-fixtures").apply { mkdirs() }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "clear") { root.deleteRecursively(); return Bundle() }
        require(method == "serve" && arg in names)
        val bytes = requireNotNull(extras?.getByteArray("bytes"))
        require(bytes.size in 1..10_000_000)
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, arg!!).writeBytes(bytes)
        return Bundle().apply { putString("uri", "content://$AUTHORITY/$id/$arg") }
    }
    private fun file(uri: Uri): File {
        require(uri.authority == AUTHORITY && uri.pathSegments.size == 2)
        val (id, name) = uri.pathSegments
        require(UUID.fromString(id).toString() == id && name in names)
        return File(File(root, id), name).also { require(it.isFile) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?) =
        file(uri).let { f -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .apply { addRow(arrayOf<Any>(f.name, f.length())) } }
    override fun getType(uri: Uri): String = when(file(uri).extension) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
