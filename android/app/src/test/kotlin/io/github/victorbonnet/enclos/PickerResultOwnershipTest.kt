package io.github.victorbonnet.enclos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.victorbonnet.enclos.ui.Composer
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * Which conversation a picker's result belongs to, and the camera's own
 * file, across a restored composition (JVM, Robolectric, Compose's
 * StateRestorationTester: saved state restored as after rotation or the
 * process's recreation; not a real process kill or a system camera).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerResultOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val host = "fixture-host"
    private val agent = Agent(host, "box/agent", "hermes", listOf("incoming_files", "incoming_images"))

    private fun withModel(body: (AppModel, Conversation, Conversation) -> Unit) {
        val model = fixtureModel()
        val bridge = FakeBridge()
        try {
            model.agents.add(agent)
            bridge.attach(model, host)
            for (id in listOf("one", "two")) model.receive(host, message("conversation", "conversation" to id, "agent" to agent.name))
            body(model, model.conversations.single { it.id == "one" }, model.conversations.single { it.id == "two" })
        } finally {
            model.outgoing.clear()
            model.endFixture()
        }
    }

    private fun document(name: String) = File(context.cacheDir, "picked-${UUID.randomUUID()}").apply { mkdirs() }.let { File(it, name).apply { writeText("notes\n") } }

    private fun capture(name: String) = File(cameraDir(context).apply { mkdirs() }, name)

    private fun jpeg(file: File) {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.GREEN)
        try { file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { b.recycle() }
    }

    @Test fun a_result_goes_to_the_conversation_that_asked_never_to_another() {
        withModel { model, one, two ->
            val pdf = document("notes.pdf")
            model.pickedFiles(pickOwner(one), two, listOf(Uri.fromFile(pdf)), FileSource.Files)
            settle { model.outgoing.files.phase(FileKey(host, "one")) == FilePhase.Draft && model.outgoing.files.draft(FileKey(host, "one")).isNotEmpty() }
            assertEquals(listOf("notes.pdf"), model.outgoing.files.draft(FileKey(host, "one")).map { it.name })
            assertTrue(model.outgoing.files.draft(FileKey(host, "two")).isEmpty())
            assertNull(model.outgoing.files[FileKey(host, "two")].error)
        }
    }

    @Test fun a_result_whose_owner_was_lost_or_forgotten_is_not_dropped_silently_and_its_capture_goes() {
        withModel { model, one, two ->
            val before = PreparedBudget.process.used()
            val lost = capture("${UUID.randomUUID()}.jpg").apply { jpeg(this) }
            model.pickedFiles(null, one, listOf(Uri.fromFile(lost)), FileSource.Camera, lost)
            assertEquals(PICK_UNMATCHED_PHOTO, model.outgoing.files[FileKey(host, "one")].error)
            assertFalse("an unmatched capture is not kept", lost.exists())
            model.pickedFiles("$host\ngone", two, listOf(Uri.fromFile(document("a.txt"))), FileSource.Files)
            assertEquals(PICK_UNMATCHED, model.outgoing.files[FileKey(host, "two")].error)
            assertTrue(model.outgoing.files.draft(FileKey(host, "two")).isEmpty())
            assertEquals(before, PreparedBudget.process.used())
            // Nothing picked (cancelled): nothing to say.
            model.outgoing.files.error(FileKey(host, "two"), null)
            model.pickedFiles(null, two, emptyList<Uri>(), FileSource.Files)
            assertNull(model.outgoing.files[FileKey(host, "two")].error)
        }
    }

    @Test fun canonical_camera_result_consumes_only_its_registered_capture_through_a_real_alias() {
        val files = SendingFiles(PreparedBudget(1_000))
        val key = FileKey(host, "one")
        val other = FileKey(host, "two")
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "capture-alias-")
        val dir = cameraDir(context).apply { mkdirs() }.canonicalFile
        val alias = java.nio.file.Files.createSymbolicLink(root.resolve("camera"), dir.toPath())
        val shot = File(alias.toFile(), "${UUID.randomUUID()}.jpg").apply { writeText("capture") }
        val unrelated = File(dir, "${UUID.randomUUID()}.jpg").apply { writeText("other capture") }
        try {
            assertNotEquals("the fixture must exercise a lexical alias", shot, shot.canonicalFile)
            assertTrue(java.nio.file.Files.isSymbolicLink(alias))
            files.capturing(key, shot)
            files.capturing(other, unrelated)
            val result = ownedCapture(context, shot.path)!!
            assertEquals(shot.canonicalFile, result)
            assertTrue("the validated canonical result must consume its registered alias", files.captured(result))
            assertNull(files[key].capture)
            assertEquals(unrelated, files[other].capture)
            assertFalse("a result is consumed only once", files.captured(result))
            files.forget("one")
            assertTrue("preparation now owns the bytes", shot.exists())
            assertTrue(unrelated.exists())
            assertNull("canonicalization must not admit files outside the private camera folder",
                ownedCapture(context, File(root.toFile(), "${UUID.randomUUID()}.jpg").path))
        } finally {
            files.clear()
            shot.delete()
            java.nio.file.Files.delete(alias)
            java.nio.file.Files.delete(root)
        }
    }

    @Test fun deliberate_forgetting_cleans_its_own_capture_but_the_model_ending_and_a_restored_one_keep_theirs() {
        val files = SendingFiles(PreparedBudget(1_000))
        fun shot() = capture("${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1)) }
        val one = FileKey(host, "one")
        val mine = shot()
        val hostGone = shot()
        // A capture restored from saved state after the process's recreation: no conversation of this process owns it.
        val restored = shot()
        files.capturing(one, mine)
        assertEquals("the capture is the conversation's own, in its aggregate", mine, files[one].capture)
        files.capturing(FileKey("other", "three"), hostGone)
        files.forget("two")
        assertTrue(mine.exists())
        files.forget("one")
        assertFalse("a forgotten or discarded conversation's capture goes", mine.exists())
        files.forgetHost("other")
        assertFalse(hostGone.exists())

        // The ViewModel ends (onCleared, which may be the process's recreation): its result may still come back.
        val open = shot()
        files.capturing(one, open)
        files.close()
        assertTrue("the model's end deletes no capture", open.exists())
        assertFalse("and owns it no more", files.captured(open))
        // The cache cleared on purpose: its own capture goes, the restored one stays.
        val cleared = shot()
        files.capturing(one, cleared)
        files.clear()
        assertFalse(cleared.exists())
        assertTrue("a restored capture awaiting its result stays", restored.exists())

        // Taking the camera again in the same conversation replaces its own earlier capture.
        val first = shot()
        val second = shot()
        files.capturing(one, first)
        files.capturing(one, second)
        assertFalse(first.exists())
        // A capture handed to preparation is no longer the conversation's to delete.
        assertTrue(files.captured(second))
        assertNull(files[one].capture)
        files.forget("one")
        assertTrue(second.exists())
        listOf(restored, open, second).forEach { it.delete() }
    }

    @Test fun a_result_cannot_restore_files_to_a_forgotten_current_conversation() {
        withModel { model, one, _ ->
            val pdf = document("notes.pdf")
            val before = PreparedBudget.process.used()
            model.conversations.removeAll { it.host == one.host && it.id == one.id }
            model.pickedFiles(pickOwner(one), one, listOf(Uri.fromFile(pdf)), FileSource.Files)
            settle { model.outgoing.files.phase(FileKey(host, one.id)) !is FilePhase.Preparing }
            assertTrue(model.outgoing.files.draft(FileKey(host, one.id)).isEmpty())
            assertEquals(before, PreparedBudget.process.used())
        }
    }

    @Test fun files_result_after_restored_saved_state_goes_to_the_conversation_that_asked() {
        withModel { model, one, two ->
            var shown by mutableStateOf(one)
            val tester = StateRestorationTester(compose)
            tester.setContent {
                EnclosTheme(dark = false) {
                    FileComposer(model, shown) { attach, ready -> Composer("", {}, "Message", canSend = ready, attachment = attach, onSend = {}) }
                }
            }
            compose.onNodeWithContentDescription("Attach files").performClick()
            compose.onNodeWithText("Files").performClick()
            compose.waitForIdle()
            val asked = shadowOf(compose.activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.intent.action)
            tester.emulateSavedInstanceStateRestore()
            // The screen now shows another conversation in the same place.
            compose.runOnIdle { shown = two }
            val pdf = document("notes.pdf")
            compose.runOnIdle { shadowOf(compose.activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(pdf))) }
            settle { model.outgoing.files.draft(FileKey(host, "one")).isNotEmpty() }
            assertEquals(listOf("notes.pdf"), model.outgoing.files.draft(FileKey(host, "one")).map { it.name })
            assertTrue(model.outgoing.files.draft(FileKey(host, "two")).isEmpty())
        }
    }

    /** Fresh model memory plus restored owner/path models process loss, not an actual Android process kill. */
    @Test fun a_capture_survives_a_fresh_model_with_no_in_memory_picker_registration() {
        val file = capture("${UUID.randomUUID()}.jpg").apply { jpeg(this) }
        val c = Conversation(host, agent.name, "after-death")
        val savedOwner = pickOwner(c)
        val savedPath = file.path
        val first = fixtureModel()
        try {
            first.outgoing.files.capturing(FileKey(host, c.id), file)
        } finally { first.endFixture() }
        assertTrue("ending the old model must not erase a result still owned by the camera", file.exists())
        val next = fixtureModel()
        try {
            next.agents.add(agent)
            FakeBridge().attach(next, host)
            next.receive(host, message("conversation", "conversation" to c.id, "agent" to agent.name))
            assertNull(next.outgoing.files[FileKey(host, c.id)].capture)
            val restored = ownedCapture(context, savedPath)!!
            next.pickedFiles(savedOwner, c, listOf(Uri.fromFile(restored)), FileSource.Camera, restored)
            settle { next.outgoing.files.draft(FileKey(host, c.id)).isNotEmpty() }
            assertEquals("photo.jpg", next.outgoing.files.draft(FileKey(host, c.id)).single().name)
            settle { !file.exists() }
        } finally { next.endFixture(); file.delete() }
    }

    @Test fun camera_capture_survives_restored_saved_state_and_a_cancelled_one_is_deleted() {
        withModel { model, one, _ ->
            val key = FileKey(host, "one")
            val tester = StateRestorationTester(compose)
            tester.setContent {
                EnclosTheme(dark = false) {
                    FileComposer(model, one) { attach, ready -> Composer("", {}, "Message", canSend = ready, attachment = attach, onSend = {}) }
                }
            }
            fun openCamera(): Pair<Intent, File> {
                compose.onNodeWithContentDescription("Attach files").performClick()
                compose.onNodeWithText("Camera").performClick()
                compose.waitForIdle()
                val asked = shadowOf(compose.activity).nextStartedActivityForResult.intent
                assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, asked.action)
                @Suppress("DEPRECATION") val output = asked.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
                // The camera writes into the app's private folder, through its FileProvider grant.
                return asked to File(cameraDir(context), output.lastPathSegment!!)
            }
            val (asked, file) = openCamera()
            jpeg(file)
            tester.emulateSavedInstanceStateRestore()
            compose.runOnIdle { shadowOf(compose.activity).receiveResult(asked, Activity.RESULT_OK, null) }
            settle { model.outgoing.files.draft(key).isNotEmpty() }
            assertEquals(listOf("photo.jpg"), model.outgoing.files.draft(key).map { it.name })
            assertEquals("image/jpeg", model.outgoing.files.draft(key).single().mime)
            settle { !file.exists() }

            val (again, cancelled) = openCamera()
            jpeg(cancelled)
            tester.emulateSavedInstanceStateRestore()
            compose.runOnIdle { shadowOf(compose.activity).receiveResult(again, Activity.RESULT_CANCELED, null) }
            compose.waitForIdle()
            assertFalse("a cancelled capture is deleted", cancelled.exists())
            assertEquals(1, model.outgoing.files.draft(key).size)
            assertNull(model.outgoing.files[key].error)
        }
    }
}
