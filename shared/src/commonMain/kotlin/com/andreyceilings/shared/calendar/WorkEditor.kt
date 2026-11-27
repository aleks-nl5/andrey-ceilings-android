package com.andreyceilings.shared.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.input.CompactOutlinedStringField
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.registry.GlobalRegistryTarget
import com.andreyceilings.shared.objects.ProjectEditor
import com.andreyceilings.shared.objects.ClientEditor
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
    registry: ObjectsClientsStore? = null,
    onRegistryTarget: ((GlobalRegistryTarget) -> Unit)? = null,
) = key(store, route) {
    val optionsRead = calendarOptionsState(store)
    val options = optionsRead.data()
    val lifecycleScope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(route is CalendarEditorRoute.Create || (route as? CalendarEditorRoute.Open)?.edit == true || (route as? CalendarEditorRoute.Open)?.move == true) }
    var invalidDay by remember { mutableStateOf<Int?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var createLink by remember { mutableStateOf<String?>(null) }
    var linkBusy by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    var createdProject by remember { mutableStateOf<CalendarOption?>(null) }
    var createdClient by remember { mutableStateOf<CalendarOption?>(null) }
    val registryRows = registry?.let { it.rows.collectAsState(initial = it.cachedRows).value }
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
                    val deviceZone = TimeZone.currentSystemDefault()
                    val date = route.date ?: Clock.System.now().toLocalDateTime(deviceZone).date
                    val code = if (route.installation) "INSTALL" else "OTHER"
                    val end = 9 * 3600 + minOf(options.types.firstOrNull { it.code == code }?.durationMinutes ?: 60, 899) * 60
                    CalendarWorkDraft(date, WorkSchedule(WorkTimeMode.TIMED, deviceZone,
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
    val preview by produceState<CalendarConflictPreview?>(null, draft, original, scope, editing) {
        value = null
        if (draft != null && editing) {
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
        if (busy || invalidDay != null) return
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
        else -> if (editing) Res.string.work_edit else Res.string.work_view
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
                        WorkTimeField(day.start, Res.string.work_start) { start ->
                            val next = runCatching { LocalTime.parse(start).toSecondOfDay() + duration }.getOrNull()
                            val end = next?.takeIf { it < 86400 }?.let { LocalTime.fromSecondOfDay(it).shortTime() }.orEmpty()
                            input = data.copy(days = listOf(day.copy(start = start, end = end)))
                        }
                    }
                    if (recurring) ScopeSelection(scope) { scope = it }
                    if (invalidTime || error || stale || clockChange || (draft == null && data != initial)) CalendarFeedback(when {
                        stale -> Res.string.work_stale
                        clockChange -> Res.string.work_zone_change
                        error -> Res.string.work_save_failed
                        else -> Res.string.work_invalid
                    })
                    if (stale) TextButton(onClick = { reload++ }) { Text(stringResource(Res.string.work_reload)) }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CeilingsFab(::requestClose, stringResource(Res.string.oc_close), profile = CeilingsFabProfile.COMPACT, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (editing) CeilingsFab({ save() }, stringResource(Res.string.oc_save),
                            profile = CeilingsFabProfile.COMPACT, enabled = !stale && !busy && draft != null && !invalidTime && invalidDay == null) {
                            Icon(Icons.Default.Check, null)
                        } else CeilingsFab({ editing = true }, stringResource(Res.string.work_edit), profile = CeilingsFabProfile.COMPACT) {
                            Icon(Icons.Default.Edit, null)
                        }
                    }
                },
            ) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (loadFailed || optionsRead is CalendarRead.Unavailable) item {
                        CalendarFeedback(Res.string.work_unavailable)
                    } else if (data == null || options == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else if (!editing && original != null) {
                        item("work-view") {
                            WorkReadOnly(checkNotNull(original), options, store, onRegistryTarget, onDelete = { deleting = true })
                        }
                        if (stale || error) item { CalendarFeedback(if (stale) Res.string.work_stale else Res.string.work_save_failed)
                            if (stale) TextButton(onClick = { reload++ }) { Text(stringResource(Res.string.work_reload)) }
                        }
                    } else {
                        if (stale || error || invalidTime || clockChange || invalidDay != null || (draft == null && data != initial)) item {
                            CalendarFeedback(when {
                                stale -> Res.string.work_stale
                                error -> Res.string.work_save_failed
                                invalidDay != null -> Res.string.work_invalid_day
                                invalidTime || draft == null -> Res.string.work_invalid
                                else -> Res.string.work_zone_change
                            })
                            if (stale) TextButton(onClick = { reload++ }) { Text(stringResource(Res.string.work_reload)) }
                        }
                        item("work-name") {
                            val focus = remember { FocusRequester() }
                            WorkField(data.title, Res.string.work_title, error = data.title.isBlank() && data != initial,
                                modifier = Modifier.focusRequester(focus)) { input = data.copy(title = it) }
                            LaunchedEffect(Unit) { if (route is CalendarEditorRoute.Create) focus.requestFocus() }
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
                                CalendarMenu(calendarTypeLabel(options.types.firstOrNull { it.code == data.type }), modifier = Modifier.weight(1f), fillWidth = true, leading = {
                                    val selected = options.types.firstOrNull { it.code == data.type }
                                    Icon(typeIcon(selected), null, Modifier.size(18.dp), tint = typeColor(selected))
                                }) {
                                    options.types.filter { !it.archived || it.code == data.type }.forEach { type ->
                                        CalendarMenuItem(text = { Text(calendarTypeLabel(type)) },
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
                                CalendarMenu(stringResource(data.status.label()), modifier = Modifier.weight(1f), fillWidth = true, leading = {
                                    Icon(data.status.workIcon(), null, Modifier.size(18.dp), tint = data.status.workTint())
                                }) {
                                    WorkStatus.entries.forEach { state ->
                                        CalendarMenuItem(text = { Text(stringResource(state.label())) }, leadingIcon = {
                                            Icon(state.workIcon(), null, tint = state.workTint())
                                        }, onClick = {
                                            input = data.copy(status = state)
                                        })
                                    }
                                }
                            }
                        }
                        item("work-date") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, Modifier.padding(end = 8.dp))
                                Text(stringResource(Res.string.work_all_day), Modifier.weight(1f))
                                Switch(data.mode == WorkTimeMode.ALL_DAY, { input = data.copy(mode = if (it) WorkTimeMode.ALL_DAY else WorkTimeMode.TIMED) })
                            }
                            if (data.mode != WorkTimeMode.ALL_DAY) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.work_date_only), Modifier.weight(1f))
                                Switch(data.mode == WorkTimeMode.DATE_ONLY, {
                                    input = data.copy(mode = if (it) WorkTimeMode.DATE_ONLY else WorkTimeMode.TIMED)
                                })
                            }
                        }
                        items(data.days.size, key = { "day-$it" }) { index ->
                            val day = data.days[index]
                            fun set(dayInput: DayInput) { input = data.copy(days = data.days.toMutableList().also { it[index] = dayInput }) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val actualDate = runCatching { LocalDate.parse(data.date).shift(day.offset.toInt()) }.getOrNull()
                                Box(Modifier.weight(1.4f)) {
                                    DiskDateField(actualDate?.toString().orEmpty(), error = invalidDay == index) { chosen ->
                                        if (index == 0) { invalidDay = null; input = data.copy(date = chosen) }
                                        else {
                                            val offset = LocalDate.parse(chosen).dayNumber() - LocalDate.parse(data.date).dayNumber()
                                            if (offset in 1L..365L && data.days.withIndex().none { it.index != index && it.value.offset.toLongOrNull() == offset }) {
                                                invalidDay = null; set(day.copy(offset = offset.toString()))
                                            } else invalidDay = index
                                        }
                                    }
                                }
                                if (data.mode == WorkTimeMode.TIMED) {
                                    val badTime = runCatching { LocalTime.parse(day.start) >= LocalTime.parse(day.end) }.getOrDefault(true)
                                    WorkTimeField(day.start, Res.string.work_start, Modifier.weight(1f), badTime) { start ->
                                        set(day.copy(start = start))
                                    }
                                    WorkTimeField(day.end, Res.string.work_end, Modifier.weight(1f), badTime) { set(day.copy(end = it)) }
                                }
                                if (index != 0) IconButton(onClick = {
                                    invalidDay = invalidDay?.let { bad -> when { bad == index -> null; bad > index -> bad - 1; else -> bad } }
                                    input = data.copy(days = data.days.filterIndexed { i, _ -> i != index })
                                }) { Icon(Icons.Default.RemoveCircleOutline, stringResource(Res.string.work_day_remove)) }
                            }
                        }
                        item("work-more-days") {
                            OutlinedButton(enabled = data.days.maxOfOrNull { it.offset.toIntOrNull() ?: 0 } != 365, onClick = {
                                val next = (data.days.mapNotNull { it.offset.toIntOrNull() }.maxOrNull() ?: 0) + 1
                                if (next <= 365) input = data.copy(days = data.days + data.days.last().copy(offset = next.toString()))
                            }) { Icon(Icons.Default.Add, null); Text(stringResource(Res.string.work_day_add)) }
                            WorkZoneField(data.zone) {
                                input = data.copy(zone = it)
                            }
                        }
                        item("work-recurrence") {
                            if (recurring) ScopeSelection(scope) { scope = it }
                            if (original == null || recurring && scope == CalendarEditScope.THIS_AND_FOLLOWING)
                                RepeatSettings(data) { input = it }
                        }
                        item("work-links") {
                            val projects = (options.projects + listOfNotNull(createdProject)).distinctBy { it.uuid }
                            val clients = (options.clients + listOfNotNull(createdClient)).distinctBy { it.uuid }
                            CalendarLinkMenu(Res.string.work_object, data.project, projects, error = (data.type == "INSTALL" && data.project == null) ||
                                (data.project != null && projects.none { it.uuid == data.project } && original?.draft?.projectUuid != data.project),
                                onCreate = registry?.let { { createLink = "project"; linkError = false } }) {
                                input = data.copy(project = it, client = if (it == data.project || it == null) data.client else null,
                                    ceilings = if (it == data.project) data.ceilings else emptyList(), explicit = it == data.project && data.explicit)
                            }
                            val inheritedClient = projects.firstOrNull { it.uuid == data.project }?.clientUuid
                            CalendarLinkMenu(Res.string.work_client, data.client ?: inheritedClient, clients,
                                error = data.client != null && clients.none { it.uuid == data.client } && original?.draft?.clientUuid != data.client,
                                emptyLabel = if (inheritedClient != null) Res.string.work_inherit_client else Res.string.work_empty_link,
                                onCreate = registry?.let { { createLink = "client"; linkError = false } }) {
                                input = data.copy(client = it)
                            }
                            if (data.type == "INSTALL" && data.project != null) {
                                Text(stringResource(if (data.explicit) Res.string.work_selected_ceilings else Res.string.work_whole_project),
                                    style = MaterialTheme.typography.labelMedium)
                                (activeCeilings + data.ceilings.filter { it.removed }).distinctBy { it.uuid }.chunked(2).forEach { pair ->
                                    Row(Modifier.fillMaxWidth()) { pair.forEach { ceiling ->
                                    val selected = data.ceilings.any { it.uuid == ceiling.uuid }
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(selected, { checked ->
                                            val changed = if (checked) data.ceilings + ceiling else data.ceilings.filterNot { it.uuid == ceiling.uuid }
                                            input = data.copy(ceilings = changed, explicit = changed.isNotEmpty())
                                        }, enabled = !ceiling.removed)
                                        Text(if (ceiling.removed) "${ceiling.label} · ${stringResource(Res.string.work_removed)}" else ceiling.label, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                    }
                                    } }
                                }
                            }
                        }
                        item("work-contact") {
                            OutlinedButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.work_more_details), Modifier.weight(1f))
                                Icon(if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                            }
                            if (showDetails) {
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
                        }
                        item("work-notes") {
                            WorkField(data.notes, Res.string.work_notes, singleLine = false) { input = data.copy(notes = it) }
                            if (original != null) IconButton(onClick = { deleting = true }) {
                                Icon(Icons.Default.Delete, stringResource(Res.string.oc_delete), tint = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }

    if (registry != null && createLink == "client") {
        ClientEditor(null, linkBusy, if (linkError) stringResource(Res.string.oc_client_save_failed) else null,
            onDismiss = { if (!linkBusy) createLink = null }, onSave = { value ->
                if (!linkBusy) {
                    linkBusy = true; linkError = false
                    lifecycleScope.launch {
                        try {
                            val entry = store.createClient(registry, value)
                            createdClient = entry
                            input = input?.copy(client = entry.uuid)
                            createLink = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { linkError = true }
                        finally { linkBusy = false }
                    }
                }
            })
    }
    if (registry != null && createLink == "project") {
        ProjectEditor(null, registryRows?.clients.orEmpty(), linkBusy,
            if (linkError) stringResource(Res.string.work_save_failed) else null,
            onDismiss = { if (!linkBusy) createLink = null },
            onSave = { value ->
                if (!linkBusy) {
                    linkBusy = true; linkError = false
                    lifecycleScope.launch {
                        try {
                            val entry = store.createProject(registry, value)
                            createdProject = entry
                            input = input?.copy(project = entry.uuid, client = null,
                                ceilings = emptyList(), explicit = false)
                            createLink = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { linkError = true }
                        finally { linkBusy = false }
                    }
                }
            }, onCreateClient = { value -> withContext(Dispatchers.Default) { registry.saveClient(null, value) } })
    }

    if (discarding) AlertDialog(onDismissRequest = { discarding = false },
        title = { Text(stringResource(Res.string.work_exit_title)) },
        confirmButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(Res.string.p5d_cancel)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.work_discard), color = MaterialTheme.colorScheme.error) } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(Res.string.work_delete_title)) },
        text = { if (recurring) ScopeSelection(scope) { scope = it } },
        dismissButton = {
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
        }, confirmButton = { TextButton(enabled = !busy, onClick = { deleting = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
    conflict?.let { found ->
        AlertDialog(onDismissRequest = { if (!busy) conflict = null },
            title = { Text(stringResource(Res.string.work_conflicts)) },
            text = {
                Column {
                    Text(stringResource(Res.string.work_conflict_count, found.confirmation.conflicts.size))
                    found.checkedThrough?.let { Text(stringResource(Res.string.work_checked_through, it.shortDate())) }
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
        CalendarMenuItem(text = { Text(stringResource(Res.string.work_only_this)) }, onClick = { onScope(CalendarEditScope.OCCURRENCE) })
        CalendarMenuItem(text = { Text(stringResource(Res.string.work_following)) }, onClick = { onScope(CalendarEditScope.THIS_AND_FOLLOWING) })
    }
}

@Composable
private fun RepeatSettings(input: WorkInput, onChange: (WorkInput) -> Unit) {
    Text(stringResource(Res.string.work_recurrence), style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CalendarMenu(stringResource(input.frequency?.label() ?: Res.string.work_once), modifier = Modifier.weight(1f), leading = {
            Icon(Icons.Default.Repeat, null, Modifier.size(18.dp))
        }) {
            CalendarMenuItem(text = { Text(stringResource(Res.string.work_once)) }, onClick = { onChange(input.copy(frequency = null)) })
            WorkFrequency.entries.forEach {
                CalendarMenuItem(text = { Text(stringResource(it.label())) }, onClick = { onChange(input.copy(frequency = it)) })
            }
        }
        if (input.frequency != null) {
            Text(stringResource(Res.string.work_every), style = MaterialTheme.typography.labelSmall)
            WorkField(input.step, Res.string.work_interval_short, keyboard = KeyboardType.Number, modifier = Modifier.width(64.dp),
                error = input.step.toIntOrNull()?.let { it in 1..366 } != true) { onChange(input.copy(step = it)) }
            Text(stringResource(when (input.frequency) {
                WorkFrequency.DAILY -> Res.string.work_unit_days
                WorkFrequency.MONTHLY -> Res.string.work_unit_months
                else -> Res.string.work_unit_weeks
            }), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (input.frequency != null) {
        if (input.frequency == WorkFrequency.CUSTOM) {
            val bad = runCatching { LocalDate.parse(input.date).dayOfWeek !in input.repeatsOn }.getOrDefault(true)
            if (bad) CalendarFeedback(Res.string.work_repeat_anchor)
            DaySelection(input.repeatsOn) { onChange(input.copy(repeatsOn = it)) }
        }
        val ends = listOf(Res.string.work_unlimited, Res.string.work_until, Res.string.work_count)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CalendarMenu(stringResource(ends[input.endMode]), modifier = Modifier.weight(1f)) {
                ends.forEachIndexed { i, label ->
                    CalendarMenuItem(text = { Text(stringResource(label)) }, onClick = { onChange(if (i == input.endMode) input else input.copy(endMode = i, endValue = when (i) { 1 -> input.date; 2 -> "1"; else -> "" })) })
                }
            }
            if (input.endMode == 1) DiskDateField(input.endValue, Res.string.work_until, error = runCatching { LocalDate.parse(input.endValue) < LocalDate.parse(input.date) }.getOrDefault(true)) { onChange(input.copy(endValue = it)) }
            if (input.endMode == 2) WorkField(input.endValue, Res.string.work_count, keyboard = KeyboardType.Number,
                modifier = Modifier.weight(1f), error = input.endValue.toIntOrNull()?.let { it > 0 } != true) {
                onChange(input.copy(endValue = it))
            }
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
private fun CalendarLinkMenu(label: StringResource, selected: String?, entries: List<CalendarOption>,
    error: Boolean = false, emptyLabel: StringResource = Res.string.work_empty_link, onCreate: (() -> Unit)? = null, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    Box {
        OutlinedButton(onClick = { query = ""; open = true }, modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)) {
            Text(entries.firstOrNull { it.uuid == selected }?.name ?: stringResource(Res.string.work_empty_link), Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        com.andreyceilings.shared.ui.CeilingsPopupMenu(open, { open = false }) {
            if (onCreate != null) CalendarMenuItem(text = { Text(stringResource(if (label == Res.string.work_object) Res.string.oc_new_project else Res.string.oc_new_client)) },
                leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { open = false; onCreate() })
            WorkField(query, Res.string.work_search) { query = it }
            CalendarMenuItem(text = { Text(stringResource(emptyLabel)) }, onClick = { open = false; onSelect(null) })
            entries.filter { it.name.contains(query, ignoreCase = true) }.forEach {
                CalendarMenuItem(text = { Text(it.name) }, onClick = { open = false; onSelect(it.uuid) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiskDateField(value: String, label: StringResource = Res.string.work_date, error: Boolean = false, onChange: (String) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    TextButton(onClick = { picker = true }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        val formatted = runCatching { LocalDate.parse(value).shortDate() }.getOrDefault(value)
        Text(formatted.ifBlank { stringResource(label) }, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
    if (picker) {
        val current = runCatching { LocalDate.parse(value) }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = current?.dayNumber()?.times(86_400_000L))
        DatePickerDialog(onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { onChange((it / 86_400_000L).localDate().toString()) }
                picker = false
            }) { Text(stringResource(Res.string.oc_save)) } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text(stringResource(Res.string.p5d_cancel)) } }) {
            DatePicker(state, title = { Text(stringResource(label), Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) },
                headline = { Text(state.selectedDateMillis?.let { (it / 86_400_000L).localDate().shortDate() } ?: stringResource(label),
                    Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall) }, showModeToggle = false)
        }
    }
}

@Composable
internal fun WorkField(value: String, label: StringResource, error: Boolean = false, singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, placeholder: String? = null, modifier: Modifier = Modifier,
    onChange: (String) -> Unit) {
    val caption = stringResource(label)
    CompactOutlinedStringField(value, onChange, if (placeholder.isNullOrBlank()) caption else "$caption · $placeholder",
        modifier = modifier, isError = error, maxLines = if (singleLine) 1 else 4,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), dense = singleLine)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkTimeField(value: String, label: StringResource, modifier: Modifier = Modifier, error: Boolean = false, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleSmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
    if (open) {
        val parsed = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime(9, 0))
        val time = rememberTimePickerState(initialHour = parsed.hour, initialMinute = parsed.minute, is24Hour = true)
        var dial by remember { mutableStateOf(true) }
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 360.dp).padding(6.dp).imePadding()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(label), Modifier.align(Alignment.Start), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    if (dial) TimePicker(time) else TimeInput(time)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { dial = !dial }) {
                            Icon(if (dial) Icons.Default.Keyboard else Icons.Default.Schedule, stringResource(Res.string.work_switch_time_input))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { open = false }) { Text(stringResource(Res.string.p5d_cancel)) }
                        TextButton(onClick = { onChange(LocalTime(time.hour, time.minute).shortTime()); open = false }) {
                            Text(stringResource(Res.string.oc_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkZoneField(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val zones = remember { (TimeZone.availableZoneIds + TimeZone.currentSystemDefault().id + "UTC").sorted() }
    OutlinedButton(onClick = { query = ""; open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Public, null, Modifier.size(18.dp))
        Text(value, Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1)
        Icon(Icons.Default.ArrowDropDown, stringResource(Res.string.work_zone))
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(Res.string.work_zone)) },
        text = {
            Column {
                WorkField(query, Res.string.work_search) { query = it }
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    item { TextButton(onClick = { onChange(TimeZone.currentSystemDefault().id); open = false }) {
                        Text(stringResource(Res.string.work_system) + " · " + TimeZone.currentSystemDefault().id)
                    } }
                    val matches = zones.filter { it.contains(query.replace(' ', '_'), true) }
                    items(matches.size) { i -> TextButton(onClick = { onChange(matches[i]); open = false }) { Text(matches[i]) } }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.oc_close)) } })
}

@Composable
private fun WorkReadOnly(work: CalendarWork, options: CalendarFormOptions, store: CalendarStore,
    onRegistryTarget: ((GlobalRegistryTarget) -> Unit)?, onDelete: () -> Unit) {
    val data = work.draft
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var externalError by remember { mutableStateOf(false) }
    fun open(uri: String, fallback: String? = null) {
        try { uriHandler.openUri(uri) } catch (_: Exception) {
            if (fallback == null) externalError = true
            else try { uriHandler.openUri(fallback) } catch (_: Exception) { externalError = true }
        }
    }
    fun navigate(resolve: suspend () -> GlobalRegistryTarget?) {
        val handler = onRegistryTarget ?: return
        scope.launch {
            try {
                val target = resolve()
                if (target != null) handler(target) else externalError = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { externalError = true }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(data.title, style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val type = options.types.firstOrNull { it.code == data.typeCode }
            Icon(typeIcon(type), null, tint = typeColor(type))
            Text(calendarTypeLabel(type))
            Icon(data.status.workIcon(), null, Modifier.size(18.dp), tint = data.status.workTint())
            Text(stringResource(data.status.label()), style = MaterialTheme.typography.labelMedium)
        }
        work.days.forEach { day ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(day.date.shortDate(), Modifier.weight(1f))
                Text(if (data.schedule.mode == WorkTimeMode.TIMED) day.interval?.let { interval ->
                    interval.start.toLocalDateTime(data.schedule.zone).time.shortTime() + "–" +
                        interval.endExclusive.toLocalDateTime(data.schedule.zone).time.shortTime()
                } ?: stringResource(Res.string.work_invalid) else stringResource(data.schedule.mode.label()))
            }
        }
        Text(data.schedule.zone.id, style = MaterialTheme.typography.labelMedium)
        data.recurrence?.let { rule ->
            Text(stringResource(rule.frequency.label()) + " · " + stringResource(Res.string.work_every) + " " + rule.step + " " +
                stringResource(when (rule.frequency) {
                    WorkFrequency.DAILY -> Res.string.work_unit_days
                    WorkFrequency.MONTHLY -> Res.string.work_unit_months
                    else -> Res.string.work_unit_weeks
                }))
            if (rule.weekdays.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rule.weekdays.sortedBy { it.ordinal }.forEach { Text(stringResource(it.label()), style = MaterialTheme.typography.labelSmall) }
                }
            }
            Text(when {
                rule.until != null -> stringResource(Res.string.work_until) + " " + rule.until.shortDate()
                rule.count != null -> stringResource(Res.string.work_count) + ": " + rule.count
                else -> stringResource(Res.string.work_unlimited)
            }, style = MaterialTheme.typography.bodySmall)
        }
        work.projectLabel?.let { label ->
            val uuid = data.projectUuid
            if (uuid != null && onRegistryTarget != null) TextButton(onClick = { navigate { store.projectTarget(uuid) } }) {
                Text(stringResource(Res.string.work_object) + " · " + label)
            } else Text(stringResource(Res.string.work_object) + " · " + label)
        }
        data.ceilings.forEach { ceiling ->
            if (!ceiling.removed && onRegistryTarget != null) TextButton(onClick = { navigate { store.ceilingTarget(ceiling.uuid) } }) { Text(ceiling.label) }
            else Text(ceiling.label + if (ceiling.removed) " · " + stringResource(Res.string.work_removed) else "")
        }
        work.contact.name?.let { label ->
            if ((data.clientUuid != null || data.projectUuid != null) && onRegistryTarget != null)
                TextButton(onClick = { navigate { store.clientTarget(data.projectUuid, data.clientUuid) } }) {
                    Text(stringResource(Res.string.work_client) + " · " + label)
                }
            else Text(stringResource(Res.string.work_client) + " · " + label)
        }
        work.contact.address?.takeIf { it.isNotBlank() }?.let { address ->
            TextButton(onClick = {
                val encoded = calendarUriComponent(address)
                open("geo:0,0?q=" + encoded, "https://www.google.com/maps/search/?api=1&query=" + encoded)
            }) { Text(address) }
        }
        work.contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            TextButton(onClick = { open("tel:" + phone.filter { it.isDigit() || it == '+' }) }) { Text(phone) }
        }
        work.contact.email?.takeIf { it.isNotBlank() }?.let { email -> TextButton(onClick = { open("mailto:" + calendarUriComponent(email, keepAt = true)) }) { Text(email) } }
        if (data.notes.isNotBlank()) Text(data.notes)
        if (externalError) CalendarFeedback(Res.string.work_external_unavailable)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(Res.string.oc_delete), tint = MaterialTheme.colorScheme.error) }
    }
}

private fun calendarUriComponent(value: String, keepAt: Boolean = false): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val n = byte.toInt() and 255
        val ch = n.toChar()
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~" || keepAt && ch == '@') append(ch)
        else { append('%'); append(n.toString(16).uppercase().padStart(2, '0')) }
    }
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
