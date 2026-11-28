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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import com.andreyceilings.shared.objects.CardSwipeState
import com.andreyceilings.shared.objects.SwipeActionsSurface
import com.andreyceilings.shared.objects.SwipeTrayAction
import com.andreyceilings.shared.objects.dismissCardSwipeOnInteraction
import com.andreyceilings.shared.objects.ObjectsClientsCreateFab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

enum class CalendarDisplayMode { WEEK, MONTH, LIST }

// Outgoing AnimatedContent must retain its own mode and weekday layout.
private data class CalendarVisualPeriod(val range: WorkDateRange, val mode: CalendarDisplayMode, val firstDay: DayOfWeek)

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
    showWeekNumbers: Boolean = true,
    onShowWeekNumbersChange: ((Boolean) -> Unit)? = null,
) {
    var retry by remember { mutableIntStateOf(0) }
    val optionsRead = key(retry) { calendarOptionsState(store) }
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
    val workingDays = options?.settings?.workingDays ?: DayOfWeek.entries.take(5).toSet()
    val colors = MaterialTheme.colorScheme
    val light = colors.background.luminance() > .4f
    val weekdayBackground = colors.surfaceVariant.copy(alpha = if (light) .95f else .46f)
    val weekendBackground = if (light) Color(0xFFFCEEF1) else Color(0xFF3D3036)
    val range = periodOf(date, mode, firstDay)
    val integration = LocalCalendarIntegrationPlatform.current
    val cachedAvailability = integration?.cachedAvailability(range, zone) ?: CalendarAvailability.Disabled
    val availability by produceState(cachedAvailability, integration?.revision, range, zone,
        projectFilter, query.isBlank()) {
        value = if (integration != null && projectFilter == null && query.isBlank()) integration.refreshAvailability(range, zone)
            else CalendarAvailability.Disabled
    }
    val busyByDate = remember(availability, range, zone) {
        val busy = (availability as? CalendarAvailability.Available)?.busy.orEmpty()
        generateSequence(range.from) { it.shift(1).takeIf { next -> next <= range.through } }.associateWith { day ->
            val start = day.atStartOfDayIn(zone)
            val end = day.shift(1).atStartOfDayIn(zone)
            busy.filter { it.interval.start < end && it.interval.endExclusive > start }.sortedBy { it.interval.start }
        }.filterValues { it.isNotEmpty() }
    }
    val currentPeriod = remember(store, range, zone, retry) { store?.period(range, zone) }
    val period = key(store, range, zone, retry) {
        if (store != null && options == null && optionsRead is CalendarRead.Loading) CalendarRead.Loading
        else calendarState(currentPeriod, store?.cached(range, zone))
    }
    val fallbackRead = key(retry) {
        if (options == null && optionsRead is CalendarRead.Loading) CalendarRead.Loading
        else upcomingCalendarState(store, today, project, 12, now, type)
    }
    val upcoming = fallbackRead.data().orEmpty().filter { it.upcoming(now) }
    var searchHorizon by rememberSaveable { mutableIntStateOf(90) }
    val searchFlow = remember(store, query, searchHorizon, retry) {
        if (query.isBlank()) null else store?.search(query, searchHorizon)
    }
    val search = key(store, query, searchHorizon, retry) { if (query.isBlank()) CalendarRead.Loading else calendarState(searchFlow) }
    val list = rememberLazyListState()
    val swipes = remember { CardSwipeState() }
    var fabVisible by remember { mutableStateOf(true) }
    val scrollEvents = remember { Channel<Unit>(Channel.CONFLATED) }
    val fabScroll = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val canMove = if (available.y < 0f) list.canScrollForward else list.canScrollBackward
            if (source == NestedScrollSource.UserInput && abs(available.y) > 0.5f && canMove) {
                fabVisible = false; swipes.dismiss(); scrollEvents.trySend(Unit)
            }
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (abs(consumed.y) > 0.5f) { fabVisible = false; swipes.dismiss(); scrollEvents.trySend(Unit) }
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            scrollEvents.trySend(Unit)
            return Velocity.Zero
        }
    } }
    LaunchedEffect(list) {
        scrollEvents.receiveAsFlow().collectLatest {
            delay(120L)
            snapshotFlow { list.isScrollInProgress }.first { !it }
            fabVisible = true
        }
    }
    LaunchedEffect(range, query, type, status, project) { swipes.dismiss() }
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
    val fallbackNeeded = dayMode && exact.isEmpty() && busyByDate[date].isNullOrEmpty() && period is CalendarRead.Ready
    val displayed = when {
        query.isNotBlank() -> filtered
        mode == CalendarDisplayMode.LIST -> filtered
        exact.isNotEmpty() || busyByDate[date].orEmpty().isNotEmpty() -> exact
        period.data() == null -> emptyList()
        else -> upcoming.filter { (type == null || it.draft.typeCode == type) &&
            (status == null || it.draft.status.name == status) }.take(3)
    }
    val grouped = if (query.isBlank() && mode == CalendarDisplayMode.LIST)
        days.filterKeys { it in range } else emptyMap()
    val read = if (query.isBlank()) period else search
    val fabBottom = if (onClose == null) maxOf(142.dp, contentPadding.calculateBottomPadding() + 8.dp) else 8.dp
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    CeilingsScreenScaffold(
        modifier = Modifier.dismissCardSwipeOnInteraction(swipes),
        contentPadding = PaddingValues(start = contentPadding.calculateLeftPadding(direction), end = contentPadding.calculateRightPadding(direction),
            top = contentPadding.calculateTopPadding(), bottom = maxOf(contentPadding.calculateBottomPadding(), fabBottom + 84.dp)),
        windowInsets = WindowInsets(0, 0, 0, 0),
        topGradient = {},
        bottomGradient = false,
        overlay = {
            ObjectsClientsCreateFab(fabVisible && store != null, stringResource(Res.string.work_add),
                Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = fabBottom)) {
                swipes.dismiss()
                onEdit(CalendarEditorRoute.Create(date, projectFilter))
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().nestedScroll(fabScroll), state = list, contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item("calendar-controls") {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onClose != null) IconButton(onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.p5d_cancel))
                        }
                        if (projectFilter == null) CalendarDisplayMode.entries.forEach {
                            FilterChip(selected = mode == it, onClick = { onMode(it) },
                                label = { Text(stringResource(it.label()), maxLines = 1, style = MaterialTheme.typography.bodyMedium) })
                        }
                        Spacer(Modifier.weight(1f))
                        TodayButton(today) { dateValue = null; scope.launch { list.scrollToItem(0) } }
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(Icons.Default.FilterList, stringResource(Res.string.work_filters),
                                tint = if (expanded || type != null || status != null || project != projectFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (expanded) {
                        if (onShowWeekNumbersChange != null && mode != CalendarDisplayMode.LIST) WeekNumbersSetting(showWeekNumbers, onShowWeekNumbersChange)
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.work_search)) }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CalendarMenu(modifier = Modifier.weight(1f), fillWidth = true, menuModifier = Modifier.height(240.dp), label = type?.let { calendarTypeLabel(types[it]) } ?: stringResource(Res.string.work_type)) {
                                CalendarMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { type = null })
                                types.values.forEach { item ->
                                    CalendarMenuItem(text = { Text(calendarTypeLabel(item)) }, onClick = { type = item.code })
                                }
                            }
                            CalendarMenu(modifier = Modifier.weight(1f), fillWidth = true, menuModifier = Modifier.height(240.dp), label = status?.let { stringResource(WorkStatus.valueOf(it).label()) }
                                ?: stringResource(Res.string.work_status)) {
                                CalendarMenuItem(text = { Text(stringResource(Res.string.work_all)) }, onClick = { status = null })
                                WorkStatus.entries.forEach { item ->
                                    CalendarMenuItem(text = { Text(stringResource(item.label())) }, onClick = { status = item.name })
                                }
                            }
                            if (projectFilter == null) CalendarMenu(modifier = Modifier.weight(1f), fillWidth = true, menuModifier = Modifier.height(240.dp), label = options?.projects?.firstOrNull { it.uuid == project }?.name
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
                AnimatedContent(targetState = CalendarVisualPeriod(range, mode, firstDay), transitionSpec = {
                    val forward = targetState.range.from > initialState.range.from
                    (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it else it } + fadeOut())
                }) { visualPeriod ->
                val (visualRange, visualMode, visualFirstDay) = visualPeriod
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
                        Text(if (visualMode == CalendarDisplayMode.MONTH) visualRange.from.shift(7).let { it.monthNumber.toString().padStart(2, '0') + "." + it.year }
                            else "${visualRange.from.shortDate()} – ${visualRange.through.shortDate()}",
                            style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { movePeriod(1) }) {
                            Icon(Icons.Default.ChevronRight, stringResource(Res.string.work_next))
                        }
                    }
                    if (visualMode != CalendarDisplayMode.LIST) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (showWeekNumbers) Box(Modifier.width(30.dp).height(28.dp)
                                .clip(RoundedCornerShape(9.dp)).background(weekdayBackground), contentAlignment = Alignment.Center) {
                                Text(stringResource(Res.string.work_week_number_short), style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant)
                            }
                            (0..6).forEach { offset ->
                                val weekday = DayOfWeek.entries[(visualFirstDay.ordinal + offset) % 7]
                                Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(9.dp))
                                    .background(if (weekday in workingDays) weekdayBackground else weekendBackground),
                                    contentAlignment = Alignment.Center) {
                                    Text(stringResource(weekday.label()), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        generateSequence(visualRange.from) { it.shift(1).takeIf { next -> next <= visualRange.through } }
                            .toList().chunked(7).forEach { week ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (showWeekNumbers) Box(Modifier.width(30.dp).height(43.dp)
                                        .clip(RoundedCornerShape(10.dp)).background(weekdayBackground), contentAlignment = Alignment.Center) {
                                        Text(calendarRowIsoWeekNumber(week.first()).toString(),
                                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    }
                                    week.forEach { day ->
                                        val selected = day == date
                                        val workday = day.dayOfWeek in workingDays
                                        val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else if (!workday) weekendBackground
                                            else Color.Transparent
                                        Column(Modifier.weight(1f).heightIn(min = 43.dp)
                                            .clip(RoundedCornerShape(22.dp)).background(container)
                                            .clickable { dateValue = day.toString() }.padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(day.day.toString(), color = if (day == today) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                visualDays[day].orEmpty().map { it.draft.typeCode }.distinct().take(4).forEach { code ->
                                                    Box(Modifier.size(4.dp).background(typeColor(types[code]), CircleShape))
                                                }
                                                if (visualRange == range && busyByDate[day].orEmpty().isNotEmpty()) {
                                                    val description = stringResource(Res.string.p7b_busy)
                                                    Box(Modifier.size(4.dp).background(colors.onSurfaceVariant, CircleShape)
                                                        .semantics { contentDescription = description })
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                            }
                    }
                }
            }
            }
            }
            if (read is CalendarRead.Unavailable || store == null || optionsRead is CalendarRead.Unavailable) item("calendar-error") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CalendarFeedback(Res.string.work_unavailable) }
                    if (store != null) IconButton(onClick = { retry++ }) { Icon(Icons.Default.Refresh, stringResource(Res.string.work_reload)) }
                }
            } else if (read is CalendarRead.Loading) item("calendar-loading") {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else if (fallbackNeeded || displayed.isEmpty() &&
                (query.isNotBlank() || if (mode == CalendarDisplayMode.LIST) busyByDate.isEmpty() else busyByDate[date].isNullOrEmpty())) {
                item("calendar-empty") { CalendarFeedback(Res.string.work_none, ContextFeedbackKind.INFO) }
                if (fallbackNeeded && fallbackRead is CalendarRead.Loading) item("calendar-fallback-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (fallbackNeeded && fallbackRead is CalendarRead.Unavailable) item("calendar-fallback-error") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { CalendarFeedback(Res.string.work_unavailable) }
                        IconButton(onClick = { retry++ }) { Icon(Icons.Default.Refresh, stringResource(Res.string.work_reload)) }
                    }
                }
                if (fallbackNeeded && displayed.isNotEmpty()) item("calendar-fallback-header") {
                    Text(stringResource(Res.string.p6c_upcoming), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            if (query.isBlank() && availability is CalendarAvailability.Unknown) item("calendar-external-unknown") {
                CalendarFeedback(Res.string.p7b_busy_unknown, ContextFeedbackKind.WARNING)
            }
            if (query.isBlank() && mode == CalendarDisplayMode.LIST) {
                (grouped.keys + busyByDate.keys).sorted().forEach { day ->
                    val works = grouped[day].orEmpty()
                    item("calendar-day/$day") {
                        Text(day.shortDate(), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                    items(calendarDayRows(works, busyByDate[day].orEmpty(), day, zone),
                        key = { "$day/${it.key}" }) { row ->
                        when (row) {
                            is CalendarDayRow.Work -> CalendarWorkRow(row.work, types[row.work.draft.typeCode], zone,
                                now, onEdit, selectedDate = day, swipeState = swipes)
                            is CalendarDayRow.Busy -> ExternalBusyRow(row.busy, day, zone)
                        }
                    }
                }
            } else {
                val busy = if (query.isBlank() && !fallbackNeeded) busyByDate[date].orEmpty() else emptyList()
                if (busy.isNotEmpty()) {
                    items(calendarDayRows(displayed, busy, date, zone), key = { it.key }) { row ->
                        when (row) {
                            is CalendarDayRow.Work -> CalendarWorkRow(row.work, types[row.work.draft.typeCode],
                                zone, now, onEdit, selectedDate = date, swipeState = swipes)
                            is CalendarDayRow.Busy -> ExternalBusyRow(row.busy, date, zone)
                        }
                    }
                } else items(displayed, key = { "${it.key.rootUuid}/${it.key.originalDate}" }) { work ->
                    CalendarWorkRow(work, types[work.draft.typeCode], zone, now, onEdit,
                        selectedDate = date.takeIf { dayMode && !fallbackNeeded }, swipeState = swipes)
                }
            }
        }
    }
}

@Composable
internal fun CalendarWorkRow(
    work: CalendarWork, type: CalendarWorkType?, zone: TimeZone, now: Instant,
    onEdit: (CalendarEditorRoute) -> Unit,
    selectedDate: LocalDate? = null,
    swipeState: CardSwipeState = remember { CardSwipeState() },
) {
    val status = work.draft.status
    val context = CalendarEditorRoute.Open(work.key)
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val color = if (status.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val obsolete = status.active && !work.upcoming(now)
    val id = "${work.key.rootUuid}/${work.key.originalDate}/${selectedDate ?: work.draft.date}"
    var opened by swipeState.opened
    fun copyInfo() {
        val summary = buildList {
            add(work.draft.title)
            work.days.forEach { day ->
                val time = if (work.draft.schedule.mode == WorkTimeMode.TIMED) day.interval?.let { interval ->
                    interval.start.toLocalDateTime(work.draft.schedule.zone).time.shortTime() + "–" + interval.endExclusive.toLocalDateTime(work.draft.schedule.zone).time.shortTime()
                }.orEmpty() else ""
                add(listOf(day.date.shortDate(), time).filter { it.isNotBlank() }.joinToString(" · "))
            }
            addAll(listOfNotNull(work.projectLabel, work.contact.name, work.contact.address, work.contact.phone, work.contact.email, work.draft.notes).filter { it.isNotBlank() }.distinct())
        }.joinToString("\n")
        clipboard.setText(AnnotatedString(summary))
    }
    val delete = { onEdit(context.copy(delete = true)) }
    val tray = listOf(
        SwipeTrayAction(stringResource(Res.string.oc_delete), Icons.Default.Delete, destructive = true, onClick = delete),
        SwipeTrayAction(stringResource(Res.string.work_copy_info), Icons.Default.ContentCopy, onClick = ::copyInfo),
        SwipeTrayAction(stringResource(Res.string.work_move), Icons.Default.Event,
            enabled = work.draft.schedule.days.size == 1 && status.active, onClick = { onEdit(context.copy(move = true)) }),
        SwipeTrayAction(stringResource(Res.string.work_edit), Icons.Default.Edit, onClick = { onEdit(context.copy(edit = true)) })
    )
    SwipeActionsSurface(id, emptyList(), opened, { opened = it }, { swipeState.dismiss() },
        onDelete = delete, onAction = {}, deleteTrayHeight = 58.dp, trayActions = tray.filterNot { it.destructive }) {
        Surface(color = MaterialTheme.colorScheme.surface, contentColor = color, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().clickable {
            if (opened?.id == id) swipeState.dismiss() else onEdit(context)
        }.padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(typeIcon(type), null, tint = typeColor(type), modifier = Modifier.size(19.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(work.draft.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
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
                    style = MaterialTheme.typography.bodyMedium)
            }
            val icon = if (obsolete) Icons.Default.WarningAmber else when (status) {
                WorkStatus.PLANNED -> Icons.Default.Schedule
                WorkStatus.CONFIRMED -> Icons.Default.EventAvailable
                WorkStatus.COMPLETED -> Icons.Default.CheckCircle
                WorkStatus.CANCELLED -> Icons.Default.Cancel
            }
            Icon(icon, stringResource(if (obsolete) Res.string.work_overdue else status.label()), modifier = Modifier.size(17.dp))
            if (work.key in LocalCalendarIntegrationPlatform.current?.problems.orEmpty()) {
                val label = stringResource(Res.string.p7b_sync_problem)
                ContextFeedbackIcon(label, label, ContextFeedbackKind.WARNING, compact = true)
            }
            Box {
                IconButton(onClick = { swipeState.dismiss(); menu = true }) { Icon(Icons.Default.MoreVert, stringResource(Res.string.work_edit)) }
                CeilingsPopupMenu(menu, onDismissRequest = { menu = false }) {
                    tray.filter { it.enabled }.forEach { action ->
                        CalendarMenuItem(text = { Text(action.label) },
                            leadingIcon = { Icon(action.icon, null, tint = if (action.destructive)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { menu = false; swipeState.dismiss(); action.onClick() })
                    }
                }
            }
        }
    }
}
}

private sealed interface CalendarDayRow {
    val key: String
    val start: Instant
    data class Work(val work: CalendarWork, override val start: Instant) : CalendarDayRow {
        override val key = "${work.key.rootUuid}/${work.key.originalDate}"
    }
    data class Busy(val busy: ExternalBusyInterval, override val start: Instant) : CalendarDayRow {
        override val key = "external/${busy.interval.start}/${busy.interval.endExclusive}/${busy.allDay}"
    }
}

private fun calendarDayRows(works: List<CalendarWork>, busy: List<ExternalBusyInterval>, day: LocalDate,
    zone: TimeZone): List<CalendarDayRow> {
    val from = day.atStartOfDayIn(zone)
    val through = day.shift(1).atStartOfDayIn(zone)
    val workRows = works.map { work ->
        val start = work.days.firstOrNull { scheduled ->
            if (work.draft.schedule.mode != WorkTimeMode.TIMED) scheduled.date == day
            else scheduled.interval?.let { it.start < through && it.endExclusive > from } == true
        }?.interval?.start
        CalendarDayRow.Work(work, maxOf(from, start ?: from))
    }
    return (workRows + busy.map { CalendarDayRow.Busy(it, maxOf(from, it.interval.start)) })
        .sortedBy { it.start }
}

@Composable
private fun ExternalBusyRow(busy: ExternalBusyInterval, day: LocalDate, zone: TimeZone) {
    val from = day.atStartOfDayIn(zone)
    val through = day.shift(1).atStartOfDayIn(zone)
    val timing = if (busy.allDay) stringResource(Res.string.work_all_day) else {
        val start = maxOf(busy.interval.start, from).toLocalDateTime(zone).time.shortTime()
        val end = if (busy.interval.endExclusive >= through) "24:00"
            else busy.interval.endExclusive.toLocalDateTime(zone).time.shortTime()
        "$start–$end"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Schedule, null, Modifier.size(19.dp))
            Text(stringResource(Res.string.p7b_busy), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(timing, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TodayButton(today: LocalDate, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
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
    leading: (@Composable () -> Unit)? = null, fillWidth: Boolean = false, menuModifier: Modifier = Modifier,
    options: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(label) { expanded = false }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)) {
            if (leading != null) { leading(); Spacer(Modifier.width(6.dp)) }
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        CeilingsPopupMenu(expanded, { expanded = false }, modifier = menuModifier) {
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

/** ISO-8601: the week belongs to the year containing its Thursday. */
internal fun isoWeekNumber(date: LocalDate): Int {
    val thursday = date.shift(3 - date.dayOfWeek.ordinal)
    val january4 = LocalDate(thursday.year, 1, 4)
    val firstThursday = january4.shift(3 - january4.dayOfWeek.ordinal)
    return ((thursday.dayNumber() - firstThursday.dayNumber()) / 7).toInt() + 1
}

/** Number the displayed row by its Thursday, even if a transient range is incomplete. */
internal fun calendarRowIsoWeekNumber(firstDate: LocalDate): Int =
    isoWeekNumber(firstDate.shift((DayOfWeek.THURSDAY.ordinal - firstDate.dayOfWeek.ordinal + 7) % 7))

internal fun periodOf(date: LocalDate, mode: CalendarDisplayMode, first: DayOfWeek): WorkDateRange {
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
internal fun WorkStatus.workIcon() = when (this) {
    WorkStatus.PLANNED -> Icons.Default.Schedule
    WorkStatus.CONFIRMED -> Icons.Default.EventAvailable
    WorkStatus.COMPLETED -> Icons.Default.CheckCircle
    WorkStatus.CANCELLED -> Icons.Default.Cancel
}
@Composable
internal fun WorkStatus.workTint() = when (this) {
    WorkStatus.PLANNED -> MaterialTheme.colorScheme.onSurfaceVariant
    WorkStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
    WorkStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    WorkStatus.CANCELLED -> MaterialTheme.colorScheme.error
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
internal fun LocalDate.shortDate() = "${day.toString().padStart(2, '0')}.${monthNumber.toString().padStart(2, '0')}.${year.toString().padStart(4, '0')}"
internal fun kotlinx.datetime.LocalTime?.shortTime() = this?.toString()?.take(5).orEmpty()
