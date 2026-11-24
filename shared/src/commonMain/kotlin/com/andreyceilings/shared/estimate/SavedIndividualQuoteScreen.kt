package com.andreyceilings.shared.estimate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** External library routes retain exact revision identity and use the workspace workflow controls. */
@Composable
fun SavedIndividualQuoteList(store: MeasurementElementsStore?, projectId: Long? = null, measurementUuid: String? = null) {
    var selectedSeries by rememberSaveable(projectId, measurementUuid) { mutableStateOf<String?>(null) }
    var selectedRevision by rememberSaveable(projectId, measurementUuid) { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var rows by remember(store, projectId, measurementUuid) { mutableStateOf<List<SavedIndividualQuote>?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deleteSeries by remember { mutableStateOf<List<SavedIndividualQuote>?>(null) }
    val scope = rememberCoroutineScope()
    fun change(action: suspend (MeasurementElementsStore) -> Boolean) {
        if (store == null || busy) return
        busy = true
        error = false
        scope.launch {
            try { error = !action(store) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false; generation++ }
        }
    }
    val shareResult = store?.quoteShareResult?.collectAsState()?.value
    LaunchedEffect(store, projectId, measurementUuid, generation, shareResult) {
        if (store == null) {
            rows = emptyList()
            return@LaunchedEffect
        }
        try { rows = store.savedQuotes(projectId, measurementUuid) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.p5d_quotes), style = MaterialTheme.typography.titleMedium)
        if (error) Text(stringResource(Res.string.p5d_quote_failed), color = MaterialTheme.colorScheme.error)
        else if (rows == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (rows.isNullOrEmpty()) Text(stringResource(Res.string.p5d_no_quotes))
        rows.orEmpty().groupBy { it.seriesUuid }.values.forEach { series ->
            val head = series.first()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(head.frozen.title, style = MaterialTheme.typography.titleSmall)
                    Text(head.frozen.publicNumber, style = MaterialTheme.typography.labelSmall)
                }
                val allowed = series.all { it.canDeleteQuote }
                if (!allowed) WorkspaceCostHelp(stringResource(Res.string.p5d_delete_protected))
                WorkspaceTrash(allowed && !busy, Res.string.p5d_delete_series) { deleteSeries = series.toList() }
            }
            series.forEach { quote ->
                val approved = rows.orEmpty().filter {
                    it.projectId == quote.projectId && it.frozen.source.measurementUuid == quote.frozen.source.measurementUuid &&
                        it.status == EstimateWorkflowStatus.APPROVED
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    WorkspaceQuoteStatusIcon(quote, approved, !busy) { target, status, previous ->
                        change { it.setQuoteStatus(target.revisionUuid, target.statusVersion, status,
                            previous.associate { old -> old.revisionUuid to old.statusVersion }) }
                    }
                    Row(Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clickable {
                        selectedSeries = quote.seriesUuid
                        selectedRevision = quote.revisionUuid
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.p5d_version, quote.versionNumber))
                            if (quote.frozen.title != head.frozen.title) Text(quote.frozen.title, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(measurementMoney(quote.frozen.amounts.finalCents))
                    }
                }
            }
        }
    }
    deleteSeries?.let { versions ->
        WorkspaceQuoteDeleteDialog(versions, series = true, onClose = { deleteSeries = null }) {
            deleteSeries = null
            change { it.deleteQuoteSeries(versions.first().seriesUuid, versions.associate { row -> row.revisionUuid to row.statusVersion }) }
        }
    }
    selectedSeries?.let { series -> SavedIndividualQuoteDialog(store, series, selectedRevision) {
        selectedSeries = null
        selectedRevision = null
        generation++
    } }
}

