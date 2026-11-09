package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.routineText(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
private fun JSONObject.routineStrings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()

data class RoutineChoice(val id: String, val label: String)
data class RoutineModelChoice(val id: String, val label: String, val provider: String?)
fun routineModelKey(provider: String?, model: String): String = "${provider?.length ?: -1}:${provider.orEmpty()}${model.length}:$model"

fun routineSelectionProblem(selected: List<String>, value: String): String? {
    val id = value.trim()
    return when {
        id.isBlank() -> "Enter one identifier."
        id.toByteArray(Charsets.UTF_8).size > 128 || '\u0000' in id -> "An identifier must fit in 128 bytes and contain no null character."
        id !in selected && selected.size >= 32 -> "Choose at most 32 identifiers."
        else -> null
    }
}

fun addRoutineSelection(selected: List<String>, value: String): List<String> {
    require(routineSelectionProblem(selected, value) == null) { routineSelectionProblem(selected, value).orEmpty() }
    val id = value.trim()
    return if (id in selected) selected else selected + id
}

data class RoutineOptions(val timezone: String, val features: Set<String>, val skills: List<RoutineChoice>, val models: List<RoutineModelChoice>, val toolsets: List<RoutineChoice>) {
    companion object {
        fun from(m: JSONObject): RoutineOptions = RoutineOptions(m.getString("timezone"), m.routineStrings("features").toSet(), choices(m.optJSONArray("skills")), m.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { model -> RoutineModelChoice(model.getString("id"), model.getString("label"), model.routineText("provider")) } } }.orEmpty(), choices(m.optJSONArray("toolsets")))
        fun choices(a: JSONArray?): List<RoutineChoice> = a?.let { (0 until it.length()).map { i -> it.getJSONObject(i).let { c -> RoutineChoice(c.getString("id"), c.getString("label")) } } }.orEmpty()
    }
}

data class RoutineSummary(
    val id: String,
    val revision: String,
    val name: String?,
    val schedule: RoutineSchedule,
    val enabled: Boolean,
    val preview: String,
    val delivery: String,
    val editable: Boolean,
    val nextRun: String?,
    val lastRun: String?,
    val lastStatus: String?,
    val state: String? = null,
    val pausedReason: String? = null,
    val lastError: String? = null,
) {
    val title: String get() = name ?: preview.take(80).ifBlank { "Unnamed routine" }
    companion object {
        fun from(m: JSONObject) = RoutineSummary(m.getString("id"), m.getString("revision"), m.routineText("name"), routineScheduleFrom(m.getJSONObject("schedule")), m.getBoolean("enabled"), m.optString("prompt_preview"), m.optString("delivery_label"), m.optBoolean("editable"), m.routineText("next_run_at"), m.routineText("last_run_at"), m.routineText("last_status"), m.routineText("state"), m.routineText("paused_reason"), m.routineText("last_error"))
    }
}

