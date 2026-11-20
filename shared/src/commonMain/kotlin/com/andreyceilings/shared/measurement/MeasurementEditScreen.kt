package com.andreyceilings.shared.measurement

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Category
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.ui.CeilingsWorkspaceScaffold
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.ui.ceilingsFloatingOutlineAlpha
import kotlinx.coroutines.*

private enum class MeasurementSheet { CLOSED, COMPACT, EXPANDED }

private enum class MeasurementElementSection(val title: String) {
    POINTS("Точкові позначки"), LINES("Лінії"), AREAS("Площинні об’єкти")
}

private const val SheetMotionMillis = 240
private val WorkspacePanelShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

private enum class MeasurementEditContext(val label: String) {
    CONTOUR("Контур"),
    ELEMENTS("Елементи"),
    COST("Вартість")
}

@Composable
fun MeasurementEditScreen(
    projectId: Long, measurementId: Long, store: MeasurementEditStore?,
    contentPadding: PaddingValues, systemTopInset: Dp, onBack: () -> Unit,
    navigationGuard: MeasurementCreateNavigationGuard?
) {
    val editorPadding = measurementEditorPadding(contentPadding, systemTopInset)
    // Do not consume restored form state against Android's temporary null session
    // during Activity startup. The unlocked-session store can reattach a pending write.
    if (store == null) {
        Column(Modifier.fillMaxSize().padding(editorPadding)) {
            Text("Захищена база недоступна або завантажується.", color = MaterialTheme.colorScheme.onSurface)
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
            Text(error ?: "Завантаження заміру…", color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onBack) { Text("До заміру") }
        } else MeasurementEditScreen(editor, contentPadding, systemTopInset, onBack, navigationGuard)
    }
}

