package com.andreyceilings.shared.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.andreyceilings.shared.ui.ceilingsFloatingOutlineAlpha
import com.andreyceilings.shared.ui.ContextFeedbackIcon
import com.andreyceilings.shared.ui.ContextFeedbackKind
import com.andreyceilings.shared.objects.ClientReadCard
import com.andreyceilings.shared.objects.ContactAction
import com.andreyceilings.shared.objects.ExternalBrandArtwork
import com.andreyceilings.shared.objects.messengerLinks
import com.andreyceilings.shared.objects.tryOpenContactLinks
import com.andreyceilings.shared.measurement.LocalMeasurementInputFocus
import com.andreyceilings.shared.measurement.MeasurementInputFocus
import com.andreyceilings.shared.measurement.measurementFieldFocusModifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    openExternalUri: ((String) -> Unit)? = null,
    incomingWork: WorkOccurrenceKey? = null,
    onIncomingWorkHandled: (Boolean) -> Unit = {},
) = key(store, route) {
    var reload by remember { mutableIntStateOf(0) }
    var optionsRetry by remember { mutableIntStateOf(0) }
    var loadedRevision by remember { mutableIntStateOf(-1) }
    var confirmReload by remember { mutableStateOf(false) }
    val optionsRead = key(optionsRetry) { calendarOptionsState(store) }
    val options = optionsRead.data()
    val lifecycleScope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(route is CalendarEditorRoute.Create || (route as? CalendarEditorRoute.Open)?.edit == true || (route as? CalendarEditorRoute.Open)?.move == true) }
    var invalidDay by remember { mutableStateOf<Int?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var createLink by remember { mutableStateOf<String?>(null) }
    var clientCardId by remember { mutableStateOf<Long?>(null) }
    val uriHandler = LocalUriHandler.current
    val openUri: (String) -> Unit = openExternalUri ?: { uriHandler.openUri(it) }
    var linkBusy by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    var createdProject by remember { mutableStateOf<CalendarOption?>(null) }
    var createdClient by remember { mutableStateOf<CalendarOption?>(null) }
    val registryRows = registry?.let { it.rows.collectAsState(initial = it.cachedRows).value }
    var original by remember { mutableStateOf<CalendarWork?>(null) }
    var initial by remember { mutableStateOf<WorkInput?>(null) }
    var input by remember { mutableStateOf<WorkInput?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var switchingWork by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(CalendarEditScope.OCCURRENCE) }
    var editScopeChosen by remember { mutableStateOf(false) }
    val integrationPlatform = LocalCalendarIntegrationPlatform.current
    val integrationSnapshot = if (integrationPlatform != null) integrationSnapshotState(store) else null
    var integrationEdit by remember { mutableStateOf(CalendarWorkIntegration()) }
    var integrationValid by remember { mutableStateOf(true) }
    var externalConflict by remember { mutableStateOf<Pair<CalendarSave, CalendarAvailability>?>(null) }
    var confirmedExternal by remember { mutableStateOf<Pair<CalendarSave, CalendarAvailability>?>(null) }
    var conflict by remember { mutableStateOf<CalendarSaveResult.Conflicts?>(null) }
    val deleteOnly = (route as? CalendarEditorRoute.Open)?.delete == true
    val move = (route as? CalendarEditorRoute.Open)?.move == true &&
        (input?.days?.size ?: 1) == 1

    LaunchedEffect(options != null, reload) {
        if (options == null || loadedRevision == reload) return@LaunchedEffect
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
            integrationEdit = CalendarWorkIntegration()
            integrationValid = true
            confirmedExternal = null
            externalConflict = null
            loadedRevision = reload
            stale = false
            error = false
            invalidDay = null
            loadFailed = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }

    LaunchedEffect(incomingWork, busy, linkBusy, loadedRevision, reload, loadFailed) {
        val target = incomingWork ?: return@LaunchedEffect
        if (busy || linkBusy || loadedRevision != reload && !loadFailed) return@LaunchedEffect
        if ((route as? CalendarEditorRoute.Open)?.key == target) {
            onIncomingWorkHandled(false)
        } else if (input != initial || integrationEdit != CalendarWorkIntegration()) {
            switchingWork = true
            discarding = true
        } else onIncomingWorkHandled(true)
    }

    fun requestClose() {
        if (!busy && !linkBusy) {
            if (input != initial || integrationEdit != CalendarWorkIntegration()) discarding = true else onClose()
        }
    }
    fun requestReload() {
        if (optionsRead is CalendarRead.Unavailable) optionsRetry++
        else if (stale && (input != initial || integrationEdit != CalendarWorkIntegration())) confirmReload = true
        else reload++
    }
    val recurring = original?.let { work ->
        val now = Clock.System.now()
        work.draft.recurrence != null && work.upcoming(now) &&
            work.draft.date >= now.toLocalDateTime(work.draft.schedule.zone).date
    } == true
    LaunchedEffect(recurring) { if (!recurring) scope = CalendarEditScope.OCCURRENCE }
    val awaitingEditScope = recurring && editing && !move && !deleteOnly && !editScopeChosen
    val data = input
    val draft = remember(data, scope, original) {
        runCatching { data?.draft(original == null || (recurring && scope == CalendarEditScope.THIS_AND_FOLLOWING), original) }.getOrNull()
    }
    val preview by produceState<CalendarConflictPreview?>(null, draft, original, scope, editing, awaitingEditScope) {
        if (draft != null && editing && !awaitingEditScope) {
            delay(280)
            try { value = store.previewConflicts(CalendarSave(draft, original?.key, original?.version, scope, replaceRecurrence = !move)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = null /* Explicit Save reports any unavailable writer. */ }
        } else value = null
    }
    val command = draft?.let {
        CalendarSave(it, original?.key, original?.version, scope, replaceRecurrence = !move,
            integration = integrationEdit.takeUnless { value -> value == CalendarWorkIntegration() })
    }
    val externalPreview by produceState<CalendarAvailability>(CalendarAvailability.Disabled, command,
        integrationPlatform?.revision, editing, awaitingEditScope) {
        if (command != null && editing && !awaitingEditScope && integrationPlatform != null) {
            delay(280)
            try { value = externalConflictSnapshot(integrationPlatform, store, command) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = CalendarAvailability.Unknown }
        } else value = CalendarAvailability.Disabled
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

    fun save(confirmation: CalendarConflictConfirmation? = null,
        externalConfirmation: Pair<CalendarSave, CalendarAvailability>? = confirmedExternal) {
        val saving = command ?: return
        val valid = saving.draft
        if (busy || linkBusy || awaitingEditScope || invalidDay != null || !integrationValid ||
            optionsRead is CalendarRead.Unavailable) return
        busy = true
        error = false
        lifecycleScope.launch {
            try {
                val fresh = externalConflictSnapshot(integrationPlatform, store, saving)
                val warning = saving to fresh
                if (fresh.requiresConfirmation() && warning != externalConfirmation) {
                    externalConflict = warning
                    conflict = null
                    return@launch
                }
                confirmedExternal = warning.takeIf { fresh.requiresConfirmation() }
                externalConflict = null
                val source = original
                if (source != null && recurring && scope == CalendarEditScope.THIS_AND_FOLLOWING &&
                    valid.status == WorkStatus.CANCELLED) {
                    if (store.cancelFollowing(source.key, source.version)) onSaved(CalendarSaveResult.Saved(source.key), valid)
                    else stale = true
                } else when (val result = store.save(saving, confirmation, integrationPlatform?.let { it::reactivateSyncRule })) {
                    is CalendarSaveResult.Saved -> { conflict = null; onSaved(result, valid) }
                    is CalendarSaveResult.Conflicts -> conflict = result
                    CalendarSaveResult.Stale -> { conflict = null; stale = true }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { conflict = null; error = true }
            finally { busy = false }
        }
    }

    val formMessage = when {
        loadFailed || optionsRead is CalendarRead.Unavailable -> Res.string.work_unavailable
        stale -> Res.string.work_stale
        error -> Res.string.work_save_failed
        !editing || data == null -> null
        invalidDay != null -> Res.string.work_invalid_day
        data != initial && data.title.isBlank() -> Res.string.work_invalid_title
        data.type == "INSTALL" && data.project == null -> Res.string.work_invalid_project
        data.mode == WorkTimeMode.TIMED && data.days.any {
            runCatching { LocalTime.parse(it.start) >= LocalTime.parse(it.end) }.getOrDefault(true)
        } -> Res.string.work_invalid_time
        invalidTime -> Res.string.work_invalid_time
        draft == null && data != initial -> Res.string.work_invalid
        clockChange -> Res.string.work_zone_change
        else -> null
    }
    val title = when {
        move -> Res.string.work_move
        route is CalendarEditorRoute.Create -> Res.string.work_add
        else -> if (editing) Res.string.work_edit else Res.string.work_view
    }
    fun cancelDelete() {
        if (!busy) {
            deleting = false
            if (deleteOnly) onClose()
        }
    }
    if (deleteOnly) {
        // A single confirmation below owns loading, ready, error and deletion states.
    } else if (move && data != null) {
        AlertDialog(onDismissRequest = ::requestClose,
            title = { Text(stringResource(title)) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        DiskDateField(data.date, prominent = true) { next -> input = data.copy(date = next) }
                        if (data.mode == WorkTimeMode.TIMED) {
                            val day = data.days.single()
                            val source = checkNotNull(original).draft.schedule.days.single()
                            val duration = checkNotNull(source.end).toSecondOfDay() - checkNotNull(source.start).toSecondOfDay()
                            WorkTimeField(day.start, Res.string.work_start, prominent = true) { start ->
                                val next = runCatching { LocalTime.parse(start).toSecondOfDay() + duration }.getOrNull()
                                val end = next?.takeIf { it < 86400 }?.let { LocalTime.fromSecondOfDay(it).shortTime() }.orEmpty()
                                input = data.copy(days = listOf(day.copy(start = start, end = end)))
                            }
                        }
                    }
                    if (recurring) ScopeSelection(scope) { scope = it }
                    if (invalidTime || error || stale || clockChange || (draft == null && data != initial)) CalendarFeedback(when {
                        stale -> Res.string.work_stale
                        clockChange -> Res.string.work_zone_change
                        error -> Res.string.work_save_failed
                        else -> Res.string.work_invalid
                    })
                    if (stale || optionsRead is CalendarRead.Unavailable) TextButton(onClick = ::requestReload) { Text(stringResource(Res.string.work_reload)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { save() }, enabled = !stale && draft != null && !invalidTime && !busy && optionsRead !is CalendarRead.Unavailable) {
                    Text(stringResource(Res.string.oc_save))
                }
            },
            dismissButton = { TextButton(onClick = ::requestClose) { Text(stringResource(Res.string.p5d_cancel)) } })
    } else {
        Dialog(onDismissRequest = ::requestClose, properties = properties) {
            val scheme = MaterialTheme.colorScheme
            val dark = scheme.background.luminance() < 0.4f
            val fabColor = if (dark) scheme.surfaceContainerHigh else Color.White
            val fabOutline = ceilingsFloatingOutlineAlpha(dark)
            // Shrink the actual scroll viewport above the keyboard, rather than only adding
            // bottom item padding. The shared focus helper reads this dialog's own IME insets.
            CeilingsScreenScaffold(
                modifier = Modifier.imePadding(),
                windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
                header = {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CeilingsFab(::requestClose, stringResource(Res.string.oc_close), profile = CeilingsFabProfile.COMPACT,
                            enabled = !busy, containerColor = fabColor, outlineAlpha = fabOutline) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = scheme.onSurface, modifier = Modifier.size(23.dp))
                        }
                        Row(Modifier.weight(1f).height(56.dp), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            formMessage?.let { message ->
                                val description = stringResource(message)
                                ContextFeedbackIcon(description, description,
                                    if (message == Res.string.work_zone_change) ContextFeedbackKind.WARNING else ContextFeedbackKind.ERROR)
                                Text(description, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall,
                                    color = if (message == Res.string.work_zone_change) scheme.onSurfaceVariant else scheme.error,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (stale || loadFailed || optionsRead is CalendarRead.Unavailable) IconButton(onClick = ::requestReload,
                                    enabled = !busy && !linkBusy) { Icon(Icons.Default.Refresh, stringResource(Res.string.work_reload)) }
                            }
                            if (editing && externalPreview.requiresConfirmation()) {
                                val message = if (externalPreview is CalendarAvailability.Unknown) Res.string.p7b_busy_unknown
                                    else Res.string.p7b_busy_conflicts
                                val label = stringResource(message)
                                ContextFeedbackIcon(label, label, ContextFeedbackKind.WARNING)
                            }
                            if (editing && (preview?.count ?: 0) > 0) {
                                val detail = stringResource(Res.string.work_conflict_count, preview?.count ?: 0) +
                                    (preview?.checkedThrough?.let { "\n" + stringResource(Res.string.work_checked_through, it.shortDate()) } ?: "")
                                ContextFeedbackIcon(detail, stringResource(Res.string.work_conflicts), ContextFeedbackKind.WARNING)
                            }
                        }
                        if (editing) CeilingsFab({ save() }, stringResource(Res.string.oc_save),
                            profile = CeilingsFabProfile.COMPACT, enabled = !awaitingEditScope && !stale && !busy && !loadFailed && optionsRead !is CalendarRead.Unavailable && draft != null && !invalidTime && invalidDay == null && integrationValid,
                            containerColor = fabColor, outlineAlpha = fabOutline) {
                            Icon(Icons.Default.Check, null, tint = if (draft != null && !invalidTime && !busy) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = .45f), modifier = Modifier.size(23.dp))
                        } else CeilingsFab({ editScopeChosen = false; scope = CalendarEditScope.OCCURRENCE; editing = true }, stringResource(Res.string.work_edit), profile = CeilingsFabProfile.COMPACT,
                            enabled = original != null && !busy, containerColor = fabColor, outlineAlpha = fabOutline) {
                            Icon(Icons.Default.Edit, null, tint = scheme.onSurface, modifier = Modifier.size(23.dp))
                        }
                    }
                },
                bottomNavigation = {
                    if (original != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { deleting = true }, enabled = !busy && !awaitingEditScope) {
                            Icon(Icons.Default.Delete, stringResource(Res.string.oc_delete), tint = scheme.error)
                        }
                    }
                }
            ) { padding ->
                CompositionLocalProvider(LocalMeasurementInputFocus provides remember { MeasurementInputFocus(null) }) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (loadFailed || (optionsRead is CalendarRead.Unavailable && options == null)) {
                        // The persistent feedback above owns the failure and retry action.
                    } else if (data == null || options == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else if (awaitingEditScope) {
                        // No editable fields until the owner explicitly chooses the scope.
                    } else if (!editing && original != null) {
                        item("work-view") {
                            WorkReadOnly(checkNotNull(original), options, store, onRegistryTarget, openUri,
                                onClientCard = { clientCardId = it })
                        }
                        if (integrationPlatform != null) item("work-integration-actions") {
                            WorkIntegrationActions(checkNotNull(original), integrationPlatform, integrationSnapshot?.settings)
                        }
                    } else {
                        if (editing && original?.draft?.recurrence != null) item("work-edit-scope") {
                            if (recurring) ScopeSelection(scope) { scope = it }
                            else Text(stringResource(Res.string.work_only_this), style = MaterialTheme.typography.labelMedium)
                        }
                        item("work-name") {
                            val focus = remember { FocusRequester() }
                            WorkField(data.title, Res.string.work_title, error = data.title.isBlank() && data != initial,
                                modifier = Modifier.padding(top = 12.dp).focusRequester(focus)) { input = data.copy(title = it) }
                            LaunchedEffect(Unit) { if (route is CalendarEditorRoute.Create) focus.requestFocus() }
                        }
                        item("work-type-status") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val actualDate = runCatching { LocalDate.parse(data.date).shift(day.offset.toInt()) }.getOrNull()
                                Box(Modifier.weight(1.5f)) {
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
                                        val duration = options.types.firstOrNull { it.code == data.type }?.durationMinutes ?: 60
                                        set(day.copy(start = start, end = shiftedWorkEnd(day.start, day.end, start, duration)))
                                    }
                                    WorkTimeField(day.end, Res.string.work_end, Modifier.weight(1f), badTime) { set(day.copy(end = it)) }
                                }
                                if (index != 0) IconButton(onClick = {
                                    invalidDay = invalidDay?.let { bad -> when { bad == index -> null; bad > index -> bad - 1; else -> bad } }
                                    input = data.copy(days = data.days.filterIndexed { i, _ -> i != index })
                                }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.RemoveCircleOutline, stringResource(Res.string.work_day_remove)) }
                                else Spacer(Modifier.width(44.dp))
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
                        if (integrationPlatform != null && !move) item("work-integrations") {
                            val base = original?.key?.let { integrationSnapshot?.overrides(it) } ?: CalendarWorkIntegration()
                            if (integrationSnapshot == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                val defaults = integrationSnapshot.types[data.type]?.reminders ?: standardReminderRules
                                WorkIntegrationControls(base, integrationEdit, data.mode, data.days.mapNotNull { it.offset.toIntOrNull() },
                                    defaults, setValidation = { integrationValid = it }) { integrationEdit = it }
                            }
                        }
                        item("work-recurrence") {
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
                            if (showDetails) Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
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
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                }
            }
        }
    }

    val viewedClient = registryRows?.clients?.firstOrNull { it.id == clientCardId }
    if (viewedClient != null) {
        ClientReadCard(viewedClient, onDismiss = { clientCardId = null },
            onEdit = null, onDelete = null,
            onDial = { phone -> openUri("tel:" + phone.filter { it.isDigit() || it == '+' }) },
            onExternalAction = { action, raw ->
                val links = when (action) {
                    ContactAction.GOOGLE_MAPS -> listOf("https://www.google.com/maps/search/?api=1&query=" + calendarUriComponent(raw))
                    ContactAction.WAZE -> listOf("https://waze.com/ul?q=" + calendarUriComponent(raw) + "&navigate=yes")
                    else -> messengerLinks(action, raw)
                }
                check(tryOpenContactLinks(links, openUri))
            })
    } else if (clientCardId != null) {
        AlertDialog(onDismissRequest = { clientCardId = null },
            title = { Text(stringResource(Res.string.work_client)) },
            text = { if (registry != null && registryRows == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else CalendarFeedback(Res.string.work_unavailable) },
            confirmButton = { TextButton(onClick = { clientCardId = null }) { Text(stringResource(Res.string.oc_close)) } })
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

    if (awaitingEditScope && data != null && options != null && !loadFailed) {
        fun cancelScope() {
            if ((route as? CalendarEditorRoute.Open)?.edit == true) onClose()
            else { editing = false; scope = CalendarEditScope.OCCURRENCE; editScopeChosen = false }
        }
        AlertDialog(onDismissRequest = ::cancelScope,
            title = { Text(stringResource(Res.string.work_edit)) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        scope = CalendarEditScope.OCCURRENCE
                        editScopeChosen = true
                    }) { Text(stringResource(Res.string.work_only_this)) }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        scope = CalendarEditScope.THIS_AND_FOLLOWING
                        editScopeChosen = true
                    }) { Text(stringResource(Res.string.work_following)) }
                }
            },
            confirmButton = { TextButton(onClick = ::cancelScope) { Text(stringResource(Res.string.p5d_cancel)) } })
    }

    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false },
        title = { Text(stringResource(Res.string.work_reload_discard)) },
        dismissButton = { TextButton(onClick = { confirmReload = false; reload++ }) {
            Text(stringResource(Res.string.work_discard), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { confirmReload = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
    if (discarding) {
        fun keepEditing() {
            discarding = false
            if (switchingWork) onIncomingWorkHandled(false)
            switchingWork = false
        }
        AlertDialog(onDismissRequest = ::keepEditing,
            title = { Text(stringResource(Res.string.work_exit_title)) },
            confirmButton = { TextButton(onClick = ::keepEditing) { Text(stringResource(Res.string.p5d_cancel)) } },
            dismissButton = { TextButton(onClick = {
                if (switchingWork) onIncomingWorkHandled(true) else onClose()
            }) { Text(stringResource(Res.string.work_discard), color = MaterialTheme.colorScheme.error) } })
    }
    if (deleteOnly || deleting && original != null) {
        val deleteProblem = when {
            stale -> Res.string.work_stale
            error -> Res.string.work_save_failed
            loadFailed || optionsRead is CalendarRead.Unavailable -> Res.string.work_unavailable
            else -> null
        }
        val deleteReady = original != null && loadedRevision == reload && deleteProblem == null
        AlertDialog(onDismissRequest = ::cancelDelete,
            title = { Text(stringResource(Res.string.work_delete_title)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    original?.let { Text(it.draft.title, style = MaterialTheme.typography.bodyMedium) }
                    if (deleteProblem != null) CalendarFeedback(deleteProblem)
                    else if (!deleteReady) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (recurring) ScopeSelection(scope) { scope = it }
                }
            },
            dismissButton = {
                if (deleteOnly && deleteProblem != null) {
                    TextButton(onClick = ::requestReload, enabled = !busy) { Text(stringResource(Res.string.work_reload)) }
                } else TextButton(enabled = deleteReady && !busy, onClick = {
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
            },
            confirmButton = { TextButton(enabled = !busy, onClick = ::cancelDelete) { Text(stringResource(Res.string.p5d_cancel)) } })
    }
    externalConflict?.let { warning ->
        AlertDialog(onDismissRequest = { if (!busy) externalConflict = null },
            title = { Text(stringResource(Res.string.p7b_busy_conflicts)) },
            text = {
                when (val evidence = warning.second) {
                    CalendarAvailability.Unknown -> Text(stringResource(Res.string.p7b_busy_unknown))
                    is CalendarAvailability.Available -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        val zone = warning.first.draft.schedule.zone
                        evidence.busy.sortedBy { it.interval.start }.take(8).forEach {
                            val start = it.interval.start.toLocalDateTime(zone)
                            val end = it.interval.endExclusive.toLocalDateTime(zone)
                            Text("${start.date.shortDate()} · ${start.time.shortTime()}–${end.time.shortTime()}")
                        }
                    }
                    CalendarAvailability.Disabled -> Unit
                }
            },
            dismissButton = { TextButton(onClick = { externalConflict = null }) { Text(stringResource(Res.string.p5d_cancel)) } },
            confirmButton = { TextButton(enabled = !busy, onClick = { save(externalConfirmation = warning) }) {
                Text(stringResource(Res.string.work_save_anyway))
            } })
    }
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
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Text(stringResource(Res.string.work_recurrence), style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CalendarMenu(stringResource(input.frequency?.label() ?: Res.string.work_once), modifier = Modifier.weight(1f), fillWidth = true, leading = {
            Icon(Icons.Default.Repeat, null, Modifier.size(18.dp))
        }) {
            CalendarMenuItem(text = { Text(stringResource(Res.string.work_once)) }, onClick = { onChange(input.copy(frequency = null)) })
            WorkFrequency.entries.forEach {
                CalendarMenuItem(text = { Text(stringResource(it.label())) }, onClick = {
                    val weekdays = if (it == WorkFrequency.CUSTOM) input.repeatsOn + LocalDate.parse(input.date).dayOfWeek else input.repeatsOn
                    onChange(input.copy(frequency = it, repeatsOn = weekdays))
                })
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
            }), modifier = Modifier.width(38.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
            if (input.endMode == 1) DiskDateField(input.endValue, Res.string.work_until, neutral = true, error = runCatching { LocalDate.parse(input.endValue) < LocalDate.parse(input.date) }.getOrDefault(true)) { onChange(input.copy(endValue = it)) }
            if (input.endMode == 2) WorkField(input.endValue, Res.string.work_count, keyboard = KeyboardType.Number,
                modifier = Modifier.weight(1f), error = input.endValue.toIntOrNull()?.let { it > 0 } != true) {
                onChange(input.copy(endValue = it))
            }
        }
    }
    }
}

@Composable
internal fun DaySelection(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in days
            Surface(onClick = { onChange(if (selected) days - day else days + day) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = selected },
                shape = MaterialTheme.shapes.small,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(day.label()), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CalendarLinkMenu(label: StringResource, selected: String?, entries: List<CalendarOption>,
    error: Boolean = false, emptyLabel: StringResource = Res.string.work_empty_link, onCreate: (() -> Unit)? = null, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val project = label == Res.string.work_object
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { query = ""; open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)) {
        Icon(if (project) Icons.Default.Business else Icons.Default.Person, null, Modifier.size(18.dp))
        Text(entries.firstOrNull { it.uuid == selected }?.name ?: stringResource(emptyLabel),
            Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ArrowDropDown, null)
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(stringResource(label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkField(query, if (project) Res.string.work_search_projects else Res.string.work_search_clients) { query = it }
                LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
                    if (onCreate != null) item {
                        DropdownMenuItem(text = { Text(stringResource(if (project) Res.string.oc_new_project else Res.string.oc_new_client)) },
                            leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { open = false; onCreate() })
                    }
                    item { DropdownMenuItem(text = { Text(stringResource(emptyLabel)) }, onClick = { open = false; onSelect(null) }) }
                    val matches = entries.filter { it.name.contains(query.trim(), ignoreCase = true) }
                    items(matches.size, key = { matches[it].uuid }) { i ->
                        val entry = matches[i]
                        DropdownMenuItem(text = { Text(entry.name) },
                            trailingIcon = { if (entry.uuid == selected) Icon(Icons.Default.Check, null) },
                            onClick = { open = false; onSelect(entry.uuid) })
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.oc_close)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiskDateField(value: String, label: StringResource = Res.string.work_date, error: Boolean = false, neutral: Boolean = false, prominent: Boolean = false, onChange: (String) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    TextButton(onClick = { picker = true }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        val formatted = runCatching { LocalDate.parse(value).shortDate() }.getOrDefault(value)
        Text(formatted.ifBlank { stringResource(label) }, color = if (error) MaterialTheme.colorScheme.error else if (neutral) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge, maxLines = 1)
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
        modifier = modifier.then(measurementFieldFocusModifier()), isError = error, maxLines = if (singleLine) 1 else 4,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), dense = singleLine)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkTimeField(value: String, label: StringResource, modifier: Modifier = Modifier, error: Boolean = false, prominent: Boolean = false, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!prominent) Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
            Text(value, style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
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
    val zones = remember { (TimeZone.availableZoneIds + TimeZone.currentSystemDefault().id + "UTC").sortedWith(compareBy<String> { if (it.startsWith("Europe/")) 0 else 1 }.thenBy { it }) }
    OutlinedButton(onClick = { query = ""; open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Public, null, Modifier.size(18.dp))
        Text(value, Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1)
        Icon(Icons.Default.ArrowDropDown, stringResource(Res.string.work_zone))
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(Res.string.work_zone)) },
        text = {
            Column {
                WorkField(query, Res.string.work_search_zones) { query = it }
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
    onRegistryTarget: ((GlobalRegistryTarget) -> Unit)?, openUri: (String) -> Unit, onClientCard: (Long) -> Unit) {
    val data = work.draft
    val scope = rememberCoroutineScope()
    var externalError by remember { mutableStateOf(false) }
    fun open(uri: String, fallback: String? = null) {
        externalError = false
        try { openUri(uri) } catch (_: Exception) {
            if (fallback == null) externalError = true
            else try { openUri(fallback) } catch (_: Exception) { externalError = true }
        }
    }
    fun navigate(resolve: suspend () -> GlobalRegistryTarget?) {
        scope.launch {
            try {
                when (val target = resolve()) {
                    is GlobalRegistryTarget.Client -> onClientCard(target.id)
                    null -> externalError = true
                    else -> if (onRegistryTarget != null) onRegistryTarget(target) else externalError = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { externalError = true }
        }
    }
    val type = options.types.firstOrNull { it.code == data.typeCode }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Icon(typeIcon(type), null, Modifier.size(26.dp).padding(top = 3.dp), tint = typeColor(type))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SelectionContainer { Text(data.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
                work.days.forEach { day ->
                    val time = if (data.schedule.mode == WorkTimeMode.TIMED) day.interval?.let { interval ->
                        interval.start.toLocalDateTime(data.schedule.zone).time.shortTime() + " – " +
                            interval.endExclusive.toLocalDateTime(data.schedule.zone).time.shortTime()
                    } ?: stringResource(Res.string.work_invalid) else stringResource(data.schedule.mode.label())
                    SelectionContainer { Text(stringResource(day.date.dayOfWeek.label()) + ", " + day.date.shortDate() + " · " + time,
                        style = MaterialTheme.typography.bodyLarge) }
                }
                Text(data.schedule.zone.id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        WorkDetailRow(data.status.workIcon(), stringResource(data.status.label()), calendarTypeLabel(type))
        data.recurrence?.let { rule ->
            val unit = stringResource(when (rule.frequency) {
                WorkFrequency.DAILY -> Res.string.work_unit_days
                WorkFrequency.MONTHLY -> Res.string.work_unit_months
                else -> Res.string.work_unit_weeks
            })
            val detail = buildList {
                add(stringResource(rule.frequency.label()) + " · " + stringResource(Res.string.work_every) + " " + rule.step + " " + unit)
                if (rule.weekdays.isNotEmpty()) add(rule.weekdays.sortedBy { it.ordinal }.map { stringResource(it.label()) }.joinToString(" "))
                add(when {
                    rule.until != null -> stringResource(Res.string.work_until) + " " + rule.until.shortDate()
                    rule.count != null -> stringResource(Res.string.work_count) + ": " + rule.count
                    else -> stringResource(Res.string.work_unlimited)
                })
            }.joinToString("\n")
            WorkDetailRow(Icons.Default.Repeat, detail)
        }
        work.contact.address?.takeIf { it.isNotBlank() }?.let { address ->
            val encoded = calendarUriComponent(address)
            val maps = { open("https://www.google.com/maps/dir/?api=1&destination=" + encoded, "geo:0,0?q=" + encoded) }
            WorkDetailRow(Icons.Default.Place, address, onClick = maps, trailing = {
                WorkContactIcon(ContactAction.GOOGLE_MAPS, "Google Maps", maps)
                WorkContactIcon(ContactAction.WAZE, "Waze") {
                    open("https://waze.com/ul?q=" + encoded + "&navigate=yes")
                }
            })
        }
        work.projectLabel?.let { label ->
            val uuid = data.projectUuid
            WorkDetailRow(Icons.Default.Business, label, stringResource(Res.string.work_object),
                onClick = if (uuid != null && onRegistryTarget != null) { { navigate { store.projectTarget(uuid) } } } else null)
        }
        data.ceilings.forEach { ceiling ->
            WorkDetailRow(Icons.Default.Layers, ceiling.label + if (ceiling.removed) " · " + stringResource(Res.string.work_removed) else "",
                onClick = if (!ceiling.removed && onRegistryTarget != null) { { navigate { store.ceilingTarget(ceiling.uuid) } } } else null)
        }
        if (data.typeCode == "INSTALL" && !data.explicitCeilings) WorkDetailRow(Icons.Default.Layers, stringResource(Res.string.work_whole_project))
        val effectiveClient = data.clientUuid ?: options.projects.firstOrNull { it.uuid == data.projectUuid }?.clientUuid
        val clientName = options.clients.firstOrNull { it.uuid == effectiveClient }?.name
        (clientName ?: work.contact.name)?.takeIf { it.isNotBlank() }?.let { name ->
            WorkDetailRow(Icons.Default.Person, name, stringResource(Res.string.work_client),
                onClick = if (effectiveClient != null) { { navigate { store.clientTarget(data.projectUuid, data.clientUuid) } } } else null)
        }
        if (clientName != null && !work.contact.name.isNullOrBlank() && work.contact.name != clientName)
            WorkDetailRow(Icons.Default.Badge, work.contact.name, stringResource(Res.string.work_name))
        work.contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            WorkDetailRow(Icons.Default.Call, phone, onClick = { open("tel:" + phone.filter { it.isDigit() || it == '+' }) }, trailing = {
                listOf(ContactAction.WHATSAPP to "WhatsApp", ContactAction.TELEGRAM to "Telegram",
                    ContactAction.VIBER to "Viber").forEach { (action, label) ->
                    WorkContactIcon(action, label) {
                        externalError = !tryOpenContactLinks(messengerLinks(action, phone), openUri)
                    }
                }
            })
        }
        work.contact.email?.takeIf { it.isNotBlank() }?.let { email ->
            WorkDetailRow(Icons.Default.Email, email, onClick = { open("mailto:" + calendarUriComponent(email, keepAt = true)) })
        }
        if (data.notes.isNotBlank()) WorkDetailRow(Icons.Default.Notes, data.notes)
        if (externalError) CalendarFeedback(Res.string.work_external_unavailable)
    }
}

@Composable
private fun WorkDetailRow(icon: ImageVector, value: String, subtitle: String? = null,
    onClick: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(Res.string.work_copy_info)
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onClick?.invoke() },
        onLongClickLabel = copyLabel, onLongClick = { clipboard.setText(AnnotatedString(value)) })
        .padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(0.dp), content = trailing)
    }
}

@Composable
private fun WorkContactIcon(action: ContactAction, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) {
        ExternalBrandArtwork(action, modifier = Modifier.size(25.dp), monochromeTint = MaterialTheme.colorScheme.onSurfaceVariant)
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
