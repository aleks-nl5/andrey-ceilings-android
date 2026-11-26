package com.andreyceilings.shared.registry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.ui.CeilingsScreenScaffold
import com.andreyceilings.shared.navigation.AppHeaderHeight
import com.andreyceilings.shared.objects.CompactObjectsSearchField
import com.andreyceilings.shared.measurement.MeasurementDrawingContour
import kotlinx.datetime.TimeZone
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.estimate.ConsolidatedComposerSession
import com.andreyceilings.shared.estimate.ConsolidatedEstimateScreen
import com.andreyceilings.shared.estimate.EstimateWorkflowStatus
import com.andreyceilings.shared.estimate.SavedIndividualQuoteDialog
import com.andreyceilings.shared.measurement.workspaceStatusAppearance
import com.andreyceilings.shared.measurement.workspaceStatusLabel
import com.andreyceilings.shared.navigation.rememberRegistryScroll
import com.andreyceilings.shared.objects.CompactCeilingCard
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.objects.ProjectMeasurement
import com.andreyceilings.shared.objects.compactQuoteAmount
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource


internal sealed interface RegistryRead<out T> {
    data object Loading : RegistryRead<Nothing>
    data object Unavailable : RegistryRead<Nothing>
    data class Ready<T>(val data: T) : RegistryRead<T>
}

@Composable
internal fun <T : Any> registryRead(flow: Flow<T?>?, retry: Int = 0): RegistryRead<T> =
    key(flow) { produceState<RegistryRead<T>>(RegistryRead.Loading, retry) {
        value = RegistryRead.Loading
        if (flow == null) {
            value = RegistryRead.Unavailable
        } else try {
            flow.collect { rows -> value = rows?.let { RegistryRead.Ready(it) } ?: RegistryRead.Unavailable }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = RegistryRead.Unavailable
        }
    }.value }

@Composable
fun CeilingRegistryScreen(
    store: ObjectsClientsStore?,
    contentPadding: PaddingValues,
    initialSearch: String = "",
    searchRequest: Int = 0,
    headerVisibleFraction: Float = 1f,
    onOpen: (Long, Long) -> Unit
) {
    val controls = rememberRegistryControls(RegistrySort.CREATED, initialSearch, searchRequest)
    var retry by remember { mutableIntStateOf(0) }
    val baseFlow = remember(store) { store?.observeRegistryCeilings() }
    val financeFlow = remember(store) { store?.observeRegistryCeilingFinance() }
    val base = registryRead(baseFlow, retry)
    val finance = registryRead(financeFlow, retry)
    val rows = (base as? RegistryRead.Ready)?.data
    val finances = (finance as? RegistryRead.Ready)?.data
    val filtered by produceState(rows?.takeIf { it.isEmpty() }, rows, finances, controls.filter) {
        value = rows?.let { withContext(Dispatchers.Default) { filterCeilingRegistry(it, finances, controls.filter) } }
    }
    val waitingForFinance = finance is RegistryRead.Loading &&
        (controls.filter.query.isNotBlank() || controls.filter.statuses.isNotEmpty() ||
            controls.filter.sort == RegistrySort.COST)
    val visible = filtered.takeIf { rows != null && !waitingForFinance }
    val keys = remember(visible) { visible?.map { row -> row.uuid } }
    val list = rememberRegistryScroll(keys)
    var contourKeys by remember(store) { mutableStateOf(emptyList<String>()) }
    val eligibleKeys = remember(visible) { visible.orEmpty().mapTo(hashSetOf()) { it.uuid } }
    LaunchedEffect(store, list, eligibleKeys) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged().collect { composed ->
                contourKeys = composed.filter { it in eligibleKeys }.sorted().take(40)
            }
    }
    // Keep already-rendered thumbnails while the next visible batch is read.
    var contours by remember(store) { mutableStateOf<Map<String, MeasurementDrawingContour?>>(emptyMap()) }
    LaunchedEffect(store, contourKeys, retry) {
        if (store != null && contourKeys.isNotEmpty()) {
            try {
                store.observeVisibleCeilingContours(contourKeys).collect { next ->
                    if (next != null) {
                        val retained = LinkedHashMap(contours)
                        contourKeys.forEach { retained.remove(it) }
                        retained.putAll(next)
                        while (retained.size > 160) retained.remove(retained.keys.first())
                        contours = retained
                    } else contours = emptyMap()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { contours = contours - contourKeys.toSet() }
        }
    }
    RegistryScaffold(contentPadding, headerVisibleFraction, Res.string.p6c_ceilings, controls, list) { padding ->
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = list, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        when {
            base is RegistryRead.Unavailable && store != null -> item { ReadUnavailable { retry++ } }
            visible == null -> Unit
            visible.orEmpty().isEmpty() -> item { Text(stringResource(Res.string.p6c_no_matches)) }
        }
        items(visible.orEmpty(), key = { it.uuid }) { row ->
            val amount = finances?.get(row.id)
            CompactCeilingCard(ProjectMeasurement(row.id, row.uuid, row.name, row.areaSquareMeters, row.perimeterMeters),
                contours[row.uuid], amount?.quote,
                quoteUnavailable = finance is RegistryRead.Unavailable || amount?.kind == CeilingQuoteKind.UNAVAILABLE,
                quoteLoading = finance is RegistryRead.Loading,
                address = row.address.ifBlank { row.projectTitle },
                onOpen = { onOpen(row.projectId, row.id) })
        }
    }
    }
    if (controls.open) {
        val projects = remember(rows) { rows.orEmpty().associate { it.projectId to it.address.ifBlank { it.projectTitle } } }
        RegistryOptions(controls, projects, quotes = false)
    }
}

