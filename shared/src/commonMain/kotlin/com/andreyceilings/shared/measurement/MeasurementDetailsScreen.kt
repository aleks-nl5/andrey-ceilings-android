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
    navigationGuard: MeasurementCreateNavigationGuard? = null, backLabel: String = "До об'єкта"
) {
    // Reset collector state with its route/session; never flash a previous record.
    key(projectId, measurementId, store) {
        val flow = remember { store?.observe(projectId, measurementId) }
        val state = flow?.collectAsState(MeasurementDetailsState.Loading)?.value
            ?: MeasurementDetailsState.Unavailable
        val data = (state as? MeasurementDetailsState.Loaded)?.data
        val model = remember(store, data?.uuid) {
            if (store != null && data != null && data.uuid.isNotBlank())
                store.elements.model(ElementRoute(projectId, measurementId, data.uuid)) else null
        }
        DisposableEffect(model, navigationGuard) {
            model?.let { navigationGuard?.bindElements(it) }
            onDispose { model?.let { navigationGuard?.unbindElements(it) } }
        }
        val elementsState = model?.state?.collectAsState()?.value ?: MeasurementElementsState.Loading
        val lines=model?.lines?.collectAsState()?.value ?: emptyList()
        var selectedLine by rememberSaveable(data?.uuid) {mutableStateOf<String?>(null)}
        val points = model?.points?.collectAsState()?.value ?: PointPlacementState()
        val pointElement = (elementsState as? MeasurementElementsState.Loaded)?.items?.find {
            it.uuid == (points.selectedElementUuid ?: points.modeElementUuid) }
        MeasurementDetailsScreen(state, contentPadding, systemTopInset, onBack, onEdit, navigationGuard, backLabel,
            elementsContent = {
                MeasurementElementsSection(elementsState, { model?.add() },
                    { element, action -> model?.open(element, action) }, { model?.retry() },
                    onPlace = { element -> data?.let { model?.beginPlacement(element, it.updatedAt) } },
                    placementUnavailable = when {
                        data?.geometry !is MeasurementGeometryView.Captured -> "Потрібен збережений виміряний контур; історичне креслення не підтримується."
                        !points.available -> "Позначки або захищена база недоступні."
                        points.saving -> "Зачекайте завершення збереження."
                        else -> null
                    }, selectedElementUuid = lines.find {it.uuid==selectedLine}?.elementUuid ?: points.selectedElementUuid,
                    onPlaceLine=if(onEdit==null)null else {element ->
                        if(model?.finishPlacement()!=false) {
                            navigationGuard?.requestedLinePlacement=data?.uuid?.let {it to element.uuid}
                            onEdit.invoke()
                        }
                    })
            }, linePlacements=lines,selectedLineUuid=selectedLine,onSelectLine={uuid ->if(model?.finishPlacement()!=false)selectedLine=uuid}, pointState = points, pointElement = pointElement,
            onPlacePoint = { x, y -> model?.tapPoint(x, y) }, onSelectPoint = { selectedLine=null;model?.selectPoint(it) },
            onFinishPlacement = { model?.finishPlacement() }, onDeletePoint = { model?.requestPointDelete() },
            onRetryPoint = { model?.retryPoint() },
            onOpenPosition = { if (model?.finishPlacement()!=false) onEdit?.invoke() },
            onBeginPointDrag = { uuid -> data?.let { model?.beginPointDrag(uuid, it.updatedAt) } },
            onPreviewPointDrag = { x, y -> model?.previewPointDrag(x, y) },
            onFinishPointDrag = { model?.finishPointDrag() }, onCancelPointDrag = { model?.cancelPointDrag() },
            onUndoPoint = { model?.undoPointMove() }, onRedoPoint = { model?.redoPointMove() })
        // Physical mounting input lives in the single co-editor, with the contour draft.
        if (points.confirmingDelete) AlertDialog(onDismissRequest = { model?.cancelPointDelete() },
            title = { Text("Видалити позначку?") }, text = { Text("Прибрати одну позначку «${pointElement?.label.orEmpty()}»? Заявлена кількість залишиться.") },
            confirmButton = { TextButton(onClick = { model?.deletePoint() }) { Text("Видалити позначку") } },
            dismissButton = { TextButton(onClick = { model?.cancelPointDelete() }) { Text("Скасувати") } })
        if (model != null) {
            val editor by model.editor.collectAsState()
            val catalog by model.catalog.collectAsState()
            editor?.let { current -> MeasurementElementEditor(current, catalog,
                elementsState is MeasurementElementsState.Loaded, model::update, model::requestDismiss, model::save,
                model::refreshCatalog, model::continueEditing, model::discardAndExit,
                model::requestReload, model::cancelReload, model::reload) }
        }
    }
}

