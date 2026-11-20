package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.ui.ceilingsFloatingOutlineAlpha
import kotlinx.coroutines.*

private enum class MeasurementElementSection { POINTS, LINES, AREAS }

private enum class MeasurementEditContext(val label: String) {
    CONTOUR("Контур"),
    ELEMENTS("Елементи"),
    COST("Вартість")
}

@Composable
fun MeasurementEditScreen(
    projectId: Long, measurementId: Long, store: MeasurementEditStore?,
    contentPadding: PaddingValues, systemTopInset: Dp, onBack: () -> Unit,
    onSaved: () -> Unit, navigationGuard: MeasurementCreateNavigationGuard?
) {
    val editorPadding = measurementEditorPadding(contentPadding, systemTopInset)
    // Do not consume restored form state against Android's temporary null session
    // during Activity startup. The unlocked-session store can reattach a pending write.
    if (store == null) {
        Column(Modifier.fillMaxSize().padding(editorPadding)) {
            Text("Захищена база недоступна або завантажується.")
            TextButton(onClick = onBack) { Text("До заміру") }
        }
        return
    }
    key(projectId, measurementId) {
        val saver = remember(store) { Saver<MeasurementEditModel?, String>(save = { it?.snapshot() },
            restore = { MeasurementEditModel.restore(store, it, projectId, measurementId) }) }
        var model by rememberSaveable(store, stateSaver = saver) { mutableStateOf<MeasurementEditModel?>(null) }
        var error by remember(store) { mutableStateOf<String?>(null) }
        LaunchedEffect(store) {
            if (model == null) try {
                val document = withContext(Dispatchers.Default) { checkNotNull(store).load(projectId, measurementId) }
                model = MeasurementEditModel(store, document).also {editor ->
                    navigationGuard?.requestedLinePlacement?.takeIf {it.first==document.draft.recordUuid}?.let {editor.beginLine(it.second)}
                    navigationGuard?.requestedLinePlacement=null
                    navigationGuard?.requestedAreaPlacement?.takeIf {it.first==document.draft.recordUuid}?.let {editor.beginArea(AreaRole.GENERAL_AREA,it.second)}
                    navigationGuard?.requestedAreaPlacement=null
                }
            } catch (_: Exception) { error = "Замір недоступний для редагування. Перевірте об'єкт і збережену геометрію." }
        }
        val editor = model
        if (editor == null) Column(Modifier.fillMaxSize().padding(editorPadding)) {
            Text(error ?: "Завантаження заміру…")
            TextButton(onClick = onBack) { Text("До заміру") }
        } else MeasurementEditScreen(editor, contentPadding, systemTopInset, onBack, onSaved, navigationGuard)
    }
}

