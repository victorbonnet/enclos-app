package io.github.victorbonnet.enclos

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileComposerSemanticsTest {
    @Test fun capture_shared_composer_with_large_text_online_and_offline() {
        val runner = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(false, true)) for (offline in listOf(false, true)) {
            val activity = runner.startActivitySync(Intent().setClassName(runner.targetContext,
                "io.github.victorbonnet.enclos.FileComposerProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("dark", dark).putExtra("offline", offline).putExtra("font", 2f).putExtra("width", 320))
            try {
                runner.waitForIdleSync()
                val image = runner.uiAutomation.takeScreenshot()
                try {
                    val file = java.io.File(runner.targetContext.getExternalFilesDir(null), "composer-$dark-$offline.png")
                    file.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            } finally { runner.runOnMainSync { activity.finish() } }
        }
    }
}
