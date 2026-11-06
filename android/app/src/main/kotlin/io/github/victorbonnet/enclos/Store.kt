package io.github.victorbonnet.enclos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the phone kept of each host, first read: its agents, its
 * conversations, and the newest messages of the most recent ones.
 */
class Saved(
    val agents: List<Agent>,
    val conversations: List<Conversation>,
    /** By conversation, in `seq` order: the newest messages only. */
    val lines: Map<String, List<Line>>,
    /** By host: the newest `seq` kept, which `sync` starts after. */
    val kept: Map<String, Long>,
)

/**
 * The phone's copy of what each host's bridge sent: its agents, the
 * conversations, their messages and the tool steps that led to an answer,
 * in the app's private database (never backed up, ADR 0003). The bridge
 * stays the source: the copy is shown at once when the app starts, and a
 * `sync` brings what is newer than its last message.
 *
 * Read off the main thread, in two passes: [recent], what the first screens
 * show, then [lines], every message.
 */
class Store(context: Context) : SQLiteOpenHelper(context, "conversations.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (id TEXT PRIMARY KEY, host TEXT NOT NULL, agent TEXT NOT NULL, title TEXT, titled_by TEXT, " +
                "home INTEGER NOT NULL DEFAULT 0)",
        )
        // `seq` is the bridge's, increasing per host.
        db.execSQL(
            "CREATE TABLE messages (host TEXT NOT NULL, seq INTEGER NOT NULL, conversation TEXT NOT NULL, sender TEXT NOT NULL, " +
                "text TEXT NOT NULL, kind TEXT NOT NULL, time INTEGER NOT NULL, attachments TEXT, steps TEXT, PRIMARY KEY (host, seq))",
        )
        db.execSQL("CREATE INDEX messages_of ON messages (conversation, seq)")
        db.execSQL(
            "CREATE TABLE agents (host TEXT NOT NULL, name TEXT NOT NULL, runtime TEXT NOT NULL, capabilities TEXT NOT NULL, PRIMARY KEY (host, name))",
        )
    }

    // Step by step from the version on the phone, never by dropping what was kept.
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // 2: an agent's Home conversation is marked.
        if (old < 2) db.execSQL("ALTER TABLE conversations ADD COLUMN home INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * What the first screens show: the agents, every conversation with its
     * last message (the inbox), and the last [history] messages of the
     * [open] most recent conversations, the ones likely opened first.
     */
    fun recent(open: Int = 10, history: Int = 40): Saved {
        val db = readableDatabase
        val agents = db.rawQuery("SELECT host, name, runtime, capabilities FROM agents ORDER BY rowid", null).use { c ->
            generateSequence { if (c.moveToNext()) c else null }.map {
                Agent(it.getString(0), it.getString(1), it.getString(2), strings(it.getString(3)))
            }.toList()
        }
        val conversations = db.rawQuery("SELECT host, agent, id, title, titled_by, home FROM conversations ORDER BY rowid", null).use { c ->
            generateSequence { if (c.moveToNext()) c else null }.map {
                Conversation(it.getString(0), it.getString(1), it.getString(2), it.getStringOrNull(3), it.getStringOrNull(4), it.getInt(5) != 0)
            }.toList()
        }
        val lines = mutableMapOf<String, List<Line>>()
        // Each conversation's last message, through the (conversation, seq) index.
        db.rawQuery(
            "SELECT $LINE FROM messages m WHERE seq = (SELECT MAX(seq) FROM messages WHERE conversation = m.conversation)",
            null,
        ).use { c -> while (c.moveToNext()) lines[c.getString(0)] = listOf(line(c)) }
        lines.entries.sortedByDescending { it.value.last().time }.take(open).map { it.key }.forEach { id ->
            db.rawQuery("SELECT $LINE FROM messages WHERE conversation = ? ORDER BY seq DESC LIMIT $history", arrayOf(id)).use { c ->
                lines[id] = generateSequence { if (c.moveToNext()) c else null }.map { line(it) }.toList().reversed()
            }
        }
        val kept = db.rawQuery("SELECT host, MAX(seq) FROM messages GROUP BY host", null).use { c ->
            generateSequence { if (c.moveToNext()) c else null }.associate { it.getString(0) to it.getLong(1) }
        }
        return Saved(agents, conversations, lines, kept)
    }

    /** Every message kept, by conversation, in `seq` order. */
    fun lines(): Map<String, List<Line>> {
        val lines = mutableMapOf<String, MutableList<Line>>()
        readableDatabase.rawQuery("SELECT $LINE FROM messages ORDER BY conversation, seq", null).use { c ->
            while (c.moveToNext()) lines.getOrPut(c.getString(0)) { mutableListOf() } += line(c)
        }
        return lines
    }

    /** A message, from a row of [LINE]'s columns. */
    private fun line(c: android.database.Cursor) = Line(
        seq = c.getLong(1),
        from = c.getString(2),
        text = c.getString(3),
        kind = c.getString(4),
        time = c.getLong(5),
        attachments = attachments(c.getStringOrNull(6)),
        steps = steps(c.getStringOrNull(7)),
    )

    /** A conversation the bridge sent, new or renamed. */
    fun conversation(c: Conversation) {
        val values = ContentValues().apply {
            put("host", c.host)
            put("agent", c.agent)
            put("title", c.title)
            put("titled_by", c.titledBy)
            put("home", if (c.home) 1 else 0)
        }
        // An update, else an insert: Android 9 and 10's SQLite has no upsert.
        val db = writableDatabase
        if (db.update("conversations", values, "id = ?", arrayOf(c.id)) == 0) {
            db.insert("conversations", null, values.apply { put("id", c.id) })
        }
    }

    /** A message of [conversation] on [host]; one already kept stays as it was. */
    fun message(host: String, conversation: String, line: Line) {
        val values = ContentValues().apply {
            put("host", host)
            put("seq", line.seq)
            put("conversation", conversation)
            put("sender", line.from)
            put("text", line.text)
            put("kind", line.kind)
            put("time", line.time)
            if (line.attachments.isNotEmpty()) put("attachments", attachments(line.attachments))
            if (line.steps.isNotEmpty()) put("steps", steps(line.steps))
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** [host]'s agents, as its bridge listed them last. */
    fun agents(host: String, agents: List<Agent>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("agents", "host = ?", arrayOf(host))
            agents.forEach { a ->
                db.insert(
                    "agents",
                    null,
                    ContentValues().apply {
                        put("host", host)
                        put("name", a.name)
                        put("runtime", a.runtime)
                        put("capabilities", JSONArray(a.capabilities).toString())
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Forgets all that every host sent: the cache was cleared; each syncs again. */
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("messages", "conversations", "agents").forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Forgets all that [host] sent: it was paired again as another device, or forgotten. */
    fun forget(host: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("messages", "conversations", "agents").forEach { db.delete(it, "host = ?", arrayOf(host)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        /** A message's columns, its conversation first, as [line] reads them. */
        const val LINE = "conversation, seq, sender, text, kind, time, attachments, steps"
    }

    private fun android.database.Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)

    private fun strings(json: String) = JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }

    private fun attachments(list: List<Attachment>) = JSONArray(
        list.map { JSONObject().put("id", it.id).put("mime", it.mime).put("name", it.name).put("size", it.size) },
    ).toString()

    private fun attachments(json: String?): List<Attachment> {
        val a = JSONArray(json ?: return emptyList())
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Attachment(it.getString("id"), it.getString("mime"), it.getString("name"), it.getLong("size"))
        }
    }

    // Kept once the answer came: every step is done.
    private fun steps(list: List<Step>) = JSONArray(
        list.map { s ->
            JSONObject().put("call", s.call).put("name", s.name).put("label", s.label).put("ok", s.ok)
                .apply { s.durationMs?.let { put("duration_ms", it) } }
        },
    ).toString()

    private fun steps(json: String?): List<Step> {
        val a = JSONArray(json ?: return emptyList())
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Step(
                call = it.getString("call"),
                name = it.getString("name"),
                label = it.optString("label").ifBlank { null },
                done = true,
                ok = it.optBoolean("ok", true),
                durationMs = if (it.has("duration_ms")) it.getLong("duration_ms") else null,
            )
        }
    }
}
