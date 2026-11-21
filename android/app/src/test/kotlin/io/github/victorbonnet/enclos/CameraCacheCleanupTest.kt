package io.github.victorbonnet.enclos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CameraCacheCleanupTest {
    @Test fun clear_cache_sweeps_abandoned_original_but_preserves_other_live_capture_and_noncanonical_paths() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dir = cameraDir(context).apply { mkdirs() }
        fun original() = File(dir, "${UUID.randomUUID()}.jpg").apply { writeText("private original") }
        val orphan = original()
        val active = original()
        val other = SendingFiles()
        other.capturing(FileKey("other-host", "other-conversation"), active)
        val noncanonical = File(dir, "1-1-1-1-1.jpg").apply { writeText("not owned") }
        val outside = File(context.cacheDir, "${UUID.randomUUID()}.jpg").apply { writeText("outside") }
        val link = File(dir, "${UUID.randomUUID()}.jpg")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val model = fixtureModel()
        try {
            model.clearCache()
            assertFalse("abandoned camera original must be swept", orphan.exists())
            assertTrue("another live capture must survive", active.exists())
            assertTrue(noncanonical.exists())
            assertTrue(outside.exists())
            assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        } finally {
            model.endFixture()
            other.clear()
            orphan.delete(); active.delete(); noncanonical.delete(); link.delete(); outside.delete()
        }
    }
}
