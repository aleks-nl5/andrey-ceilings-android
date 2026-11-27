package com.andreyceilings.shared.calendar

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.andreyceilings.shared.ui.CeilingsPopupMenu
import com.andreyceilings.shared.ui.ContextFeedbackIcon
import com.andreyceilings.shared.ui.ContextFeedbackKind
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
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

enum class CalendarDisplayMode { WEEK, MONTH, LIST }

sealed interface CalendarEditorRoute {
    data class Create(val date: LocalDate? = null, val projectUuid: String? = null, val installation: Boolean = false) : CalendarEditorRoute
    data class Open(val key: WorkOccurrenceKey, val move: Boolean = false, val delete: Boolean = false, val edit: Boolean = false) : CalendarEditorRoute
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
    val optionsRead = calendarOptionsState(store)
    val options = optionsRead.data()
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
    val period = key(store, range, zone) {
        if (store != null && options == null && optionsRead is CalendarRead.Loading) CalendarRead.Loading
        else calendarState(currentPeriod, store?.cached(range, zone))
    }
    val fallbackRead = upcomingCalendarState(store, today, project, 12, now, type)
    val upcoming = fallbackRead.data().orEmpty().filter { it.upcoming(now) }
    var searchHorizon by rememberSaveable { mutableIntStateOf(90) }
    val searchFlow = remember(store, query, searchHorizon) {
        if (query.isBlank()) null else store?.search(query, searchHorizon)
    }
    val search = key(store, query, searchHorizon) { if (query.isBlank()) CalendarRead.Loading else calendarState(searchFlow) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val types = options?.types.orEmpty().associateBy { it.code }

    fun movePeriod(direction: Int) {
        dateValue = nextPeriod(date, mode, direction).toString()
        scope.launch { list.scrollToItem(0) }
    }
    LaunchedEffect(store, range, zone, mode, firstDay, options != null) {
        if (options == null) return@LaunchedEffect
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
    val dayMode = query.isBlank() && mode != CalendarDisplayMode.LIST
    val fallbackNeeded = dayMode && exact.isEmpty()
    val displayed = when {
        query.isNotBlank() -> filtered
        mode == CalendarDisplayMode.LIST -> filtered
        exact.isNotEmpty() -> exact
        period.data() == null -> emptyList()
        else -> upcoming.filter { (type == null || it.draft.typeCode == type) &&
            (status == null || it.draft.status.name == status) }.take(3)
    }
    val grouped = if (query.isBlank() && mode == CalendarDisplayMode.LIST)
        days.entries.filter { it.key in range }.sortedBy { it.key } else emptyList()
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
                        if (projectFilter != null) TodayButton(today) {
                            dateValue = null
                            scope.launch { list.scrollToItem(0) }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(Icons.Default.FilterList, stringResource(Res.string.work_filters))
                        }
                        IconButton(onClick = { onEdit(CalendarEditorRoute.Create(date, projectFilter)) }, enabled = store != null) {
                            Icon(Icons.Default.Add, stringResource(Res.string.work_add))
                        }
                    }
                    if (projectFilter == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CalendarDisplayMode.entries.forEach {
                            FilterChip(selected = mode == it, onClick = { onMode(it) },
                                label = { Text(stringResource(it.label()), maxLines = 1) })
                        }
                        Spacer(Modifier.weight(1f))
                        TodayButton(today) { dateValue = null; scope.launch { list.scrollToItem(0) } }
                    }
                    if (expanded) {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.work_search)) }, singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CalendarMenu(modifier = Modifier.weight(1f), label = type?.let { calendarTypeLabel(types[it]) } ?: stringResource(Res.string.work_type)) {
                                CalendarMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { type = null })
                                types.values.forEach { item ->
                                    CalendarMenuItem(text = { Text(calendarTypeLabel(item)) }, onClick = { type = item.code })
                                }
                            }
                            CalendarMenu(modifier = Modifier.weight(1f), label = status?.let { stringResource(WorkStatus.valueOf(it).label()) }
                                ?: stringResource(Res.string.work_status)) {
                                CalendarMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { status = null })
                                WorkStatus.entries.forEach { item ->
                                    CalendarMenuItem(text = { Text(stringResource(item.label())) }, onClick = { status = item.name })
                                }
                            }
                            if (projectFilter == null) CalendarMenu(modifier = Modifier.weight(1f), label = options?.projects?.firstOrNull { it.uuid == project }?.name
                                ?: stringResource(Res.string.work_object)) {
                                CalendarMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { project = null })
                                options?.projects.orEmpty().forEach { item ->
                                    CalendarMenuItem(text = { Text(item.name) }, onClick = { project = item.uuid })
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
                var dragOffset by remember(range) { mutableFloatStateOf(0f) }
                val visualOffset by animateFloatAsState(dragOffset)
                val drag = Modifier.pointerInput(date, mode) {
                    var delta = 0f
                    detectHorizontalDragGestures(onDragStart = { delta = 0f }, onDragCancel = { dragOffset = 0f }, onDragEnd = {
                        dragOffset = 0f
                        if (abs(delta) > 48.dp.toPx()) movePeriod(if (delta < 0f) 1 else -1)
                    }) { change, amount -> change.consume(); delta += amount
                        dragOffset = delta.coerceIn(-size.width.toFloat(), size.width.toFloat())
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(drag)) {
                AnimatedContent(targetState = range, transitionSpec = {
                    val forward = targetState.from > initialState.from
                    (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it else it } + fadeOut())
                }) { visualRange ->
                val visualDays = if (visualRange == range) days else store?.cached(visualRange, zone).orEmpty().filter {
                    (project == null || it.draft.projectUuid == project) && (type == null || it.draft.typeCode == type) &&
                        (status == null || it.draft.status.name == status)
                }.flatMap { work -> work.displayDates(zone).map { it to work } }.groupBy({ it.first }, { it.second })
                Column(Modifier.graphicsLayer { translationX = visualOffset }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { movePeriod(-1) }) {
                            Icon(Icons.Default.ChevronLeft, stringResource(Res.string.work_previous))
                        }
                        Text(if (mode == CalendarDisplayMode.MONTH) visualRange.from.shift(7).toString().take(7)
                            else "${visualRange.from.shortDate()} – ${visualRange.through.shortDate()}",
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
                        generateSequence(visualRange.from) { it.shift(1).takeIf { next -> next <= visualRange.through } }
                            .toList().chunked(7).forEach { week ->
                                Row(Modifier.fillMaxWidth()) {
                                    week.forEach { day ->
                                        val selected = day == date
                                        val workday = options?.settings?.workingDays?.contains(day.dayOfWeek) != false
                                        val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else if (!workday) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .46f)
                                            else Color.Transparent
                                        Column(Modifier.weight(1f).heightIn(min = 43.dp)
                                            .clip(RoundedCornerShape(22.dp)).background(container)
                                            .clickable { dateValue = day.toString() }.padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(day.day.toString(), color = if (day == today) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                visualDays[day].orEmpty().map { it.draft.typeCode }.distinct().take(4).forEach { code ->
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
            }
            }
            if (read is CalendarRead.Unavailable || store == null || optionsRead is CalendarRead.Unavailable) item("calendar-error") {
                CalendarFeedback(Res.string.work_unavailable)
            } else if (read is CalendarRead.Loading) item("calendar-loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (fallbackNeeded || displayed.isEmpty()) {
                item("calendar-empty") { CalendarFeedback(Res.string.work_none, ContextFeedbackKind.INFO) }
                if (fallbackNeeded && fallbackRead is CalendarRead.Loading) item("calendar-fallback-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (fallbackNeeded && fallbackRead is CalendarRead.Unavailable) item("calendar-fallback-error") {
                    CalendarFeedback(Res.string.work_unavailable)
                }
                if (fallbackNeeded && displayed.isNotEmpty()) item("calendar-fallback-header") {
                    Text(stringResource(Res.string.p6c_upcoming), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            if (query.isBlank() && mode == CalendarDisplayMode.LIST) {
                grouped.forEach { (day, works) ->
                    item("calendar-day/$day") {
                        Text(day.shortDate(), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                    val ordered = works.sortedBy { work ->
                        work.days.firstOrNull { scheduled ->
                            scheduled.interval?.let { interval ->
                                day >= interval.start.toLocalDateTime(zone).date &&
                                    day <= (interval.endExclusive - 1.nanoseconds).toLocalDateTime(zone).date
                            } ?: (scheduled.date == day)
                        }?.interval?.start
                    }
                    items(ordered, key = { "$day/${it.key.rootUuid}/${it.key.originalDate}" }) { work ->
                        CalendarWorkRow(work, types[work.draft.typeCode], zone, now, onEdit, selectedDate = day)
                    }
                }
            } else items(displayed, key = { "${it.key.rootUuid}/${it.key.originalDate}" }) { work ->
                CalendarWorkRow(work, types[work.draft.typeCode], zone, now, onEdit,
                    selectedDate = date.takeIf { dayMode && !fallbackNeeded })
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
    var swipeOffset by remember(work.key) { mutableFloatStateOf(0f) }
    val swipeVisual by animateFloatAsState(swipeOffset)
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)) {
        if (swipeVisual > 1f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterStart) {
            Icon(Icons.Default.Delete, stringResource(Res.string.oc_delete), Modifier.padding(start = 14.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        }
        Surface(modifier = Modifier.graphicsLayer { translationX = swipeVisual },
            color = MaterialTheme.colorScheme.surface, contentColor = color, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().pointerInput(work.key) {
            var delta = 0f
            detectHorizontalDragGestures(onDragStart = { delta = 0f }, onDragCancel = { swipeOffset = 0f }, onDragEnd = {
                swipeOffset = 0f
                if (delta > 64.dp.toPx()) onEdit(context.copy(delete = true))
            }) { change, dragAmount -> change.consume(); delta += dragAmount
                swipeOffset = delta.coerceIn(0f, 104.dp.toPx())
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
                        day.date == selectedDate || day.interval?.let {
                            selectedDate != null && selectedDate >= it.start.toLocalDateTime(zone).date &&
                                selectedDate <= (it.endExclusive - 1.nanoseconds).toLocalDateTime(zone).date
                        } == true
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
                CeilingsPopupMenu(menu, onDismissRequest = { menu = false }) {
                    CalendarMenuItem(text = { Text(stringResource(Res.string.work_edit)) },
                        onClick = { menu = false; onEdit(context.copy(edit = true)) })
                    if (work.draft.schedule.days.size == 1 && status.active) {
                        CalendarMenuItem(text = { Text(stringResource(Res.string.work_move)) },
                            onClick = { menu = false; onEdit(context.copy(move = true)) })
                    }
                    CalendarMenuItem(text = { Text(stringResource(Res.string.oc_delete)) },
                        onClick = { menu = false; onEdit(context.copy(delete = true)) })
                }
            }
        }
    }
}
}

@Composable
private fun TodayButton(today: LocalDate, onClick: () -> Unit) {
    OutlinedIconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.CalendarToday, stringResource(Res.string.work_today), Modifier.size(27.dp))
            Text(today.day.toString(), Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun CalendarFeedback(message: StringResource, kind: ContextFeedbackKind = ContextFeedbackKind.ERROR) {
    val label = stringResource(message)
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ContextFeedbackIcon(label, label, kind, compact = true)
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (kind == ContextFeedbackKind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CalendarMenu(label: String, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null, options: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(label) { expanded = false }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)) {
            leading?.invoke()
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        CeilingsPopupMenu(expanded, { expanded = false }) {
            CompositionLocalProvider(LocalCalendarMenuClose provides { expanded = false }) { options() }
        }
    }
}

private val LocalCalendarMenuClose = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun CalendarMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, leadingIcon: (@Composable () -> Unit)? = null) {
    val close = LocalCalendarMenuClose.current
    DropdownMenuItem(text = text, leadingIcon = leadingIcon, onClick = { close(); onClick() })
}

@Composable
internal fun <T> calendarState(flow: kotlinx.coroutines.flow.Flow<CalendarRead<T>>?, previous: T? = null): CalendarRead<T> =
    flow?.collectAsState(if (previous == null) CalendarRead.Loading else CalendarRead.Ready(previous))?.value
        ?: CalendarRead.Unavailable()

@Composable
internal fun calendarOptionsState(store: CalendarStore?): CalendarRead<CalendarFormOptions> =
    key(store) { calendarState(remember(store) { store?.options() }, store?.cachedOptions()) }

@Composable
internal fun upcomingCalendarState(
    store: CalendarStore?, since: LocalDate, projectUuid: String?, limit: Int, now: Instant,
    typeCode: String? = null,
): CalendarRead<List<CalendarWork>> = key(store, since, projectUuid, limit, typeCode) {
    val flow = remember(store, since, projectUuid, limit, typeCode) { store?.upcoming(since, projectUuid, limit, typeCode) }
    val state = calendarState(flow, store?.cachedUpcoming(since, projectUuid, limit, typeCode))
    val works = state.data().orEmpty()
    // Remember the next boundary for this projection; do not advance it just
    // because the display clock ticked past it.
    var nextBoundary by remember(works) { mutableStateOf(works.mapNotNull { it.nextBoundary(now) }.minOrNull()) }
    val expired = nextBoundary?.let { it <= now } == true || works.any { !it.upcoming(now) }
    LaunchedEffect(store, works, nextBoundary, expired) {
        if (expired) {
            store?.refreshUpcoming()
            // The same multiday occurrence may remain in the result after a
            // day expires. Advance the remembered boundary even if equality
            // prevents the new projection from causing recomposition.
            nextBoundary = works.mapNotNull { it.nextBoundary(now) }.filter { it > now }.minOrNull()
        }
    }
    state
}

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
