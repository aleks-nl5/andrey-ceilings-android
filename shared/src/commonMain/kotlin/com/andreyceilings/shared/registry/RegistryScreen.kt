package com.andreyceilings.shared.registry

import androidx.compose.foundation.clickable
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val headerKey = "registry-heading"

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
    val keys = remember(visible) { visible?.let { listOf(headerKey) + it.map { row -> row.uuid } } }
    val list = rememberRegistryScroll(keys)
    var contourKeys by remember(store) { mutableStateOf(emptyList<String>()) }
    val eligibleKeys = remember(visible) { visible.orEmpty().mapTo(hashSetOf()) { it.uuid } }
    LaunchedEffect(store, list, eligibleKeys) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged().collect { composed ->
                contourKeys = composed.filter { it in eligibleKeys }.sorted().take(40)
            }
    }
    val contourFlow = remember(store, contourKeys) { store?.observeVisibleCeilingContours(contourKeys) }
    val contours = (registryRead(contourFlow, retry) as? RegistryRead.Ready)?.data.orEmpty()
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = list, contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item(headerKey) { RegistryHeader(Res.string.p6c_ceilings, controls) }
        when {
            base is RegistryRead.Unavailable -> item { ReadUnavailable { retry++ } }
            visible == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
    if (controls.open) {
        val projects = remember(rows) { rows.orEmpty().associate { it.projectId to it.address.ifBlank { it.projectTitle } } }
        RegistryOptions(controls, projects, quotes = false)
    }
}

