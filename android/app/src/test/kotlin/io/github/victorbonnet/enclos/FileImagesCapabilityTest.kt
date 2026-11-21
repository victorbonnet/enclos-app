package io.github.victorbonnet.enclos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * `incoming_images` depends on `incoming_files`: documents alone without it,
 * and images refused by name, checked again at send and at retry (JVM, Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileImagesCapabilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dir by lazy { java.nio.file.Files.createTempDirectory("images-capability").toFile() }

    private fun png(name: String): File = File(dir, name).apply {
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.RED)
        try { outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { b.recycle() }
    }

    private fun withAgent(capabilities: List<String>, body: (AppModel, Conversation, FakeBridge) -> Unit) {
        val model = fixtureModel()
        val bridge = FakeBridge()
        try {
            val agent = Agent("fixture-host", "box/agent", "hermes", capabilities)
            model.agents.add(agent)
            val id = model.newConversation(agent)
            val c = model.conversations.single { it.id == id }
            bridge.attach(model, c.host)
            body(model, c, bridge)
        } finally {
            model.outgoing.clear()
            model.endFixture()
            dir.deleteRecursively()
        }
    }

    private fun AppModel.capabilities(c: Conversation, vararg names: String) {
        val at = agents.indexOfFirst { it.host == c.host && it.name == c.agent }
        agents[at] = agents[at].copy(capabilities = names.toList())
    }

    @Test fun images_depend_on_files_and_documents_stay_offered_without_them() {
        withAgent(listOf("incoming_files")) { model, c, _ ->
            assertTrue(model.offersFiles(c))
            assertFalse(model.offersImages(c))
            model.capabilities(c, "incoming_files", "incoming_images")
            assertTrue(model.offersFiles(c))
            assertTrue(model.offersImages(c))
            model.capabilities(c, "incoming_images")
            assertFalse("no attachments at all without incoming_files", model.offersFiles(c))
            assertFalse("incoming_images alone is not enough", model.offersImages(c))
        }
    }

    @Test fun files_refuse_each_image_by_name_for_a_documents_only_agent_and_keep_documents() {
        withAgent(listOf("incoming_files")) { model, c, _ ->
            val key = FileKey(c.host, c.id)
            val pdf = File(dir, "notes.pdf").apply { writeText("%PDF-1.4\n%%EOF") }
            val gif = File(dir, "moving.gif").apply { writeBytes("GIF89a".toByteArray() + ByteArray(20)) }
            val before = PreparedBudget.process.used()
            model.prepareFiles(c, listOf(png("pic.png"), pdf, gif).map(::providerPick), FileSource.Files)
            settle { model.outgoing.files.phase(key) == FilePhase.Draft }
            assertEquals(listOf("notes.pdf"), model.outgoing.files.draft(key).map { it.name })
            val error = model.outgoing.files[key].error.orEmpty()
            assertTrue(error, error.contains("pic.png ($NO_IMAGES)"))
            assertTrue(error, error.contains("moving.gif ($NO_IMAGES)"))
            assertFalse("a document is not refused", error.contains("notes.pdf"))
            assertEquals("only the document is held", pdf.length(), PreparedBudget.process.used() - before)
        }
    }

    @Test fun photos_and_camera_refuse_images_for_a_documents_only_agent_without_holding_bytes() {
        withAgent(listOf("incoming_files")) { model, c, _ ->
            val key = FileKey(c.host, c.id)
            val before = PreparedBudget.process.used()
            model.prepareFiles(c, listOf(providerPick(png("IMG_1.png"))), FileSource.Photos)
            settle { model.outgoing.files.phase(key) == FilePhase.Draft }
            assertTrue(model.outgoing.files.draft(key).isEmpty())
            assertTrue(model.outgoing.files[key].error.orEmpty().contains(NO_IMAGES))
            assertEquals(before, PreparedBudget.process.used())
        }
    }

    @Test fun losing_images_before_send_refuses_the_image_by_name_and_sends_nothing() {
        withAgent(listOf("incoming_files", "incoming_images")) { model, c, bridge ->
            val key = FileKey(c.host, c.id)
            val image = SendFile("pic.png", "image/png", byteArrayOf(1, 2, 3))
            val doc = SendFile("notes.pdf", "application/pdf", byteArrayOf(4))
            model.outgoing.files.drafted(key, image, doc)
            model.capabilities(c, "incoming_files")
            val why = model.send(c, "look")
            assertNotNull(why)
            assertTrue(why!!, why.contains("pic.png"))
            assertFalse(why, why.contains("notes.pdf"))
            assertTrue("nothing goes", bridge.frames.isEmpty())
            assertEquals(FilePhase.Draft, model.outgoing.files.phase(key))
            assertEquals(listOf(image, doc), model.outgoing.files.draft(key))
            // Documents alone still go to the same agent.
            model.outgoing.files.remove(key, 0)
            assertNull(model.send(c, "only the notes"))
            settle { model.outgoing.files.phase(key) is FilePhase.AwaitingAck }
        }
    }

    @Test fun losing_images_before_an_explicit_retry_keeps_the_unknown_original_and_sends_nothing() {
        withAgent(listOf("incoming_files", "incoming_images")) { model, c, bridge ->
            val key = FileKey(c.host, c.id)
            model.outgoing.files.drafted(key, SendFile("pic.png", "image/png", byteArrayOf(1, 2, 3)))
            assertNull(model.send(c, ""))
            settle { model.outgoing.files.phase(key) is FilePhase.AwaitingAck }
            val original = model.outgoing.files.phase(key).send()!!
            model.outgoing.ended(c.host, "lost")
            assertTrue(model.outgoing.files.phase(key) is FilePhase.Unknown)
            val sent = bridge.frames.size
            // A live socket alone cannot authorize retry before fresh transport capabilities.
            model.field<MutableMap<String, Set<String>>>("hostCapabilities").remove(c.host)
            assertNotNull(model.retryFiles(c))
            assertEquals(sent, bridge.frames.size)
            model.field<MutableMap<String, Set<String>>>("hostCapabilities")[c.host] = emptySet()
            assertNotNull(model.retryFiles(c))
            assertEquals(sent, bridge.frames.size)
            model.field<MutableMap<String, Set<String>>>("hostCapabilities")[c.host] = setOf("message.begin")
            model.capabilities(c, "incoming_files")
            val why = model.retryFiles(c)
            assertTrue(why.orEmpty(), why.orEmpty().contains("pic.png"))
            assertEquals("no frame goes", sent, bridge.frames.size)
            val still = model.outgoing.files.phase(key) as FilePhase.Unknown
            assertSame("the same original is kept for a later retry or discard", original, still.send)
            model.capabilities(c, "incoming_files", "incoming_images")
            assertNull(model.retryFiles(c))
            settle { model.outgoing.files.phase(key) is FilePhase.AwaitingAck }
            assertEquals(original.id, bridge.ofType("message.begin").last().getString("id"))
        }
    }
}