@Composable
fun QuoteRegistryScreen(store: ObjectsClientsStore?, contentPadding: PaddingValues, initialSearch: String = "",
    searchRequest: Int = 0,
    headerVisibleFraction: Float = 1f
) {
    val controls = rememberRegistryControls(RegistrySort.LATEST, initialSearch, searchRequest)
    var retry by remember { mutableIntStateOf(0) }
    val flow = remember(store) { store?.observeRegistryQuotes() }
    val result = registryRead(flow, retry)
    val series = (result as? RegistryRead.Ready)?.data
    val filtered by produceState(series?.takeIf { it.isEmpty() }, series, controls.filter) {
        value = series?.let { withContext(Dispatchers.Default) { filterQuoteRegistry(it, controls.filter) } }
    }
    val visible = filtered.takeIf { series != null }
    var document by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val revision = document.getOrNull(0)
    val seriesId = document.getOrNull(1)
    val projectId = document.getOrNull(2)?.toLongOrNull()
    val consolidated = document.getOrNull(3) == "consolidated"
    val editor = store?.measurementDetails?.elements
    val keys = remember(visible) { visible?.map { row -> row.id } }
    val list = rememberRegistryScroll(keys, visible = !consolidated)
    if (consolidated && revision != null && seriesId != null && projectId != null && editor != null) {
        var carriedFrom by remember(revision) { mutableStateOf<String?>(null) }
        val session = remember(editor, revision, carriedFrom) {
            ConsolidatedComposerSession(projectId, revision.takeIf { carriedFrom == null }, carriedFrom)
        }
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            ConsolidatedEstimateScreen(editor, session,
                onClose = {
                    if (carriedFrom != null && session.savedRevision.value == null) carriedFrom = null
                    else document = arrayListOf()
                },
                onCarry = { carriedFrom = it },
                expectedSeriesUuid = seriesId)
        }
        return
    }
    RegistryScaffold(contentPadding, headerVisibleFraction, Res.string.p6c_quotes, controls, list) { padding ->
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = list, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        when {
            result is RegistryRead.Unavailable && store != null -> item { ReadUnavailable { retry++ } }
            visible == null -> Unit
            visible.orEmpty().isEmpty() -> item { Text(stringResource(Res.string.p6c_no_matches)) }
        }
        items(visible.orEmpty(), key = { it.id }) { row ->
            QuoteSeriesCard(row) { version ->
                document = arrayListOf(version.id, row.id, row.projectId.toString(),
                    if (row.consolidated) "consolidated" else "individual")
            }
        }
    }
    }
    if (controls.open) RegistryOptions(controls, emptyMap(), quotes = true)
    if (revision != null && seriesId != null && projectId != null && !consolidated) {
        SavedIndividualQuoteDialog(editor, seriesId, revision, onDismiss = { document = arrayListOf() },
            expectedProjectId = projectId)
    }
}

@Composable
private fun ReadUnavailable(retry: () -> Unit) {
    Text(stringResource(Res.string.p6c_unavailable), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = retry) { Text(stringResource(Res.string.p6c_retry)) }
}

