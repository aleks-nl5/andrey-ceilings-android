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
    navigationGuard: MeasurementCreateNavigationGuard? = null, backLabel: String = "До об'єкта",
    editStore: MeasurementEditStore? = null
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
        val pricingState = model?.pricing?.collectAsState()?.value ?: MeasurementPricingState.Loading
        val elementsState = model?.state?.collectAsState()?.value ?: MeasurementElementsState.Loading
        val areas=model?.areas?.collectAsState()?.value ?: emptyList()
        val lines=model?.lines?.collectAsState()?.value ?: emptyList()
        var selectedArea by rememberSaveable(data?.uuid) {mutableStateOf<String?>(null)}
        var selectedLine by rememberSaveable(data?.uuid) {mutableStateOf<String?>(null)}
        val points = model?.points?.collectAsState()?.value ?: PointPlacementState()
        val items=(elementsState as? MeasurementElementsState.Loaded)?.items
        val (drawingEditor,drawingReady)=if(data?.geometry is MeasurementGeometryView.Captured)
            rememberDetailsDrawingEditor(editStore,projectId,measurementId,data.uuid) else (null to false)
        val drawingState=drawingEditor?.state?.collectAsState()?.value
        fun liveActionsAllowed(retry:Boolean=false):Boolean {
            val scene=model?.points?.value ?: return false
            if(!scene.available || scene.saving || !retry && scene.retry!=null || model.state.value !is MeasurementElementsState.Loaded)return false
            if(editStore!=null && data?.geometry is MeasurementGeometryView.Captured && !drawingReady)return false
            return drawingEditor?.state?.value?.let {
                !it.dirty && !it.saving && !it.checking && !it.saved && !it.exitReady && !it.exitRequested && !it.uncertain && !it.conflict &&
                    it.drag==null && it.lineDrag==null && it.area.drag==null
            }!=false
        }
        val liveActions=liveActionsAllowed()
        val effectiveArea=if(drawingEditor!=null)drawingState?.area?.selected else selectedArea
        val effectiveLine=if(drawingEditor!=null)drawingState?.selectedLineUuid else selectedLine
        val effectivePoint=if(drawingEditor!=null)drawingState?.selectedPointUuid else points.selectedPointUuid
        val displayedPoints=drawingEditor?.displayPoints() ?: points.points
        val displayedLines=drawingEditor?.displayLines() ?: lines
        val displayedAreas=drawingEditor?.displayAreas() ?: areas
        val modalOpen=model?.editor?.collectAsState()?.value!=null || points.confirmingDelete || points.saving || points.retry!=null || points.positionEditor!=null || points.modeElementUuid!=null
        val pointElement = (elementsState as? MeasurementElementsState.Loaded)?.items?.find {
            it.uuid == (displayedPoints.find {it.uuid==effectivePoint}?.elementUuid ?: points.modeElementUuid) }
        MeasurementDetailsScreen(state, contentPadding, systemTopInset, onBack, onEdit, navigationGuard, backLabel,
            pricingContent = { compact -> MeasurementPricingSection(
                if (elementsState == MeasurementElementsState.Unavailable) MeasurementPricingState.Unavailable(
                    com.andreyceilings.shared.livepricing.PricingProblemCode.DATABASE_UNAVAILABLE) else pricingState, items.orEmpty(),
                editing = drawingState?.let { it.dirty || it.saving || it.checking || it.uncertain || it.conflict || it.error != null ||
                    it.drag != null || it.lineDrag != null || it.area.drag != null } == true || points.saving || points.retry != null ||
                    model?.editor?.value != null,
                compact = compact, measurementUpdatedAt = data?.updatedAt, onRefresh = { model?.refreshPricing() }) },
            drawingEditor=drawingEditor,drawingEnabled=!modalOpen && (editStore==null || drawingReady) && points.available && items!=null,
            elementsContent = {
                MeasurementElementsSection(elementsState, { if(liveActionsAllowed())model?.add() },
                    { element, action -> if(liveActionsAllowed())model?.open(element, action) }, { model?.retry() },
                    onPlace = { element -> if(liveActionsAllowed())data?.let { drawingEditor?.clearDrawingSelection();model?.beginPlacement(element, it.updatedAt) } },
                    placementUnavailable = when {
                        !liveActions -> "Спершу збережіть або відхиліть чернетку креслення."
                        data?.geometry !is MeasurementGeometryView.Captured -> "Потрібен збережений виміряний контур; історичне креслення не підтримується."
                        !points.available -> "Позначки або захищена база недоступні."
                        points.saving -> "Зачекайте завершення збереження."
                        else -> null
                    }, selectedElementUuid = displayedAreas.find {it.uuid==effectiveArea}?.elementUuid ?: displayedLines.find {it.uuid==effectiveLine}?.elementUuid ?: pointElement?.uuid,
                    onPlaceArea=if(onEdit==null)null else {element ->
                        if(liveActionsAllowed() && model?.finishPlacement()!=false) {
                            navigationGuard?.requestedAreaPlacement=data?.uuid?.let {it to element.uuid}
                            onEdit.invoke()
                        }
                    },onPlaceLine=if(onEdit==null)null else {element ->
                        if(liveActionsAllowed() && model?.finishPlacement()!=false) {
                            navigationGuard?.requestedLinePlacement=data?.uuid?.let {it to element.uuid}
                            onEdit.invoke()
                        }
                    })
            }, areaFeatures=displayedAreas,selectedAreaUuid=effectiveArea,onSelectArea={uuid -> if(!modalOpen && model?.finishPlacement()!=false){selectedArea=uuid;selectedLine=null;drawingEditor?.selectArea(uuid)}},linePlacements=displayedLines,selectedLineUuid=effectiveLine,onSelectLine={uuid ->if(!modalOpen && model?.finishPlacement()!=false){selectedLine=uuid;selectedArea=null;drawingEditor?.selectLine(uuid)}},
            pointState = if(drawingEditor==null)points else points.copy(points=displayedPoints,selectedPointUuid=effectivePoint,
                selectedElementUuid=pointElement?.uuid,drag=drawingState?.drag,undo=null,redo=null), pointElement = pointElement,
            onPlacePoint = { x, y -> if(liveActionsAllowed())model?.tapPoint(x, y) }, onSelectPoint = { if(!modalOpen || points.modeElementUuid!=null){
                selectedArea=null;selectedLine=null
                if(drawingEditor!=null){if(model?.finishPlacement()!=false)drawingEditor.selectPoint(it)} else model?.selectPoint(it)
            } },
            onFinishPlacement = { model?.finishPlacement() }, onDeletePoint = { if(liveActionsAllowed()){
                effectivePoint?.let {model?.selectPoint(it)};model?.requestPointDelete()
            } },
            onRetryPoint = { if(liveActionsAllowed(retry=true))model?.retryPoint() },
            onOpenPosition = { if (liveActionsAllowed() && model?.finishPlacement()!=false) onEdit?.invoke() })
        // Physical mounting input lives in the single co-editor, with the contour draft.
        fun cancelDelete() {model?.cancelPointDelete();if(drawingEditor!=null)model?.finishPlacement()}
        if (points.confirmingDelete) AlertDialog(onDismissRequest = ::cancelDelete,
            title = { Text("Видалити позначку?") }, text = { Text("Прибрати одну позначку «${pointElement?.label.orEmpty()}»? Заявлена кількість залишиться.") },
            confirmButton = { TextButton(onClick = { if(liveActionsAllowed())model?.deletePoint() }) { Text("Видалити позначку") } },
            dismissButton = { TextButton(onClick = ::cancelDelete) { Text("Скасувати") } })
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
    pricingContent: @Composable (Boolean) -> Unit = {},
    drawingEditor:MeasurementEditModel?=null,drawingEnabled:Boolean=true,
    areaFeatures: List<AreaFeature> = emptyList(),selectedAreaUuid:String?=null,onSelectArea:(String)->Unit={},
    linePlacements: List<LinePlacement> = emptyList(),selectedLineUuid:String?=null,onSelectLine:(String)->Unit={},
    pointState: PointPlacementState = PointPlacementState(), pointElement: MeasurementElement? = null,
    onPlacePoint: (Long, Long) -> Unit = { _, _ -> }, onSelectPoint: (String) -> Unit = {},
    onFinishPlacement: () -> Unit = {}, onDeletePoint: () -> Unit = {}, onRetryPoint: () -> Unit = {},
    onOpenPosition: () -> Unit = {}
) {
    val drawingState=drawingEditor?.state?.collectAsState()?.value
    val canDrag=drawingEnabled && drawingState?.let {!it.saving && !it.checking && !it.saved && !it.exitReady && !it.uncertain && !it.conflict && !it.exitRequested}==true
    val pointActionsEnabled=!pointState.saving && pointState.retry==null && drawingState?.let {
        !it.dirty && !it.saving && !it.checking && !it.exitReady && !it.uncertain && !it.conflict && !it.exitRequested
    }!=false
    val displayedPoints=drawingEditor?.displayPoints() ?: pointState.points
    val selectedPoint=if(drawingEditor!=null)drawingState?.selectedPointUuid else pointState.selectedPointUuid
    DisposableEffect(drawingEditor,navigationGuard) {
        drawingEditor?.let {navigationGuard?.bindEdit(it)}
        onDispose {
            drawingEditor?.let {
                val state=it.state.value
                if(!state.saving && !state.checking && !state.exitReady){it.cancelPointDrag();it.cancelLineDrag();it.cancelAreaDrag()}
                navigationGuard?.unbindEdit(it)
            }
        }
    }
    drawingEditor?.let {DetailsDrawingExitDialog(it)}
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
            TextButton(enabled = !pointState.saving && drawingState?.saving!=true && drawingState?.checking!=true && drawingState?.exitReady!=true, onClick = { drawingEditor?.cancelLineDrag();drawingEditor?.cancelAreaDrag();onFinishPlacement(); fullscreen = !fullscreen }) { Text(if (fullscreen) "Згорнути" else "На весь екран") }
            if (onEdit != null && data?.geometry is MeasurementGeometryView.Captured)
                Button(enabled=drawingState?.saving!=true && drawingState?.checking!=true && drawingState?.exitReady!=true,onClick = {drawingEditor?.requestExit(onEdit) ?: onEdit()}) { Text("Редагувати") }
        }
    }
    if (fullscreen && data != null) {
        Column(Modifier.fillMaxSize().padding(start = start, end = end,
            bottom = contentPadding.calculateBottomPadding(), top = systemTopInset + 8.dp)) {
            controls()
            pricingContent(true)
            if (displayedPoints.isNotEmpty() || linePlacements.isNotEmpty() || areaFeatures.isNotEmpty()) Text("Позначки у fullscreen лише для перегляду. Положення змінюйте у звичайних деталях заміру.")
            Box(Modifier.weight(1f)) {
                MeasurementDrawing(data.geometry, Modifier.fillMaxSize(), interactive = true,
                    selectedSide = selectedSide, onSelectSide = { selectedSide = it }, fullscreen = true,
                    areaFeatures=drawingEditor?.displayAreas() ?: areaFeatures,selectedAreaUuid=selectedAreaUuid,onSelectArea=onSelectArea,pointPlacements = displayedPoints,selectedPointUuid=selectedPoint,linePlacements=drawingEditor?.displayLines() ?: linePlacements,selectedLineUuid=selectedLineUuid,onSelectLine=onSelectLine)
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
                CeilingsFab(onClick = {drawingEditor?.requestExit(onBack) ?: onBack()}, actionLabel = backLabel, profile = CeilingsFabProfile.COMPACT) {
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
                                val unresolved=displayedPoints.count { it.elementUuid==pointElement.uuid && it.mounting.unresolved }
                                Text("Розміщено ${pointElement.placedQuantityMilli / 1000} з $capacity · Потребує уточнення: $unresolved")
                            }
                        }
                        val reviewCount=displayedPoints.count { it.mounting.needsReview }+(drawingEditor?.displayLines() ?: linePlacements).count {it.needsReview}
                        (drawingEditor?.displayAreas() ?: areaFeatures).forEach {area -> FilterChip(area.uuid==selectedAreaUuid,onClick={onSelectArea(area.uuid)},label={Text(areaDescription(area))});if(area.note.isNotBlank())Text(area.note)}
                        if(areaFeatures.isNotEmpty())Text("Площинні позначки не віднімаються від площі стелі; перекриття не об’єднуються.")
                        (drawingEditor?.displayLines() ?: linePlacements).forEachIndexed {i,line ->
                            FilterChip(line.uuid==selectedLineUuid,onClick={onSelectLine(line.uuid)},enabled=canDrag,
                                label={Text("Сегмент ${i+1}: ${formatElementMilli(line.lengthMm)} м")})
                        }
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
                        if (selectedPoint != null) TextButton(enabled = pointActionsEnabled,
                            onClick = onDeletePoint) { Text("Видалити позначку") }
                        if (selectedPoint != null) {
                            val marker = displayedPoints.find { it.uuid == selectedPoint }
                            Text(when {
                                marker?.mounting?.anchor != null && marker.mounting.unresolved -> "Ця позначка: збережені виміряні відстані; положення потребує уточнення"
                                marker?.mounting?.anchor != null -> "Ця позначка: положення за виміряними відстанями"
                                marker?.precision == PointPrecision.EXACT -> "Ця позначка: точне числове положення без виміряних стінних прив'язок"
                                else -> "Ця позначка: приблизне положення"
                            })
                            if(marker?.mounting?.anchor==null) Text("Виміряних стінних прив'язок немає.")
                            if(marker?.mounting?.needsReview==true) Text(if(marker.mounting.unresolved) "Потребує уточнення" else "Потрібно перевірити розміщення")
                            Text("Перетягування скидає активну прив'язку й задає приблизне положення; порожнє поле пересуває креслення.")
                            TextButton(enabled = pointActionsEnabled, onClick = onOpenPosition) { Text("Задати розміри від стін") }
                        }
                        drawingEditor?.let {DetailsDrawingControls(it,drawingEnabled)}
                        MeasurementDrawing(row.geometry, Modifier.fillMaxWidth(), interactive = true,
                            selectedSide = selectedSide, onSelectSide = { selectedSide = it },
                            areaFeatures=drawingEditor?.displayAreas() ?: areaFeatures,selectedAreaUuid=selectedAreaUuid,onSelectArea=onSelectArea,
                            areaEditingEnabled=canDrag,areaDrag=drawingState?.area?.drag,
                            onBeginAreaDrag={drawingEditor?.beginAreaDrag(it)},onPreviewAreaDrag={x,y->drawingEditor?.previewAreaDrag(x,y)},
                            onFinishAreaDrag={drawingEditor?.finishAreaDrag()},onCancelAreaDrag={drawingEditor?.cancelAreaDrag()},
                            linePlacements=drawingEditor?.displayLines() ?: linePlacements,selectedLineUuid=selectedLineUuid,onSelectLine=onSelectLine,
                            lineEditingEnabled=canDrag,lineDrag=drawingState?.lineDrag,
                            onBeginLineDrag={drawingEditor?.beginLineDrag(it)},onPreviewLineDrag={x,y->drawingEditor?.previewLineDrag(x,y)},
                            onFinishLineDrag={drawingEditor?.finishLineDrag()},onCancelLineDrag={drawingEditor?.cancelLineDrag()},
                            pointPlacements = displayedPoints, fitPointPlacements = true, selectedPointUuid = selectedPoint,
                            placementMode = pointState.modeElementUuid != null, onPlacePoint = onPlacePoint, onSelectPoint = onSelectPoint,
                            pointEditingEnabled = canDrag,
                            pointDrag = drawingState?.drag, onBeginPointDrag = {drawingEditor?.beginPointDrag(it)},
                            onPreviewPointDrag = {x,y->drawingEditor?.previewPointDrag(x,y)},
                            onFinishPointDrag = {drawingEditor?.finishPointDrag()}, onCancelPointDrag = {drawingEditor?.cancelPointDrag()})
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
                    item(key = "pricing") { pricingContent(false) }
                    item(key = "elements") { elementsContent() }
                }
            }
        }
    }
}
