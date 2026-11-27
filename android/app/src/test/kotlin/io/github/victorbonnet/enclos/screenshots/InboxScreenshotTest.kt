package io.github.victorbonnet.enclos.screenshots

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.victorbonnet.enclos.Agent
import io.github.victorbonnet.enclos.AppModel
import io.github.victorbonnet.enclos.Conversation
import io.github.victorbonnet.enclos.FakeBridge
import io.github.victorbonnet.enclos.Line
import io.github.victorbonnet.enclos.Screens
import io.github.victorbonnet.enclos.endFixture
import io.github.victorbonnet.enclos.field
import io.github.victorbonnet.enclos.fixtureHost
import io.github.victorbonnet.enclos.fixtureModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Inbox (docs/spec.md, Inbox) as the person sees it, drawn by `just
 * screenshots`: the real first screen, in the real theme, over a made-up
 * host and agents. Light and dark on a small and a large phone, and the
 * small one with the font at 1.3 times.
 *
 * Fixture times are hours before the clock reads when the test starts, so
 * the rows say "2 hours ago" whenever it runs; nothing else here depends on
 * the day or the machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class InboxScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val host = "studio"

    private fun fixture(): AppModel {
        val model = fixtureModel()
        FakeBridge().attach(model, host)
        model.paired.add(fixtureHost(host))
        val now = System.currentTimeMillis() / 1000
        val hours = 3_600L
        val read = model.field<MutableMap<String, Long>>("lastRead")
        var seq = 0L

        fun agent(name: String, shown: String) = Agent(host, name, "hermes", emptyList(), displayName = shown).also { model.agents.add(it) }

        /** [lines] are (who, words, hours ago); the agent's unread ones are those after [readTo]. */
        fun talk(conversation: Conversation, vararg lines: Triple<String, String, Double>, unread: Int = 0) {
            model.conversations.add(conversation)
            val kept = lines.map { (from, text, ago) -> Line(++seq, from, text, "reply", now - (ago * hours).toLong()) }
            model.lines[conversation.id] = kept
            read[conversation.id] = kept.getOrNull(kept.size - unread - 1)?.seq ?: 0L
        }

        val engineer = agent("lab/engineer", "Engineer")
        val designer = agent("lab/designer", "Designer")
        val reviewer = agent("lab/reviewer", "Reviewer")
        val assistant = agent("home/assistant", "Assistant")

        talk(
            Conversation(host, engineer.name, "home.lab.engineer", home = true),
            Triple("agent", "Draft pull request 412 is open; its checks are running.", 2.5),
        )
        talk(
            Conversation(host, engineer.name, "c-engineer-1", title = "Fix the reconnect backoff", titledBy = "agent"),
            Triple("person", "Can the backoff stop growing after five minutes?", 5.5),
            Triple("agent", "Yes. I capped it at 300 seconds and added a test for the cap.", 5.4),
            Triple("agent", "The cap is in; the checks are running.", 2.5),
            unread = 2,
        )
        talk(
            Conversation(host, designer.name, "home.lab.designer", home = true),
            Triple("agent", "The three Inbox variants are ready for you to look at.", 26.0),
            unread = 1,
        )
        talk(
            Conversation(host, reviewer.name, "home.lab.reviewer", home = true),
            Triple("agent", "Reviewed the backoff change: two small comments, nothing blocking.", 50.0),
        )
        talk(
            Conversation(host, assistant.name, "home.home.assistant", home = true),
            Triple("person", "What is on my calendar tomorrow?", 75.0),
            Triple("agent", "Two things: the design review at 10:00 and lunch at 12:30.", 74.9),
        )
        talk(
            Conversation(host, "lab", "g-release", title = "Release review", titledBy = "person", members = listOf("lab/engineer", "lab/reviewer")),
            Triple("agent", "Both changes are merged; the release can go out.", 8.0),
        )
        // The engineer's and the designer's conversations show under them.
        model.unfolded.add("$host/${engineer.name}")
        return model
    }

    private fun draw(look: Look) {
        val model = fixture()
        try {
            val file = compose.drawn("inbox", look) { Screens(model) { _, _ -> } }
            println("drew ${file.path}")
        } finally {
            model.endFixture()
        }
    }

    @Test fun lightSmall() = draw(Look(Phone.Small, dark = false))
    @Test fun darkSmall() = draw(Look(Phone.Small, dark = true))
    @Test fun lightLarge() = draw(Look(Phone.Large, dark = false))
    @Test fun darkLarge() = draw(Look(Phone.Large, dark = true))
    @Test fun lightSmallLargeFont() = draw(Look(Phone.Small, dark = false, fontScale = 1.3f))
    @Test fun darkSmallLargeFont() = draw(Look(Phone.Small, dark = true, fontScale = 1.3f))
}