@Composable
fun MeasurementEditScreen(
    model: MeasurementEditModel, contentPadding: PaddingValues = PaddingValues(16.dp),
    systemTopInset: Dp = 0.dp, onBack: () -> Unit, onSaved: () -> Unit,
    navigationGuard: MeasurementCreateNavigationGuard? = null,
    imeInsets: WindowInsets? = null
) {
    val state by model.state.collectAsState()
    val validation = remember(state.draft) { validateMeasurementCreate(state.draft) }
    val pointFlow = remember(model, state.original.draft.recordUuid) { model.observePoints() }
    val overlay = key(model, state.original.draft.recordUuid) { pointFlow.collectAsState(PointOverlayState.Loading).value }
    LaunchedEffect(overlay,state.original) {
        val incoming=(overlay as? PointOverlayState.Loaded)?.points
        if(incoming!=null && !state.saving && !state.saved && (incoming.map {it.uuid}.toSet()!=state.original.placements.points.map {it.uuid}.toSet() ||
            incoming.any { p -> state.original.placements.points.any { it.uuid==p.uuid && p.updatedAt>it.updatedAt } })) model.markPointsChanged()
    }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val measurementUuid = state.original.draft.recordUuid
    var fullscreen by rememberSaveable(measurementUuid) { mutableStateOf(navigationGuard?.fullscreen?.value == true) }
    val draft = state.draft
    val sideIds = (draft.outline as? MeasurementOutlineDraft.Orthogonal)?.sides?.map { it.inputId }
    var selected by rememberSaveable(measurementUuid, draft.shape, sideIds, state.original.updatedAt) {
        mutableStateOf<Int?>(null)
    }
    var allFields by rememberSaveable(measurementUuid) { mutableStateOf(false) }
    var context by rememberSaveable(measurementUuid) { mutableStateOf(
        if (state.lineElementUuid != null || state.area.tool != null) MeasurementEditContext.ELEMENTS
        else MeasurementEditContext.CONTOUR
    ) }
    var normalPanelExpanded by rememberSaveable(measurementUuid) { mutableStateOf(true) }
    var fullscreenPanelExpanded by rememberSaveable(measurementUuid) { mutableStateOf(false) }
    val panelExpanded = if (fullscreen) fullscreenPanelExpanded else normalPanelExpanded
    var statusDetails by rememberSaveable(measurementUuid) { mutableStateOf<String?>(null) }
    val panelState = rememberSaveableStateHolder()
    val contourScroll = rememberScrollState()
    var reloadConfirm by remember { mutableStateOf(false) }
    val currentSaved by rememberUpdatedState(onSaved)
    val surfaceBack = remember(model, measurementUuid) {
        {
            if (fullscreen) {
                model.cancelPointDrag()
                model.cancelLineDrag()
                model.cancelAreaDrag()
                fullscreen = false
                true
            } else false
        }
    }
    DisposableEffect(model, navigationGuard) {
        navigationGuard?.bindEdit(model); navigationGuard?.bindSurface(surfaceBack)
        onDispose {
            model.cancelPointDrag(); model.cancelLineDrag(); model.cancelAreaDrag()
            navigationGuard?.unbindEdit(model); navigationGuard?.unbindSurface(surfaceBack)
        }
    }
    SideEffect { navigationGuard?.fullscreen?.value = fullscreen }
    LaunchedEffect(model) { model.reconcile() }
    LaunchedEffect(state.saved) { if (state.saved) { currentSaved(); model.delivered() } }
    val canLeaveSurface = !state.saving && !state.checking && !state.saved && !state.exitReady
    val enabled = canLeaveSurface && !state.uncertain
    fun save() {
        focus.clearFocus(); model.commitInput()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { model.save() }
    }
    fun select(side: Int) {
        focus.clearFocus()
        model.commitInput()
        selected = side
        context = MeasurementEditContext.CONTOUR
        scope.launch { contourScroll.scrollTo(0) }
    }
    fun showElements() {
        context = MeasurementEditContext.ELEMENTS
    }

    val selectedFeature = state.selectedPointUuid ?: state.selectedLineUuid ?: state.area.selected
    val activeElementControls = selectedFeature ?: state.lineElementUuid ?: state.area.tool?.name
    var previousElementControls by rememberSaveable(measurementUuid) { mutableStateOf(activeElementControls) }
    LaunchedEffect(activeElementControls) {
        if (activeElementControls != null && activeElementControls != previousElementControls) showElements()
        previousElementControls = activeElementControls
    }
    val savedGeometry = (overlay as? PointOverlayState.Loaded)?.savedGeometry
        ?: validateMeasurementCreate(state.original.draft).geometry
    val geometry = validation.geometry ?: savedGeometry
    val geometryWarning = if (validation.geometry == null && savedGeometry != null)
        "Невалідна чернетка: показано збережений контур і позначки. ${validation.geometryError.orEmpty()}"
    else null
    val status = when {
        state.saved -> "Замір збережено. Відкриття…"
        state.saving -> "Збереження триває. Дочекайтеся результату."
        state.checking -> "Перевірка стану заміру. Дочекайтеся результату."
        state.error != null -> state.error
        state.uncertain -> "Результат збереження невідомий."
        state.conflict -> "Збережений замір змінився. Перезавантажте запис."
        geometryWarning != null -> geometryWarning
        overlay is PointOverlayState.Missing -> "Замір або об'єкт недоступний."
        overlay is PointOverlayState.Unavailable -> "Позначки недоступні: захищена база заблокована або помилка читання."
        overlay is PointOverlayState.Loading -> "Завантаження збережених позначок…"
        else -> null
    }
    val statusIsProblem = state.error != null || geometryWarning != null || state.uncertain || state.conflict ||
        overlay is PointOverlayState.Missing || overlay is PointOverlayState.Unavailable
    val editorPadding = measurementEditorPadding(contentPadding, systemTopInset)
    val keyboardPadding = if (imeInsets == null) Modifier.imePadding() else Modifier.windowInsetsPadding(imeInsets)
    val keyboard = imeInsets ?: WindowInsets.ime
    val keyboardVisible = keyboard.getBottom(LocalDensity.current) > 0
    val inputFocus = remember(imeInsets) { MeasurementInputFocus(imeInsets) }
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(editorPadding).consumeWindowInsets(editorPadding).then(keyboardPadding)
    ) {
        val restingPanelHeight = maxHeight * when {
            panelExpanded -> 0.30f
            fullscreen -> 0.10f
            else -> 0.16f
        }
        val panelMaxHeight = if (keyboardVisible) {
            // Leave room for a field and its supporting text even in the compact panel.
            maxOf(restingPanelHeight, ((maxHeight - 108.dp) * 0.66f).coerceIn(0.dp, 144.dp))
        } else restingPanelHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth().testTag("measurement-edit-header"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MeasurementEditorFab(
                    label = "До заміру",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    enabled = canLeaveSurface,
                    modifier = Modifier.testTag("measurement-edit-back")
                ) { if (!surfaceBack()) model.requestExit(onBack) }
                Column(Modifier.weight(1f)) {
                    Text(draft.name.ifBlank { "Стеля" }, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (state.dirty && !statusIsProblem) {
                        Text("Незбережено", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                MeasurementIconAction("Скасувати дію", Icons.AutoMirrored.Filled.Undo, enabled && state.canUndo) {
                    focus.clearFocus()
                    model.undo()
                }
                MeasurementIconAction("Повторити", Icons.AutoMirrored.Filled.Redo, enabled && state.canRedo) {
                    focus.clearFocus()
                    model.redo()
                }
                MeasurementIconAction(
                    if (fullscreen) "Згорнути" else "На весь екран",
                    if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    canLeaveSurface
                ) {
                    if (!surfaceBack()) {
                        fullscreenPanelExpanded = false
                        fullscreen = true
                    }
                }
                MeasurementEditorFab(
                    label = "Зберегти замір",
                    icon = Icons.Default.Save,
                    enabled = enabled && validation.canSave && model.canSaveMountingDraft() &&
                        !state.conflict && overlay is PointOverlayState.Loaded,
                    modifier = Modifier.testTag("measurement-edit-save"),
                    onClick = ::save
                )
            }
            status?.let { message ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusIsProblem) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (statusIsProblem) {
                        MeasurementIconAction("Показати повне повідомлення", Icons.Default.Info) {
                            statusDetails = message
                        }
                    }
                }
            }
            if (state.uncertain || state.conflict) FlowRow {
                if (state.uncertain) TextButton(enabled = !state.saving, onClick = { scope.launch { model.save() } }) {
                    Text("Повторити ту саму спробу")
                }
                TextButton(enabled = !state.saving, onClick = { reloadConfirm = true }) { Text("Перезавантажити запис") }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (geometry == null) Text("Креслення недоступне: ${validation.geometryError.orEmpty()}")
                else MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
                    Modifier.fillMaxSize(), interactive = true, fillAvailableHeight = true, showSideLegend = false,
                    retainCameraOnResize = true, verticalViewControls = true,
                    selectedSide = selected, onSelectSide = ::select, fullscreen = fullscreen,
                    pointPlacements = if(overlay is PointOverlayState.Loaded) model.displayPoints() else emptyList(), fitPointPlacements = true,
                    selectedPointUuid=state.selectedPointUuid,onSelectPoint = { model.selectPoint(it); showElements() },
                    pointEditingEnabled=enabled && overlay is PointOverlayState.Loaded && validation.geometry!=null,editPointsInFullscreen=true,
                    pointDrag=state.drag,onBeginPointDrag=model::beginPointDrag,onPreviewPointDrag=model::previewPointDrag,
                    onFinishPointDrag=model::finishPointDrag,onCancelPointDrag=model::cancelPointDrag,
                    mountingDimensions=if(validation.geometry==null) emptyList() else mountingDimensions(model.displayPoints().find { it.uuid==state.selectedPointUuid },geometry,model.draftWalls())+
                        model.displayLines().find {it.uuid==state.selectedLineUuid}?.takeUnless {it.unresolved}?.let {line ->
                            mountingDimensions(line.endpoint(true),geometry,model.draftWalls())+mountingDimensions(line.endpoint(false),geometry,model.draftWalls())
                        }.orEmpty()+model.displayAreas().find {it.uuid==state.area.selected}?.takeUnless {it.unresolved}?.let {a ->
                            mountingDimensions(a.point(state.area.mountingIndex ?: state.area.moveVertex ?: 0),geometry,model.draftWalls())
                        }.orEmpty(),
                    areaFeatures=model.displayAreas()+listOfNotNull(model.areaCreationShape()?.let {shape -> AreaFeature("area-preview",state.draft.recordUuid,shape,state.original.updatedAt,0,0,role=state.area.role)}),
                    selectedAreaUuid=state.area.selected,areaPlacementMode=enabled && state.area.tool!=null,onAreaTap=model::areaTap,onSelectArea = { model.selectArea(it); showElements() },
                    areaEditingEnabled=enabled && validation.geometry!=null,areaDrag=state.area.drag,areaCreationPoints=state.area.vertices,
                    onBeginAreaDrag=model::beginAreaDrag,onPreviewAreaDrag=model::previewAreaDrag,onFinishAreaDrag=model::finishAreaDrag,onCancelAreaDrag=model::cancelAreaDrag,
                    linePlacements=model.displayLines()+listOfNotNull(state.linePreview),selectedLineUuid=state.selectedLineUuid,
                    linePlacementMode=enabled && state.lineElementUuid!=null,onLineTap=model::lineTap,onSelectLine = { model.selectLine(it); showElements() },
                    lineStartPoint=state.lineStart,lineEditingEnabled=enabled && validation.geometry!=null,lineDrag=state.lineDrag,onBeginLineDrag=model::beginLineDrag,
                    onPreviewLineDrag=model::previewLineDrag,onFinishLineDrag=model::finishLineDrag,onCancelLineDrag=model::cancelLineDrag,
                    mountingWallOrdinals=if(validation.geometry==null) emptyList() else model.displayPoints().find { it.uuid==state.selectedPointUuid }?.mounting?.anchor?.let { a ->
                        model.draftWalls().filter { it.uuid==a.firstWall || it.uuid==a.secondWall }.map { it.ordinal } }.orEmpty())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MeasurementEditContext.entries.forEach { mode ->
                    FilterChip(
                        selected = context == mode,
                        onClick = { context = mode },
                        label = { Text(mode.label) },
                        modifier = Modifier.weight(1f).testTag("measurement-edit-context-${mode.name.lowercase()}")
                    )
                }
                IconButton(
                    onClick = {
                        if (fullscreen) fullscreenPanelExpanded = !fullscreenPanelExpanded
                        else normalPanelExpanded = !normalPanelExpanded
                    },
                    modifier = Modifier.testTag("measurement-edit-panel")
                ) {
                    Icon(
                        if (panelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (panelExpanded) "Згорнути панель" else "Розгорнути панель"
                    )
                }
            }
            panelState.SaveableStateProvider("$measurementUuid:${context.name}") {
                val scroll = if (context == MeasurementEditContext.CONTOUR) contourScroll else rememberScrollState()
                var lastFeature by rememberSaveable { mutableStateOf(activeElementControls) }
                LaunchedEffect(context, activeElementControls) {
                    if (context == MeasurementEditContext.ELEMENTS && lastFeature != activeElementControls) {
                        scroll.scrollTo(0)
                        lastFeature = activeElementControls
                    }
                }
                CompositionLocalProvider(LocalMeasurementInputFocus provides inputFocus) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).verticalScroll(scroll)
                            .testTag("measurement-edit-context-panel"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (context) {
                            MeasurementEditContext.CONTOUR -> {
                                val nameFocus = remember { FocusRequester() }
                                val notesFocus = remember { FocusRequester() }
                                MeasurementWallPicker(draft, selected, ::select)
                                TextButton(onClick = { focus.clearFocus(); allFields = !allFields }) {
                                    Text(if (allFields) "Контекстні розміри" else "Усі розміри")
                                }
                                MeasurementSelectedFields(model, selected, validation, enabled, { selected = it },
                                    compact = !allFields, followingField = nameFocus)
                                Text("Точність заміру: ${draft.precision.label}")
                                OutlinedTextField(draft.name, { model.accept(MeasurementCreateIntent.Name(it), grouped = true) },
                                    enabled = enabled, label = { Text("Назва заміру *") }, singleLine = true,
                                    modifier = measurementFieldFocusModifier().fillMaxWidth().focusRequester(nameFocus)
                                        .onFocusChanged { if (it.isFocused) model.beginInput() else model.commitInput() },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { notesFocus.requestFocus() }),
                                    isError = validation.nameError != null)
                                MeasurementShapePicker(draft.shape, enabled) {
                                    focus.clearFocus()
                                    model.accept(MeasurementCreateIntent.Shape(it))
                                    selected = null
                                }
                                validation.geometry?.let {
                                    Text("Площа: ${formatMeasurementArea(it.areaSquareMeters, draft.precision)} м² · Периметр: ${formatMeasurementLength(it.perimeterMeters, draft.precision)} м")
                                }
                                geometry?.let { measurementDrawingContour(MeasurementGeometryView.Captured(it)) }?.let { contour ->
                                    Text(if (contour.measuredSides) "Виміряні сторони" else "Сторони за збереженим контуром")
                                    Text(contour.sideLabels.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedTextField(draft.notes, { model.accept(MeasurementCreateIntent.Notes(it), grouped = true) },
                                    enabled = enabled, label = { Text("Примітки") }, maxLines = 3,
                                    modifier = measurementFieldFocusModifier().fillMaxWidth().focusRequester(notesFocus)
                                        .onFocusChanged { if (it.isFocused) model.beginInput() else model.commitInput() })
                            }
                            MeasurementEditContext.ELEMENTS -> {
                                if (selectedFeature != null) TextButton(enabled = enabled, onClick = model::clearDrawingSelection) {
                                    Text("Зняти виділення")
                                }
                                val firstSection = when {
                                    state.selectedPointUuid != null -> MeasurementElementSection.POINTS
                                    state.selectedLineUuid != null || state.lineElementUuid != null -> MeasurementElementSection.LINES
                                    state.area.selected != null || state.area.tool != null -> MeasurementElementSection.AREAS
                                    else -> MeasurementElementSection.POINTS
                                }
                                val sections = listOf(firstSection) + MeasurementElementSection.entries.filter { it != firstSection }
                                val canEditGeometry = enabled && validation.geometry != null
                                for (section in sections) key(section) {
                                    when (section) {
                                        MeasurementElementSection.POINTS ->
                                            MeasurementPointControls(model, state, overlay, validation, savedGeometry, enabled)
                                        MeasurementElementSection.LINES -> LinePlacementControls(model, canEditGeometry)
                                        MeasurementElementSection.AREAS -> AreaFeatureControls(model, canEditGeometry)
                                    }
                                }
                            }
                            MeasurementEditContext.COST -> {
                                Text("Розрахунок за останнім збереженим заміром", style = MaterialTheme.typography.titleSmall)
                                if (state.dirty) {
                                    Text("Збережіть зміни, щоб переглянути розрахунок для оновленого заміру.")
                                } else {
                                    TextButton(enabled = enabled && !state.conflict && overlay is PointOverlayState.Loaded,
                                        onClick = {
                                            model.requestExit {
                                                fullscreen = false
                                                navigationGuard?.fullscreen?.value = false
                                                onBack()
                                            }
                                        }) {
                                        Text("Відкрити замір із калькулятором")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    statusDetails?.let { message ->
        AlertDialog(
            onDismissRequest = { statusDetails = null },
            title = { Text("Стан заміру") },
            text = { Text(message, Modifier.verticalScroll(rememberScrollState()).testTag("measurement-edit-status-details")) },
            confirmButton = { TextButton(onClick = { statusDetails = null }) { Text("Закрити") } }
        )
    }
    CompositionLocalProvider(LocalMeasurementInputFocus provides inputFocus) { WallAnchorEditor(model) }
    if(state.reviewPrompt) AlertDialog(onDismissRequest={},title={Text("Потрібно перевірити розміщення")},
        text={Text("Контур стелі змінено. Збережені монтажні відстані використано для перерахунку. Перевірте розташування точок і ліній.")},
        confirmButton={TextButton(enabled=!state.saving && !state.checking,onClick={ model.reviewNow(); showElements() }){Text("Перевірити")}},
        dismissButton={TextButton(enabled=!state.saving && !state.checking,onClick={scope.launch {model.reviewLater()}}){Text("Пізніше")}})
    if (state.exitRequested) AlertDialog(onDismissRequest = model::continueEditing,
        title = { Text("Незбережені зміни") }, text = { Text("Зберегти зміни заміру? Збереження поверне до перегляду цього заміру.") },
        confirmButton = { TextButton(onClick = { model.continueEditing(); save() }, enabled = validation.canSave && model.canSaveMountingDraft() && !state.conflict && overlay is PointOverlayState.Loaded) { Text("Зберегти") } },
        dismissButton = { Row {
            TextButton(onClick = model::discardAndExit) { Text("Відхилити зміни") }
            TextButton(onClick = model::continueEditing) { Text("Продовжити") }
        } })
    if (reloadConfirm) AlertDialog(onDismissRequest = { reloadConfirm = false }, title = { Text("Перезавантажити замір?") },
        text = { Text("Незбережені зміни буде відхилено. Відкриються поточні дані з бази.") },
        confirmButton = { TextButton(onClick = { reloadConfirm = false; scope.launch { model.reload() } }) { Text("Перезавантажити") } },
        dismissButton = { TextButton(onClick = { reloadConfirm = false }) { Text("Продовжити редагування") } })
}

@Composable
private fun measurementEditorPadding(contentPadding: PaddingValues, systemTopInset: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        top = maxOf(contentPadding.calculateTopPadding(), systemTopInset),
        end = contentPadding.calculateEndPadding(direction),
        bottom = contentPadding.calculateBottomPadding()
    )
}

@Composable
private fun MeasurementEditorFab(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.4f
    val background = when {
        !enabled -> scheme.surfaceVariant
        dark -> scheme.surfaceContainerHigh
        else -> Color.White
    }
    CeilingsFab(
        onClick = onClick, actionLabel = label, profile = CeilingsFabProfile.COMPACT,
        actionModifier = modifier, enabled = enabled,
        containerColor = background, outlineAlpha = ceilingsFloatingOutlineAlpha(dark)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp),
            tint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasurementIconAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun MeasurementPointControls(
    model: MeasurementEditModel,
    state: MeasurementEditState,
    overlay: PointOverlayState,
    validation: MeasurementCreateValidation,
    savedGeometry: FrozenPolygonGeometry?,
    enabled: Boolean
) {
    val scope = rememberCoroutineScope()
    when (val pointState = overlay) {
        PointOverlayState.Loading -> Text("Завантаження збережених позначок…")
        PointOverlayState.Missing -> Text("Замір або об'єкт недоступний; позначки не завантажено.")
        PointOverlayState.Unavailable -> Text("Позначки недоступні: захищена база заблокована або помилка читання.")
        is PointOverlayState.Loaded -> {
            val points = model.displayPoints()
            val marker = points.find { it.uuid == state.selectedPointUuid }
            val elements = state.original.pointElements.associateBy { it.uuid }
            val reviewCount = points.count { it.mounting.needsReview }
            if (marker != null) {
                Text(elements[marker.elementUuid]?.label ?: "Вибрана позначка", style = MaterialTheme.typography.titleMedium)
                when {
                    marker.mounting.unresolved -> Text("Потребує уточнення: опори втрачено або позиція недопустима.")
                    marker.mounting.needsReview -> Text("Потрібно перевірити розміщення.")
                }
                Text(if (marker.precision == PointPrecision.EXACT) "Точність позначки: точна" else "Точність позначки: приблизна")
                val anchor = marker.mounting.anchor
                if (anchor == null) {
                    Text("Виміряних прив'язок немає. Перетягування задає лише приблизне положення.")
                } else {
                    val walls = model.draftWalls()
                    val labels = measurementSideLabels(walls.size)
                    fun name(uuid: String?) = walls.find { it.uuid == uuid }?.let { labels[it.ordinal] } ?: "втрачена стіна"
                    val first = formatPointMetres(anchor.firstMm)
                    val second = formatPointMetres(anchor.secondMm)
                    Text(if (anchor.kind == WallAnchor.Kind.TWO_WALLS)
                        "${name(anchor.firstWall)}: $first м · ${name(anchor.secondWall)}: $second м"
                    else "${name(anchor.firstWall)}: уздовж $first м · усередину $second м")
                }
                TextButton(enabled = enabled && validation.geometry != null, onClick = model::openMounting) {
                    Text("Задати розміри від стін")
                }
                if (state.reviewing && marker.mounting.needsReview) {
                    TextButton(enabled = enabled && !marker.mounting.unresolved && !state.dirty,
                        onClick = { scope.launch { model.confirmSelected() } }) { Text("Розміщення перевірено") }
                }
            }
            Text("Точкові позначки", style = MaterialTheme.typography.titleMedium)
            val pointsByElement = points.groupBy { it.elementUuid }
            if (elements.isEmpty() && pointsByElement.isEmpty()) Text("Точкових елементів немає.")
            for (uuid in elements.keys + pointsByElement.keys) {
                val element = elements[uuid]
                val placed = pointsByElement[uuid].orEmpty()
                val unresolved = placed.count { it.mounting.unresolved }
                val declared = element?.let { formatElementQuantity(it.quantity, it.unit) }
                    ?: state.original.placements.quantities[uuid]?.let { "$it шт." }
                element?.let { Text(it.label, style = MaterialTheme.typography.titleSmall) }
                Text("Розміщено: ${placed.size - unresolved} · Заявлено: ${declared ?: "невідомо"} · Потребує уточнення: $unresolved")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    placed.forEachIndexed { index, point ->
                        FilterChip(selected = state.selectedPointUuid == point.uuid, enabled = enabled,
                            onClick = { model.selectPoint(point.uuid) }, label = { Text("Позначка ${index + 1}") })
                    }
                }
            }
            if (reviewCount > 0) {
                TextButton(enabled = enabled, onClick = model::reviewNow) { Text("Перевірити ($reviewCount)") }
                if (state.reviewing) Text("Виберіть позначку, огляньте опори й відстані. Нерозв'язану прив'язку спершу виправте та збережіть.")
            }
            if (points.isNotEmpty() && validation.geometry != savedGeometry) {
                Text("Попередній перерахунок. Після збереження зміненого контуру перевірте розміщення.")
            }
        }
    }
}

@Composable
internal fun MeasurementWallPicker(draft: MeasurementCreateDraft, selected: Int?, onSelect: (Int) -> Unit) {
    val count = when (val outline = draft.outline) {
        is MeasurementOutlineDraft.Orthogonal -> outline.sides.size
        is MeasurementOutlineDraft.Captured -> outline.geometry.vertices.size
        is MeasurementOutlineDraft.Template -> if (outline.shape == "l_shaped") 6 else 4
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        measurementSideLabels(count).forEachIndexed { i, name ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(name) })
        }
    }
}

@Composable
private fun MeasurementSelectedFields(model: MeasurementEditModel, selected: Int?, validation: MeasurementCreateValidation,
    enabled: Boolean, onSelect: (Int) -> Unit, compact: Boolean = false, followingField: FocusRequester) {
    val currentState by model.state.collectAsState()
    val draft = currentState.draft
    val focus = LocalFocusManager.current
    val inputFocus: (Int) -> (Boolean) -> Unit = { side -> { focused ->
        if (focused) { model.commitInput(); model.beginInput(); onSelect(side) } else model.commitInput()
    } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compact && selected == null) Text("Виберіть стіну на кресленні або в списку для зміни розміру.")
        if (selected != null) validation.geometry?.let { geometry ->
            val contour = measurementDrawingContour(MeasurementGeometryView.Captured(geometry))
            contour?.namedSideLabels?.getOrNull(selected)?.let { Text("Вибрано: $it", style = MaterialTheme.typography.titleSmall) }
        }
        when (val outline = draft.outline) {
            is MeasurementOutlineDraft.Captured -> {
                Text("Цей контур збережено вершинами, без виміряних сторін. Монтажні розміри й позначки можна редагувати; сторони не вигадуються.")
                TextButton(enabled=enabled,onClick={model.accept(MeasurementCreateIntent.ReplaceCapturedWithSides)}) { Text("Задати виміряні сторони") }
            }
            is MeasurementOutlineDraft.Template -> {
                if (selected != null && draft.shape == "l_shaped" && measurementWallParameter(draft.shape, selected) == null)
                    Text(measurementWallRelation(selected), style = MaterialTheme.typography.bodySmall)
                val keys = CeilingShapeTemplates.template(outline.shape)!!.parameterKeys
                    .filter { !compact || selected != null && it in measurementWallParameters(draft.shape, selected) }
                val requesters = remember(keys) { List(keys.size) { FocusRequester() } }
                keys.forEachIndexed { index, key ->
                    val side = measurementParameterWall(draft.shape, key)
                    MeasurementLengthField(outline.fields.getValue(key), measurementTemplateFieldLabel(draft.shape, key),
                        validation.fieldErrors[key], enabled,
                        modifier = Modifier.focusRequester(requesters[index]),
                        onNext = { (requesters.getOrNull(index + 1) ?: followingField).requestFocus() },
                        selected = selected != null && key in measurementWallParameters(draft.shape, selected),
                        onFocus = inputFocus(side), onChange = { model.accept(MeasurementCreateIntent.Dimension(key, it), grouped = true) })
                }
            }
            is MeasurementOutlineDraft.Orthogonal -> {
                val labels = measurementSideLabels(outline.sides.size)
                val visibleSides = outline.sides.indices.filter { !compact || selected == it }
                val ids = visibleSides.map { outline.sides[it].inputId }
                val requesters = remember(ids) { List(ids.size) { FocusRequester() } }
                visibleSides.forEachIndexed { index, i ->
                    val side = outline.sides[i]
                    key(side.inputId) {
                        MeasurementLengthField(side.length, "Сторона ${labels[i]}, м", validation.fieldErrors[side.inputId], enabled,
                            modifier = Modifier.focusRequester(requesters[index]),
                            onNext = { (requesters.getOrNull(index + 1) ?: followingField).requestFocus() },
                            selected = selected == i,
                            onFocus = inputFocus(i), onChange = { model.accept(MeasurementCreateIntent.SideLength(i, it), grouped = true) })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OrthogonalTurn.entries.forEach { turn ->
                                FilterChip(side.turn == turn, onClick = { focus.clearFocus(); model.accept(MeasurementCreateIntent.SideTurn(i, turn)) }, enabled = enabled,
                                    label = { Text(if (turn == OrthogonalTurn.LEFT) "↶ 90°" else "↷ 90°") })
                            }
                            TextButton(onClick = { focus.clearFocus(); model.accept(MeasurementCreateIntent.RemoveSide(i)); onSelect(0) },
                                enabled = enabled && outline.sides.size > 4) { Text("Видалити ${labels[i]}") }
                        }
                    }
                }
                if (!compact) TextButton(onClick = { focus.clearFocus(); model.accept(MeasurementCreateIntent.AddSide) },
                    enabled = enabled && outline.sides.size < CeilingPolygonCalculator.MAX_VERTICES) { Text("Додати сторону") }
            }
        }
    }
}
