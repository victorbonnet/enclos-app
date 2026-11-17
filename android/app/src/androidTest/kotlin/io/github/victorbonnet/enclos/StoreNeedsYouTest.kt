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
            CREATE.filterNot { it.startsWith("CREATE TABLE open_items") }.forEach {
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
                restored.forget("two")
                assertTrue(restored.recent().openItems.isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
}
