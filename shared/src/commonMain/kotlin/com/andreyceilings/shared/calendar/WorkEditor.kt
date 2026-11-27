package com.andreyceilings.shared.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.CeilingsScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private data class DayInput(val offset: String, val start: String, val end: String)

private data class WorkInput(
    val date: String, val zone: String, val title: String, val type: String, val status: WorkStatus,
    val notes: String, val project: String?, val client: String?, val contact: WorkContact,
    val ceilings: List<WorkCeiling>, val explicit: Boolean, val mode: WorkTimeMode, val days: List<DayInput>,
    val frequency: WorkFrequency?, val step: String, val repeatsOn: Set<DayOfWeek>,
    val endMode: Int, val endValue: String,
) {
    fun draft(includeRule: Boolean, original: CalendarWork?): CalendarWorkDraft {
        val localDate = LocalDate.parse(date)
        require(localDate.year in 1..9998)
        val rule = frequency?.takeIf { includeRule }?.let { freq ->
            WorkRecurrence(localDate, freq, step.toInt(),
                if (freq == WorkFrequency.CUSTOM) repeatsOn else emptySet(),
                until = if (endMode == 1) LocalDate.parse(endValue) else null,
                count = if (endMode == 2) endValue.toInt() else null,
                monthDay = original?.takeIf { it.draft.date == localDate }?.draft?.recurrence
                    ?.takeIf { it.frequency == freq }?.monthDay ?: localDate.day)
        }
        val schedule = WorkSchedule(mode, TimeZone.of(zone), days.map { day ->
            if (mode == WorkTimeMode.TIMED) WorkDay(day.offset.toInt(), LocalTime.parse(day.start), LocalTime.parse(day.end))
            else WorkDay(day.offset.toInt())
        }.sortedBy { it.offset })
        require(title.isNotBlank() && (type != "INSTALL" || project != null))
        return CalendarWorkDraft(localDate, schedule, title, type, status, notes, project, client, contact,
            ceilings, explicit, rule)
    }

    companion object {
        fun from(draft: CalendarWorkDraft, defaultDuration: Int): WorkInput {
            val rule = draft.recurrence
            return WorkInput(draft.date.toString(), draft.schedule.zone.id, draft.title, draft.typeCode, draft.status,
                draft.notes, draft.projectUuid, draft.clientUuid, draft.contactOverrides, draft.ceilings, draft.explicitCeilings,
                draft.schedule.mode, draft.schedule.days.map {
                    DayInput(it.offset.toString(), it.start?.shortTime() ?: "09:00",
                        it.end?.shortTime() ?: LocalTime.fromSecondOfDay(9 * 3600 + minOf(defaultDuration, 899) * 60).shortTime())
                }, rule?.frequency, rule?.step?.toString() ?: "1", rule?.weekdays ?: setOf(draft.date.dayOfWeek),
                if (rule?.until != null) 1 else if (rule?.count != null) 2 else 0,
                rule?.until?.toString() ?: rule?.count?.toString() ?: "")
        }
    }
}

