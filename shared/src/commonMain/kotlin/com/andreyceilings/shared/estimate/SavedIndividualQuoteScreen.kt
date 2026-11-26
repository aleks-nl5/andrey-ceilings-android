package com.andreyceilings.shared.estimate

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

@Composable
fun SavedIndividualQuoteDialog(
    store: MeasurementElementsStore?, seriesUuid: String, revisionUuid: String? = null,
    expectedProjectId: Long? = null, onDismiss: () -> Unit
) {
    val session = remember(store, seriesUuid, revisionUuid) { WorkspaceQuoteSession() }
    var selected by rememberSaveable(seriesUuid, revisionUuid) { mutableStateOf(revisionUuid) }
    var quote by remember(session) { mutableStateOf<SavedIndividualQuote?>(null) }
    var history by remember(session) { mutableStateOf(emptyList<com.andreyceilings.shared.db.IndividualVersionOptions>()) }
    var approved by remember(session) { mutableStateOf(emptyList<SavedIndividualQuote>()) }
    var editorRoute by remember(session) { mutableStateOf<ElementRoute?>(null) }
    var error by remember(session) { mutableStateOf(false) }
    var busy by remember(session) { mutableStateOf(false) }
    var delete by remember(session) { mutableStateOf<SavedIndividualQuote?>(null) }
    val scope = rememberCoroutineScope()
    val changeLinkedStatus = linkedQuoteStatusChange()

    val shareResult = store?.quoteShareResult?.collectAsState()?.value
    LaunchedEffect(store, selected, session.refresh, shareResult) {
        error = false
        if (selected != quote?.revisionUuid) quote = null
        try {
            val source = checkNotNull(store)
            val exact = if (expectedProjectId != null) source.registryQuote(seriesUuid, checkNotNull(selected), expectedProjectId)
                else source.savedQuote(seriesUuid, selected)
            if (exact == null) { error = true; quote = null }
            else {
                selected = exact.revisionUuid
                history = source.quoteVersionOptions(seriesUuid)
                approved = source.approvedQuotesForCeiling(exact.projectId, exact.frozen.source.measurementUuid)
                quote = exact
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true; quote = null }
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
            try { error = !changeLinkedStatus(store, target, status, previous) }
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
                                            stringResource(Res.string.p5d_version, alternative.versionNumber), alternative.title.orEmpty())) },
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