@Composable
fun SavedIndividualQuoteDialog(store: MeasurementElementsStore?, seriesUuid: String, revisionUuid: String? = null, onDismiss: () -> Unit) {
    val session = remember(store, seriesUuid, revisionUuid) { WorkspaceQuoteSession() }
    var selected by rememberSaveable(seriesUuid, revisionUuid) { mutableStateOf(revisionUuid) }
    var quote by remember(session) { mutableStateOf<SavedIndividualQuote?>(null) }
    var history by remember(session) { mutableStateOf(emptyList<SavedIndividualQuote>()) }
    var approved by remember(session) { mutableStateOf(emptyList<SavedIndividualQuote>()) }
    var editorRoute by remember(session) { mutableStateOf<ElementRoute?>(null) }
    var error by remember(session) { mutableStateOf(false) }
    var busy by remember(session) { mutableStateOf(false) }
    var delete by remember(session) { mutableStateOf<SavedIndividualQuote?>(null) }
    val scope = rememberCoroutineScope()

    val shareResult = store?.quoteShareResult?.collectAsState()?.value
    LaunchedEffect(store, selected, session.refresh, shareResult) {
        try {
            val exact = checkNotNull(store).savedQuote(seriesUuid, selected)
            if (exact == null) { error = true; quote = null }
            else {
                selected = exact.revisionUuid
                history = store.quoteHistory(seriesUuid)
                approved = store.savedQuotes(exact.projectId, exact.frozen.source.measurementUuid)
                    .filter { it.status == EstimateWorkflowStatus.APPROVED }
                quote = exact
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    LaunchedEffect(session.opened?.revisionUuid) {
        session.opened?.let { saved -> selected = saved.revisionUuid; session.opened = null }
    }
    DisposableEffect(session) {
        onDispose { if (session.presenter?.saveState?.value?.saving != true) session.closeDraft(store) }
    }
    fun exit() {
        if (busy || session.presenter?.saveState?.value?.saving == true) return
        val editor = session.presenter
        if (editor == null) onDismiss()
        else if (editor.hasUserChanges) session.discardPending = true
        else session.closeDraft(store)
    }
    fun changeStatus(target: SavedIndividualQuote, status: EstimateWorkflowStatus, previous: List<SavedIndividualQuote>) {
        if (store == null || busy) return
        busy = true
        error = false
        scope.launch {
            try { error = !store.setQuoteStatus(target.revisionUuid, target.statusVersion, status,
                previous.associate { it.revisionUuid to it.statusVersion }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false; session.refresh++ }
        }
    }

    Dialog(onDismissRequest = ::exit, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
                if (error) Text(stringResource(Res.string.p5d_quote_failed), color = MaterialTheme.colorScheme.error)
                val editor = session.presenter
                val route = editorRoute
                val opened = quote
                when {
                    editor != null && route != null && store != null ->
                        WorkspaceQuoteComposer(store, session, editor, route, current = true, onRefresh = {})
                    opened == null || store == null -> {
                        TextButton(onClick = ::exit) { Text(stringResource(Res.string.p5d_back_quotes)) }
                        if (!error) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        if (!session.expanded) {
                            val source = opened.frozen.source
                            val geometry = remember(source.drawingJson) { MeasurementGeometryCodec.read(source.drawingJson) }
                            key(opened.revisionUuid) {
                                MeasurementDrawing(MeasurementGeometryView.Captured(geometry), compact = true, interactive = true,
                                    pointPlacements = source.points, linePlacements = source.lines, areaFeatures = source.areas,
                                    showSideLegend = false, modifier = Modifier.fillMaxWidth().height(160.dp))
                            }
                        }
                        if (history.size > 1) {
                            var choose by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { choose = true }) {
                                    Text(stringResource(Res.string.p5d_version, opened.versionNumber))
                                }
                                DropdownMenu(choose, onDismissRequest = { choose = false }) {
                                    history.forEach { alternative ->
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.p5d_line_problem,
                                            stringResource(Res.string.p5d_version, alternative.versionNumber), alternative.frozen.title)) },
                                            onClick = { choose = false; selected = alternative.revisionUuid })
                                    }
                                }
                            }
                        }
                        WorkspaceFrozenQuote(store, session, opened, approved, busy, ::changeStatus,
                            onNewRevision = { origin ->
                                if (origin != null && !busy) {
                                    busy = true
                                    error = false
                                    scope.launch {
                                        try {
                                            val (base, currentRoute) = checkNotNull(store.prepareRevision(origin.seriesUuid, origin.revisionUuid))
                                            editorRoute = currentRoute
                                            session.presenter = store.newQuote(currentRoute.measurementUuid, base, origin)
                                        } catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { error = true }
                                        finally { busy = false }
                                    }
                                }
                            }, onDelete = { delete = opened }, onBack = ::exit)
                    }
                }
            }
        }
    }
    delete?.let { target ->
        WorkspaceQuoteDeleteDialog(listOf(target), series = false, onClose = { delete = null }) {
            delete = null
            if (store == null || busy) return@WorkspaceQuoteDeleteDialog
            busy = true
            scope.launch {
                try {
                    if (store.deleteQuoteRevision(target.seriesUuid, target.revisionUuid, target.statusVersion)) onDismiss()
                    else error = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = true }
                finally { busy = false; session.refresh++ }
            }
        }
    }
}
