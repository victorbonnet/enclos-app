package io.github.victorbonnet.enclos

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StoreNeedsYouTest {
    @Test fun version_8_upgrade_keeps_history_and_persists_reply_buttons_and_host_scoped_requests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "needs-you-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            CREATE.filterNot { it.startsWith("CREATE TABLE open_item") }.forEach {
                old.execSQL(it.replace(", suggested_replies TEXT", ""))
            }
            old.execSQL("INSERT INTO conversations(id,host,agent) VALUES ('home','one','lab/main')")
            old.execSQL("INSERT INTO messages(host,seq,conversation,sender,text,kind,time) VALUES ('one',1,'home','agent','Earlier message','reply',1)")
            old.version = 8
        }
        try {
            Store(context, name).use { store ->
                assertEquals("Earlier message", store.recent().lines["home"]!!.single().text)
                val reply = Line(2, "agent", "Allow a fix?", "reply", 2, suggestedReplies = listOf("Fix Inbox filters", "Keep paused"))
                store.message("one", "home", reply)
                store.openItems("one", listOf(OpenItem("one", "q", "question", "lab/main", "home", "Allow a fix?", 2, true)))
                store.openItems("two", listOf(OpenItem("two", "q", "question", "lab/main", "other", "Choose a next step", 1, false)))
            }
            Store(context, name).use { restored ->
                val saved = restored.recent()
                assertEquals(2, saved.openItems.size)
                assertEquals(listOf("Fix Inbox filters", "Keep paused"), saved.lines["home"]!!.last().suggestedReplies)
                restored.openItems("one", emptyList())
                assertEquals(listOf("two"), restored.recent().openItems.map { it.host })
                assertEquals(setOf("one", "two"), restored.recent().indexedHosts)
                restored.forget("two")
                assertTrue(restored.recent().openItems.isEmpty())
                assertEquals(setOf("one"), restored.recent().indexedHosts)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun versions_9_and_10_upgrade_and_corrupt_cached_items_are_removed_without_losing_history() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in listOf(9, 10)) {
            val name = "needs-you-$version-${UUID.randomUUID()}.db"
            val path = context.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                CREATE.filterNot { it.startsWith("CREATE TABLE open_item_hosts") }.forEach(old::execSQL)
                if (version == 10) old.execSQL("CREATE TABLE open_item_hosts (host TEXT PRIMARY KEY)")
                old.execSQL("INSERT INTO conversations(id,host,agent) VALUES ('home','one','lab/main')")
                old.execSQL("INSERT INTO messages(host,seq,conversation,sender,text,kind,time) VALUES ('one',1,'home','agent','History preserved','reply',1)")
                val good = OpenItem("one", "good", "question", "lab/main", "home", "Choose", 1, false, "question:1")
                old.execSQL("INSERT INTO open_items(host,item_key,conversation,info) VALUES (?,?,?,?)", arrayOf("one", good.key, "home", good.json().toString()))
                old.execSQL("INSERT INTO open_items(host,item_key,info) VALUES ('one','broken','{')")
                old.execSQL("INSERT INTO open_items(host,item_key,info) VALUES ('one','future','{\"kind\":\"future\"}')")
                if (version == 10) old.execSQL("INSERT INTO open_item_hosts(host) VALUES ('one')")
                old.version = version
            }
            try {
                Store(context, name).use { store ->
                    val saved = store.recent()
                    assertEquals("History preserved", saved.lines["home"]!!.single().text)
                    assertEquals(listOf("good"), saved.openItems.map { it.id })
                    assertEquals(setOf("one"), saved.indexedHosts)
                    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM open_items", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
                    store.openItems("one", saved.openItems, mapOf("policy" to "policy:2:failed"))
                }
                Store(context, name).use { store ->
                    assertEquals("policy:2:failed", store.recent().policyCoverage["one"]?.get("policy"))
                    store.deleteConversation(ConversationKey("one", "home"))
                    assertTrue(store.recent().openItems.isEmpty())
                    store.forget("one")
                    assertTrue(store.recent().policyCoverage.isEmpty())
                }
            } finally { context.deleteDatabase(name) }
        }
    }

}
