package com.andreyceilings.shared.estimate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shared list: the stable series ID opens exactly the same frozen card in every context. */
@Composable
fun SavedIndividualQuoteList(store: MeasurementElementsStore?, projectId: Long? = null, measurementUuid: String? = null) {
    var selected by rememberSaveable(projectId, measurementUuid) { mutableStateOf<String?>(null) }
    var selectedDocument by rememberSaveable(projectId, measurementUuid) { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var rows by remember(store, projectId, measurementUuid) { mutableStateOf<List<SavedIndividualQuote>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(store, projectId, measurementUuid, generation) {
        rows = null; error = false
        try { rows = checkNotNull(store).savedQuotes(projectId, measurementUuid) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Збережені кошториси", style = MaterialTheme.typography.titleMedium)
        if (error) Text("Не вдалося прочитати збережені кошториси.", color = MaterialTheme.colorScheme.error)
        else if (rows == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (rows.isNullOrEmpty()) Text("Збережених кошторисів ще немає")
        rows.orEmpty().groupBy { it.seriesUuid }.values.forEach { alternatives ->
            Text("${alternatives.first().frozen.publicNumber} · варіантів: ${alternatives.size}", style = MaterialTheme.typography.titleSmall)
            alternatives.forEach { row ->
            OutlinedCard(onClick = { selected = row.seriesUuid; selectedDocument = row.revisionUuid }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(row.frozen.title, style = MaterialTheme.typography.titleSmall)
                    Text("${row.frozen.publicNumber} · v${row.versionNumber} · ${row.status?.label ?: "Статус не зафіксовано"}")
                    Text("${row.frozen.source.projectTitle} / ${row.frozen.source.measurementName}")
                    Text(measurementMoney(row.frozen.amounts.finalCents))
                    if (row.selectedRevisionUuid == row.revisionUuid) Text("Обрано для роботи")
                }
            }
        }
        }
        TextButton(onClick = { generation++ }) { Text("Оновити список") }
    }
    selected?.let { id -> SavedIndividualQuoteDialog(store, id, selectedDocument) { selected = null; generation++ } }
}

/** Full-window read-only route. Only its series UUID is restored; Android Dialog owns gesture Back. */
@Composable
fun SavedIndividualQuoteDialog(store: MeasurementElementsStore?, seriesUuid: String, revisionUuid: String? = null, onDismiss: () -> Unit) {
    var selectedRevision by rememberSaveable(seriesUuid) { mutableStateOf(revisionUuid) }
    var generation by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<List<SavedIndividualQuote>>(emptyList()) }
    var preparation by remember { mutableStateOf<Pair<SavedIndividualQuote, ElementRoute>?>(null) }
    var editing by rememberSaveable(seriesUuid) { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Pair<com.andreyceilings.shared.livepricing.TransientQuotePresenter, ElementRoute>?>(null) }
    var data by remember(store, seriesUuid) { mutableStateOf<SavedIndividualQuote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmingChoice by remember { mutableStateOf(false) }
    var pdfRevision by rememberSaveable(seriesUuid) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, seriesUuid, generation) {
        loading = true; error = null; data = null; preparation = null
        try {
            val loaded = checkNotNull(store).savedQuote(seriesUuid, selectedRevision)
            if (loaded == null) error = "Документ не знайдено."
            else {
                val loadedHistory = store.quoteHistory(seriesUuid)
                val loadedPreparation = store.prepareRevision(seriesUuid)
                // Publish the complete card together. Pinning its UUID must not cancel
                // this load and expose a second, temporarily disabled document frame.
                selectedRevision = loaded.revisionUuid
                history = loadedHistory
                preparation = loadedPreparation
                data = loaded
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Документ недоступний або збережені дані пошкоджено." }
        finally { loading = false }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { TextButton(onClick = onDismiss, enabled = !busy) { Text("Назад") } }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                data?.let { saved ->
                    val f = saved.frozen
                    item {
                        Text(f.title, style = MaterialTheme.typography.headlineSmall)
                        Text("${f.publicNumber} · v${saved.versionNumber}", style = MaterialTheme.typography.titleMedium)
                        Text("${f.source.projectTitle} / ${f.source.measurementName}")
                        Text("Створено: ${kotlin.time.Instant.fromEpochMilliseconds(saved.createdAt)}")
                        Text("Статус пропозиції: ${saved.status?.label ?: "Статус не зафіксовано"}")
                        if (saved.selectedRevisionUuid == saved.revisionUuid) Text("Обрано для роботи")
                        OutlinedButton(enabled = !busy && !loading && store != null,
                            onClick = { pdfRevision = saved.revisionUuid }) { Text("Створити PDF") }
                    }
                    items(EstimateWorkflowStatus.entries.toList()) { status ->
                        OutlinedButton(enabled = !busy && !loading && status != saved.status, onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val changed = checkNotNull(store).setQuoteStatus(saved.revisionUuid, saved.statusVersion, status)
                                    data = store.savedQuote(seriesUuid, saved.revisionUuid)
                                    history = store.quoteHistory(seriesUuid)
                                    preparation = store.prepareRevision(seriesUuid)
                                    if (!changed) error = "Статус уже змінено. Перевірте актуальне значення."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Не вдалося змінити статус. Оновіть документ." }
                                finally { busy = false }
                            }
                        }) { Text(status.label) }
                    }
                    item {
                        Text("Варіанти пропозиції", style = MaterialTheme.typography.titleMedium)
                        history.forEach { revision ->
                            TextButton(enabled = !busy && revision.revisionUuid != saved.revisionUuid,
                                onClick = { selectedRevision = revision.revisionUuid; generation++ }) {
                                Text("v${revision.versionNumber}${if (revision == history.firstOrNull()) " · найновіша" else ""} · ${revision.status?.label ?: "Статус не зафіксовано"}${if (revision.selectedRevisionUuid == revision.revisionUuid) " · обрано для роботи" else ""} · ${revision.frozen.title} · ${measurementMoney(revision.frozen.amounts.finalCents)}")
                            }
                        }
                        Button(enabled = !busy && !loading && preparation != null, onClick = {
                            preparation?.let { (base, route) ->
                                editing = true
                                editor = checkNotNull(store).revisionQuote(base, route) to route
                            }
                        }) { Text("Створити нову версію") }
                        if (preparation == null) Text("Нову версію неможливо створити: актуальний замір або його об’єкт недоступний. Історію збережено.")
                    }
                    item {
                        OutlinedButton(enabled = !busy && saved.selectedRevisionUuid != saved.revisionUuid,
                            onClick = { confirmingChoice = true }) { Text("Обрати для роботи") }
                        Text("Вибір пропозиції не змінює статус інших версій.")
                    }
                    item { FrozenQuoteDrawing(f.source) }
                    items(f.rows, key = { it.uuid }) { frozen ->
                        val row = frozen.line
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleSmall)
                                Text("${row.stableCode} · ${if (row.bucket == com.andreyceilings.shared.livepricing.PricingBucket.CONFIRMED) "Погоджено" else "Обговорюється — включено явно"}")
                                if (row.quantityRoundingPolicy != null) {
                                    val geometry = remember(f.source.drawingJson) { MeasurementGeometryCodec.read(f.source.drawingJson) }
                                    Text("Площа заміру: ${formatMeasurementArea(geometry.areaSquareMeters, com.andreyceilings.shared.calculation.MeasurementPrecision.fromStep(geometry.precisionStepMeters))} м²")
                                    Text("Розрахункова площа округлена до 0,001 м²")
                                }
                                Text("${if (row.quantityRoundingPolicy != null) "Із заміру (до 0,001 м²)" else "Із заміру"}: ${formatElementMilli(row.sourceQuantityMilli)} ${row.unit}")
                                Text("До оплати: ${formatElementMilli(row.effectiveBillableQuantityMilli)} ${row.unit}")
                                row.overrideNote?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                Text("${measurementMoney(row.unitPriceCents)} / ${row.unit} · ${measurementMoney(row.totalCents)}")
                            }
                        }
                    }
                    if (f.partial) item {
                        Text("Частковий · невраховані роботи підтверджено")
                        f.omissions.forEach { omission ->
                            val label = f.source.elements.find { "element:${it.uuid}" == omission.sourceKey }?.label ?: omission.sourceKey
                            Text("$label: ${if (omission.reason == com.andreyceilings.shared.livepricing.ExclusionReason.USER_EXCLUDED) "виключено вами" else omission.problems.joinToString { pricingProblemText(it) }}")
                        }
                    }
                    item {
                        Text("До знижки: ${measurementMoney(f.amounts.subtotalCents)}")
                        Text("Знижка ${f.discountText}%: ${measurementMoney(f.amounts.discountCents)}")
                        Text(measurementMoney(f.amounts.finalCents), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
    pdfRevision?.let { exactRevision ->
        store?.let { EstimateDocumentPreview(it, seriesUuid, exactRevision) { pdfRevision = null } }
    }
    if (confirmingChoice) data?.let { saved ->
        AlertDialog(onDismissRequest = { if (!busy) confirmingChoice = false },
            title = { Text("Обрати v${saved.versionNumber} для роботи?") },
            text = { Text("Ця пропозиція стане обраною замість попереднього вибору. Статуси та збережені суми всіх версій залишаться незмінними.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val changed = checkNotNull(store).selectQuoteRevision(seriesUuid, saved.revisionUuid, saved.choiceVersion)
                        data = store.savedQuote(seriesUuid, saved.revisionUuid)
                        history = store.quoteHistory(seriesUuid)
                        preparation = store.prepareRevision(seriesUuid)
                        confirmingChoice = false
                        if (!changed) error = "Вибір уже змінено. Перевірте актуальне значення."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Не вдалося зберегти вибір. Оновіть документ." }
                    finally { busy = false }
                }
            }) { Text("Обрати") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmingChoice = false }) { Text("Скасувати") } })
    }
    LaunchedEffect(editing, preparation) {
        if (editing && editor == null) {
            editor = store?.retainedRevision(seriesUuid)
            if (editor == null) preparation?.let { (base, route) -> editor = checkNotNull(store).revisionQuote(base, route) to route }
        }
    }
    editor?.let { (presenter, route) ->
        val save by presenter.saveState.collectAsState()
        fun closeEditor() {
            if (!presenter.saveState.value.saving) {
                store?.releaseQuote(presenter); editor = null; editing = false; generation++
            }
        }
        Dialog(onDismissRequest = { closeEditor() }, properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnBackPress = !save.saving, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize()) {
                MeasurementQuoteScreen(presenter, checkNotNull(store), route, PaddingValues(16.dp), 0.dp) { closeEditor() }
            }
        }
    }

}

@Composable
private fun FrozenQuoteDrawing(source: FrozenIndividualSource) {
    val geometry = remember(source.drawingJson) { MeasurementGeometryCodec.read(source.drawingJson) }
    Column {
        Text("Збережене креслення", style = MaterialTheme.typography.titleMedium)
        MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
            pointPlacements = source.points, linePlacements = source.lines, areaFeatures = source.areas)
        Text("Площа: ${formatMeasurementArea(geometry.areaSquareMeters, com.andreyceilings.shared.calculation.MeasurementPrecision.fromStep(geometry.precisionStepMeters))} м² · Периметр: ${formatMeasurementLength(geometry.perimeterMeters, com.andreyceilings.shared.calculation.MeasurementPrecision.fromStep(geometry.precisionStepMeters))} м")
    }
}