@Composable
fun MeasurementEditScreen(
    model: MeasurementEditModel, contentPadding: PaddingValues = PaddingValues(16.dp),
    systemTopInset: Dp = 0.dp, onBack: () -> Unit,
    navigationGuard: MeasurementCreateNavigationGuard? = null,
    imeInsets: WindowInsets? = null
) {
    val state by model.state.collectAsState()
    val validation = remember(state.draft) { validateMeasurementCreate(state.draft) }
    val pointFlow = remember(model, state.original.draft.recordUuid) { model.observePoints() }
    val overlay = key(model, state.original.draft.recordUuid) { pointFlow.collectAsState(PointOverlayState.Loading).value }
    LaunchedEffect(overlay,state.original) {
        val incoming=(overlay as? PointOverlayState.Loaded)?.points
        if(incoming!=null && !state.saving && !state.saved && !state.checking && !state.uncertain && (incoming.map {it.uuid}.toSet()!=state.original.placements.points.map {it.uuid}.toSet() ||
            incoming.any { p -> state.original.placements.points.any { it.uuid==p.uuid && p.updatedAt>it.updatedAt } })) model.markPointsChanged()
    }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val measurementUuid = state.original.draft.recordUuid
    var fullscreen by rememberSaveable(measurementUuid) { mutableStateOf(navigationGuard?.fullscreen?.value == true) }
    val draft = state.draft
    val sideIds = (draft.outline as? MeasurementOutlineDraft.Orthogonal)?.sides?.map { it.inputId }
    var selected by rememberSaveable(measurementUuid, draft.shape, sideIds) {
        mutableStateOf<Int?>(null)
    }
    var normalContext by rememberSaveable(measurementUuid) { mutableStateOf(
        if (state.lineElementUuid != null || state.area.tool != null) MeasurementEditContext.ELEMENTS
        else MeasurementEditContext.CONTOUR
    ) }
    var fullscreenContext by rememberSaveable(measurementUuid) { mutableStateOf(MeasurementEditContext.CONTOUR) }
    var lastDrawingContext by rememberSaveable(measurementUuid) { mutableStateOf(MeasurementEditContext.CONTOUR) }
    var sheet by rememberSaveable(measurementUuid) { mutableStateOf(MeasurementSheet.CLOSED) }
    var lastOpenSheet by rememberSaveable(measurementUuid) { mutableStateOf(MeasurementSheet.COMPACT) }
    SideEffect { if (sheet != MeasurementSheet.CLOSED) lastOpenSheet = sheet }
    val context = if (fullscreen) fullscreenContext else normalContext
    var statusDetails by rememberSaveable(measurementUuid) { mutableStateOf(false) }
    val panelState = rememberSaveableStateHolder()
    val normalContourScroll = rememberScrollState()
    val fullscreenContourScroll = rememberScrollState()
    val contourScroll = if (fullscreen) fullscreenContourScroll else normalContourScroll
    var reloadConfirm by remember { mutableStateOf(false) }
    val currentFocus by rememberUpdatedState(focus)
    fun closeSheet() {
        currentFocus.clearFocus()
        sheet = MeasurementSheet.CLOSED
    }
    fun exitFullscreen() {
        closeSheet()
        model.cancelPointDrag(); model.cancelLineDrag(); model.cancelAreaDrag()
        fullscreen = false
        navigationGuard?.fullscreen?.value = false
    }
    val surfaceBack = remember(model, measurementUuid, navigationGuard) {
        {
            when {
                !fullscreen -> false
                sheet != MeasurementSheet.CLOSED -> { closeSheet(); true }
                else -> { exitFullscreen(); true }
            }
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
    LaunchedEffect(model) { model.reconcile(keepEditing = true) }
    LaunchedEffect(model, state.saved) { if (state.saved) model.resumeDetailsAfterSave() }
    LaunchedEffect(model, state.exitReady) { model.deliverPendingExit() }
    var savedFeedback by remember(model) { mutableStateOf(false) }
    LaunchedEffect(state.original.updatedAt, state.workspaceSaveConfirmed) {
        savedFeedback = state.workspaceSaveConfirmed
        if (savedFeedback) {
            delay(2200)
            savedFeedback = false
        }
    }
    val canLeaveSurface = !state.saving && !state.checking && !state.saved && !state.exitReady
    val enabled = canLeaveSurface && !state.uncertain
    val saveConfirmed = savedFeedback && !state.dirty && !state.saving
    val canSave = canLeaveSurface && validation.canSave && model.canSaveMountingDraft() &&
        !state.conflict && overlay is PointOverlayState.Loaded
    fun save(exit: Boolean = false) {
        focus.clearFocus(); model.commitInput()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (exit) model.saveAndExit() else model.saveInWorkspace()
        }
    }
    fun openContext(mode: MeasurementEditContext, extent: MeasurementSheet = MeasurementSheet.COMPACT) {
        if (fullscreen) {
            if (fullscreenContext != mode) focus.clearFocus()
            fullscreenContext = mode
            if (sheet != MeasurementSheet.EXPANDED || extent == MeasurementSheet.EXPANDED) sheet = extent
        } else {
            normalContext = mode
            if (mode != MeasurementEditContext.COST) lastDrawingContext = mode
        }
    }
    fun select(side: Int) {
        focus.clearFocus()
        model.commitInput()
        selected = side
        openContext(MeasurementEditContext.CONTOUR)
        scope.launch { contourScroll.scrollTo(0) }
    }
    fun showElements() = openContext(MeasurementEditContext.ELEMENTS)

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
        state.checking -> "Перевірка стану заміру. Дочекайтеся результату."
        state.error != null -> state.error
        state.uncertain -> "Результат збереження невідомий."
        state.conflict -> "Збережений замір змінився. Перезавантажте запис."
        geometryWarning != null -> geometryWarning
        validation.nameError != null -> validation.nameError
        overlay is PointOverlayState.Missing -> "Замір або об'єкт недоступний."
        overlay is PointOverlayState.Unavailable -> "Позначки недоступні: захищена база заблокована або помилка читання."
        overlay is PointOverlayState.Loading -> "Завантаження збережених позначок…"
        else -> null
    }
    val statusIsProblem = state.error != null || geometryWarning != null || validation.nameError != null || state.uncertain || state.conflict ||
        overlay is PointOverlayState.Missing || overlay is PointOverlayState.Unavailable
    val editorPadding = measurementEditorPadding(contentPadding, systemTopInset)
    val keyboard = imeInsets ?: WindowInsets.ime
    val keyboardVisible = keyboard.getBottom(LocalDensity.current) > 0
    val inputFocus = remember(imeInsets) { MeasurementInputFocus(imeInsets) }
    fun BoxWithConstraintsScope.bodyHeight(headerHeight: Dp) = (maxHeight - headerHeight).coerceAtLeast(0.dp)
    fun BoxWithConstraintsScope.inlineKeyboardPanel() = keyboardVisible && maxWidth >= 480.dp && maxHeight < 140.dp

    fun BoxWithConstraintsScope.normalPanelHeight(headerHeight: Dp): Dp {
        val bodyHeight = bodyHeight(headerHeight)
        return when {
            inlineKeyboardPanel() -> maxHeight
            keyboardVisible -> maxOf(bodyHeight * 0.50f, minOf(192.dp, bodyHeight * 0.75f), minOf(88.dp, bodyHeight))
            else -> (bodyHeight * 0.50f).coerceAtMost((bodyHeight - 64.dp).coerceAtLeast(0.dp))
        }
    }

    CeilingsWorkspaceScaffold(
        safePadding = editorPadding,
        keyboard = keyboard,
        fullscreen = fullscreen,
        header = { _ ->
            val inlineKeyboardPanel = inlineKeyboardPanel()
            Column(Modifier.fillMaxWidth()) {
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
                    if (inlineKeyboardPanel) Spacer(Modifier.weight(1f)) else Column(Modifier.weight(1f)) {
                        Text(draft.name.ifBlank { "Стеля" }, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                    if (!inlineKeyboardPanel) {
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
                            if (fullscreen) exitFullscreen() else {
                                focus.clearFocus()
                                fullscreenContext = normalContext.takeUnless { it == MeasurementEditContext.COST } ?: lastDrawingContext
                                sheet = MeasurementSheet.CLOSED
                                fullscreen = true
                            }
                        }
                    }
                    MeasurementEditorFab(
                        label = "Зберегти замір",
                        icon = if (saveConfirmed) Icons.Default.Check else Icons.Default.Save,
                        enabled = canSave && enabled && state.dirty,
                        confirmed = saveConfirmed,
                        modifier = Modifier.testTag("measurement-edit-save").semantics {
                            stateDescription = when {
                                state.saving -> "Збереження…"
                                saveConfirmed -> "Збережено"
                                state.dirty -> "Незбережено"
                                else -> "Змін немає"
                            }
                            liveRegion = LiveRegionMode.Polite
                        },
                        onClick = { save() }
                    )
                }
            }
        },
        overlay = { headerHeight ->
            val inlineKeyboardPanel = inlineKeyboardPanel()
            if (status != null && (!inlineKeyboardPanel || statusIsProblem)) {
                val position = if (inlineKeyboardPanel) Modifier.align(Alignment.TopEnd).padding(end = 56.dp)
                else Modifier.align(Alignment.TopStart).padding(top = headerHeight)
                Box(position) {
                    MeasurementIconAction("Показати повне повідомлення", Icons.Default.Info,
                        modifier = Modifier.testTag("measurement-edit-status").semantics {
                            stateDescription = status
                            liveRegion = LiveRegionMode.Polite
                        },
                        tint = if (statusIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    ) { statusDetails = true }
                }
            }
            val normalPanelHeight = normalPanelHeight(headerHeight)
            val bodyHeight = bodyHeight(headerHeight)
            val sheetContentHeight = when {
                inlineKeyboardPanel -> maxHeight
                keyboardVisible -> bodyHeight
                else -> (bodyHeight - 104.dp).coerceAtLeast(0.dp)
            }
            val visibleSheet = if (sheet == MeasurementSheet.CLOSED) lastOpenSheet else sheet
            val sheetMaxHeight = if (visibleSheet == MeasurementSheet.EXPANDED || keyboardVisible)
                sheetContentHeight else minOf(sheetContentHeight, maxHeight * 0.32f)
            val contextPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                panelState.SaveableStateProvider(
                    "$measurementUuid:$fullscreen:${context.name}"
                ) {
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
                            panelModifier.fillMaxWidth().testTag("measurement-edit-context-panel").verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (context) {
                                MeasurementEditContext.CONTOUR -> {
                                    var contourDetails by rememberSaveable { mutableStateOf(false) }
                                    val nameFocus = remember { FocusRequester() }
                                    val notesFocus = remember { FocusRequester() }
                                    val detailsVisible = contourDetails
                                    MeasurementSelectedFields(model, selected, validation, enabled,
                                        followingField = nameFocus.takeIf { detailsVisible })
                                    MeasurementDisclosure("Деталі заміру", detailsVisible) {
                                        if (detailsVisible) focus.clearFocus()
                                        contourDetails = !contourDetails
                                    }
                                    if (detailsVisible) {
                                        OutlinedTextField(draft.name, { model.accept(MeasurementCreateIntent.Name(it), grouped = true) },
                                            enabled = enabled, label = { Text("Назва заміру *") }, singleLine = true,
                                            modifier = measurementFieldFocusModifier().fillMaxWidth().focusRequester(nameFocus)
                                                .onFocusChanged { if (it.isFocused) model.beginInput() else model.commitInput() },
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                            keyboardActions = KeyboardActions(onNext = { notesFocus.requestFocus() }),
                                            isError = validation.nameError != null)
                                        OutlinedTextField(draft.notes, { model.accept(MeasurementCreateIntent.Notes(it), grouped = true) },
                                            enabled = enabled, label = { Text("Примітки") }, maxLines = 3,
                                            modifier = measurementFieldFocusModifier().fillMaxWidth().focusRequester(notesFocus)
                                                .onFocusChanged { if (it.isFocused) model.beginInput() else model.commitInput() })
                                        MeasurementShapePicker(draft.shape, enabled) {
                                            focus.clearFocus()
                                            model.accept(MeasurementCreateIntent.Shape(it))
                                            selected = null
                                        }
                                        Text("Точність · ${draft.precision.label}", style = MaterialTheme.typography.bodySmall)
                                        validation.geometry?.let {
                                            Text("Площа ${formatMeasurementArea(it.areaSquareMeters, draft.precision)} м² · Периметр ${formatMeasurementLength(it.perimeterMeters, draft.precision)} м",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                MeasurementEditContext.ELEMENTS -> {
                                    val activeSection = when {
                                        state.selectedPointUuid != null -> MeasurementElementSection.POINTS
                                        state.selectedLineUuid != null || state.lineElementUuid != null -> MeasurementElementSection.LINES
                                        state.area.selected != null || state.area.tool != null -> MeasurementElementSection.AREAS
                                        else -> null
                                    }
                                    var openedSection by rememberSaveable { mutableStateOf<MeasurementElementSection?>(null) }
                                    val sections = listOfNotNull(activeSection) + MeasurementElementSection.entries.filter { it != activeSection }
                                    val canEditGeometry = enabled && validation.geometry != null
                                    val points = model.displayPoints()
                                    val lines = model.displayLines()
                                    val areas = model.displayAreas()
                                    for (section in sections) key(section) {
                                        val expanded = section == activeSection || section == openedSection
                                        if (section != activeSection) {
                                            val (count, unresolved, reviewCount) = when (section) {
                                                MeasurementElementSection.POINTS -> Triple(points.size, points.count { it.mounting.unresolved }, points.count { it.mounting.needsReview })
                                                MeasurementElementSection.LINES -> Triple(lines.size, lines.count { it.unresolved }, lines.count { it.needsReview })
                                                MeasurementElementSection.AREAS -> Triple(areas.size, areas.count { it.unresolved }, areas.count { it.needsReview })
                                            }
                                            MeasurementDisclosure("${section.title} · $count", expanded,
                                                modifier = Modifier.fillMaxWidth().testTag("measurement-edit-section-${section.name.lowercase()}")) {
                                                openedSection = if (expanded) null else section
                                            }
                                            if (!expanded && (unresolved > 0 || reviewCount > 0)) Text(
                                                if (unresolved > 0) "Невирішені опори · $unresolved" else "Перевірити · $reviewCount",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                            if (!expanded && section == MeasurementElementSection.LINES) model.lineCapacityError()?.let {
                                                Text(it, color = MaterialTheme.colorScheme.error)
                                            }
                                            if (!expanded && section == MeasurementElementSection.AREAS) model.areaCapacityError()?.let {
                                                Text(it, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                        if (expanded) {
                                            when (section) {
                                                MeasurementElementSection.POINTS ->
                                                    MeasurementPointControls(model, state, overlay, validation, savedGeometry, enabled)
                                                MeasurementElementSection.LINES -> LinePlacementControls(model, canEditGeometry, inWorkspace = true)
                                                MeasurementElementSection.AREAS -> AreaFeatureControls(model, canEditGeometry, inWorkspace = true)
                                            }
                                            if (section == activeSection && selectedFeature != null) {
                                                TextButton(enabled = enabled, onClick = model::clearDrawingSelection) { Text("Зняти виділення") }
                                            }
                                        }
                                    }
                                    if (points.isNotEmpty() && validation.geometry != savedGeometry && activeSection != MeasurementElementSection.POINTS &&
                                        openedSection != MeasurementElementSection.POINTS) {
                                        Text("Попередній перерахунок. Після збереження перевірте розміщення.",
                                            style = MaterialTheme.typography.bodySmall)
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
                                            Text("Переглянути збережений розрахунок у картці заміру")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(
                if (fullscreen) Modifier else Modifier.height(normalPanelHeight).testTag("measurement-edit-normal-panel")
            ).then(if (inlineKeyboardPanel) Modifier.padding(start = 60.dp,
                end = if (statusIsProblem) 112.dp else 60.dp) else Modifier)) {
                if (!fullscreen && !keyboardVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val tabLines = if (LocalDensity.current.fontScale >= 1.35f) 2 else 1
                    MeasurementEditContext.entries.forEach { mode ->
                        FilterChip(
                            selected = context == mode,
                            onClick = { if (context != mode) { focus.clearFocus(); openContext(mode) } },
                            label = { Text(mode.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                maxLines = tabLines, softWrap = tabLines > 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f).testTag("measurement-edit-context-${mode.name.lowercase()}")
                        )
                    }
                }
                AnimatedVisibility(visible = fullscreen && !keyboardVisible,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }) {
                    if (fullscreen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        for (mode in listOf(MeasurementEditContext.CONTOUR, MeasurementEditContext.ELEMENTS)) {
                            val expanded = sheet != MeasurementSheet.CLOSED && context == mode
                            MeasurementEditorFab(
                                label = mode.label,
                                icon = if (mode == MeasurementEditContext.CONTOUR) Icons.Default.CropSquare else Icons.Default.Category,
                                enabled = canLeaveSurface,
                                modifier = Modifier.testTag("measurement-edit-context-${mode.name.lowercase()}")
                                    .measurementSheetDrag(onUp = { openContext(mode, MeasurementSheet.EXPANDED) }, onDown = ::closeSheet)
                                    .semantics {
                                        stateDescription = if (expanded) "Панель відкрита" else "Панель закрита"
                                        customActions = listOf(CustomAccessibilityAction("Розгорнути панель") {
                                            openContext(mode, MeasurementSheet.EXPANDED); true
                                        })
                                    }
                            ) { if (expanded) closeSheet() else openContext(mode) }
                        }
                    }
                }
                if (!fullscreen) {
                    Surface(Modifier.fillMaxWidth().weight(1f), shape = WorkspacePanelShape,
                        color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        contextPanel(Modifier.fillMaxSize().padding(horizontal = 10.dp))
                    }
                } else AnimatedVisibility(
                    visible = sheet != MeasurementSheet.CLOSED,
                    enter = expandVertically(tween(SheetMotionMillis), expandFrom = Alignment.Bottom),
                    exit = shrinkVertically(tween(SheetMotionMillis), shrinkTowards = Alignment.Bottom)
                ) {
                    Column(Modifier.fillMaxWidth().animateContentSize(
                        animationSpec = if (keyboardVisible) snap() else tween(SheetMotionMillis),
                        alignment = Alignment.BottomCenter
                    )) {
                        if (!keyboardVisible) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                IconButton(
                                    onClick = ::closeSheet,
                                    modifier = Modifier.size(48.dp).testTag("measurement-edit-sheet-handle")
                                        .measurementSheetDrag(
                                            onUp = { sheet = MeasurementSheet.EXPANDED },
                                            onDown = ::closeSheet
                                        )
                                        .semantics {
                                            stateDescription = if (visibleSheet == MeasurementSheet.EXPANDED) "Повна панель" else "Компактна панель"
                                            customActions = listOf(CustomAccessibilityAction("Розгорнути панель") {
                                                sheet = MeasurementSheet.EXPANDED; true
                                            })
                                        },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                                ) {
                                    Box(Modifier.size(40.dp, 24.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Закрити панель",
                                            modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                        Surface(Modifier.fillMaxWidth(), shape = WorkspacePanelShape,
                            color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                            contextPanel(Modifier.heightIn(max = sheetMaxHeight).padding(horizontal = 12.dp))
                        }
                    }
                }
            }
        }
    ) { headerHeight ->
        val normalPanelHeight = normalPanelHeight(headerHeight)
        val inlineKeyboardPanel = inlineKeyboardPanel()
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen && !inlineKeyboardPanel) Spacer(Modifier.height(headerHeight))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (geometry == null) Text("Креслення недоступне: ${validation.geometryError.orEmpty()}")
                else MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
                    Modifier.fillMaxSize(), interactive = true, fillAvailableHeight = true, showSideLegend = false,
                    retainCameraOnResize = true, verticalViewControls = !keyboardVisible, showWorkspaceGrid = true,
                    fitTop = if (fullscreen) headerHeight else 0.dp,
                    fitBottom = if (fullscreen) 56.dp else 0.dp,
                    cameraFitReady = overlay !is PointOverlayState.Loading && !state.checking,
                    selectedSide = selected, onSelectSide = ::select, fullscreen = fullscreen,
                    onEmptyTap = {
                        if (enabled) {
                            selected = null
                            model.clearDrawingSelection()
                        }
                    },
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
            if (!fullscreen) Spacer(Modifier.height(normalPanelHeight))
        }
    }
    if (statusDetails && status != null) {
        AlertDialog(
            onDismissRequest = { statusDetails = false },
            title = { Text("Стан заміру") },
            text = { Text(status, Modifier.verticalScroll(rememberScrollState()).testTag("measurement-edit-status-details")) },
            confirmButton = { TextButton(onClick = { statusDetails = false }) { Text("Закрити") } },
            dismissButton = {
                FlowRow {
                    if (state.uncertain) TextButton(onClick = { statusDetails = false; save(exit = state.exitRequested) }) {
                        Text("Повторити ту саму спробу")
                    }
                    if (state.uncertain || state.conflict) TextButton(onClick = { statusDetails = false; reloadConfirm = true }) {
                        Text("Перезавантажити запис")
                    }
                }
            }
        )
    }
    CompositionLocalProvider(LocalMeasurementInputFocus provides inputFocus) { WallAnchorEditor(model) }
    if(state.reviewPrompt) AlertDialog(onDismissRequest={},title={Text("Потрібно перевірити розміщення")},
        text={Text("Контур стелі змінено. Збережені монтажні відстані використано для перерахунку. Перевірте розташування точок і ліній.")},
        confirmButton={TextButton(enabled=!state.saving && !state.checking,onClick={ model.reviewNow(); showElements() }){Text("Перевірити")}},
        dismissButton={TextButton(enabled=!state.saving && !state.checking,onClick={scope.launch {model.reviewLater(inWorkspace = true)}}){Text("Пізніше")}})
    if (state.exitRequested) AlertDialog(onDismissRequest = model::continueEditing,
        title = { Text("Незбережені зміни") }, text = {
            Text(state.error ?: "Зберегти зміни перед поверненням до картки заміру?")
        },
        confirmButton = { TextButton(onClick = { save(exit = true) }, enabled = canSave) {
            Text(if (state.uncertain) "Повторити ту саму спробу" else "Зберегти й вийти")
        } },
        dismissButton = { Row {
            TextButton(onClick = model::discardAndExit) { Text("Відхилити зміни") }
            TextButton(onClick = model::continueEditing) { Text("Продовжити редагування") }
        } })
    if (reloadConfirm) AlertDialog(onDismissRequest = { reloadConfirm = false }, title = { Text("Перезавантажити замір?") },
        text = { Text("Незбережені зміни буде відхилено. Відкриються поточні дані з бази.") },
        confirmButton = { TextButton(onClick = { reloadConfirm = false; scope.launch { model.reload() } }) { Text("Перезавантажити") } },
        dismissButton = { TextButton(onClick = { reloadConfirm = false }) { Text("Продовжити редагування") } })
}

@Composable
private fun Modifier.measurementSheetDrag(onUp: () -> Unit, onDown: () -> Unit): Modifier {
    val expand by rememberUpdatedState(onUp)
    val close by rememberUpdatedState(onDown)
    return pointerInput(Unit) {
        var travel = 0f
        detectVerticalDragGestures(
            onDragStart = { travel = 0f },
            onDragEnd = {
                if (travel <= -24.dp.toPx()) expand()
                if (travel >= 24.dp.toPx()) close()
            }
        ) { change, delta ->
            change.consume()
            travel += delta
        }
    }
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
private fun MeasurementEditorFab(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier,
    confirmed: Boolean = false, onClick: () -> Unit) {
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
        val tint = when {
            confirmed -> scheme.primary
            enabled -> scheme.onSurface
            else -> scheme.onSurface.copy(alpha = 0.38f)
        }
        Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp), tint = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasurementIconAction(label: String, icon: ImageVector, enabled: Boolean = true,
    zoomDisc: Boolean = false, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        val scheme = MaterialTheme.colorScheme
        IconButton(onClick = onClick, enabled = enabled,
            modifier = modifier.size(48.dp).semantics { contentDescription = label }
                .then(if (zoomDisc) Modifier.testTag("measurement-edit-zoom-target") else Modifier),
            colors = IconButtonDefaults.iconButtonColors(contentColor = if (tint == Color.Unspecified) scheme.onSurface else tint,
                disabledContentColor = scheme.onSurface.copy(alpha = 0.38f))) {
            if (zoomDisc) Box(
                Modifier.size(36.dp).background(scheme.surface.copy(alpha = 0.90f), CircleShape)
                    .testTag("measurement-edit-zoom-disc"),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
            else Icon(icon, contentDescription = null)
        }
    }
}

@Composable
private fun MeasurementDisclosure(
    label: String,
    expanded: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TextButton(onClick, modifier.heightIn(min = 48.dp).semantics {
        stateDescription = if (expanded) "Розгорнуто" else "Згорнуто"
    }, enabled = enabled) {
        Text(label)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null, modifier = Modifier.size(18.dp))
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
                    marker.mounting.unresolved -> Text("Потребує уточнення: опори втрачено або позиція недопустима.",
                        color = MaterialTheme.colorScheme.error)
                    marker.mounting.needsReview -> Text("Потрібно перевірити розміщення.", color = MaterialTheme.colorScheme.error)
                }
                Text(if (marker.precision == PointPrecision.EXACT) "Точна позначка" else "Приблизна позначка",
                    style = MaterialTheme.typography.bodySmall)
                val anchor = marker.mounting.anchor
                if (anchor == null) {
                    Text("Виміряних прив'язок немає", style = MaterialTheme.typography.bodySmall)
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
            if (marker != null) Text("Точкові позначки", style = MaterialTheme.typography.titleSmall)
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
private fun MeasurementSelectedFields(
    model: MeasurementEditModel,
    selected: Int?,
    validation: MeasurementCreateValidation,
    enabled: Boolean,
    followingField: FocusRequester?
) {
    val state by model.state.collectAsState()
    val draft = state.draft
    var showRelation by rememberSaveable { mutableStateOf(false) }
    var topology by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val inputFocus: (Boolean) -> Unit = { focused ->
        if (focused) { model.commitInput(); model.beginInput() } else model.commitInput()
    }
    selected?.let { side ->
        val label = validation.geometry?.let { measurementDrawingContour(MeasurementGeometryView.Captured(it)) }
            ?.namedSideLabels?.getOrNull(side)
        val dependent = draft.shape == "l_shaped" && measurementWallParameter(draft.shape, side) == null
        if (label != null || dependent) Row(verticalAlignment = Alignment.CenterVertically) {
            label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (dependent) MeasurementIconAction("Як обчислюється сторона", Icons.Default.Info) { showRelation = true }
        }
        if (dependent) {
            if (showRelation) AlertDialog(onDismissRequest = { showRelation = false },
                title = { Text("Залежні сторони") },
                text = { Text(measurementWallRelation(side)) },
                confirmButton = { TextButton(onClick = { showRelation = false }) { Text("Закрити") } })
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 240.dp || density.fontScale >= 1.45f) 1 else 2
        val gap = 8.dp
        val fieldWidth = (maxWidth - gap * (columns - 1)) / columns
        when (val outline = draft.outline) {
            is MeasurementOutlineDraft.Captured -> Column {
                Text("Контур за вершинами · виміряних сторін немає", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = enabled, onClick = { model.accept(MeasurementCreateIntent.ReplaceCapturedWithSides) }) {
                    Text("Задати виміряні сторони")
                }
            }
            is MeasurementOutlineDraft.Template -> {
                val keys = CeilingShapeTemplates.template(outline.shape)!!.parameterKeys
                val requesters = remember(keys) { List(keys.size) { FocusRequester() } }
                FlowRow(maxItemsInEachRow = columns, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    keys.forEachIndexed { index, key ->
                        val wall = when (outline.shape to key) {
                            "rectangle" to "length", "l_shaped" to "A" -> "AB"
                            "rectangle" to "width", "l_shaped" to "D" -> "BC"
                            "l_shaped" to "B" -> "BC+DE"
                            "l_shaped" to "C" -> "EF"
                            else -> key
                        }
                        MeasurementLengthField(outline.fields.getValue(key), "$wall, м", validation.fieldErrors[key], enabled,
                            modifier = Modifier.width(fieldWidth).focusRequester(requesters[index]),
                            onNext = (requesters.getOrNull(index + 1) ?: followingField)?.let { next -> { next.requestFocus() } },
                            selected = selected != null && key in measurementWallParameters(draft.shape, selected),
                            onFocus = inputFocus, onChange = { model.accept(MeasurementCreateIntent.Dimension(key, it), grouped = true) })
                    }
                }
            }
            is MeasurementOutlineDraft.Orthogonal -> {
                val labels = measurementSideLabels(outline.sides.size)
                val ids = outline.sides.map { it.inputId }
                val requesters = remember(ids) { List(ids.size) { FocusRequester() } }
                FlowRow(maxItemsInEachRow = columns, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    outline.sides.forEachIndexed { i, side ->
                        key(side.inputId) {
                            MeasurementLengthField(side.length, "${labels[i]}, м", validation.fieldErrors[side.inputId], enabled,
                                modifier = Modifier.width(fieldWidth).focusRequester(requesters[i]),
                                onNext = (requesters.getOrNull(i + 1) ?: followingField)?.let { next -> { next.requestFocus() } },
                                selected = selected == i, onFocus = inputFocus,
                                onChange = { model.accept(MeasurementCreateIntent.SideLength(i, it), grouped = true) })
                        }
                    }
                }
            }
        }
    }
    val outline = draft.outline as? MeasurementOutlineDraft.Orthogonal
    if (outline != null) {
        MeasurementDisclosure("Додатково", topology) { topology = !topology }
        if (topology) {
            val labels = measurementSideLabels(outline.sides.size)
            outline.sides.forEachIndexed { i, side ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(labels[i], style = MaterialTheme.typography.titleSmall)
                    OrthogonalTurn.entries.forEach { turn ->
                        FilterChip(side.turn == turn, onClick = {
                            focus.clearFocus()
                            model.accept(MeasurementCreateIntent.SideTurn(i, turn))
                        }, enabled = enabled, label = { Text(if (turn == OrthogonalTurn.LEFT) "↶ 90°" else "↷ 90°") })
                    }
                    TextButton(onClick = {
                        focus.clearFocus()
                        model.accept(MeasurementCreateIntent.RemoveSide(i))
                    }, enabled = enabled && outline.sides.size > 4) { Text("Видалити ${labels[i]}") }
                }
            }
            TextButton(onClick = {
                focus.clearFocus()
                model.accept(MeasurementCreateIntent.AddSide)
            }, enabled = enabled && outline.sides.size < CeilingPolygonCalculator.MAX_VERTICES) { Text("Додати сторону") }
        }
    }
}