@Composable
fun WorkEditor(
    store: CalendarStore,
    route: CalendarEditorRoute,
    onClose: () -> Unit,
    onSaved: (CalendarSaveResult.Saved, CalendarWorkDraft) -> Unit = { _, _ -> onClose() },
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
) = key(store, route) {
    val optionsRead = calendarState(remember(store) { store.options() })
    val options = optionsRead.data()
    val lifecycleScope = rememberCoroutineScope()
    var original by remember { mutableStateOf<CalendarWork?>(null) }
    var initial by remember { mutableStateOf<WorkInput?>(null) }
    var input by remember { mutableStateOf<WorkInput?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(CalendarEditScope.OCCURRENCE) }
    var conflict by remember { mutableStateOf<CalendarSaveResult.Conflicts?>(null) }
    val move = (route as? CalendarEditorRoute.Open)?.move == true &&
        (input?.days?.size ?: 1) == 1

    LaunchedEffect(options != null, reload) {
        if (options == null) return@LaunchedEffect
        try {
            val draft = when (route) {
                is CalendarEditorRoute.Open -> {
                    val work = store.exact(route.key)
                    if (work == null) {
                        loadFailed = true
                        return@LaunchedEffect
                    }
                    original = work
                    work.draft
                }
                is CalendarEditorRoute.Create -> {
                    val date = route.date ?: Clock.System.now().toLocalDateTime(options.settings.zone).date
                    val code = if (route.installation) "INSTALL" else "OTHER"
                    val end = 9 * 3600 + minOf(options.types.firstOrNull { it.code == code }?.durationMinutes ?: 60, 899) * 60
                    CalendarWorkDraft(date, WorkSchedule(WorkTimeMode.TIMED, options.settings.zone,
                        listOf(WorkDay(0, LocalTime(9, 0), LocalTime.fromSecondOfDay(end)))),
                        "", code, projectUuid = route.projectUuid)
                }
            }
            val data = WorkInput.from(draft, options.types.firstOrNull { it.code == draft.typeCode }?.durationMinutes ?: 60)
            initial = data
            input = data
            stale = false
            loadFailed = false
            if ((route as? CalendarEditorRoute.Open)?.delete == true && reload == 0) deleting = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }

    fun requestClose() {
        if (!busy) {
            if (input != initial) discarding = true else onClose()
        }
    }
    val recurring = original?.let { work ->
        val now = Clock.System.now()
        work.draft.recurrence != null && work.upcoming(now) &&
            work.draft.date >= now.toLocalDateTime(work.draft.schedule.zone).date
    } == true
    LaunchedEffect(recurring) { if (!recurring) scope = CalendarEditScope.OCCURRENCE }
    val data = input
    val draft = remember(data, scope, original) {
        runCatching { data?.draft(original == null || (recurring && scope == CalendarEditScope.THIS_AND_FOLLOWING), original) }.getOrNull()
    }
    val preview by produceState<CalendarConflictPreview?>(null, draft, original, scope) {
        value = null
        if (draft != null) {
            delay(280)
            try { value = store.previewConflicts(CalendarSave(draft, original?.key, original?.version, scope, replaceRecurrence = !move)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Explicit Save reports any unavailable writer. */ }
        }
    }
    val invalidTime = remember(draft) { draft != null && draft.schedule.mode == WorkTimeMode.TIMED &&
        draft.schedule.on(draft.date).any { it.interval == null } }
    val clockChange = remember(draft) { draft?.schedule?.on(draft.date)?.any { it.shiftedByZone } == true }
    val linkedContact by produceState(WorkContact(), data?.project, data?.client) {
        try { value = store.linkedContact(data?.project, data?.client) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = WorkContact() }
    }
    val activeCeilings by produceState(emptyList<WorkCeiling>(), data?.project, data?.type) {
        val project = data?.project
        value = if (project != null && data.type == "INSTALL") {
            try { store.ceilings(project) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        } else emptyList()
    }

    fun save(confirmation: CalendarConflictConfirmation? = null) {
        val valid = draft ?: return
        if (busy) return
        busy = true
        error = false
        lifecycleScope.launch {
            try {
                val source = original
                if (source != null && recurring && scope == CalendarEditScope.THIS_AND_FOLLOWING &&
                    valid.status == WorkStatus.CANCELLED) {
                    if (store.cancelFollowing(source.key, source.version)) onSaved(CalendarSaveResult.Saved(source.key), valid)
                    else stale = true
                } else when (val result = store.save(CalendarSave(valid, source?.key, source?.version, scope, replaceRecurrence = !move), confirmation)) {
                    is CalendarSaveResult.Saved -> { conflict = null; onSaved(result, valid) }
                    is CalendarSaveResult.Conflicts -> conflict = result
                    CalendarSaveResult.Stale -> { conflict = null; stale = true }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { conflict = null; error = true }
            finally { busy = false }
        }
    }

    val title = when {
        move -> Res.string.work_move
        route is CalendarEditorRoute.Create -> Res.string.work_add
        else -> Res.string.work_edit
    }
    if (move && data != null) {
        AlertDialog(onDismissRequest = ::requestClose,
            title = { Text(stringResource(title)) },
            text = {
                Column {
                    DiskDateField(data.date) { next ->
                        input = data.copy(date = next)
                    }
                    if (data.mode == WorkTimeMode.TIMED) {
                        val day = data.days.single()
                        val source = checkNotNull(original).draft.schedule.days.single()
                        val duration = checkNotNull(source.end).toSecondOfDay() - checkNotNull(source.start).toSecondOfDay()
                        WorkField(day.start, Res.string.work_start) { start ->
                            val next = runCatching { LocalTime.parse(start).toSecondOfDay() + duration }.getOrNull()
                            val end = next?.takeIf { it < 86400 }?.let { LocalTime.fromSecondOfDay(it).shortTime() }.orEmpty()
                            input = data.copy(days = listOf(day.copy(start = start, end = end)))
                        }
                    }
                    if (recurring) ScopeSelection(scope) { scope = it }
                    if (invalidTime || error || stale || clockChange || (draft == null && data != initial)) Text(stringResource(when {
                        stale -> Res.string.work_stale
                        clockChange -> Res.string.work_zone_change
                        error -> Res.string.work_save_failed
                        else -> Res.string.work_invalid
                    }), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = { save() }, enabled = !stale && draft != null && !invalidTime && !busy) {
                    Text(stringResource(Res.string.oc_save))
                }
            },
            dismissButton = { TextButton(onClick = ::requestClose) { Text(stringResource(Res.string.p5d_cancel)) } })
    } else {
        Dialog(onDismissRequest = ::requestClose, properties = properties) {
            CeilingsScreenScaffold(
                header = {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = ::requestClose, enabled = !busy) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.p5d_cancel))
                            }
                            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { save() }, enabled = !stale && !busy && draft != null && !invalidTime) {
                                Text(stringResource(Res.string.oc_save))
                            }
                        }
                    }
                },
            ) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loadFailed || optionsRead is CalendarRead.Unavailable) item {
                        Text(stringResource(Res.string.work_unavailable), color = MaterialTheme.colorScheme.error)
                    } else if (data == null || options == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else {
                        if (stale || error || invalidTime || clockChange) item {
                            Text(stringResource(when {
                                stale -> Res.string.work_stale
                                error -> Res.string.work_save_failed
                                invalidTime -> Res.string.work_invalid
                                else -> Res.string.work_zone_change
                            }), color = MaterialTheme.colorScheme.error)
                            if (stale) TextButton(onClick = { reload++ }) { Text(stringResource(Res.string.work_reload)) }
                        }
                        item("work-name") {
                            WorkField(data.title, Res.string.work_title, error = data.title.isBlank() && data != initial) {
                                input = data.copy(title = it)
                            }
                        }
                        if ((preview?.count ?: 0) > 0) item("work-conflict-preview") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary)
                                Text(stringResource(Res.string.work_conflict_count, preview?.count ?: 0),
                                    style = MaterialTheme.typography.labelMedium)
                                preview?.checkedThrough?.let { Text(stringResource(Res.string.work_checked_through, it.shortDate()),
                                    style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        item("work-type-status") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CalendarMenu(calendarTypeLabel(options.types.firstOrNull { it.code == data.type })) {
                                    options.types.filter { !it.archived || it.code == data.type }.forEach { type ->
                                        DropdownMenuItem(text = { Text(calendarTypeLabel(type)) },
                                            leadingIcon = { Icon(typeIcon(type), null, tint = typeColor(type)) },
                                            onClick = {
                                                val first = data.days.first()
                                                val start = runCatching { LocalTime.parse(first.start).toSecondOfDay() }.getOrDefault(9 * 3600)
                                                val end = LocalTime.fromSecondOfDay(minOf(86340, start + type.durationMinutes * 60)).shortTime()
                                                input = data.copy(type = type.code,
                                                    days = if (original == null && data.days.size == 1) listOf(first.copy(end = end)) else data.days,
                                                    ceilings = if (type.code != "INSTALL") emptyList() else data.ceilings,
                                                    explicit = type.code == "INSTALL" && data.explicit)
                                            })
                                    }
                                }
                                CalendarMenu(stringResource(data.status.label())) {
                                    WorkStatus.entries.forEach { state ->
                                        DropdownMenuItem(text = { Text(stringResource(state.label())) }, onClick = {
                                            input = data.copy(status = state)
                                        })
                                    }
                                }
                            }
                        }
                        item("work-date") {
                            DiskDateField(data.date) { input = data.copy(date = it) }
                            CalendarMenu(stringResource(data.mode.label())) {
                                WorkTimeMode.entries.forEach { mode ->
                                    DropdownMenuItem(text = { Text(stringResource(mode.label())) }, onClick = {
                                        input = data.copy(mode = mode)
                                    })
                                }
                            }
                        }
                        items(data.days.size, key = { "day-$it" }) { index ->
                            val day = data.days[index]
                            fun set(dayInput: DayInput) { input = data.copy(days = data.days.toMutableList().also { it[index] = dayInput }) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (index != 0) OutlinedTextField(day.offset, { set(day.copy(offset = it)) },
                                    modifier = Modifier.weight(.65f), label = { Text(stringResource(Res.string.work_date)) },
                                    prefix = { Text("+") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                else Text(data.date, style = MaterialTheme.typography.labelMedium)
                                if (data.mode == WorkTimeMode.TIMED) {
                                    OutlinedTextField(day.start, { set(day.copy(start = it)) }, Modifier.weight(1f),
                                        label = { Text(stringResource(Res.string.work_start)) }, singleLine = true)
                                    OutlinedTextField(day.end, { set(day.copy(end = it)) }, Modifier.weight(1f),
                                        label = { Text(stringResource(Res.string.work_end)) }, singleLine = true)
                                }
                                if (index != 0) IconButton(onClick = {
                                    input = data.copy(days = data.days.filterIndexed { i, _ -> i != index })
                                }) { Icon(Icons.Default.RemoveCircleOutline, stringResource(Res.string.work_day_remove)) }
                            }
                        }
                        item("work-more-days") {
                            TextButton(onClick = {
                                val next = (data.days.mapNotNull { it.offset.toIntOrNull() }.maxOrNull() ?: 0) + 1
                                if (next <= 365) input = data.copy(days = data.days + data.days.last().copy(offset = next.toString()))
                            }) { Icon(Icons.Default.Add, null); Text(stringResource(Res.string.work_day_add)) }
                            WorkField(data.zone, Res.string.work_zone, error = runCatching { TimeZone.of(data.zone) }.isFailure) {
                                input = data.copy(zone = it)
                            }
                        }
                        item("work-recurrence") {
                            if (recurring) ScopeSelection(scope) { scope = it }
                            if (original == null || recurring && scope == CalendarEditScope.THIS_AND_FOLLOWING)
                                RepeatSettings(data) { input = it }
                        }
                        item("work-links") {
                            CalendarLinkMenu(Res.string.work_object, data.project, options.projects) {
                                input = data.copy(project = it, ceilings = emptyList(), explicit = false)
                            }
                            CalendarLinkMenu(Res.string.work_client, data.client, options.clients) {
                                input = data.copy(client = it)
                            }
                            if (data.type == "INSTALL" && data.project != null) {
                                Text(stringResource(if (data.explicit) Res.string.work_selected_ceilings else Res.string.work_whole_project),
                                    style = MaterialTheme.typography.labelMedium)
                                (activeCeilings + data.ceilings.filter { it.removed }).distinctBy { it.uuid }.forEach { ceiling ->
                                    val selected = data.ceilings.any { it.uuid == ceiling.uuid }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(selected, { checked ->
                                            val changed = if (checked) data.ceilings + ceiling else data.ceilings.filterNot { it.uuid == ceiling.uuid }
                                            input = data.copy(ceilings = changed, explicit = changed.isNotEmpty())
                                        }, enabled = !ceiling.removed)
                                        Text(if (ceiling.removed) "${ceiling.label} · ${stringResource(Res.string.work_removed)}" else ceiling.label)
                                    }
                                }
                            }
                        }
                        item("work-contact") {
                            WorkField(data.contact.address.orEmpty(), Res.string.work_address, placeholder = linkedContact.address) {
                                input = data.copy(contact = data.contact.copy(address = it.ifEmpty { null }))
                            }
                            WorkField(data.contact.name.orEmpty(), Res.string.work_name, placeholder = linkedContact.name) {
                                input = data.copy(contact = data.contact.copy(name = it.ifEmpty { null }))
                            }
                            WorkField(data.contact.phone.orEmpty(), Res.string.work_phone, keyboard = KeyboardType.Phone,
                                placeholder = linkedContact.phone) {
                                input = data.copy(contact = data.contact.copy(phone = it.ifEmpty { null }))
                            }
                            WorkField(data.contact.email.orEmpty(), Res.string.work_email, keyboard = KeyboardType.Email,
                                placeholder = linkedContact.email) {
                                input = data.copy(contact = data.contact.copy(email = it.ifEmpty { null }))
                            }
                        }
                        item("work-notes") {
                            WorkField(data.notes, Res.string.work_notes, singleLine = false) { input = data.copy(notes = it) }
                            if (original != null) Row {
                                if (data.status.active) TextButton(onClick = { input = data.copy(status = WorkStatus.CANCELLED) }) {
                                    Text(stringResource(Res.string.work_cancel_action))
                                }
                                TextButton(onClick = { deleting = true }) {
                                    Text(stringResource(Res.string.oc_delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (draft == null && data != initial) Text(stringResource(Res.string.work_invalid),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }

    if (discarding) AlertDialog(onDismissRequest = { discarding = false },
        title = { Text(stringResource(Res.string.work_exit_title)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.work_discard)) } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(Res.string.work_delete_title)) },
        text = { if (recurring) ScopeSelection(scope) { scope = it } },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val source = original ?: return@TextButton
                busy = true
                lifecycleScope.launch {
                    try {
                        if (store.delete(source.key, source.version, scope)) onClose()
                        else { stale = true; deleting = false }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true; deleting = false }
                    finally { busy = false }
                }
            }) { Text(stringResource(Res.string.oc_delete), color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
    conflict?.let { found ->
        AlertDialog(onDismissRequest = { if (!busy) conflict = null },
            title = { Text(stringResource(Res.string.work_conflicts)) },
            text = {
                Column {
                    Text(stringResource(Res.string.work_conflict_count, found.confirmation.conflicts.size))
                    found.checkedThrough?.let { Text(stringResource(Res.string.work_checked_through, it.toString())) }
                    val zone = draft?.schedule?.zone
                    if (zone != null) found.confirmation.conflicts.take(4).forEach {
                        val start = it.otherInterval.start.toLocalDateTime(zone)
                        val end = it.otherInterval.endExclusive.toLocalDateTime(zone)
                        Text("${start.date.shortDate()} · ${start.time.shortTime()}–${end.time.shortTime()}")
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { save(found.confirmation) }) {
                Text(stringResource(Res.string.work_save_anyway))
            } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text(stringResource(Res.string.p5d_cancel)) } })
    }
}

@Composable
private fun ScopeSelection(scope: CalendarEditScope, onScope: (CalendarEditScope) -> Unit) {
    CalendarMenu(stringResource(if (scope == CalendarEditScope.OCCURRENCE) Res.string.work_only_this else Res.string.work_following)) {
        DropdownMenuItem(text = { Text(stringResource(Res.string.work_only_this)) }, onClick = { onScope(CalendarEditScope.OCCURRENCE) })
        DropdownMenuItem(text = { Text(stringResource(Res.string.work_following)) }, onClick = { onScope(CalendarEditScope.THIS_AND_FOLLOWING) })
    }
}

@Composable
private fun RepeatSettings(input: WorkInput, onChange: (WorkInput) -> Unit) {
    Text(stringResource(Res.string.work_recurrence), style = MaterialTheme.typography.labelMedium)
    CalendarMenu(stringResource(input.frequency?.label() ?: Res.string.work_once)) {
        DropdownMenuItem(text = { Text(stringResource(Res.string.work_once)) }, onClick = { onChange(input.copy(frequency = null)) })
        WorkFrequency.entries.forEach {
            DropdownMenuItem(text = { Text(stringResource(it.label())) }, onClick = { onChange(input.copy(frequency = it)) })
        }
    }
    if (input.frequency != null) {
        WorkField(input.step, Res.string.work_step, keyboard = KeyboardType.Number) { onChange(input.copy(step = it)) }
        if (input.frequency == WorkFrequency.CUSTOM) DaySelection(input.repeatsOn) { onChange(input.copy(repeatsOn = it)) }
        val ends = listOf(Res.string.work_unlimited, Res.string.work_until, Res.string.work_count)
        CalendarMenu(stringResource(ends[input.endMode])) {
            ends.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onChange(input.copy(endMode = i, endValue = "")) })
            }
        }
        if (input.endMode == 1) DiskDateField(input.endValue, Res.string.work_until) { onChange(input.copy(endValue = it)) }
        if (input.endMode == 2) WorkField(input.endValue, Res.string.work_count, keyboard = KeyboardType.Number) {
            onChange(input.copy(endValue = it))
        }
    }
}

@Composable
internal fun DaySelection(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(day in days, { onChange(if (day in days) days - day else days + day) },
                label = { Text(stringResource(day.label())) })
        }
    }
}