@Composable
private fun QuoteSeriesCard(series: RegistrySeries, onOpen: (RegistryRevision) -> Unit) {
    val commonCount = series.versions.map { it.ceilingCount }.distinct().singleOrNull()
    val colors = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = colors.surface, contentColor = colors.onSurface)) {
        Column(Modifier.fillMaxWidth()) {
            Surface(color = colors.surfaceContainerHighest, contentColor = colors.onSurface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val type = stringResource(if (series.consolidated) Res.string.p6c_consolidated else Res.string.p6c_individual)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (series.consolidated) Icons.Default.ReceiptLong else Icons.Default.Receipt, null,
                            Modifier.size(19.dp), tint = colors.primary)
                        Text(listOfNotNull(type, series.number).joinToString(" · "), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    val subtitle = listOfNotNull(series.ceilingName, series.address,
                        commonCount?.let { stringResource(Res.string.p6c_ceiling_count, it.toString()) })
                    Text(subtitle.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            series.versions.forEachIndexed { index, version ->
                if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable { onOpen(version) }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(stringResource(Res.string.p5d_version, version.version), version.title).joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge)
                        val count = version.ceilingCount?.takeIf { commonCount == null }
                            ?.let { stringResource(Res.string.p6c_ceiling_count, it.toString()) }
                        Text(listOfNotNull(dateOf(version.createdAt).toString(), count).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    if (version.amount != null) Text(compactQuoteAmount(version.amount), style = MaterialTheme.typography.titleSmall)
                    if (version.status != null && version.amount != null) {
                        val (icon, tint) = workspaceStatusAppearance(version.status)
                        Icon(icon, workspaceStatusLabel(version.status), Modifier.size(18.dp), tint = tint)
                    } else Icon(Icons.Default.Warning, stringResource(Res.string.p6c_unavailable), Modifier.size(18.dp),
                        tint = colors.error)
                }
            }
        }
    }
}

private class RegistryControls(val filter: RegistryFilter, val open: Boolean, val show: (Boolean) -> Unit,
                               val update: (RegistryFilter) -> Unit, val defaultSort: RegistrySort)

