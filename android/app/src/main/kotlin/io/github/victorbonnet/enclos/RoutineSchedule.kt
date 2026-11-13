package io.github.victorbonnet.enclos

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schedule semantics shared by runtimes; the phone never runs a scheduler. */
sealed interface RoutineSchedule {
    data class Cron(val expression: String) : RoutineSchedule
    data class Interval(val minutes: Long) : RoutineSchedule
    data class Once(val at: String) : RoutineSchedule
}

enum class RoutineFrequency(val label: String) {
    Daily("Daily"), Weekly("Weekly"), Monthly("Monthly"), Interval("Repeat"), Once("Once"), Custom("Custom"),
}

/** The six native form modes, independent of a runtime's schedule grammar. */
data class RoutineScheduleDraft(
    val frequency: RoutineFrequency = RoutineFrequency.Daily,
    val time: String = "09:00",
    val weekdays: Set<Int> = setOf(1),
    val monthday: String = "1",
    val interval: String = "1",
    val intervalUnit: String = "hours",
    val date: String = "",
    val custom: String = "",
    val originalOnceAt: String? = null,
    val originalOnceDate: String? = null,
    val originalOnceTime: String? = null,
    val unreadableOnce: String? = null,
) {
    fun schedule(timezone: String): RoutineSchedule {
        val (hour, minute) = if (frequency in listOf(RoutineFrequency.Daily, RoutineFrequency.Weekly, RoutineFrequency.Monthly)) {
            val parts = time.split(':')
            require(parts.size == 2) { "Choose a time." }
            val h = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            require(h != null && h in 0..23 && m != null && m in 0..59) { "Choose a valid time." }
            h to m
        } else 0 to 0
        return when (frequency) {
            RoutineFrequency.Daily -> RoutineSchedule.Cron("$minute $hour * * *")
            RoutineFrequency.Weekly -> {
                require(weekdays.isNotEmpty() && weekdays.all { it in 0..6 }) { "Choose at least one weekday." }
                RoutineSchedule.Cron("$minute $hour * * ${weekdays.sorted().joinToString(",")}")
            }
            RoutineFrequency.Monthly -> {
                val day = monthday.toIntOrNull()
                require(day != null && day in 1..31) { "Choose a day from 1 to 31." }
                RoutineSchedule.Cron("$minute $hour $day * *")
            }
            RoutineFrequency.Interval -> {
                val count = interval.toLongOrNull()
                require(count != null && count > 0) { "Repeat must be a positive whole number." }
                val factor = when (intervalUnit) { "minutes" -> 1L; "hours" -> 60L; "days" -> 1440L; else -> throw IllegalArgumentException("Choose a repeat unit.") }
                val minutes = try { Math.multiplyExact(count, factor) } catch (_: ArithmeticException) { throw IllegalArgumentException("The repeat interval is too large.") }
                require(minutes <= 525600L) { "Repeat intervals can be at most one year." }
                RoutineSchedule.Interval(minutes)
            }
            RoutineFrequency.Once -> {
                if (originalOnceAt != null && date == originalOnceDate && time == originalOnceTime) return RoutineSchedule.Once(originalOnceAt)
                val local = try { LocalDateTime.parse("${date}T$time") } catch (_: Exception) { throw IllegalArgumentException("Choose a date and time.") }
                val zone = try { ZoneId.of(timezone) } catch (_: Exception) { throw IllegalArgumentException("The runtime has not supplied a usable time zone. Refresh before choosing a one-time schedule.") }
                val offsets = zone.rules.getValidOffsets(local)
                require(offsets.isNotEmpty()) { "This time does not exist because the clocks change. Choose another time." }
                RoutineSchedule.Once(OffsetDateTime.of(local, offsets.first()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            }
            RoutineFrequency.Custom -> {
                val expression = custom.trim()
                require(expression.split(Regex("\\s+")).size == 5) { "Enter a five-field cron expression." }
                require(expression.toByteArray(Charsets.UTF_8).size <= 128 && '\u0000' !in expression) { "The cron expression is too long or contains a null character." }
                RoutineSchedule.Cron(expression)
            }
        }
    }

    fun problem(timezone: String): String? = try { schedule(timezone); null } catch (e: IllegalArgumentException) { e.message }

    fun onceNotice(timezone: String): String? {
        if (frequency != RoutineFrequency.Once) return null
        if (unreadableOnce != null && date == originalOnceDate && time == originalOnceTime) return unreadableOnce
        if (originalOnceAt != null && date == originalOnceDate && time == originalOnceTime) return "Saved instant: $originalOnceAt. It stays unchanged unless you change its date or time."
        return runCatching {
            val local = LocalDateTime.parse("${date}T$time")
            val offsets = ZoneId.of(timezone).rules.getValidOffsets(local)
            if (offsets.size > 1) "This time occurs twice when the clocks change. The first occurrence (${offsets.first()}) will be used." else null
        }.getOrNull()
    }

    companion object {
        /** Only recognise expressions the simple controls can reproduce exactly. */
        fun from(schedule: RoutineSchedule, timezone: String): RoutineScheduleDraft = when (schedule) {
            is RoutineSchedule.Interval -> {
                val unit = if (schedule.minutes % 1440L == 0L) "days" else if (schedule.minutes % 60L == 0L) "hours" else "minutes"
                val divisor = if (unit == "days") 1440L else if (unit == "hours") 60L else 1L
                RoutineScheduleDraft(frequency = RoutineFrequency.Interval, interval = (schedule.minutes / divisor).toString(), intervalUnit = unit)
            }
            is RoutineSchedule.Once -> {
                val at = runCatching { OffsetDateTime.parse(schedule.at).atZoneSameInstant(ZoneId.of(timezone)) }.getOrNull()
                val date = at?.toLocalDate()?.toString().orEmpty()
                val time = at?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
                RoutineScheduleDraft(frequency = RoutineFrequency.Once, date = date, time = time, originalOnceAt = schedule.at, originalOnceDate = date, originalOnceTime = time, unreadableOnce = if (at == null) "The saved instant or runtime time zone cannot be displayed. Its original value (${schedule.at}) will be kept unless you choose another schedule." else null)
            }
            is RoutineSchedule.Cron -> {
                val fields = schedule.expression.split(Regex("\\s+"))
                val minute = fields.getOrNull(0)?.toIntOrNull()
                val hour = fields.getOrNull(1)?.toIntOrNull()
                val custom = RoutineScheduleDraft(frequency = RoutineFrequency.Custom, custom = schedule.expression)
                if (fields.size != 5 || minute == null || minute !in 0..59 || hour == null || hour !in 0..23 || fields[3] != "*") custom
                else {
                    val time = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                    when {
                        fields[2] == "*" && fields[4] == "*" -> RoutineScheduleDraft(time = time)
                        fields[2] == "*" && fields[4].split(',').all { it.toIntOrNull() in 0..6 } -> RoutineScheduleDraft(frequency = RoutineFrequency.Weekly, time = time, weekdays = fields[4].split(',').map(String::toInt).toSet())
                        fields[4] == "*" && fields[2].toIntOrNull() in 1..31 -> RoutineScheduleDraft(frequency = RoutineFrequency.Monthly, time = time, monthday = fields[2])
                        else -> custom
                    }
                }
            }
        }
    }
}

/** An exact request receipt. Replies from another host, agent or operation are ignored. */
data class RoutineRequest(val id: String, val address: String, val operation: String, val routine: String? = null, val cursor: String? = null) {
    fun matches(host: String, of: String, agent: String): Boolean = id == of && address == "$host/$agent"

    fun accepts(result: String, returnedRoutine: String?): Boolean = when (operation) {
        "list" -> result == "list"
        "create" -> result == "routine"
        "get", "update", "set_enabled" -> result == "routine" && routine == returnedRoutine
        "delete" -> result == "deleted" && routine == returnedRoutine
        "run" -> result == "run" && routine == returnedRoutine
        else -> false
    }
}
