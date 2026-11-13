package io.github.victorbonnet.enclos

import org.junit.Assert.*
import org.junit.Test

class RoutineScheduleTest {
    @Test fun daily_weekly_and_monthly_keep_calendar_semantics() {
        assertEquals(RoutineSchedule.Cron("0 9 * * *"), RoutineScheduleDraft().schedule("Europe/Paris"))
        assertEquals(RoutineSchedule.Cron("15 8 * * 0,1,5"), RoutineScheduleDraft(frequency = RoutineFrequency.Weekly, time = "08:15", weekdays = setOf(5, 0, 1)).schedule("Europe/Paris"))
        assertEquals(RoutineSchedule.Cron("45 23 31 * *"), RoutineScheduleDraft(frequency = RoutineFrequency.Monthly, time = "23:45", monthday = "31").schedule("UTC"))
        assertNotNull(RoutineScheduleDraft(frequency = RoutineFrequency.Weekly, weekdays = emptySet()).problem("UTC"))
        assertNotNull(RoutineScheduleDraft(frequency = RoutineFrequency.Monthly, monthday = "32").problem("UTC"))
        assertNotNull(RoutineScheduleDraft(time = "24:00").problem("UTC"))
    }

    @Test fun intervals_are_typed_minutes_and_invalid_input_is_not_clamped() {
        assertEquals(RoutineSchedule.Interval(120), RoutineScheduleDraft(frequency = RoutineFrequency.Interval, interval = "2", intervalUnit = "hours").schedule("UTC"))
        assertEquals(RoutineSchedule.Interval(2880), RoutineScheduleDraft(frequency = RoutineFrequency.Interval, interval = "2", intervalUnit = "days").schedule("UTC"))
        listOf("0", "-1", "1.5", "9223372036854775807").forEach { value ->
            assertNotNull(RoutineScheduleDraft(frequency = RoutineFrequency.Interval, interval = value, intervalUnit = "days").problem("UTC"))
        }
        assertNotNull(RoutineScheduleDraft(frequency = RoutineFrequency.Interval, interval = "366", intervalUnit = "days").problem("UTC"))
    }

    @Test fun new_once_uses_runtime_zone_and_explains_clock_overlap() {
        val draft = RoutineScheduleDraft(frequency = RoutineFrequency.Once, date = "2026-10-25", time = "02:30")
        assertEquals(RoutineSchedule.Once("2026-10-25T02:30:00+02:00"), draft.schedule("Europe/Paris"))
        assertTrue(draft.onceNotice("Europe/Paris")!!.contains("first occurrence (+02:00)"))
        assertNotNull(draft.copy(date = "2026-03-29").problem("Europe/Paris"))
        assertNotNull(draft.problem("local"))
    }

    @Test fun editing_prompt_preserves_seconds_and_second_clock_overlap_instant() {
        val schedule = RoutineSchedule.Once("2026-10-25T02:30:47.123+01:00")
        val draft = RoutineScheduleDraft.from(schedule, "Europe/Paris")
        assertEquals("02:30", draft.time)
        assertEquals(schedule, draft.schedule("Europe/Paris"))
        assertEquals(schedule, draft.copy(frequency = RoutineFrequency.Daily).copy(frequency = RoutineFrequency.Once).schedule("Europe/Paris"))
        assertEquals(RoutineSchedule.Once("2026-10-25T03:00:00+01:00"), draft.copy(time = "03:00").schedule("Europe/Paris"))
    }

    @Test fun unreadable_existing_once_is_preserved_without_using_phone_timezone() {
        listOf(RoutineSchedule.Once("2026-10-25T02:30:47+01:00"), RoutineSchedule.Once("2026-10-25T02:30:47")).forEach { schedule ->
            val draft = RoutineScheduleDraft.from(schedule, "server-local")
            assertEquals(schedule, draft.schedule("server-local"))
            assertTrue(draft.onceNotice("server-local")!!.contains("kept unless"))
            assertNotNull(draft.copy(date = "2026-11-01", time = "09:00").problem("server-local"))
        }
    }

    @Test fun editing_unknown_cron_keeps_custom_expression() {
        val schedule = RoutineSchedule.Cron("*/17 6-18 * JAN,MAR MON-FRI")
        val draft = RoutineScheduleDraft.from(schedule, "UTC")
        assertEquals(RoutineFrequency.Custom, draft.frequency)
        assertEquals(schedule, draft.schedule("UTC"))
        assertEquals(RoutineSchedule.Interval(47), RoutineScheduleDraft.from(RoutineSchedule.Interval(47), "UTC").schedule("UTC"))
    }

    @Test fun definition_validation_counts_utf8_bytes_and_requires_script_only_content() {
        val definition = RoutineDefinition("Brief", "Check feeds", RoutineSchedule.Cron("0 9 * * *"), null)
        assertNull(definition.copy(prompt = "é".repeat(4096)).problem())
        assertNotNull(definition.copy(prompt = "é".repeat(4097)).problem())
        assertNotNull(definition.copy(scriptOnly = true, prompt = "").problem())
        assertNull(definition.copy(scriptOnly = true, prompt = "", script = "check.sh").problem())
        assertNull(definition.copy(prompt = "", skills = listOf("briefing")).problem())
        assertNull(definition.copy(prompt = "", script = "context.sh").problem())
    }

    @Test fun choosing_catalog_model_changes_provider_with_model_and_distinguishes_duplicate_ids() {
        val original = RoutineDefinition(null, "Check feeds", RoutineSchedule.Cron("0 9 * * *"), null, model = "old", provider = "old-provider")
        val first = RoutineModelChoice("same-model", "First / same-model", "first")
        val second = RoutineModelChoice("same-model", "Second / same-model", "second")
        assertNotEquals(routineModelKey(first.provider, first.id), routineModelKey(second.provider, second.id))
        val selected = original.selectModel(second)
        assertEquals("same-model", selected.model)
        assertEquals("second", selected.provider)
        assertNull(selected.selectModel(null).model)
        assertNull(selected.selectModel(null).provider)
    }

    @Test fun manual_skill_and_toolset_ids_keep_order_without_duplicates_and_obey_wire_limits() {
        val selected = listOf("first", "not-in-catalog")
        assertEquals(listOf("first", "not-in-catalog", "manual/id"), addRoutineSelection(selected, " manual/id "))
        assertEquals(selected, addRoutineSelection(selected, "first"))
        assertNotNull(routineSelectionProblem(selected, " "))
        assertNotNull(routineSelectionProblem(selected, "é".repeat(65)))
        assertNotNull(routineSelectionProblem(selected, "bad\u0000id"))
        val full = (1..32).map { "id-$it" }
        assertNotNull(routineSelectionProblem(full, "extra"))
        assertNull(routineSelectionProblem(full, "id-1"))
    }
}
