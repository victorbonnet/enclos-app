package io.github.victorbonnet.enclos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The phone's database, upgraded from every earlier version on
 * Robolectric's SQLite: what was kept stays, and the tables end as a new
 * phone's. Versions 1 and 5 are the tables those releases created; the
 * others are version 1 with the upgrade steps up to them.
 */
@RunWith(RobolectricTestRunner::class)
class StoreUpgradeTest {
    private lateinit var context: Context

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(NAME)
    }

    @After
    fun end() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun a_version_1_database_keeps_what_it_held() {
        old(1, V1)
        val saved = Store(context).use { it.recent() }

        assertEquals(listOf(Agent("mac", "box/ada", "hermes", listOf("files"))), saved.agents)
        val conversation = saved.conversations.single()
        assertEquals(Conversation("mac", "box/ada", "c1", "Plans", "agent"), conversation)
        assertFalse(conversation.home)
        assertFalse(conversation.archived)
        val line = saved.lines.getValue("c1").single()
        assertEquals("hello", line.text)
        assertEquals(listOf(Attachment("f1", "image/png", "chart.png", 12)), line.attachments)
        assertNull(line.agent)
        assertNull(line.question)
        assertEquals(mapOf("mac" to 7L), saved.kept)
    }

    @Test
    fun the_newer_columns_take_writes_after_an_upgrade() {
        old(1, V1)
        Store(context).use { store ->
            store.conversation(Conversation("mac", "box", "g1", "Team", "person", members = listOf("box/ada", "box/bob"), archived = true, revision = 3))
            store.message("mac", "g1", Line(8, "agent", "done", "text", 1_700_000_001, agent = "box/bob", question = "q1"))
            store.agents("mac", listOf(Agent("mac", "box/ada", "hermes", listOf("files"), displayName = "Ada", nameRevision = 2)))
            val saved = store.recent()
            val group = saved.conversations.single { it.id == "g1" }
            assertEquals(listOf("box/ada", "box/bob"), group.members)
            assertEquals(true, group.archived)
            assertEquals(3L, group.revision)
            val line = saved.lines.getValue("g1").single()
            assertEquals("box/bob", line.agent)
            assertEquals("q1", line.question)
            assertEquals("Ada", saved.agents.single().displayName)
        }
    }

    @Test
    fun every_old_version_upgrades_to_the_tables_a_new_phone_gets() {
        val fresh = Store(context).use { schema(it.readableDatabase) }
        context.deleteDatabase(NAME)
        for (version in 1 until STORE_VERSION) {
            old(version, tablesAt(version))
            val upgraded = Store(context).use { schema(it.readableDatabase) }
            assertEquals("from version $version", fresh, upgraded)
            assertEquals("from version $version", "hello", Store(context).use { it.recent() }.lines.getValue("c1").single().text)
            context.deleteDatabase(NAME)
        }
    }

    @Test
    fun a_version_9_database_lists_the_hosts_its_open_items_came_from() {
        old(9, tablesAt(9))
        SQLiteDatabase.openDatabase(context.getDatabasePath(NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO open_items (host, item_key, conversation, info) VALUES ('mac', 'q1', 'c1', '{}')")
            it.execSQL("INSERT INTO conversation_deletions (host, id) VALUES ('mac', 'gone')")
        }
        Store(context).use { store ->
            val db = store.readableDatabase
            assertEquals(listOf("mac"), db.rawQuery("SELECT host FROM open_item_hosts", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() })
            assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM conversation_deletions", null).use { c -> c.moveToFirst(); c.getInt(0) })
        }
    }

    @Test
    fun a_task_line_is_kept_with_its_task_and_one_unreadable_reads_as_its_text() {
        old(11, tablesAt(11))
        val event = TaskEvent("t_a09759c7", "S2a", "lab/reviewer", TaskEventKind.Triage, TaskState.Queued)
        Store(context).use { store ->
            store.message("mac", "c1", Line(8, "agent", "🛑 Kanban t_a09759c7 routed to TRIAGE", "task_event", 1_700_000_001, taskEvent = event))
            store.message("mac", "c1", Line(9, "agent", "plain", "notify", 1_700_000_002))
            store.writableDatabase.execSQL(
                "INSERT INTO messages (host, seq, conversation, sender, text, kind, time, task_event) VALUES " +
                    "('mac', 10, 'c1', 'agent', 'pinged', 'task_event', 1700000003, '{\"task\":\"\",\"title\":\"x\",\"event\":\"done\"}')",
            )
        }
        for (lines in Store(context).use { listOf(it.recent().lines.getValue("c1"), it.lines().getValue("c1")) }) {
            val (first, task, plain, unreadable) = lines
            assertEquals("hello", first.text)
            assertNull(first.taskEvent)
            assertEquals("task_event", task.kind)
            assertEquals(event, task.taskEvent)
            assertEquals("🛑 Kanban t_a09759c7 routed to TRIAGE", task.text)
            assertNull(plain.taskEvent)
            assertEquals("notify" to null, unreadable.kind to unreadable.taskEvent)
            assertEquals("pinged", unreadable.text)
        }
    }

    /** The tables at [version]: as recorded, else version 1 with the steps up to it. */
    private fun tablesAt(version: Int): List<String> = when (version) {
        1 -> V1
        5 -> V5
        else -> V1 + upgrades(1).dropLast(upgrades(version).size)
    }

    /** A database as a release at [version] created it, holding one host's agent, conversation and message. */
    private fun old(version: Int, tables: List<String>) {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }, null)
        tables.forEach(db::execSQL)
        db.execSQL("INSERT INTO agents (host, name, runtime, capabilities) VALUES ('mac', 'box/ada', 'hermes', '[\"files\"]')")
        db.execSQL("INSERT INTO conversations (id, host, agent, title, titled_by) VALUES ('c1', 'mac', 'box/ada', 'Plans', 'agent')")
        db.execSQL(
            "INSERT INTO messages (host, seq, conversation, sender, text, kind, time, attachments) VALUES " +
                "('mac', 7, 'c1', 'agent', 'hello', 'text', 1700000000, '[{\"id\":\"f1\",\"mime\":\"image/png\",\"name\":\"chart.png\",\"size\":12}]')",
        )
        db.version = version
        db.close()
    }

    /** Each table's and index's columns, as SQLite describes them, by name. */
    private fun schema(db: SQLiteDatabase): Map<String, List<String>> {
        val names = db.rawQuery("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toList()
        }
        return names.associate { (type, name) ->
            val pragma = if (type == "index") "index_info" else "table_info"
            name to db.rawQuery("PRAGMA $pragma($name)", null).use { c ->
                generateSequence {
                    if (!c.moveToNext()) null else (0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "null" else c.getString(it) }
                }.toList().sorted()
            }
        }
    }

    private companion object {
        const val NAME = "conversations.db"

        /** The tables of the first release (0.1.0). */
        val V1 = listOf(
            "CREATE TABLE conversations (id TEXT PRIMARY KEY, host TEXT NOT NULL, agent TEXT NOT NULL, title TEXT, titled_by TEXT)",
            "CREATE TABLE messages (host TEXT NOT NULL, seq INTEGER NOT NULL, conversation TEXT NOT NULL, sender TEXT NOT NULL, " +
                "text TEXT NOT NULL, kind TEXT NOT NULL, time INTEGER NOT NULL, attachments TEXT, steps TEXT, PRIMARY KEY (host, seq))",
            "CREATE INDEX messages_of ON messages (conversation, seq)",
            "CREATE TABLE agents (host TEXT NOT NULL, name TEXT NOT NULL, runtime TEXT NOT NULL, capabilities TEXT NOT NULL, PRIMARY KEY (host, name))",
        )

        /** The tables at version 5: agents' shared names, before questions. */
        val V5 = listOf(
            "CREATE TABLE conversations (id TEXT PRIMARY KEY, host TEXT NOT NULL, agent TEXT NOT NULL, title TEXT, titled_by TEXT, " +
                "home INTEGER NOT NULL DEFAULT 0, members TEXT, archived INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE messages (host TEXT NOT NULL, seq INTEGER NOT NULL, conversation TEXT NOT NULL, sender TEXT NOT NULL, " +
                "text TEXT NOT NULL, kind TEXT NOT NULL, time INTEGER NOT NULL, attachments TEXT, steps TEXT, speaker TEXT, PRIMARY KEY (host, seq))",
            "CREATE INDEX messages_of ON messages (conversation, seq)",
            "CREATE TABLE agents (host TEXT NOT NULL, name TEXT NOT NULL, runtime TEXT NOT NULL, capabilities TEXT NOT NULL, display_name TEXT, " +
                "name_revision INTEGER NOT NULL DEFAULT 0 CHECK(name_revision>=0), PRIMARY KEY (host, name))",
        )
    }
}