@Composable
private fun rememberRegistryControls(defaultSort: RegistrySort, initialSearch: String, searchRequest: Int): RegistryControls {
    var query by rememberSaveable { mutableStateOf(initialSearch) }
    var appliedSearch by rememberSaveable { mutableIntStateOf(searchRequest) }
    LaunchedEffect(searchRequest) {
        if (searchRequest > appliedSearch) {
            appliedSearch = searchRequest
            query = initialSearch
        }
    }
    var statuses by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    var through by rememberSaveable { mutableStateOf<String?>(null) }
    var project by rememberSaveable { mutableStateOf<Long?>(null) }
    var type by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var sort by rememberSaveable { mutableStateOf(defaultSort.name) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    val filter = RegistryFilter(query, statuses.split(',').filter(String::isNotEmpty).toSet(),
        from?.let(LocalDate::parse), through?.let(LocalDate::parse), project, type,
        RegistrySort.entries.firstOrNull { it.name == sort } ?: defaultSort, ascending)
    return RegistryControls(filter, open, { open = it }, { next ->
        query = next.query
        statuses = next.statuses.joinToString(",")
        from = next.from?.toString()
        through = next.through?.toString()
        project = next.projectId
        type = next.consolidated
        sort = next.sort.name
        ascending = next.ascending
    }, defaultSort)
}

@Composable
private fun RegistryScaffold(
    contentPadding: PaddingValues, headerVisibleFraction: Float, title: StringResource,
    controls: RegistryControls, list: LazyListState, content: @Composable (PaddingValues) -> Unit
) {
    val direction = LocalLayoutDirection.current
    val top = contentPadding.calculateTopPadding()
    val headerSpace = top - minOf(top, AppHeaderHeight) * (1f - headerVisibleFraction.coerceIn(0f, 1f))
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling && searchFocused) { focus.clearFocus(); keyboard?.hide() }
        }
    }
    CeilingsScreenScaffold(
        windowInsets = WindowInsets(0, 0, 0, 0),
        contentPadding = PaddingValues(start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction), bottom = contentPadding.calculateBottomPadding()),
        bottomGradient = false,
        header = { Spacer(Modifier.height(headerSpace)) },
        pinned = {
            Column(Modifier.fillMaxWidth().padding(
                start = contentPadding.calculateStartPadding(direction),
                end = contentPadding.calculateEndPadding(direction), bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CompactObjectsSearchField(controls.filter.query,
                        onChange = { controls.update(controls.filter.copy(query = it)) },
                        onClear = { controls.update(controls.filter.copy(query = "")) },
                        onFocusChange = { searchFocused = it },
                        placeholder = stringResource(Res.string.p6c_search),
                        clearLabel = stringResource(Res.string.p6c_clear_search),
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); controls.show(true) }) {
                        Icon(Icons.Default.Tune, stringResource(Res.string.p6c_filters), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    ) { padding -> content(padding) }
}

@Composable
private fun <T> RegistryDropdown(
    label: String, selected: String, options: List<T>, optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp),
            containerColor = MaterialTheme.colorScheme.surface) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistryOptions(controls: RegistryControls, projects: Map<Long, String>, quotes: Boolean) {
    val applied = controls.filter
    var draft by remember { mutableStateOf(applied) }
    val today = dateOf(Clock.System.now().toEpochMilliseconds())
    var period by remember { mutableStateOf(RegistryPeriod.entries.firstOrNull {
        registryPeriod(it, today) == (applied.from to applied.through)
    }) }
    var dateSide by remember { mutableStateOf<Boolean?>(null) }
    var projectSearch by remember { mutableStateOf("") }
    val valid = draft.from == null || draft.through == null || checkNotNull(draft.from) <= checkNotNull(draft.through)
    val sorts = if (quotes) listOf(RegistrySort.LATEST, RegistrySort.CREATED, RegistrySort.NUMBER, RegistrySort.ADDRESS)
        else listOf(RegistrySort.CREATED, RegistrySort.MODIFIED, RegistrySort.NAME, RegistrySort.ADDRESS, RegistrySort.AREA, RegistrySort.COST)
    val all = stringResource(Res.string.p6c_all)
    val kinds = CeilingQuoteKind.entries.filter { !quotes || it != CeilingQuoteKind.NONE }
    var statusExpanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { controls.show(false) },
        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.p6c_filters)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    RegistryDropdown(stringResource(Res.string.p6c_sort), stringResource(draft.sort.label()), sorts,
                        { stringResource(it.label()) }, { draft = draft.copy(sort = it) }, Modifier.weight(1f))
                    RegistryDropdown(stringResource(Res.string.p6c_sort),
                        stringResource(if (draft.ascending) Res.string.p6c_ascending else Res.string.p6c_descending),
                        listOf(false, true),
                        { stringResource(if (it) Res.string.p6c_ascending else Res.string.p6c_descending) },
                        { draft = draft.copy(ascending = it) }, Modifier.weight(1f))
                }
                if (quotes) {
                    val typeLabel: @Composable (Boolean?) -> String = {
                        if (it == null) all else stringResource(if (it) Res.string.p6c_consolidated else Res.string.p6c_individual)
                    }
                    RegistryDropdown(stringResource(Res.string.p6c_type), typeLabel(draft.consolidated),
                        listOf(null, false, true), typeLabel, { draft = draft.copy(consolidated = it) })
                } else if (projects.isNotEmpty() || draft.projectId != null) {
                    if (projects.size > 12) {
                        OutlinedTextField(projectSearch, { projectSearch = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.p6c_search)) })
                    }
                    val matches = remember(projects, projectSearch, draft.projectId) {
                        projects.entries.filter { it.key == draft.projectId || it.value.contains(projectSearch.trim(), ignoreCase = true) }
                            .sortedBy { it.value.lowercase() }.map { it.key }
                    }
                    RegistryDropdown(stringResource(Res.string.p6c_project), projects[draft.projectId] ?: all,
                        listOf<Long?>(null) + matches, { id -> projects[id] ?: all }, { draft = draft.copy(projectId = it) })
                }
                Box {
                    val selected = kinds.filter { it.name in draft.statuses }
                    OutlinedButton(onClick = { statusExpanded = true }, modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.p6c_status), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val names = mutableListOf<String>()
                            for (kind in selected) names.add(registryStatusLabel(kind, quotes))
                            Text(if (names.isEmpty()) all else names.joinToString(", "), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(statusExpanded, onDismissRequest = { statusExpanded = false },
                        containerColor = MaterialTheme.colorScheme.surface) {
                        DropdownMenuItem(text = { Text(all) }, onClick = { draft = draft.copy(statuses = emptySet()) })
                        kinds.forEach { kind ->
                            val checked = kind.name in draft.statuses
                            DropdownMenuItem(text = { Text(registryStatusLabel(kind, quotes)) },
                                leadingIcon = { Checkbox(checked, onCheckedChange = null) },
                                onClick = { draft = draft.copy(statuses = if (checked) draft.statuses - kind.name else draft.statuses + kind.name) })
                        }
                    }
                }
                val periodLabel: @Composable (RegistryPeriod?) -> String = { value ->
                    stringResource(when (value) {
                        null -> Res.string.p6c_period_custom
                        RegistryPeriod.ALL -> Res.string.p6c_period_all
                        RegistryPeriod.TODAY -> Res.string.p6c_period_today
                        RegistryPeriod.LAST_7_DAYS -> Res.string.p6c_period_week
                        RegistryPeriod.MONTH -> Res.string.p6c_period_month
                        RegistryPeriod.YEAR -> Res.string.p6c_period_year
                    })
                }
                RegistryDropdown(stringResource(Res.string.p6c_date_from_short) + " – " + stringResource(Res.string.p6c_date_through_short),
                    periodLabel(period), RegistryPeriod.entries + listOf<RegistryPeriod?>(null),
                    periodLabel, {
                        period = it
                        if (it != null) {
                            val (from, through) = registryPeriod(it, today)
                            draft = draft.copy(from = from, through = through)
                        }
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (from in listOf(true, false)) {
                        OutlinedButton(onClick = { dateSide = from }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)) {
                            Icon(Icons.Default.CalendarMonth, null, Modifier.size(17.dp))
                            Column(Modifier.padding(start = 4.dp).weight(1f)) {
                                Text(stringResource(if (from) Res.string.p6c_date_from_short else Res.string.p6c_date_through_short),
                                    style = MaterialTheme.typography.labelSmall)
                                Text((if (from) draft.from else draft.through)?.toString() ?: "—",
                                    maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = {
            controls.update(draft)
            controls.show(false)
        }) { Text(stringResource(Res.string.p6c_apply)) } },
        dismissButton = { TextButton(onClick = {
            controls.update(RegistryFilter(sort = controls.defaultSort))
            controls.show(false)
        }) { Text(stringResource(Res.string.p6c_reset)) } })
    dateSide?.let { from ->
        val initial = if (from) draft.from else draft.through
        val state = rememberDatePickerState(initialSelectedDateMillis = initial?.toEpochDays()?.toLong()?.times(86_400_000L))
        DatePickerDialog(onDismissRequest = { dateSide = null },
            confirmButton = { TextButton(onClick = {
                val date = state.selectedDateMillis?.let { dateOf(it, TimeZone.UTC) }
                period = null
                draft = if (from) draft.copy(from = date)
                    else draft.copy(through = date)
                dateSide = null
            }) { Text(stringResource(Res.string.p6c_apply)) } },
            dismissButton = { TextButton(onClick = {
                period = null
                draft = if (from) draft.copy(from = null) else draft.copy(through = null)
                dateSide = null
            }) { Text(stringResource(Res.string.p6c_clear_date)) } }) {
            DatePicker(state)
        }
    }
}

@Composable
private fun registryStatusLabel(kind: CeilingQuoteKind, quotes: Boolean): String = when (kind) {
    CeilingQuoteKind.NONE -> stringResource(Res.string.p6c_no_quote)
    CeilingQuoteKind.UNAVAILABLE -> stringResource(Res.string.p6c_unavailable)
    CeilingQuoteKind.REJECTED -> if (quotes) workspaceStatusLabel(EstimateWorkflowStatus.REJECTED)
        else stringResource(Res.string.p6c_only_rejected)
    else -> workspaceStatusLabel(EstimateWorkflowStatus.valueOf(kind.name))
}

private fun RegistrySort.label() = when (this) {
    RegistrySort.LATEST -> Res.string.p6c_sort_latest
    RegistrySort.CREATED -> Res.string.p6c_sort_created
    RegistrySort.MODIFIED -> Res.string.p6c_sort_modified
    RegistrySort.NAME -> Res.string.p6c_sort_name
    RegistrySort.NUMBER -> Res.string.p6c_sort_number
    RegistrySort.ADDRESS -> Res.string.p6c_sort_address
    RegistrySort.AREA -> Res.string.p6c_area
    RegistrySort.COST -> Res.string.p6c_sort_cost
}
