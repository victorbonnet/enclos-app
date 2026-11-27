package io.github.victorbonnet.enclos

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two clash notices as the phone builds them from a real refusal: the losing host's heading
 * names the affected agent and the host that keeps its conversations, an agent's page names the
 * host holding its Home, a host or agent without a clash says nothing, and a holder the phone no
 * longer pairs falls back to today's sentence (issue #94).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClashNoticeTest {
    private val id = "home.comptoir.comptoir-dev"

    private fun message() = JSONObject()
        .put("type", "message")
        .put("conversation", id)
        .put("agent", "comptoir/comptoir-dev")

    private fun conversation() = JSONObject()
        .put("type", "conversation")
        .put("conversation", id)
        .put("agent", "comptoir/comptoir-dev")
        .put("revision", 1)

    @Test
    fun the_losing_host_names_the_agent_and_the_other_host_and_an_unaffected_host_says_nothing() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost("mac"))
            model.paired.add(fixtureHost("atelier"))
            // `atelier` keeps the Home; the other host's message about the same id is left out.
            model.conversations.add(Conversation("atelier", "comptoir/comptoir-dev", id, title = "Home", home = true, revision = 1))
            model.receive("mac", message())
            assertEquals(1, model.leftOut("mac"))
            assertEquals(
                "1 conversation of this host not shown: atelier also has comptoir/comptoir-dev, with the same sandbox and name.",
                model.clashNotice("mac"),
            )
            // The host that keeps it, and any agent without a left-out Home, draw nothing.
            assertNull(model.clashNotice("atelier"))
            assertNull(model.clashNote(Agent("atelier", "comptoir/comptoir-dev", "hermes", emptyList())))
            assertEquals(
                "This agent's Home is not shown here: atelier also has comptoir/comptoir-dev, with the same sandbox and name.",
                model.clashNote(Agent("mac", "comptoir/comptoir-dev", "hermes", emptyList())),
            )
        } finally { model.endFixture() }
    }

    @Test
    fun a_holder_the_phone_no_longer_pairs_falls_back_to_todays_sentence() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost("mac"))
            model.paired.add(fixtureHost("atelier"))
            model.conversations.add(Conversation("atelier", "comptoir/comptoir-dev", id, title = "Home", home = true, revision = 1))
            model.receive("mac", message())
            model.paired.removeAll { it.name == "atelier" }
            assertEquals(
                "1 conversation of this host not shown: another paired host has an agent with the same sandbox and name, whose conversations use the same ids.",
                model.clashNotice("mac"),
            )
            assertEquals(
                "This agent's Home is not shown here: another paired host has an agent named comptoir/comptoir-dev too, and this phone keeps the first one's.",
                model.clashNote(Agent("mac", "comptoir/comptoir-dev", "hermes", emptyList())),
            )
        } finally { model.endFixture() }
    }

    @Test
    fun the_record_goes_once_the_phone_keeps_that_conversation_again() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost("mac"))
            model.paired.add(fixtureHost("atelier"))
            model.conversations.add(Conversation("atelier", "comptoir/comptoir-dev", id, title = "Home", home = true, revision = 1))
            model.receive("mac", message())
            assertEquals(1, model.leftOut("mac"))
            // The person forgets the host that held it: its conversations go with it, and mac is
            // paired again, so the next frame about that id is kept rather than left out.
            model.forgetHost("atelier")
            // Any frame about the id the phone keeps again ends the record, not only its metadata.
            model.receive("mac", JSONObject().put("type", "typing").put("conversation", id).put("on", true))
            assertEquals(0, model.leftOut("mac"))
            assertNull(model.clashNotice("mac"))
            assertNull(model.clashNote(Agent("mac", "comptoir/comptoir-dev", "hermes", emptyList())))
            // And Home shows the conversation it described: the heading and the Inbox agree.
            model.receive("mac", conversation())
            assertEquals("mac", model.conversations.first { it.id == id }.host)
        } finally { model.endFixture() }
    }

    @Test
    fun a_frame_whose_agent_is_a_json_null_is_not_named_as_null() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost("mac"))
            model.paired.add(fixtureHost("atelier"))
            model.conversations.add(Conversation("atelier", "comptoir/comptoir-dev", id, title = "Home", home = true, revision = 1))
            model.receive("mac", message().put("agent", JSONObject.NULL))
            assertEquals(
                "1 conversation of this host not shown: another paired host has an agent with the same sandbox and name, whose conversations use the same ids.",
                model.clashNotice("mac"),
            )
        } finally { model.endFixture() }
    }

    @Test
    fun a_holder_paired_again_without_that_conversation_is_not_named() {
        val model = fixtureModel()
        try {
            model.paired.add(fixtureHost("mac"))
            model.paired.add(fixtureHost("atelier"))
            model.conversations.add(Conversation("atelier", "comptoir/comptoir-dev", id, title = "Home", home = true, revision = 1))
            model.receive("mac", message())
            assertEquals(
                "1 conversation of this host not shown: atelier also has comptoir/comptoir-dev, with the same sandbox and name.",
                model.clashNotice("mac"),
            )
            // Paired again under the same name, with nothing of the old pairing: atelier is still
            // paired, but no conversation of that id is its own any more.
            AppModel::class.java.getDeclaredMethod("forget", String::class.java).apply { isAccessible = true }.invoke(model, "atelier")
            assertEquals(
                "1 conversation of this host not shown: another paired host has an agent with the same sandbox and name, whose conversations use the same ids.",
                model.clashNotice("mac"),
            )
        } finally { model.endFixture() }
    }
}
