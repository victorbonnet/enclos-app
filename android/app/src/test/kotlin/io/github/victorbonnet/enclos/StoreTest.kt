package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTest {
    @Test
    fun version_2_gets_a_groups_members_and_each_messages_speaker() {
        assertEquals(
            listOf("ALTER TABLE conversations ADD COLUMN members TEXT", "ALTER TABLE messages ADD COLUMN speaker TEXT") + upgrades(3),
            upgrades(2),
        )
    }

    @Test
    fun version_3_gets_a_conversations_archived_state_and_revision() {
        assertEquals(
            listOf(
                "ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE conversations ADD COLUMN revision INTEGER NOT NULL DEFAULT 0",
            ) + upgrades(4),
            upgrades(3),
        )
    }

    @Test
    fun version_4_gets_an_agents_display_name_and_its_revision() {
        assertEquals(
            listOf(
                "ALTER TABLE agents ADD COLUMN display_name TEXT",
                "ALTER TABLE agents ADD COLUMN name_revision INTEGER NOT NULL DEFAULT 0 CHECK(name_revision>=0)",
            ),
            upgrades(4),
        )
    }

    @Test
    fun version_1_goes_through_each_step_in_order() {
        assertEquals(listOf("ALTER TABLE conversations ADD COLUMN home INTEGER NOT NULL DEFAULT 0") + upgrades(2), upgrades(1))
        assertEquals(emptyList<String>(), upgrades(STORE_VERSION))
    }

    @Test
    fun an_upgrade_only_adds_columns_a_new_database_has_too() {
        val adding = Regex("""ALTER TABLE (\w+) ADD COLUMN (.+)""")
        for (statement in upgrades(1)) {
            val (table, column) = adding.matchEntire(statement)?.destructured ?: error("not an addition: $statement")
            val created = CREATE.first { it.startsWith("CREATE TABLE $table (") }
            assertTrue("$table lacks $column", created.contains(column))
        }
    }
}
