package com.andreyceilings.shared.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.CeilingsScreenScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

enum class CalendarDisplayMode { WEEK, MONTH, LIST }

sealed interface CalendarEditorRoute {
    data class Create(val date: LocalDate? = null, val projectUuid: String? = null, val installation: Boolean = false) : CalendarEditorRoute
    data class Open(val key: WorkOccurrenceKey, val move: Boolean = false, val delete: Boolean = false) : CalendarEditorRoute
}

@Composable
fun WorkCalendarScreen(
    store: CalendarStore?,
    mode: CalendarDisplayMode,
    onMode: (CalendarDisplayMode) -> Unit,
    onEdit: (CalendarEditorRoute) -> Unit,
    contentPadding: PaddingValues,
    systemFirstDay: DayOfWeek = DayOfWeek.MONDAY,
    projectFilter: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val options = calendarState(remember(store) { store?.options() }).data()
    val zone = options?.settings?.zone ?: TimeZone.currentSystemDefault()
    val now = calendarNow()
    val today = now.toLocalDateTime(zone).date
    var dateValue by rememberSaveable(projectFilter) { mutableStateOf<String?>(null) }
    val date = dateValue?.let(LocalDate::parse) ?: today
    var query by rememberSaveable(projectFilter) { mutableStateOf("") }
    var expanded by rememberSaveable(projectFilter) { mutableStateOf(false) }
    var type by rememberSaveable(projectFilter) { mutableStateOf<String?>(null) }
    var status by rememberSaveable(projectFilter) { mutableStateOf<String?>(null) }
    var project by rememberSaveable(projectFilter) { mutableStateOf(projectFilter) }
    val firstDay = options?.settings?.firstWeekday ?: systemFirstDay
    val range = periodOf(date, mode, firstDay)
    val currentPeriod = remember(store, range, zone) { store?.period(range, zone) }
    val period = key(range, zone) { calendarState(currentPeriod, store?.cached(range, zone)) }
    val fallback = remember(store, today, project) { store?.upcoming(today, project, 12) }
    val fallbackRead = calendarState(fallback)
    val upcoming = fallbackRead.data().orEmpty().filter { it.upcoming(now) }
    var searchHorizon by rememberSaveable { mutableIntStateOf(90) }
    val searchFlow = remember(store, query, searchHorizon) {
        if (query.isBlank()) null else store?.search(query, searchHorizon)
    }
    val search = key(query, searchHorizon) { calendarState(searchFlow) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val types = options?.types.orEmpty().associateBy { it.code }

    fun movePeriod(direction: Int) {
        dateValue = nextPeriod(date, mode, direction).toString()
        scope.launch { list.scrollToItem(0) }
    }
    LaunchedEffect(store, range, zone, mode, firstDay) {
        store?.prefetch(periodOf(nextPeriod(date, mode, -1), mode, firstDay), zone)
        store?.prefetch(periodOf(nextPeriod(date, mode, 1), mode, firstDay), zone)
    }
    val filtered = (if (query.isBlank()) period.data() else search.data()).orEmpty().filter {
        (project == null || it.draft.projectUuid == project) &&
            (type == null || it.draft.typeCode == type) &&
            (status == null || it.draft.status.name == status)
    }
    val days = filtered.flatMap { work -> work.displayDates(zone).map { it to work } }.groupBy({ it.first }, { it.second })
    val exact = days[date].orEmpty()
    val displayed = when {
        query.isNotBlank() -> filtered
        mode == CalendarDisplayMode.LIST -> filtered
        exact.isNotEmpty() || date != today -> exact
        else -> upcoming.filter { (type == null || it.draft.typeCode == type) &&
            (status == null || it.draft.status.name == status) }.take(3)
    }
    val read = if (query.isBlank()) period else search
    CeilingsScreenScaffold(
        contentPadding = contentPadding,
        windowInsets = WindowInsets(0, 0, 0, 0),
        topGradient = {},
        bottomGradient = false,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item("calendar-controls") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onClose != null) IconButton(onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.p5d_cancel))
                        }
                        TextButton(onClick = {
                            dateValue = null
                            scope.launch { list.scrollToItem(0) }
                        }) { Text(stringResource(Res.string.work_today)) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(Icons.Default.FilterList, stringResource(Res.string.work_filters))
                        }
                        IconButton(onClick = { onEdit(CalendarEditorRoute.Create(date, projectFilter)) }, enabled = store != null) {
                            Icon(Icons.Default.Add, stringResource(Res.string.work_add))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CalendarDisplayMode.entries.forEach {
                            FilterChip(selected = mode == it, onClick = { onMode(it) },
                                label = { Text(stringResource(it.label())) })
                        }
                    }
                    if (expanded) {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.work_search)) }, singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            CalendarMenu(label = type?.let { calendarTypeLabel(types[it]) } ?: stringResource(Res.string.work_type)) {
                                DropdownMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { type = null })
                                types.values.forEach { item ->
                                    DropdownMenuItem(text = { Text(calendarTypeLabel(item)) }, onClick = { type = item.code })
                                }
                            }
                            CalendarMenu(label = status?.let { stringResource(WorkStatus.valueOf(it).label()) }
                                ?: stringResource(Res.string.work_status)) {
                                DropdownMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { status = null })
                                WorkStatus.entries.forEach { item ->
                                    DropdownMenuItem(text = { Text(stringResource(item.label())) }, onClick = { status = item.name })
                                }
                            }
                            if (projectFilter == null) CalendarMenu(label = options?.projects?.firstOrNull { it.uuid == project }?.name
                                ?: stringResource(Res.string.work_object)) {
                                DropdownMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { project = null })
                                options?.projects.orEmpty().forEach { item ->
                                    DropdownMenuItem(text = { Text(item.name) }, onClick = { project = item.uuid })
                                }
                            }
                        }
                    }
                    if (query.isNotBlank()) {
                        TextButton(onClick = { searchHorizon = if (searchHorizon == 90) 365 else 90 }) {
                            Text(stringResource(Res.string.work_search_window, searchHorizon))
                        }
                    }
                }
            }
            if (query.isBlank()) item("calendar-grid") {
                val drag = Modifier.pointerInput(date, mode) {
                    var delta = 0f
                    detectHorizontalDragGestures(onDragStart = { delta = 0f }, onDragEnd = {
                        if (abs(delta) > 48.dp.toPx()) movePeriod(if (delta < 0f) 1 else -1)
                    }) { change, amount -> change.consume(); delta += amount }
                }
                Column(drag) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { movePeriod(-1) }) {
                            Icon(Icons.Default.ChevronLeft, stringResource(Res.string.work_previous))
                        }
                        Text(if (mode == CalendarDisplayMode.MONTH) date.toString().take(7)
                            else "${range.from.shortDate()} – ${range.through.shortDate()}",
                            style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { movePeriod(1) }) {
                            Icon(Icons.Default.ChevronRight, stringResource(Res.string.work_next))
                        }
                    }
                    if (mode != CalendarDisplayMode.LIST) {
                        Row(Modifier.fillMaxWidth()) {
                            (0..6).forEach { offset ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text(stringResource(DayOfWeek.entries[(firstDay.ordinal + offset) % 7].label()),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        generateSequence(range.from) { it.shift(1).takeIf { next -> next <= range.through } }
                            .toList().chunked(7).forEach { week ->
                                Row(Modifier.fillMaxWidth()) {
                                    week.forEach { day ->
                                        val selected = day == date
                                        val workday = options?.settings?.workingDays?.contains(day.dayOfWeek) != false
                                        val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else if (!workday) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .46f)
                                            else Color.Transparent
                                        Column(Modifier.weight(1f).heightIn(min = 43.dp)
                                            .background(container, MaterialTheme.shapes.small)
                                            .clickable { dateValue = day.toString() }.padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(day.day.toString(), color = if (day == today) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                days[day].orEmpty().map { it.draft.typeCode }.distinct().take(4).forEach { code ->
                                                    Box(Modifier.size(4.dp).background(typeColor(types[code]), CircleShape))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
            if (read is CalendarRead.Unavailable || store == null) item("calendar-error") {
                Text(stringResource(Res.string.work_unavailable), color = MaterialTheme.colorScheme.error)
            } else if (read is CalendarRead.Loading || (date == today && displayed.isEmpty() &&
                    query.isBlank() && mode != CalendarDisplayMode.LIST && fallbackRead is CalendarRead.Loading)) item("calendar-loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (displayed.isEmpty()) {
                item("calendar-empty") { Text(stringResource(Res.string.work_none), Modifier.padding(12.dp)) }
            }
            items(displayed, key = { "${it.key.rootUuid}/${it.key.originalDate}" }) { work ->
                CalendarWorkRow(work, types[work.draft.typeCode], zone, now, onEdit,
                    selectedDate = date.takeIf { query.isBlank() && mode != CalendarDisplayMode.LIST })
            }
        }
    }
}

@Composable
internal fun CalendarWorkRow(
    work: CalendarWork, type: CalendarWorkType?, zone: TimeZone, now: Instant,
    onEdit: (CalendarEditorRoute) -> Unit,
    selectedDate: LocalDate? = null,
) {
    val status = work.draft.status
    val context = CalendarEditorRoute.Open(work.key)
    var menu by remember { mutableStateOf(false) }
    val color = if (status.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val obsolete = status.active && !work.upcoming(now)
    Surface(color = MaterialTheme.colorScheme.surface, contentColor = color, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().pointerInput(work.key) {
            var delta = 0f
            detectHorizontalDragGestures(onDragStart = { delta = 0f }, onDragEnd = { if (delta > 42.dp.toPx()) menu = true }) {
                change, dragAmount -> change.consume(); delta += dragAmount
            }
        }.clickable { onEdit(context) }.padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(typeIcon(type), null, tint = typeColor(type), modifier = Modifier.size(19.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(work.draft.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (status == WorkStatus.CANCELLED) TextDecoration.LineThrough else null)
                val zoneLabel = if (work.draft.schedule.zone != zone) " · ${work.draft.schedule.zone.id}" else ""
                val localZone = work.draft.schedule.zone
                val relevant = work.days.firstOrNull { day ->
                    if (work.draft.schedule.mode == WorkTimeMode.TIMED)
                        day.date == selectedDate || day.interval?.start?.toLocalDateTime(zone)?.date == selectedDate
                    else day.date == selectedDate
                } ?: work.days.firstOrNull { it.interval?.endExclusive?.let { end -> end > now } ?: (it.date >= now.toLocalDateTime(localZone).date) }
                    ?: work.days.first()
                val modeLabel = when (work.draft.schedule.mode) {
                    WorkTimeMode.TIMED -> relevant.interval?.let { interval ->
                        "${interval.start.toLocalDateTime(localZone).time.shortTime()}–${interval.endExclusive.toLocalDateTime(localZone).time.shortTime()}"
                    }.orEmpty()
                    else -> stringResource(work.draft.schedule.mode.label())
                }
                Text(listOfNotNull("${relevant.date.shortDate()} · $modeLabel$zoneLabel", work.projectLabel)
                    .joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall)
            }
            val icon = if (obsolete) Icons.Default.WarningAmber else when (status) {
                WorkStatus.PLANNED -> Icons.Default.Schedule
                WorkStatus.CONFIRMED -> Icons.Default.EventAvailable
                WorkStatus.COMPLETED -> Icons.Default.CheckCircle
                WorkStatus.CANCELLED -> Icons.Default.Cancel
            }
            Icon(icon, stringResource(if (obsolete) Res.string.work_overdue else status.label()), modifier = Modifier.size(17.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(Res.string.work_edit)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.work_edit)) },
                        onClick = { menu = false; onEdit(context) })
                    if (work.draft.schedule.days.size == 1 && status.active) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.work_move)) },
                            onClick = { menu = false; onEdit(context.copy(move = true)) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(Res.string.oc_delete)) },
                        onClick = { menu = false; onEdit(context.copy(delete = true)) })
                }
            }
        }
    }
}

