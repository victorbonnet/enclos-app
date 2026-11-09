package io.github.victorbonnet.enclos

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(model: AppModel, address: String, back: () -> Unit) {
    val client = model.routines
    val p = palette
    val live = client.available(address)
    val list = client.lists[address]
    val busy = address in client.pending
    val canChange = client.canChange(address)
    var creating by rememberSaveable(address) { mutableStateOf(false) }
    var selected by rememberSaveable(address) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<RoutineSummary?>(null) }
    var editorDirty by remember { mutableStateOf(false) }
    var discardSheet by remember { mutableStateOf(false) }
    LaunchedEffect(address, live) { if (live) client.refresh(address) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AppBar {
            IconButton(Glyphs.back, "Back", back)
            Text("Routines", style = Type.name, color = p.text, modifier = Modifier.weight(1f))
            TextButton(onClick = { client.refresh(address) }, enabled = live && !busy) { Text("Refresh") }
            if (canChange && list?.options != null) IconButton(Glyphs.plus, "New routine", { creating = true })
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(address, style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            Text("Routines run on this agent's host, including while the app is closed.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(horizontal = 16.dp))
            list?.options?.let { Text("Schedule time zone: ${it.timezone}", style = Type.small, color = p.textFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (!live) Text("Connect to an agent that supports routines to read or change its schedules.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            else if (list != null && !list.fresh && !busy) Text("This list is out of date. Refresh before making changes.", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            if (busy) Text(if (client.pending[address]?.operation == "list") "Loading routines…" else "Waiting for the host…", style = Type.small, color = p.textMuted, modifier = Modifier.padding(16.dp))
            client.errors[address]?.let { Text(it, style = Type.small, color = p.text, modifier = Modifier.padding(16.dp)) }
            client.notices[address]?.let { Text(it, style = Type.small, color = p.accent, modifier = Modifier.padding(16.dp)) }
            if (list?.fresh == true && list.items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Glyph(Glyphs.clock, p.textFaint, size = 40.dp)
                    Text("Nothing scheduled", style = Type.heading, color = p.text)
                    Text("Routines this agent runs on a schedule appear here.", style = Type.small, color = p.textMuted)
                    TextButton(onClick = { creating = true }, enabled = canChange) { Text("+ New routine") }
                }
            }
            list?.items?.forEach { routine ->
                SettingRow(Modifier.clickable(enabled = canChange, onClickLabel = "Open ${routine.title}") { selected = routine.id; client.get(address, routine.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(routine.title, style = Type.name, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(routine.schedule.label(), style = Type.small, color = p.textMuted)
                        }
                        if (routine.enabled || routine.state == null || routine.state == "paused") Switch(checked = routine.enabled, onCheckedChange = { client.act(address, routine, "set_enabled", it) }, enabled = canChange && routine.editable)
                    }
                    if (routine.name != null && routine.preview.isNotBlank()) Text(routine.preview, style = Type.small, color = p.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Send to: ${routine.delivery}", style = Type.small, color = p.textFaint)
                    if (!routine.editable) Text("Managed outside enclos · read only", style = Type.small, color = p.textFaint)
                    RoutineStatus(routine)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selected = routine.id; client.get(address, routine.id) }, enabled = canChange) { Text(if (routine.editable) "Edit" else "Details") }
                        if (list.options?.features?.contains("run") == true) TextButton(onClick = { client.act(address, routine, "run") }, enabled = canChange && routine.editable && (routine.enabled || (routine.state == "paused" && "run_paused" in list.options.features))) { Text("Run now") }
                        TextButton(onClick = { deleting = routine }, enabled = canChange && routine.editable) { Text("Delete") }
                    }
                }
            }
            if (list?.nextCursor != null) TextButton(onClick = { client.refresh(address, more = true) }, enabled = canChange, modifier = Modifier.padding(start = 8.dp)) { Text("Load more") }
        }
    }
    val selectedId = selected
    if (creating || selectedId != null) {
        val routine = selectedId?.let { client.details[client.key(address, it)] }
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
            if (target == SheetValue.Hidden && (busy || editorDirty)) { if (!busy) discardSheet = true; false } else true
        })
        val close: () -> Unit = { creating = false; selected = null; editorDirty = false }
        ModalBottomSheet(onDismissRequest = { if (!busy) { if (editorDirty) discardSheet = true else close() } }, sheetState = sheet, containerColor = p.bg) {
            if (!creating && routine == null) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Routine", style = Type.heading, color = p.text)
                    Text(if (busy) "Loading its full definition…" else client.errors[address] ?: "Refresh and open the routine again.", style = Type.small, color = p.textMuted)
                    TextButton(onClick = { selected = null }, enabled = !busy) { Text("Close") }
                }
            } else if (list?.options != null) {
                RoutineEditor(model, address, routine, list.options, list.deliveries, dirty = { editorDirty = it }, dismiss = close)
            }
        }
    }
    if (discardSheet) AlertDialog(onDismissRequest = { discardSheet = false }, title = { Text("Discard changes?") }, confirmButton = { TextButton(onClick = { discardSheet = false; editorDirty = false; creating = false; selected = null }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discardSheet = false }) { Text("Keep editing") } })
    deleting?.let { routine ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete routine?") },
            text = { Text("${routine.title} and its saved runtime output may be removed. Its previous conversation messages stay. An execution already running is not stopped.") },
            confirmButton = { TextButton(enabled = canChange, onClick = { client.act(address, routine, "delete"); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun routineTime(value: String): String = runCatching { OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm XXX")) }.getOrDefault(value)

@Composable
private fun RoutineStatus(routine: RoutineSummary) {
    val p = palette
    Text(routine.state?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: if (routine.enabled) "Enabled" else "Disabled", style = Type.small, color = p.textMuted)
    if (routine.enabled) routine.nextRun?.let { Text("Next: ${routineTime(it)}", style = Type.small, color = p.textMuted) }
    routine.pausedReason?.let { Text(it, style = Type.small, color = p.textMuted) }
    routine.lastRun?.let { Text("Last: ${routineTime(it)}", style = Type.small, color = p.textFaint) }
    routine.lastStatus?.let { Text("Last result: $it", style = Type.small, color = p.textMuted) }
    routine.lastError?.let { Text("Last error: $it", style = Type.small, color = p.text) }
}

@Composable
private fun RoutineEditor(model: AppModel, address: String, routine: Routine?, options: RoutineOptions, deliveries: List<RoutineChoice>, dirty: (Boolean) -> Unit, dismiss: () -> Unit) {
    val client = model.routines
    val p = palette
    val editKey = routine?.summary?.id ?: "new"
    val original = remember(editKey) { routine?.summary }
    val initial = remember(editKey) { routine?.definition ?: RoutineDefinition(null, "", RoutineSchedule.Cron("0 9 * * *"), null) }
    val editorTimezone = remember(editKey) { options.timezone }
    val initialSchedule = remember(editKey) { RoutineScheduleDraft.from(initial.schedule, editorTimezone) }
    var draft by remember(editKey) { mutableStateOf(initial) }
    var schedule by remember(editKey) { mutableStateOf(initialSchedule) }
    var advanced by rememberSaveable(editKey) { mutableStateOf(false) }
    var submitted by rememberSaveable(editKey) { mutableStateOf<String?>(null) }
    var cancelConfirm by remember { mutableStateOf(false) }
    val busy = address in client.pending
    val editable = original?.editable != false
    val revisionCurrent = original == null || sameRoutineRevision(client.lists[address]?.items?.firstOrNull { it.id == original.id }, original)
    val timezoneCurrent = editorTimezone == options.timezone
    val enabled = client.canChange(address) && editable && revisionCurrent && timezoneCurrent
    val changed = draft != initial || schedule != initialSchedule
    SideEffect { dirty(changed && editable) }
    val requestDismiss = { if (changed && editable) cancelConfirm = true else dismiss() }
    val normalized = runCatching { schedule.schedule(editorTimezone) }.getOrNull()
    val definition = normalized?.let { draft.copy(schedule = it) }
    val problem = schedule.problem(editorTimezone) ?: definition?.problem()
    LaunchedEffect(client.receipts[address], submitted) { if (submitted != null && client.receipts[address] == submitted) dismiss() }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).windowInsetsPadding(WindowInsets.ime).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (!editable) "Routine details" else if (routine == null) "New routine" else "Edit routine", style = Type.heading, color = p.text, modifier = Modifier.weight(1f))
            TextButton(onClick = requestDismiss, enabled = !busy) { Text("Close") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!editable) Text("This routine is managed outside enclos. Its schedule, delivery and other settings are read only.", style = Type.small, color = p.textMuted)
            if (!client.canChange(address)) Text("Refresh the list before saving. An unconfirmed request will not be sent again automatically.", style = Type.small, color = p.textMuted)
            if (!revisionCurrent && !busy) Text("This routine changed or is no longer in the current list. Close and open it again to edit its current definition. Your draft is still here.", style = Type.small, color = p.textMuted)
            if (!timezoneCurrent) Text("The runtime's time zone changed from $editorTimezone to ${options.timezone}. Close and reopen the editor before saving with the new time zone.", style = Type.small, color = p.textMuted)
            if (!client.canChange(address) && !busy) TextButton(onClick = { client.refresh(address) }, enabled = client.available(address)) { Text("Refresh routines") }
            OutlinedTextField(draft.name.orEmpty(), { draft = draft.copy(name = it.takeUnless(String::isBlank)) }, enabled = enabled, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RoutineScheduleFields(schedule, { schedule = it }, editorTimezone, enabled)
            OutlinedTextField(draft.prompt, { draft = draft.copy(prompt = it) }, enabled = enabled && !draft.scriptOnly, label = { Text("What should it do?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (draft.scriptOnly) Text("The script runs without an agent and its output is delivered verbatim.", style = Type.small, color = p.textMuted)
            if (editable) {
                val savedDelivery = draft.delivery?.takeIf { id -> deliveries.none { it.id == id } }?.let { listOf(RoutineChoice(it, original?.delivery ?: it)) }.orEmpty()
                RoutineSelect("Send to", draft.delivery, listOf(RoutineChoice("", "Nowhere · local output only")) + savedDelivery + deliveries, enabled) { draft = draft.copy(delivery = it.takeUnless(String::isBlank)) }
                if (client.lists[address]?.deliveriesLimited == true) Text("The picker shows Home and the 50 most recently created conversations. This routine's saved destination is kept too.", style = Type.small, color = p.textMuted)
            }
            else Text("Send to: ${routine?.summary?.delivery}", style = Type.small, color = p.textMuted)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
            if (advanced) {
                val agentEnabled = enabled && !draft.scriptOnly
                if ("skills" in options.features) RoutineMultiSelect("Skills", draft.skills, options.skills, agentEnabled) { draft = draft.copy(skills = it) }
                else if (draft.skills.isNotEmpty()) RoutineReadOnly("Skills", draft.skills.joinToString(", "))
                if ("model" in options.features) {
                    if (options.models.isNotEmpty()) {
                        val key = if (draft.model == null && draft.provider == null) "default" else routineModelKey(draft.provider, draft.model.orEmpty())
                        val models = options.models.map { RoutineChoice(routineModelKey(it.provider, it.id), it.label) }
                        val saved = if (key != "default" && models.none { it.id == key }) listOf(RoutineChoice(key, "${draft.provider?.let { "$it / " }.orEmpty()}${draft.model ?: "Default model"} (saved value)")) else emptyList()
                        RoutineSelect("Model suggestions", key, listOf(RoutineChoice("default", "Agent default")) + saved + models, agentEnabled) { selected ->
                            if (selected == "default") draft = draft.selectModel(null)
                            else options.models.firstOrNull { routineModelKey(it.provider, it.id) == selected }?.let { draft = draft.selectModel(it) }
                        }
                    }
                    OutlinedTextField(draft.model.orEmpty(), { draft = draft.copy(model = it.takeUnless(String::isBlank)) }, enabled = agentEnabled, label = { Text("Model ID (blank follows agent)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else draft.model?.let { RoutineReadOnly("Model", it) }
                if ("provider" in options.features) OutlinedTextField(draft.provider.orEmpty(), { draft = draft.copy(provider = it.takeUnless(String::isBlank)) }, enabled = agentEnabled, label = { Text("Provider (blank follows agent)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else draft.provider?.let { RoutineReadOnly("Provider", it) }
                if ("toolsets" in options.features) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Use default toolsets", style = Type.label, color = p.text, modifier = Modifier.weight(1f))
                        Switch(draft.toolsets == null, { draft = draft.copy(toolsets = if (it) null else emptyList()) }, enabled = agentEnabled)
                    }
                    if (draft.toolsets != null) RoutineMultiSelect("Toolsets", draft.toolsets.orEmpty(), options.toolsets, agentEnabled) { draft = draft.copy(toolsets = it) }
                    Text(if (draft.toolsets == null) "The runtime's default tools are loaded." else if (draft.toolsets.isNullOrEmpty()) "No tools will be loaded." else "Only these toolsets will be loaded.", style = Type.small, color = p.textMuted)
                } else draft.toolsets?.let { RoutineReadOnly("Toolsets", it.joinToString(", ").ifBlank { "None" }) }
                if ("script_only" in options.features) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Script only", style = Type.label, color = p.text)
                            Text("Skip the agent; deliver the script's output.", style = Type.small, color = p.textMuted)
                        }
                        Switch(draft.scriptOnly, { draft = draft.copy(scriptOnly = it) }, enabled = enabled)
                    }
                } else if (draft.scriptOnly) RoutineReadOnly("Script only", "Enabled")
                if ("script" in options.features) {
                    OutlinedTextField(draft.script.orEmpty(), { draft = draft.copy(script = it.takeUnless(String::isBlank)) }, enabled = enabled, label = { Text("Script") }, placeholder = { Text("relative/path/in/scripts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("A path under the agent's scripts directory. The runtime validates it.", style = Type.small, color = p.textMuted)
                } else draft.script?.let { RoutineReadOnly("Script", it) }
                if ("working_directory" in options.features) OutlinedTextField(draft.workingDirectory.orEmpty(), { draft = draft.copy(workingDirectory = it.takeUnless(String::isBlank)) }, enabled = enabled, label = { Text("Working directory (absolute path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else draft.workingDirectory?.let { RoutineReadOnly("Working directory", it) }
            }
            routine?.summary?.let { RoutineStatus(it) }
            if (problem != null && editable) Text(problem, style = Type.small, color = p.textMuted)
            client.errors[address]?.let { Text(it, style = Type.small, color = p.text) }
            if (busy) Text("Waiting for the host…", style = Type.small, color = p.textMuted)
            Spacer(Modifier.height(8.dp))
        }
        if (editable) Button(
            onClick = { definition?.let { submitted = client.save(address, original, it) } },
            enabled = enabled && problem == null && definition != null,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) { Text(if (routine == null) "Create routine" else "Save routine") }
    }
    if (cancelConfirm) AlertDialog(onDismissRequest = { cancelConfirm = false }, title = { Text("Discard changes?") }, confirmButton = { TextButton(onClick = dismiss) { Text("Discard") } }, dismissButton = { TextButton(onClick = { cancelConfirm = false }) { Text("Keep editing") } })
}

@Composable
private fun RoutineReadOnly(label: String, value: String) {
    Text("$label: $value · kept unchanged", style = Type.small, color = palette.textMuted)
}

@Composable
private fun RoutineSelect(label: String, selected: String?, choices: List<RoutineChoice>, enabled: Boolean, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val value = selected.orEmpty()
    val shown = choices.firstOrNull { it.id == value }?.label ?: value
    Column {
        Text(label, style = Type.small, color = palette.textMuted)
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) { Text(shown.ifBlank { "None" }) }
            DropdownMenu(open, { open = false }) {
                if (value.isNotEmpty() && choices.none { it.id == value }) DropdownMenuItem(text = { Text("$value (saved value)") }, onClick = { open = false })
                choices.forEach { choice -> DropdownMenuItem(text = { Text((if (choice.id == value) "✓ " else "") + choice.label) }, onClick = { choose(choice.id); open = false }) }
            }
        }
    }
}

@Composable
private fun RoutineMultiSelect(label: String, selected: List<String>, choices: List<RoutineChoice>, enabled: Boolean, choose: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf("") }
    val all = selected.filter { id -> choices.none { it.id == id } }.map { RoutineChoice(it, "$it (saved value)") } + choices
    val problem = routineSelectionProblem(selected, entering)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Type.small, color = palette.textMuted)
        Box {
            TextButton(onClick = { open = true }, enabled = enabled && all.isNotEmpty()) { Text("Catalog suggestions") }
            DropdownMenu(open, { open = false }) {
                all.forEach { item -> DropdownMenuItem(text = { Text((if (item.id in selected) "✓ " else "") + item.label) }, onClick = { choose(if (item.id in selected) selected - item.id else selected + item.id) }) }
            }
        }
        if (selected.isEmpty()) Text("None selected", style = Type.small, color = palette.textFaint)
        selected.forEach { id ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(id, style = Type.small, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { choose(selected - id) }, enabled = enabled) { Text("Remove") }
            }
        }
        OutlinedTextField(entering, { entering = it }, enabled = enabled, label = { Text("Add ${label.dropLast(1).lowercase()} ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { choose(addRoutineSelection(selected, entering)); entering = "" }, enabled = enabled && problem == null) { Text("Add") }
        if (entering.isNotEmpty() && problem != null) Text(problem, style = Type.small, color = palette.textMuted)
        Text("Suggestions may be limited. You can enter one ID; the runtime checks it.", style = Type.small, color = palette.textFaint)
    }
}

@Composable
private fun RoutineScheduleFields(draft: RoutineScheduleDraft, change: (RoutineScheduleDraft) -> Unit, timezone: String, enabled: Boolean) {
    val p = palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("When", style = Type.label, color = p.text)
        RoutineFrequency.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { frequency -> FilterChip(selected = draft.frequency == frequency, onClick = { change(draft.copy(frequency = frequency)) }, enabled = enabled, label = { Text(frequency.label) }) }
            }
        }
        Text("Time zone: $timezone (set by the runtime)", style = Type.small, color = p.textMuted)
        when (draft.frequency) {
            RoutineFrequency.Daily -> RoutineTime(draft.time, enabled) { change(draft.copy(time = it)) }
            RoutineFrequency.Weekly -> {
                val labels = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 0 to "Sun")
                labels.chunked(4).forEach { days -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { days.forEach { (day, label) -> FilterChip(selected = day in draft.weekdays, onClick = { change(draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day)) }, enabled = enabled, label = { Text(label) }) } } }
                RoutineTime(draft.time, enabled) { change(draft.copy(time = it)) }
            }
            RoutineFrequency.Monthly -> {
                OutlinedTextField(draft.monthday, { change(draft.copy(monthday = it)) }, enabled = enabled, label = { Text("Day of month (1–31)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Months without this day are skipped.", style = Type.small, color = p.textMuted)
                RoutineTime(draft.time, enabled) { change(draft.copy(time = it)) }
            }
            RoutineFrequency.Interval -> {
                OutlinedTextField(draft.interval, { change(draft.copy(interval = it)) }, enabled = enabled, label = { Text("Repeat every") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RoutineSelect("Unit", draft.intervalUnit, listOf("minutes", "hours", "days").map { RoutineChoice(it, it.replaceFirstChar(Char::uppercase)) }, enabled) { change(draft.copy(intervalUnit = it)) }
            }
            RoutineFrequency.Once -> {
                RoutineDate(draft.date, timezone, enabled) { change(draft.copy(date = it)) }
                RoutineTime(draft.time, enabled) { change(draft.copy(time = it)) }
                draft.onceNotice(timezone)?.let { Text(it, style = Type.small, color = p.textMuted) }
            }
            RoutineFrequency.Custom -> {
                OutlinedTextField(draft.custom, { change(draft.copy(custom = it)) }, enabled = enabled, label = { Text("Cron expression") }, placeholder = { Text("0 9 * * 1-5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Minute · hour · day of month · month · weekday. The runtime validates the expression.", style = Type.small, color = p.textMuted)
            }
        }
        runCatching { draft.schedule(timezone) }.getOrNull()?.let { Text(it.label(), style = Type.small, color = p.textFaint) }
    }
}

@Composable
private fun RoutineTime(value: String, enabled: Boolean, change: (String) -> Unit) {
    val context = LocalContext.current
    val hour = value.substringBefore(':').toIntOrNull() ?: 9
    val minute = value.substringAfter(':', "").toIntOrNull() ?: 0
    TextButton(onClick = { TimePickerDialog(context, { _, h, m -> change(String.format(Locale.ROOT, "%02d:%02d", h, m)) }, hour, minute, true).show() }, enabled = enabled) { Text("Time: ${value.ifBlank { "Choose time" }}") }
}

@Composable
private fun RoutineDate(value: String, timezone: String, enabled: Boolean, change: (String) -> Unit) {
    val context = LocalContext.current
    val zone = runCatching { ZoneId.of(timezone) }.getOrNull()
    TextButton(onClick = {
        val date = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now(requireNotNull(zone)) }
        DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }, enabled = enabled && zone != null) { Text("Date: ${value.ifBlank { "Choose date" }}") }
}
