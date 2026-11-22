package io.github.victorbonnet.enclos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The fixture provider runs in the test package's own process, whose class path is only the test
 * APK on top of the framework (no Kotlin standard library: the build dedupes it against the app).
 * A JVM test shares one class path with everything and cannot see that. This loads the provider
 * class from the test APK alone, under the framework's boot loader, and drives every method of
 * that isolated instance directly; it never goes through the ContentResolver, so no process starts.
 */
@RunWith(AndroidJUnit4::class)
class NativeFixtureProviderIsolationTest {
    private companion object {
        const val AUTHORITY = "io.github.victorbonnet.enclos.test.nativefixtures"
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val cache = File(instrumentation.targetContext.cacheDir, "isolated-fixture-provider-test")
    private lateinit var loader: ClassLoader
    private lateinit var provider: ContentProvider

    @Before fun attach() {
        cache.deleteRecursively()
        assertTrue(cache.mkdirs())
        loader = PathClassLoader(instrumentation.context.applicationInfo.sourceDir,
            ContentProvider::class.java.classLoader)
        val type = Class.forName("io.github.victorbonnet.enclos.NativeFixtureProvider", true, loader)
        assertSame(loader, type.classLoader)
        assertNotSame(NativeFixtureProvider::class.java, type)
        assertEquals(AUTHORITY, type.getField("AUTHORITY").get(null))
        provider = type.getDeclaredConstructor().newInstance() as ContentProvider
        val context: Context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getCacheDir(): File = cache
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY; exported = true })
    }

    @After fun clean() { cache.deleteRecursively() }

    private fun serve(name: String, bytes: ByteArray): Uri {
        val result = provider.call("serve", name, Bundle().apply { putByteArray("bytes", bytes) })
        return Uri.parse(result!!.getString("uri")!!)
    }

    private fun read(uri: Uri): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }

    @Test fun isolated_provider_serves_describes_reads_refuses_and_clears() {
        val bytes = ByteArray(4096) { (it * 31 + 7).toByte() }
        val uri = serve("sunrise.jpg", bytes)
        assertEquals("content", uri.scheme)
        assertEquals(AUTHORITY, uri.authority)
        assertEquals(2, uri.pathSegments.size)
        val id = uri.pathSegments[0]
        assertEquals(UUID.fromString(id).toString(), id)
        assertEquals("sunrise.jpg", uri.pathSegments[1])
        assertEquals("content://$AUTHORITY/$id/sunrise.jpg", uri.toString())

        provider.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sunrise.jpg", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
            assertEquals(1, cursor.count)
        }

        assertEquals("image/jpeg", provider.getType(uri))
        val types = mapOf(
            "IMG_1.jpg" to "image/jpeg",
            "clear.png" to "image/png",
            "moving.gif" to "image/gif",
            "report.pdf" to "application/octet-stream",
            "example.py" to "application/octet-stream",
            "sticker.bin" to "application/octet-stream",
        )
        for ((name, type) in types) assertEquals(name, type, provider.getType(serve(name, byteArrayOf(1))))

        assertArrayEquals(bytes, read(uri))
        assertThrows(IllegalArgumentException::class.java) { provider.openFile(uri, "w") }

        assertThrows(IllegalArgumentException::class.java) { serve("evil.sh", bytes) }
        assertThrows(IllegalArgumentException::class.java) { serve("sunrise.jpg", ByteArray(0)) }
        // A parseable but non-canonical (uppercase) id is refused before any file lookup.
        val uppercase = Uri.parse("content://$AUTHORITY/ABCDEF00-0000-4000-8000-00000000000A/sunrise.jpg")
        assertThrows(IllegalArgumentException::class.java) { provider.query(uppercase, null, null, null, null) }
        val threeSegments = Uri.parse("content://$AUTHORITY/$id/sunrise.jpg/more")
        assertThrows(IllegalArgumentException::class.java) { provider.query(threeSegments, null, null, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri, ContentValues()) }

        provider.call("clear", null, null)
        assertThrows(IllegalArgumentException::class.java) { provider.query(uri, null, null, null, null) }
    }
}
