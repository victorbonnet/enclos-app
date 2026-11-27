package io.github.victorbonnet.enclos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for Downloads:
 * - AC11: Downloads.kt is the only main source that references MediaStore.Downloads or DIRECTORY_DOWNLOADS.
 * - AC9: AndroidManifest.xml requests no external storage permission.
 */
class DownloadsSourceTest {
    @Test fun only_downloads_kt_references_mediastore_downloads_or_directory_downloads() {
        val root = File("src/main/kotlin")
        assertTrue("main kotlin sources found at ${root.absolutePath}", root.isDirectory)
        val sources = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        val downloadsKt = sources.filter { it.name == "Downloads.kt" }
        assertTrue("Downloads.kt exists", downloadsKt.size == 1)

        val forbidden = listOf("MediaStore.Downloads", "DIRECTORY_DOWNLOADS")
        for (file in sources) {
            if (file.name == "Downloads.kt") continue
            val text = file.readText()
            for (token in forbidden) {
                assertFalse("${file.path} must not reference $token", text.contains(token))
            }
        }
        val downloadsText = downloadsKt.single().readText()
        assertTrue("Downloads.kt uses MediaStore.Downloads", downloadsText.contains("MediaStore.Downloads"))
        assertTrue("Downloads.kt uses DIRECTORY_DOWNLOADS", downloadsText.contains("DIRECTORY_DOWNLOADS"))
    }

    @Test fun manifest_requests_no_external_storage_permission() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml exists at ${manifest.absolutePath}", manifest.isFile)
        val text = manifest.readText()
        for (permission in listOf("WRITE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE")) {
            assertFalse("AndroidManifest.xml must not request $permission", text.contains(permission))
        }
    }
}
