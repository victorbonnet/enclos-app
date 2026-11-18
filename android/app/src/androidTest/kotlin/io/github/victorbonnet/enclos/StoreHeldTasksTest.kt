package io.github.victorbonnet.enclos

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cache of held tasks against the platform's SQLite: the upgrade from version 7, the
 * revision fence, and what deleting a conversation, forgetting a host and clearing remove.
 * Each test uses a database of its own, never the app's `conversations.db`.
 */
@RunWith(AndroidJUnit4::class)
class StoreHeldTasksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "held-tasks-test-${java.util.UUID.randomUUID()}.db"
    private val home = Conversation("mac", "lab/main", "home.lab.main", home = true)

    private fun card(revision: Long, state: String = "held", task: String = "t_1", host: String = "mac") = HeldTask(
        host = host, agent = "lab/main", conversation = "home.lab.main", task = task, title = "Write the report",
        reason = "Gave up after 3 failures", since = 1_763_000_000, resume = "Runs it again.", stateWire = state, revision = revision,
    )

    @Before @After
    fun clean() {
        if (context.getDatabasePath(name).exists()) assertTrue(context.deleteDatabase(name))
    }

    @Test fun capability_survives_actual_close_reopen_and_revision_fences() {
        val no = HeldTask.from("mac", card(5).json().put("resumable", false))
        val yes = HeldTask.from("mac", card(1, task = "true-control").json())
        Store(context, name).use { store ->
            store.conversation(home)
            store.heldTask(no); store.heldTask(yes)
        }
        Store(context, name).use { store ->
            assertEquals(setOf(no, yes), store.recent().heldTasks.toSet())
            store.heldTask(card(4)); store.heldTask(card(5))
            assertEquals(no, store.recent().heldTasks.single { it.task == no.task })
            store.heldTask(card(6))
        }
        Store(context, name).use { store ->
            assertTrue(store.recent().heldTasks.single { it.task == no.task }.resumable)
        }
    }

    @Test
    fun a_version_7_database_keeps_its_messages_and_gains_held_tasks() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            VERSION_7.forEach(db::execSQL)
            db.execSQL("INSERT INTO conversations (id, host, agent, home) VALUES ('home.lab.main', 'mac', 'lab/main', 1)")
            db.execSQL(
                "INSERT INTO messages (host, seq, conversation, sender, text, kind, time) " +
                    "VALUES ('mac', 1, 'home.lab.main', 'agent', 'Held: Write the report', 'notify', 1)",
            )
            db.version = 7
        }
        val store = Store(context, name)
        try {
            val kept = store.lines().getValue("home.lab.main").single()
            assertEquals("Held: Write the report", kept.text)
            // Kept by an app with no link: none now either.
            assertNull(kept.heldTask)
            store.message("mac", "home.lab.main", Line(2, "agent", "Held: deploy", "notify", 2, heldTask = "t_2"))
            assertEquals("t_2", store.lines().getValue("home.lab.main").last().heldTask)
            store.heldTask(card(revision = 5))
            assertEquals(listOf(card(revision = 5)), store.recent().heldTasks)
        } finally {
            store.close()
        }
    }

    @Test
    fun an_older_revision_never_replaces_a_newer_one() {
        val store = Store(context, name)
        try {
            store.conversation(home)
            store.heldTask(card(revision = 5))
            store.heldTask(card(revision = 4, state = "released"))
            assertEquals(listOf(card(revision = 5)), store.recent().heldTasks)
            store.heldTask(card(revision = 6, state = "resumed"))
            assertEquals(listOf(card(revision = 6, state = "resumed")), store.recent().heldTasks)
        } finally {
            store.close()
        }
    }

    @Test
    fun deleting_the_conversation_forgetting_the_host_or_clearing_drops_its_cards() {
        val store = Store(context, name)
        try {
            store.conversation(home)
            store.heldTask(card(revision = 1))
            store.deleteConversation(ConversationKey("mac", "home.lab.main"))
            assertTrue(store.recent().heldTasks.isEmpty())
            // And a late card of the deleted conversation is not written back.
            store.heldTask(card(revision = 2))
            assertTrue(store.recent().heldTasks.isEmpty())

            store.conversation(home.copy(host = "pi"))
            store.heldTask(card(revision = 1, host = "pi"))
            assertEquals(1, store.recent().heldTasks.size)
            store.forget("pi")
            assertTrue(store.recent().heldTasks.isEmpty())

            store.conversation(home.copy(host = "pi"))
            store.heldTask(card(revision = 1, host = "pi", task = "t_9"))
            store.clear()
            assertTrue(store.recent().heldTasks.isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun a_card_whose_home_is_not_kept_is_dropped_when_the_cache_loads() {
        val store = Store(context, name)
        try {
            store.heldTask(card(revision = 1))
            assertTrue(store.recent().heldTasks.isEmpty())
            // Dropped from the database too, not only left out.
            store.conversation(home)
            assertTrue(store.recent().heldTasks.isEmpty())
        } finally {
            store.close()
        }
    }

    private companion object {
        /** The schema of cache version 7, as the app released before held tasks created it. */
        val VERSION_7 = listOf(
            "CREATE TABLE conversations (id TEXT PRIMARY KEY, host TEXT NOT NULL, agent TEXT NOT NULL, title TEXT, titled_by TEXT, " +
                "home INTEGER NOT NULL DEFAULT 0, members TEXT, archived INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE messages (host TEXT NOT NULL, seq INTEGER NOT NULL, conversation TEXT NOT NULL, sender TEXT NOT NULL, " +
                "text TEXT NOT NULL, kind TEXT NOT NULL, time INTEGER NOT NULL, attachments TEXT, steps TEXT, speaker TEXT, question TEXT, PRIMARY KEY (host, seq))",
            "CREATE INDEX messages_of ON messages (conversation, seq)",
            "CREATE TABLE agents (host TEXT NOT NULL, name TEXT NOT NULL, runtime TEXT NOT NULL, capabilities TEXT NOT NULL, " +
                "display_name TEXT, name_revision INTEGER NOT NULL DEFAULT 0 CHECK(name_revision>=0), PRIMARY KEY (host, name))",
            "CREATE TABLE questions (host TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>=0), info TEXT NOT NULL, PRIMARY KEY (host, id))",
            "CREATE TABLE conversation_deletions (host TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(host,id))",
        )
    }
}
