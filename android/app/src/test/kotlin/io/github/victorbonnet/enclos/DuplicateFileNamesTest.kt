package io.github.victorbonnet.enclos

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DuplicateFileNamesTest {
    @Test fun duplicate_maximum_length_name_is_refused_without_a_second_owner_or_reservation() {
        val dir = java.nio.file.Files.createTempDirectory("duplicate-names").toFile()
        val name = "a".repeat(116) + ".pdf"
        val budget = PreparedBudget(100)
        try {
            val picks = (1..2).map { index ->
                Uri.fromFile(File(File(dir, "$index").apply { mkdirs() }, name).apply { writeText("x") })
            }
            val result = prepareSendFiles(ApplicationProvider.getApplicationContext<Context>(), picks, FileSource.Files, budget)
            assertEquals("duplicate chips must never share an identity", listOf(name), result.files.map { it.name })
            assertEquals(1, result.refused.size)
            assertTrue(result.refused.single().startsWith(name))
            assertEquals("the refused duplicate must not leak reserved bytes", 1L, budget.used())
            result.files.forEach { it.releaseFrom(budget) }
            assertEquals(0L, budget.used())
        } finally { dir.deleteRecursively() }
    }
}