data class RoutineDefinition(
    val name: String?,
    val prompt: String,
    val schedule: RoutineSchedule,
    val delivery: String?,
    val skills: List<String> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
    val toolsets: List<String>? = null,
    val script: String? = null,
    val scriptOnly: Boolean = false,
    val workingDirectory: String? = null,
) {
    fun selectModel(choice: RoutineModelChoice?): RoutineDefinition = copy(model = choice?.id, provider = choice?.provider)

    fun json(): JSONObject = JSONObject()
        .put("name", name ?: JSONObject.NULL).put("prompt", prompt).put("schedule", schedule.json())
        .put("delivery", delivery ?: JSONObject.NULL).put("skills", JSONArray(skills))
        .put("model", model ?: JSONObject.NULL).put("provider", provider ?: JSONObject.NULL)
        .put("toolsets", toolsets?.let(::JSONArray) ?: JSONObject.NULL)
        .put("script", script ?: JSONObject.NULL).put("script_only", scriptOnly)
        .put("working_directory", workingDirectory ?: JSONObject.NULL)

    fun problem(): String? {
        fun fits(value: String?, limit: Int) = value == null || (value.toByteArray(Charsets.UTF_8).size <= limit && '\u0000' !in value)
        return when {
            !fits(name, 512) -> "The name is too long (512 bytes maximum)."
            !fits(prompt, 8192) -> "The instruction is too long (8192 bytes maximum)."
            scriptOnly && script.isNullOrBlank() -> "Choose a script for a script-only routine."
            !scriptOnly && prompt.isBlank() && skills.isEmpty() && script.isNullOrBlank() -> "Enter an instruction or choose a skill or script."
            listOf(model, provider, script, workingDirectory).any { !fits(it, 512) } -> "A model, provider or path is too long (512 bytes maximum)."
            skills.size > 32 || (toolsets?.size ?: 0) > 32 -> "Choose at most 32 skills and 32 toolsets."
            (skills + toolsets.orEmpty()).any { !fits(it, 128) } -> "A skill or toolset identifier is too long (128 bytes maximum)."
            !workingDirectory.isNullOrBlank() && !workingDirectory.startsWith('/') -> "The working directory must be an absolute path."
            else -> null
        }
    }

    companion object {
        fun from(m: JSONObject) = RoutineDefinition(m.routineText("name"), m.optString("prompt"), routineScheduleFrom(m.getJSONObject("schedule")), m.routineText("delivery"), m.routineStrings("skills"), m.routineText("model"), m.routineText("provider"), if (m.isNull("toolsets")) null else m.routineStrings("toolsets"), m.routineText("script"), m.optBoolean("script_only"), m.routineText("working_directory"))
    }
}

data class Routine(val summary: RoutineSummary, val definition: RoutineDefinition) {
    companion object { fun from(m: JSONObject) = Routine(RoutineSummary.from(m), RoutineDefinition.from(m.getJSONObject("definition"))) }
}

data class RoutineList(
    val items: List<RoutineSummary> = emptyList(),
    val options: RoutineOptions? = null,
    val deliveries: List<RoutineChoice> = emptyList(),
    val nextCursor: String? = null,
    val fresh: Boolean = false,
    val deliveriesLimited: Boolean = false,
)

fun sameRoutineRevision(current: RoutineSummary?, candidate: RoutineSummary): Boolean = current != null && current.id == candidate.id && current.revision == candidate.revision

/** A full get may have a newer revision than the list that opened it. */
fun mergeRoutineSummary(items: List<RoutineSummary>, found: RoutineSummary): List<RoutineSummary> = items.map { if (it.id == found.id) found else it }

fun routineScheduleFrom(m: JSONObject): RoutineSchedule = when (m.getString("kind")) {
    "cron" -> RoutineSchedule.Cron(m.getString("expression"))
    "interval" -> RoutineSchedule.Interval(m.getLong("minutes"))
    "once" -> RoutineSchedule.Once(m.getString("at"))
    else -> throw IllegalArgumentException("This routine uses a schedule this app cannot read.")
}

fun RoutineSchedule.json(): JSONObject = when (this) {
    is RoutineSchedule.Cron -> JSONObject().put("kind", "cron").put("expression", expression)
    is RoutineSchedule.Interval -> JSONObject().put("kind", "interval").put("minutes", minutes)
    is RoutineSchedule.Once -> JSONObject().put("kind", "once").put("at", at)
}

fun RoutineSchedule.label(): String = when (this) {
    is RoutineSchedule.Cron -> {
        val draft = RoutineScheduleDraft.from(this, "UTC")
        when (draft.frequency) {
            RoutineFrequency.Daily -> "Daily at ${draft.time}"
            RoutineFrequency.Weekly -> "${draft.weekdays.sortedBy { if (it == 0) 7 else it }.joinToString(", ") { listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[it] }} at ${draft.time}"
            RoutineFrequency.Monthly -> "Monthly on day ${draft.monthday} at ${draft.time}"
            else -> expression
        }
    }
    is RoutineSchedule.Interval -> {
        val count = if (minutes % 1440L == 0L) minutes / 1440L else if (minutes % 60L == 0L) minutes / 60L else minutes
        val unit = if (minutes % 1440L == 0L) "day" else if (minutes % 60L == 0L) "hour" else "minute"
        "Every $count $unit${if (count == 1L) "" else "s"}"
    }
    is RoutineSchedule.Once -> "Once · $at"
}