@Composable
internal fun CalendarMenu(label: String, options: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(label) { expanded = false }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options()
        }
    }
}

@Composable
internal fun <T> calendarState(flow: kotlinx.coroutines.flow.Flow<CalendarRead<T>>?, previous: T? = null): CalendarRead<T> =
    flow?.collectAsState(if (previous == null) CalendarRead.Loading else CalendarRead.Ready(previous))?.value
        ?: CalendarRead.Unavailable()

internal fun <T> CalendarRead<T>.data(): T? = when (this) {
    CalendarRead.Loading -> null
    is CalendarRead.Ready -> value
    is CalendarRead.Unavailable -> previous
}

@Composable
internal fun calendarNow(): Instant {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Clock.System.now() } }
    return now
}

private fun periodOf(date: LocalDate, mode: CalendarDisplayMode, first: DayOfWeek): WorkDateRange {
    fun weekStart(day: LocalDate) = day.shift(-((day.dayOfWeek.ordinal - first.ordinal + 7) % 7))
    return when (mode) {
        CalendarDisplayMode.WEEK -> weekStart(date).let { WorkDateRange(it, it.shift(6)) }
        CalendarDisplayMode.MONTH -> {
            val month = LocalDate(date.year, date.monthNumber, 1)
            val end = month.plus(DatePeriod(months = 1)).shift(-1)
            WorkDateRange(weekStart(month), weekStart(end).shift(6))
        }
        CalendarDisplayMode.LIST -> WorkDateRange(date, date.shift(30))
    }
}

