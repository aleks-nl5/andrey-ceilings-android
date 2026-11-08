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
        rows.orEmpty().forEach { row ->
            OutlinedCard(onClick = { selected = row.seriesUuid }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(row.frozen.title, style = MaterialTheme.typography.titleSmall)
                    Text("${row.frozen.publicNumber} · v1 · ${row.status.label}")
                    Text("${row.frozen.source.projectTitle} / ${row.frozen.source.measurementName}")
                    Text(measurementMoney(row.frozen.amounts.finalCents))
                }
            }
        }
        TextButton(onClick = { generation++ }) { Text("Оновити список") }
    }
    selected?.let { id -> SavedIndividualQuoteDialog(store, id) { selected = null; generation++ } }
}

/** Full-window read-only route. Only its series UUID is restored; Android Dialog owns gesture Back. */
@Composable
fun SavedIndividualQuoteDialog(store: MeasurementElementsStore?, seriesUuid: String, onDismiss: () -> Unit) {
    var data by remember(store, seriesUuid) { mutableStateOf<SavedIndividualQuote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, seriesUuid) {
        try { data = checkNotNull(store).savedQuote(seriesUuid); if (data == null) error = "Документ не знайдено." }
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
                        Text("${f.publicNumber} · v1", style = MaterialTheme.typography.titleMedium)
                        Text("${f.source.projectTitle} / ${f.source.measurementName}")
                        Text("Створено: ${kotlin.time.Instant.fromEpochMilliseconds(saved.createdAt)}")
                        Text("Статус: ${saved.status.label}")
                    }
                    items(EstimateWorkflowStatus.entries.toList()) { status ->
                        OutlinedButton(enabled = !busy && status != saved.status, onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val changed = checkNotNull(store).setQuoteStatus(seriesUuid, saved.statusVersion, status)
                                    data = store.savedQuote(seriesUuid)
                                    if (!changed) error = "Статус уже змінено. Перевірте актуальне значення."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Не вдалося змінити статус. Оновіть документ." }
                                finally { busy = false }
                            }
                        }) { Text(status.label) }
                    }
                    item { FrozenQuoteDrawing(f.source) }
                    items(f.rows, key = { it.uuid }) { frozen ->
                        val row = frozen.line
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleSmall)
                                Text("${row.stableCode} · ${if (row.bucket == com.andreyceilings.shared.livepricing.PricingBucket.CONFIRMED) "Погоджено" else "Обговорюється — включено явно"}")
                                Text("Із заміру: ${formatElementMilli(row.sourceQuantityMilli)} ${row.unit}")
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
}

@Composable
private fun FrozenQuoteDrawing(source: FrozenIndividualSource) {
    val geometry = remember(source.drawingJson) { MeasurementGeometryCodec.read(source.drawingJson) }
    Column {
        Text("Збережене креслення", style = MaterialTheme.typography.titleMedium)
        MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
            pointPlacements = source.points, linePlacements = source.lines, areaFeatures = source.areas)
        Text("Площа: ${geometry.areaSquareMeters} м² · Периметр: ${geometry.perimeterMeters} м")
    }
}