@Composable
private fun CalendarLinkMenu(label: StringResource, selected: String?, entries: List<CalendarOption>, onSelect: (String?) -> Unit) {
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    CalendarMenu(entries.firstOrNull { it.uuid == selected }?.name ?: stringResource(Res.string.work_empty_link)) {
        DropdownMenuItem(text = { Text(stringResource(Res.string.work_empty_link)) }, onClick = { onSelect(null) })
        entries.forEach { DropdownMenuItem(text = { Text(it.name) }, onClick = { onSelect(it.uuid) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiskDateField(value: String, label: StringResource = Res.string.work_date, onChange: (String) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true,
        placeholder = { Text(stringResource(Res.string.work_date_format)) },
        trailingIcon = {
            IconButton(onClick = { picker = true }) { Icon(Icons.Default.CalendarMonth, stringResource(label)) }
        })
    if (picker) {
        val current = runCatching { LocalDate.parse(value) }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = current?.dayNumber()?.times(86_400_000L))
        DatePickerDialog(onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { onChange((it / 86_400_000L).localDate().toString()) }
                picker = false
            }) { Text(stringResource(Res.string.oc_save)) } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text(stringResource(Res.string.p5d_cancel)) } }) {
            DatePicker(state)
        }
    }
}

@Composable
internal fun WorkField(value: String, label: StringResource, error: Boolean = false, singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, placeholder: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        singleLine = singleLine, isError = error, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        placeholder = if (placeholder == null) null else ({ Text(placeholder) }))
}

private fun WorkFrequency.label() = when (this) {
    WorkFrequency.DAILY -> Res.string.work_daily
    WorkFrequency.WEEKLY -> Res.string.work_weekly
    WorkFrequency.MONTHLY -> Res.string.work_monthly
    WorkFrequency.CUSTOM -> Res.string.work_custom
}

@Composable
fun CalendarSaveFeedback(
    detached: Int,
    offerProjectCompletion: Boolean,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    if (detached == 0 && !offerProjectCompletion) return
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (offerProjectCompletion) Res.string.work_complete_project else Res.string.work_rows)) },
        text = { if (detached > 0) Text(stringResource(Res.string.work_detached, detached)) },
        confirmButton = {
            TextButton(onClick = if (offerProjectCompletion) onComplete else onDismiss) {
                Text(stringResource(if (offerProjectCompletion) Res.string.work_complete_project_action else Res.string.oc_save))
            }
        },
        dismissButton = {
            if (offerProjectCompletion) TextButton(onClick = onDismiss) { Text(stringResource(Res.string.p5d_cancel)) }
        })
}
