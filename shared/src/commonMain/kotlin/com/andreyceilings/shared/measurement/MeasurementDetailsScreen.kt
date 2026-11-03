package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.ui.CeilingsScreenScaffold

@Composable
fun MeasurementDetailsScreen(
    projectId: Long, measurementId: Long, store: MeasurementDetailsStore?,
    contentPadding: PaddingValues, systemTopInset: Dp, onBack: () -> Unit, onEdit: (() -> Unit)? = null,
    navigationGuard: MeasurementCreateNavigationGuard? = null
) {
    // Reset collector state with its route/session; never flash a previous record.
    key(projectId, measurementId, store) {
        val flow = remember { store?.observe(projectId, measurementId) }
        val state = flow?.collectAsState(MeasurementDetailsState.Loading)?.value
            ?: MeasurementDetailsState.Unavailable
        MeasurementDetailsScreen(state, contentPadding, systemTopInset, onBack, onEdit, navigationGuard)
    }
}

/** State-hoisted rendering is also usable by previews and real Compose scene tests. */
@Composable
fun MeasurementDetailsScreen(
    state: MeasurementDetailsState,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    systemTopInset: Dp = 0.dp,
    onBack: () -> Unit, onEdit: (() -> Unit)? = null,
    navigationGuard: MeasurementCreateNavigationGuard? = null
) {
    var fullscreen by rememberSaveable { mutableStateOf(navigationGuard?.fullscreen?.value == true) }
    var selectedSide by rememberSaveable { mutableStateOf<Int?>(null) }
    val surfaceBack = remember { { if (fullscreen) { fullscreen = false; true } else false } }
    DisposableEffect(navigationGuard) {
        navigationGuard?.bindSurface(surfaceBack)
        onDispose { navigationGuard?.unbindSurface(surfaceBack) }
    }
    SideEffect { navigationGuard?.fullscreen?.value = fullscreen }
    val data = (state as? MeasurementDetailsState.Loaded)?.data
    val listState = rememberSaveable(data?.id, saver = LazyListState.Saver) { LazyListState() }
    val direction = LocalLayoutDirection.current
    val start = contentPadding.calculateStartPadding(direction)
    val end = contentPadding.calculateEndPadding(direction)
    val controls: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { fullscreen = !fullscreen }) { Text(if (fullscreen) "Згорнути" else "На весь екран") }
            if (onEdit != null && data?.geometry is MeasurementGeometryView.Captured)
                Button(onClick = onEdit) { Text("Редагувати") }
        }
    }
    if (fullscreen && data != null) {
        Column(Modifier.fillMaxSize().padding(start = start, end = end,
            bottom = contentPadding.calculateBottomPadding(), top = systemTopInset + 8.dp)) {
            controls()
            Box(Modifier.weight(1f)) {
                MeasurementDrawing(data.geometry, Modifier.fillMaxSize(), interactive = true,
                    selectedSide = selectedSide, onSelectSide = { selectedSide = it }, fullscreen = true)
            }
            val contour = remember(data.geometry) { measurementDrawingContour(data.geometry) }
            contour?.let {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(it.points.size) { i -> FilterChip(selectedSide == i, onClick = { selectedSide = i },
                        label = { Text(it.namedSideLabels[i]) }) }
                }
            }
        }
        return
    }
    CeilingsScreenScaffold(
        windowInsets = WindowInsets(0, 0, 0, 0),
        contentPadding = PaddingValues(start = start, end = end, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding()),
        header = { Spacer(Modifier.height(systemTopInset + 8.dp)) },
        pinned = {
            Row(Modifier.fillMaxWidth().padding(start = start, end = end),
                verticalAlignment = Alignment.CenterVertically) {
                CeilingsFab(onClick = onBack, actionLabel = "До об'єкта", profile = CeilingsFabProfile.COMPACT) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(data?.name ?: "Замір", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    data?.let { Text(it.projectAddress.ifBlank { it.projectTitle },
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        bottomGradient = false // The enclosing host owns bottom navigation/insets.
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                MeasurementDetailsState.Loading -> item { Text("Завантаження заміру…") }
                MeasurementDetailsState.Unavailable -> item { Text("Захищена база недоступна або заблокована.") }
                MeasurementDetailsState.Missing -> item { Text("Замір не знайдено, видалено або він не належить цьому об'єкту.") }
                is MeasurementDetailsState.Loaded -> {
                    val row = state.data
                    item(key = "controls") { controls() }
                    item(key = "metadata") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Об'єкт: ${row.projectAddress.ifBlank { row.projectTitle }}")
                                Text("Форма: ${CeilingShapeTemplates.template(row.shape)?.title ?: if (row.shape == "polygon") "Багатокутник" else row.shape}")
                                // Display captured database totals, including historical values.
                                val precision = (row.geometry as? MeasurementGeometryView.Captured)?.geometry
                                    ?.precisionStepMeters?.let(MeasurementPrecision::fromStep)
                                Text("Площа: ${formatMeasurementArea(row.areaSquareMeters, precision)} м² · " +
                                    "Периметр: ${formatMeasurementLength(row.perimeterMeters, precision)} м")
                            }
                        }
                    }
                    item(key = "drawing") {
                        MeasurementDrawing(row.geometry, Modifier.fillMaxWidth(), interactive = true,
                            selectedSide = selectedSide, onSelectSide = { selectedSide = it })
                        val contour = remember(row.geometry) { measurementDrawingContour(row.geometry) }
                        contour?.let { c -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            c.namedSideLabels.forEachIndexed { i, label ->
                                FilterChip(selectedSide == i, onClick = { selectedSide = i }, label = { Text(label) })
                            }
                        } }
                    }
                    if (row.notes.isNotBlank()) item(key = "notes") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Примітки", style = MaterialTheme.typography.titleSmall)
                                Text(row.notes)
                            }
                        }
                    }
                }
            }
        }
    }
}
