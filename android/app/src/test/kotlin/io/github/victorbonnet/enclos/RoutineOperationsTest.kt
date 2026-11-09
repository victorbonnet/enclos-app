package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class RoutineOperationsTest {
    private fun options(operations: Set<String>? = null, features: Set<String> = emptySet()) =
        RoutineOptions("UTC", features, emptyList(), emptyList(), emptyList(), operations)

    @Test fun legacyOptionsKeepExistingControlsAndRunFeature() {
        for (operation in listOf("get", "create", "update", "delete", "set_enabled")) assertTrue(options().allows(operation))
        assertFalse(options().allows("run"))
        assertTrue(options(features = setOf("run")).allows("run"))
    }

    @Test fun explicitReadOnlyAndPartialControlsNeverInventMutations() {
        for (operation in listOf("get", "create", "update", "delete", "set_enabled", "run")) assertFalse(options(emptySet()).allows(operation))
        val readOnly = options(setOf("get"))
        assertTrue(readOnly.allows("get"))
        assertFalse(readOnly.allows("create"))
        assertFalse(readOnly.allows("delete"))
        val partial = options(setOf("get", "update"))
        assertTrue(partial.allows("update"))
        assertFalse(partial.allows("delete"))
        assertFalse(partial.allows("run"))
    }

    @Test fun unknownControlNamesConferNoAbility() {
        assertFalse(options(setOf("future_operation")).allows("future_operation"))
        assertFalse(options(setOf("future_operation")).allows("create"))
    }

    @Test fun clientRefusesUnavailableWritesBeforeBuildingOrSendingRequests() {
        var sends = 0
        val client = RoutineClient({ _, _ -> sends++; true }, { true }, { true }, { _, _ -> })
        val address = "mac/lab/main"
        val summary = RoutineSummary("r1", "revision", "Native", RoutineSchedule.Interval(60), true, "Check", "Home", true, null, null, null)
        val definition = RoutineDefinition("Native", "Check", summary.schedule, null)
        client.lists[address] = RoutineList(items = listOf(summary), options = options(setOf("get")), fresh = true)
        assertTrue(client.canOperate(address, "get"))
        assertFalse(client.canOperate(address, "create"))
        assertNull(client.save(address, null, definition))
        assertNull(client.save(address, summary, definition))
        for (action in listOf("delete", "set_enabled", "run")) assertNull(client.act(address, summary, action, false))
        assertEquals(0, sends)
        assertTrue(client.pending.isEmpty())
        client.lists[address] = client.lists.getValue(address).copy(fresh = false)
        assertFalse(client.canOperate(address, "get"))
    }
}
