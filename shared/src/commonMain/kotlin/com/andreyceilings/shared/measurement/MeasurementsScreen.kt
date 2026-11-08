package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.navigation.AppNavigationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Shared global list and actions. Hosts supply only session stores, insets and navigation. */
@Composable
fun MeasurementsScreen(
    store: MeasurementsStore?, createStore: MeasurementCreateStore?,
    detailsStore: MeasurementDetailsStore?, editStore: MeasurementEditStore?,
    navigation: AppNavigationState, precision: MeasurementPrecision,
    contentPadding: PaddingValues, systemTopInset: Dp,
    navigationGuard: MeasurementCreateNavigationGuard,
    onNavigate: (AppNavigationState) -> Unit,
    onEditSaved: (Long, Long) -> Unit,
    onUseMeasurement: (FrozenPolygonGeometry) -> Unit,
    onOpenEstimates: () -> Unit, onCreateProject: () -> Unit
) {
    val back = { onNavigate(navigation.back()) }
    navigation.measurementCreate?.let { route ->
        MeasurementCreateScreen(route.projectId, precision, createStore, contentPadding, systemTopInset,
            onBack = back, onSaved = { onNavigate(navigation.measurementCreated(route.projectId, it)) },
            navigationGuard = navigationGuard, backLabel = "До замірів")
        return
    }
    navigation.measurementEdit?.let { route ->
        MeasurementEditScreen(route.projectId, route.measurementId, editStore, contentPadding, systemTopInset,
            onBack = back, onSaved = { onEditSaved(route.projectId, route.measurementId) }, navigationGuard = navigationGuard)
        return
    }
    navigation.measurementDetails?.let { route ->
        MeasurementDetailsScreen(route.projectId, route.measurementId, detailsStore, contentPadding, systemTopInset,
            onBack = back, onEdit = { onNavigate(navigation.editMeasurement(route.projectId, route.measurementId)) },
            navigationGuard = navigationGuard, backLabel = "До замірів", editStore=editStore,
            quoteOpen = navigation.measurementQuote, onOpenQuote = { onNavigate(navigation.openMeasurementQuote()) })
        return
    }
    key(store) {
        var retry by remember { mutableIntStateOf(0) }
        val flow = remember(retry) { store?.observe() }
        val state = flow?.collectAsState(MeasurementsState.Loading)?.value ?: MeasurementsState.Unavailable
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun action(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { block(); error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Не вдалося виконати дію. Перевірте доступність заміру та бази даних." }
                finally { busy = false }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (navigation.measurementProjectPicker) "Оберіть об'єкт" else "Заміри",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (navigation.measurementProjectPicker) {
                    Text("Оберіть активний об'єкт для нового заміру.")
                    TextButton(onClick = back) { Text("Скасувати") }
                } else {
                    Text("Заміри зберігаються в зашифрованій локальній БД")
                    OutlinedButton(onClick = onOpenEstimates) { Text("Кошториси") }
                    Button(onClick = { onNavigate(navigation.chooseMeasurementProject()) },
                        enabled = state is MeasurementsState.Loaded && !busy) { Text("Створити замір") }
                }
            }
            when (val current = state) {
                MeasurementsState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Завантаження замірів…") }
                MeasurementsState.Unavailable -> item {
                    Text("Захищена база недоступна або завантажується.")
                    TextButton(onClick = { retry++ }) { Text("Повторити") }
                }
                is MeasurementsState.Loaded -> {
                    if (current.projects.isEmpty()) item {
                        Text("Спочатку створіть об'єкт для заміру.")
                        Button(onClick = onCreateProject) { Text("Створити об'єкт") }
                    }
                    if (navigation.measurementProjectPicker) {
                        items(current.projects, key = { it.id }) { project ->
                            OutlinedButton(onClick = { onNavigate(navigation.createMeasurement(project.id)) },
                                modifier = Modifier.fillMaxWidth()) {
                                Text(project.address.ifBlank { project.title })
                            }
                        }
                    } else {
                        if (current.measurements.isEmpty()) item { Text("Заміри ще не додані.") }
                        items(current.measurements, key = { it.id }) { row ->
                            val project = current.projects.firstOrNull { it.id == row.projectId }
                            val geometry = remember(row.drawingJson, row.shape) {
                                measurementGeometryForViewing(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters)
                            }
                            val capturedPrecision = (geometry as? MeasurementGeometryView.Captured)?.geometry
                                ?.precisionStepMeters?.let(MeasurementPrecision::fromStep)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(row.name, fontWeight = FontWeight.SemiBold)
                                    Text(project?.let { it.address.ifBlank { it.title } }.orEmpty())
                                    Text(CeilingShapeTemplates.template(row.shape)?.title ?:
                                        if (row.shape == "polygon") "Багатокутник" else row.shape)
                                    Text("${formatMeasurementArea(row.areaSquareMeters, capturedPrecision)} м² · " +
                                        "периметр ${formatMeasurementLength(row.perimeterMeters, capturedPrecision)} м")
                                    MeasurementDrawing(geometry, Modifier.fillMaxWidth())
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { onNavigate(navigation.openMeasurement(row.projectId, row.id)) }, enabled = !busy) { Text("Переглянути") }
                                        TextButton(onClick = {
                                            onNavigate(navigation.openMeasurement(row.projectId, row.id).editMeasurement(row.projectId, row.id))
                                        }, enabled = !busy && geometry is MeasurementGeometryView.Captured) { Text("Редагувати") }
                                        TextButton(onClick = { action { onUseMeasurement(checkNotNull(store).geometry(row.projectId, row.id)) } },
                                            enabled = !busy && geometry is MeasurementGeometryView.Captured) { Text("Розрахувати") }
                                        TextButton(onClick = { action { checkNotNull(store).delete(row.projectId, row.id) } }, enabled = !busy) { Text("Видалити") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