/** State-hoisted rendering is also usable by previews and real Compose scene tests. */
@Composable
fun MeasurementDetailsScreen(
    state: MeasurementDetailsState,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    systemTopInset: Dp = 0.dp,
    onBack: () -> Unit, onEdit: (() -> Unit)? = null,
    navigationGuard: MeasurementCreateNavigationGuard? = null, backLabel: String = "До об'єкта",
    elementsContent: @Composable () -> Unit = {},
    linePlacements: List<LinePlacement> = emptyList(),selectedLineUuid:String?=null,onSelectLine:(String)->Unit={},
    pointState: PointPlacementState = PointPlacementState(), pointElement: MeasurementElement? = null,
    onPlacePoint: (Long, Long) -> Unit = { _, _ -> }, onSelectPoint: (String) -> Unit = {},
    onFinishPlacement: () -> Unit = {}, onDeletePoint: () -> Unit = {}, onRetryPoint: () -> Unit = {},
    onOpenPosition: () -> Unit = {}, onBeginPointDrag: (String) -> Unit = {},
    onPreviewPointDrag: (Double, Double) -> Unit = { _, _ -> }, onFinishPointDrag: () -> Unit = {},
    onCancelPointDrag: () -> Unit = {}, onUndoPoint: () -> Unit = {}, onRedoPoint: () -> Unit = {}
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
    LaunchedEffect(pointState.modeElementUuid) {
        if (pointState.modeElementUuid != null) listState.scrollToItem(2)
    }
    val direction = LocalLayoutDirection.current
    val start = contentPadding.calculateStartPadding(direction)
    val end = contentPadding.calculateEndPadding(direction)
    val controls: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !pointState.saving, onClick = { onFinishPlacement(); fullscreen = !fullscreen }) { Text(if (fullscreen) "Згорнути" else "На весь екран") }
            if (onEdit != null && data?.geometry is MeasurementGeometryView.Captured)
                Button(onClick = onEdit) { Text("Редагувати") }
        }
    }
    if (fullscreen && data != null) {
        Column(Modifier.fillMaxSize().padding(start = start, end = end,
            bottom = contentPadding.calculateBottomPadding(), top = systemTopInset + 8.dp)) {
            controls()
            if (pointState.points.isNotEmpty()) Text("Позначки у fullscreen лише для перегляду. Положення змінюйте у звичайних деталях заміру.")
            Box(Modifier.weight(1f)) {
                MeasurementDrawing(data.geometry, Modifier.fillMaxSize(), interactive = true,
                    selectedSide = selectedSide, onSelectSide = { selectedSide = it }, fullscreen = true,
                    pointPlacements = pointState.points,linePlacements=linePlacements,selectedLineUuid=selectedLineUuid,onSelectLine=onSelectLine)
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
                CeilingsFab(onClick = onBack, actionLabel = backLabel, profile = CeilingsFabProfile.COMPACT) {
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
                        if (pointElement != null) {
                            Text(pointElement.label, style = MaterialTheme.typography.titleMedium)
                            pointElement.pointCapacity()?.let { capacity ->
                                val unresolved=pointState.points.count { it.elementUuid==pointElement.uuid && it.mounting.unresolved }
                                Text("Розміщено ${pointElement.placedQuantityMilli / 1000} з $capacity · Потребує уточнення: $unresolved")
                            }
                        }
                        val reviewCount=pointState.points.count { it.mounting.needsReview }+linePlacements.count {it.needsReview}
                        if(linePlacements.isNotEmpty())Text("Ліній: ${linePlacements.size} · Потребує уточнення: ${linePlacements.count {it.unresolved}}")
                        if(reviewCount>0) {
                            Text("Розміщення потребує перевірки після зміни контуру або прив'язок.")
                            TextButton(onClick={onEdit?.invoke()},enabled=onEdit!=null && !pointState.saving){Text("Перевірити ($reviewCount)")}
                        }
                        if (pointState.modeElementUuid != null) {
                            Text("Приблизне розміщення: торкніться всередині контуру. Тап по позначці вибирає її; жест масштабу чи зсуву не додає точок.")
                            TextButton(enabled = !pointState.saving, onClick = onFinishPlacement) { Text("Готово") }
                        }
                        if (pointState.saving) { LinearProgressIndicator(); Text("Збереження позначки…") }
                        pointState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (pointState.retry != null) TextButton(enabled = !pointState.saving, onClick = onRetryPoint) { Text("Повторити ту саму спробу") }
                        if (pointState.selectedPointUuid != null) TextButton(enabled = !pointState.saving && pointState.retry == null,
                            onClick = onDeletePoint) { Text("Видалити позначку") }
                        if (pointState.selectedPointUuid != null) {
                            val marker = pointState.points.find { it.uuid == pointState.selectedPointUuid }
                            Text(when {
                                marker?.mounting?.anchor != null && marker.mounting.unresolved -> "Ця позначка: збережені виміряні відстані; положення потребує уточнення"
                                marker?.mounting?.anchor != null -> "Ця позначка: положення за виміряними відстанями"
                                marker?.precision == PointPrecision.EXACT -> "Ця позначка: точне числове положення без виміряних стінних прив'язок"
                                else -> "Ця позначка: приблизне положення"
                            })
                            if(marker?.mounting?.anchor==null) Text("Виміряних стінних прив'язок немає.")
                            if(marker?.mounting?.needsReview==true) Text(if(marker.mounting.unresolved) "Потребує уточнення" else "Потрібно перевірити розміщення")
                            Text("Перетягування скидає активну прив'язку й задає приблизне положення; порожнє поле пересуває креслення.")
                            TextButton(enabled = !pointState.saving && pointState.retry == null, onClick = onOpenPosition) { Text("Задати розміри від стін") }
                        }
                        FlowRow {
                            TextButton(enabled = pointState.undo != null && !pointState.saving && pointState.retry == null,
                                onClick = onUndoPoint) { Text("Скасувати переміщення") }
                            TextButton(enabled = pointState.redo != null && !pointState.saving && pointState.retry == null,
                                onClick = onRedoPoint) { Text("Повторити переміщення") }
                        }
                        if (pointState.undo != null || pointState.redo != null)
                            Text("Історія переміщень діє лише доки відкрита ця стеля.", style = MaterialTheme.typography.bodySmall)
                        MeasurementDrawing(row.geometry, Modifier.fillMaxWidth(), interactive = true,
                            selectedSide = selectedSide, onSelectSide = { selectedSide = it },
                            linePlacements=linePlacements,selectedLineUuid=selectedLineUuid,onSelectLine=onSelectLine,pointPlacements = pointState.points, fitPointPlacements = true, selectedPointUuid = pointState.selectedPointUuid,
                            placementMode = pointState.modeElementUuid != null, onPlacePoint = onPlacePoint, onSelectPoint = onSelectPoint,
                            pointEditingEnabled = !pointState.saving && pointState.retry == null && pointState.positionEditor == null,
                            pointDrag = pointState.drag, onBeginPointDrag = onBeginPointDrag,
                            onPreviewPointDrag = onPreviewPointDrag, onFinishPointDrag = onFinishPointDrag, onCancelPointDrag = onCancelPointDrag)
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
                    item(key = "elements") { elementsContent() }
                }
            }
        }
    }
}