@Composable
fun QuoteRegistryScreen(store: ObjectsClientsStore?, contentPadding: PaddingValues, initialSearch: String = "",
    searchRequest: Int = 0
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
    val keys = remember(visible) { visible?.let { listOf(headerKey) + it.map { row -> row.id } } }
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
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = list, contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item(headerKey) { RegistryHeader(Res.string.p6c_quotes, controls) }
        when {
            result is RegistryRead.Unavailable -> item { ReadUnavailable { retry++ } }
            visible == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            visible.orEmpty().isEmpty() -> item { Text(stringResource(Res.string.p6c_no_matches)) }
        }
        items(visible.orEmpty(), key = { it.id }) { row ->
            QuoteSeriesCard(row) { version ->
                document = arrayListOf(version.id, row.id, row.projectId.toString(),
                    if (row.consolidated) "consolidated" else "individual")
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val type = stringResource(if (series.consolidated) Res.string.p6c_consolidated else Res.string.p6c_individual)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(if (series.consolidated) Icons.Default.ReceiptLong else Icons.Default.Receipt, null,
                    Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(listOfNotNull(type, series.number).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
            }
            val subtitle = listOfNotNull(series.ceilingName, series.address,
                commonCount?.let { stringResource(Res.string.p6c_ceiling_count, it.toString()) })
            Text(subtitle.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            series.versions.forEach { version ->
                HorizontalDivider(Modifier.padding(top = 3.dp))
                Row(Modifier.fillMaxWidth().clickable { onOpen(version) }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(stringResource(Res.string.p5d_version, version.version), version.title).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge)
                        val count = version.ceilingCount?.takeIf { commonCount == null }
                            ?.let { stringResource(Res.string.p6c_ceiling_count, it.toString()) }
                        Text(listOfNotNull(dateOf(version.createdAt).toString(), count).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (version.amount != null) Text(compactQuoteAmount(version.amount), style = MaterialTheme.typography.bodySmall)
                    if (version.status != null && version.amount != null) {
                        val (icon, tint) = workspaceStatusAppearance(version.status)
                        Icon(icon, workspaceStatusLabel(version.status), Modifier.size(16.dp), tint = tint)
                    } else {
                        Icon(Icons.Default.Warning, stringResource(Res.string.p6c_unavailable), Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
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
private fun RegistryHeader(title: StringResource, controls: RegistryControls) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(controls.filter.query, { controls.update(controls.filter.copy(query = it)) },
                Modifier.weight(1f), singleLine = true, label = { Text(stringResource(Res.string.p6c_search)) })
            IconButton(onClick = { controls.show(true) }) {
                Icon(Icons.Default.Tune, stringResource(Res.string.p6c_filters))
            }
        }
    }
}

@Composable
private fun RegistryOptions(controls: RegistryControls, projects: Map<Long, String>, quotes: Boolean) {
    val applied = controls.filter
    var draft by remember { mutableStateOf(applied) }
    var start by remember { mutableStateOf(applied.from?.toString().orEmpty()) }
    var end by remember { mutableStateOf(applied.through?.toString().orEmpty()) }
    var projectSearch by remember { mutableStateOf("") }
    val from = start.trim().takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val through = end.trim().takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val valid = (start.isBlank() || from != null) && (end.isBlank() || through != null) &&
        (from == null || through == null || from <= through)
    val sorts = if (quotes) listOf(RegistrySort.LATEST, RegistrySort.CREATED, RegistrySort.NUMBER, RegistrySort.ADDRESS)
        else listOf(RegistrySort.CREATED, RegistrySort.MODIFIED, RegistrySort.NAME, RegistrySort.ADDRESS, RegistrySort.AREA, RegistrySort.COST)
    AlertDialog(onDismissRequest = { controls.show(false) },
        title = { Text(stringResource(Res.string.p6c_filters)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.p6c_sort), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    sorts.forEach { sort ->
                        FilterChip(draft.sort == sort, { draft = draft.copy(sort = sort) },
                            label = { Text(stringResource(sort.label())) })
                    }
                    FilterChip(draft.ascending, { draft = draft.copy(ascending = !draft.ascending) },
                        label = { Text(stringResource(if (draft.ascending) Res.string.p6c_ascending else Res.string.p6c_descending)) })
                }
                if (quotes || projects.isNotEmpty()) {
                    Text(stringResource(if (quotes) Res.string.p6c_type else Res.string.p6c_project),
                        style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (quotes) {
                            FilterChip(draft.consolidated == null, { draft = draft.copy(consolidated = null) },
                                label = { Text(stringResource(Res.string.p6c_all)) })
                            listOf(false, true).forEach { type ->
                                FilterChip(draft.consolidated == type, { draft = draft.copy(consolidated = type) },
                                    label = { Text(stringResource(if (type) Res.string.p6c_consolidated else Res.string.p6c_individual)) })
                            }
                        } else {
                            FilterChip(draft.projectId == null, { draft = draft.copy(projectId = null) },
                                label = { Text(stringResource(Res.string.p6c_all)) })
                            val matches = remember(projects, projectSearch, draft.projectId) {
                                projects.entries.filter {
                                    it.key == draft.projectId || it.value.contains(projectSearch.trim(), ignoreCase = true)
                                }.sortedWith(compareByDescending<Map.Entry<Long, String>> { it.key == draft.projectId }
                                    .thenBy { it.value.lowercase() })
                            }
                            if (projects.size > 12) {
                                OutlinedTextField(projectSearch, { projectSearch = it }, singleLine = true,
                                    label = { Text(stringResource(Res.string.p6c_search)) })
                            }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                                items(matches, key = { it.key }) { (id, title) ->
                                    FilterChip(draft.projectId == id, { draft = draft.copy(projectId = id) }, label = { Text(title) })
                                }
                            }
                        }
                    }
                }
                Text(stringResource(Res.string.p6c_status), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val kinds = CeilingQuoteKind.entries.filter { !quotes || it != CeilingQuoteKind.NONE }
                    kinds.forEach { kind ->
                        FilterChip(kind.name in draft.statuses, {
                            draft = draft.copy(statuses = if (kind.name in draft.statuses) draft.statuses - kind.name
                                else draft.statuses + kind.name)
                        }, label = {
                            Text(when (kind) {
                                CeilingQuoteKind.NONE -> stringResource(Res.string.p6c_no_quote)
                                CeilingQuoteKind.UNAVAILABLE -> stringResource(Res.string.p6c_unavailable)
                                CeilingQuoteKind.REJECTED -> if (quotes) workspaceStatusLabel(EstimateWorkflowStatus.REJECTED)
                                    else stringResource(Res.string.p6c_only_rejected)
                                else -> workspaceStatusLabel(EstimateWorkflowStatus.valueOf(kind.name))
                            })
                        })
                    }
                }
                OutlinedTextField(start, { start = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.p6c_date_from)) }, isError = !valid)
                OutlinedTextField(end, { end = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.p6c_date_through)) }, isError = !valid)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = {
            controls.update(draft.copy(from = from, through = through))
            controls.show(false)
        }) { Text(stringResource(Res.string.p6c_apply)) } },
        dismissButton = { TextButton(onClick = {
            controls.update(RegistryFilter(sort = controls.defaultSort))
            controls.show(false)
        }) { Text(stringResource(Res.string.p6c_reset)) } })
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