private fun nextPeriod(date: LocalDate, mode: CalendarDisplayMode, direction: Int) = when (mode) {
    CalendarDisplayMode.WEEK -> date.shift(direction * 7)
    CalendarDisplayMode.MONTH -> date.plus(DatePeriod(months = direction))
    CalendarDisplayMode.LIST -> date.shift(direction * 31)
}

private fun CalendarDisplayMode.label(): StringResource = when (this) {
    CalendarDisplayMode.WEEK -> Res.string.work_week
    CalendarDisplayMode.MONTH -> Res.string.work_month
    CalendarDisplayMode.LIST -> Res.string.work_list
}

internal fun DayOfWeek.label(): StringResource = listOf(Res.string.work_monday, Res.string.work_tuesday,
    Res.string.work_wednesday, Res.string.work_thursday, Res.string.work_friday, Res.string.work_saturday,
    Res.string.work_sunday)[ordinal]

internal fun WorkStatus.label() = when (this) {
    WorkStatus.PLANNED -> Res.string.work_planned
    WorkStatus.CONFIRMED -> Res.string.work_confirmed
    WorkStatus.COMPLETED -> Res.string.work_completed
    WorkStatus.CANCELLED -> Res.string.work_cancelled
}
internal fun WorkTimeMode.label() = when (this) {
    WorkTimeMode.TIMED -> Res.string.work_timed
    WorkTimeMode.ALL_DAY -> Res.string.work_all_day
    WorkTimeMode.DATE_ONLY -> Res.string.work_date_only
}
@Composable
internal fun calendarTypeLabel(type: CalendarWorkType?) = type?.label ?: stringResource(when (type?.code) {
    "INSTALL" -> Res.string.work_install
    "MEASURE" -> Res.string.work_measure
    "DELIVER" -> Res.string.work_delivery
    else -> Res.string.work_other
})
internal fun typeColor(type: CalendarWorkType?) = Color(0xff000000 or (type?.color ?: 9408475).toLong())
internal fun typeIcon(type: CalendarWorkType?) = when (type?.icon) {
    "measure" -> Icons.Default.SquareFoot
    "install" -> Icons.Default.Handyman
    "deliver" -> Icons.Default.LocalShipping
    else -> Icons.Default.Event
}
internal fun LocalDate.shortDate() = "${day.toString().padStart(2, '0')}.${monthNumber.toString().padStart(2, '0')}"
internal fun kotlinx.datetime.LocalTime?.shortTime() = this?.toString()?.take(5).orEmpty()
