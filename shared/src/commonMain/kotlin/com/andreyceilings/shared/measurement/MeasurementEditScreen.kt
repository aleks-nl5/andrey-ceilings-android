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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Straighten
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.andreyceilings.shared.input.CompactOutlinedStringField
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource
import com.andreyceilings.shared.ui.CeilingsWorkspaceScaffold
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.ui.ceilingsFloatingOutlineAlpha
import com.andreyceilings.shared.theme.ceilingsStatusColors
import kotlinx.coroutines.*

private enum class MeasurementSheet { CLOSED, COMPACT, EXPANDED }



private const val SheetMotionMillis = 240
private val WorkspacePanelShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

private enum class MeasurementEditContext { CONTOUR, ELEMENTS, COST }

@Composable
private fun MeasurementEditContext.displayLabel(): String = stringResource(when (this) {
    MeasurementEditContext.CONTOUR -> Res.string.p5b_context_contour
    MeasurementEditContext.ELEMENTS -> Res.string.p5b_context_elements
    MeasurementEditContext.COST -> Res.string.p5b_context_cost
})

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
    imeInsets: WindowInsets? = null,
    onCreated: ((Long) -> Unit)? = null,
    creationParent: MeasurementCreateParent? = null
) {
    val state by model.state.collectAsState()
    val validation = remember(state.draft) { validateMeasurementCreate(state.draft) }
    val pointFlow = remember(model, state.original.draft.recordUuid) { model.observePoints() }
    val overlay = key(model, state.original.draft.recordUuid) { pointFlow.collectAsState(if (state.creating) PointOverlayState.Loaded(emptyList(), null)
        else PointOverlayState.Loading).value }
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
    var saveBlockOpen by remember { mutableStateOf(false) }
    var pendingShape by rememberSaveable(measurementUuid) { mutableStateOf<String?>(null) }
    fun applyShape(shape: String) {
        focus.clearFocus()
        model.accept(MeasurementCreateIntent.Shape(shape))
        selected = null
        pendingShape = null
    }
    val panelState = rememberSaveableStateHolder()
    val normalContourScroll = rememberScrollState()
    val fullscreenContourScroll = rememberScrollState()
    val contourScroll = if (fullscreen) fullscreenContourScroll else normalContourScroll
    var reloadConfirm by remember { mutableStateOf(false) }
    var lineSnapEnabled by rememberSaveable(measurementUuid) { mutableStateOf(true) }
    var showAdd by rememberSaveable(measurementUuid) { mutableStateOf(false) }
    var catalogSubpageBack by remember(measurementUuid) { mutableStateOf<(() -> Unit)?>(null) }
    val currentCatalogSubpageBack by rememberUpdatedState(catalogSubpageBack)
    val currentEditorState by rememberUpdatedState(state)
    var materialPicker by rememberSaveable(measurementUuid) { mutableStateOf<com.andreyceilings.shared.livepricing.BusinessRole?>(null) }
    var selectedWork by rememberSaveable(measurementUuid) { mutableStateOf<String?>(null) }
    val currentSelectedWork by rememberUpdatedState(selectedWork)
    var highlightedWorkId by remember(measurementUuid) { mutableStateOf<String?>(null) }
    // Only a drawing-origin selection may request relocation; tapping a row
    // within the Elements list must never move that list.
    var drawingRelocationTarget by remember(measurementUuid) { mutableStateOf<String?>(null) }
    var drawingSelectionRequest by remember(measurementUuid) { mutableIntStateOf(0) }
    LaunchedEffect(highlightedWorkId) {
        if (highlightedWorkId != null) {
            delay(1800)
            highlightedWorkId = null
        }
    }
    fun dismissCatalog() {
        showAdd = false
        catalogSubpageBack = null
        materialPicker = null
        focus.clearFocus()
    }
    val pointToolActive by rememberUpdatedState(state.pointElementUuid != null)
    val lineToolActive by rememberUpdatedState(state.lineElementUuid != null)
    val areaToolActive by rememberUpdatedState(state.area.tool != null)
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
                showAdd && currentCatalogSubpageBack != null -> {
                    currentCatalogSubpageBack?.invoke()
                    true
                }
                showAdd -> { dismissCatalog(); true }
                pointToolActive || lineToolActive || areaToolActive -> { model.cancelPlacement(); true }
                currentEditorState.lineProperties != null -> { model.dismissLineProperties(); true }
                currentEditorState.area.properties != null -> { model.dismissAreaProperties(); true }
                currentEditorState.mountingInput != null -> { model.dismissMounting(); true }
                currentSelectedWork != null -> {
                    selectedWork = null
                    model.clearDrawingSelection()
                    true
                }
                currentEditorState.selectedPointUuid != null ||
                    currentEditorState.selectedLineUuid != null ||
                    currentEditorState.area.selected != null -> {
                    model.clearDrawingSelection()
                    true
                }
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
    val currentOnCreated by rememberUpdatedState(onCreated)
    LaunchedEffect(model, state.saved) {
        if (state.saved && state.creating) {
            state.createdId?.let { currentOnCreated?.invoke(it) }
            model.delivered()
        } else if (state.saved) model.resumeDetailsAfterSave()
    }
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
    val parentReady = creationParent == null || creationParent is MeasurementCreateParent.Active
    val canSave = parentReady && canLeaveSurface && validation.canSave && model.canSaveMountingDraft() &&
        !state.conflict && overlay is PointOverlayState.Loaded
    val saveBlockedReason = if (!state.dirty || canSave) null else when {
        state.conflict -> stringResource(Res.string.p5b_save_conflict)
        creationParent == MeasurementCreateParent.Loading -> stringResource(Res.string.p5c_parent_loading)
        !parentReady -> stringResource(Res.string.p5c_parent_unavailable)
        validation.nameError != null -> stringResource(Res.string.p5c_name_required)
        !validation.canSave -> stringResource(Res.string.p5b_save_invalid_contour)
        state.lineProperties?.dirty == true -> {
            val input = state.lineProperties
            val line = model.displayLines().find { it.uuid == input?.uuid }
            if (input != null && line != null && model.linePropertiesCandidate(line, input) == null)
                stringResource(Res.string.p5b_save_line_invalid)
            else stringResource(Res.string.p5b_save_apply_fields)
        }
        state.area.properties?.dirty == true -> {
            val input = state.area.properties
            val area = model.displayAreas().find { it.uuid == input?.uuid }
            if (input != null && area != null && model.areaPropertiesCandidate(area, input) == null)
                stringResource(Res.string.p5b_save_area_invalid)
            else stringResource(Res.string.p5b_save_apply_fields)
        }
        state.mountingInput?.dirty == true || state.coordinates?.dirty == true ->
            stringResource(Res.string.p5b_save_apply_fields)
        state.lineStart != null || state.linePreview != null ||
            (state.area.tool != null && (state.area.vertices.isNotEmpty() || state.area.diameter.isNotBlank())) ->
                stringResource(Res.string.p5b_save_finish_placement)
        overlay !is PointOverlayState.Loaded -> stringResource(Res.string.p5b_save_wait_points)
        !model.canSaveMountingDraft() -> model.lineCapacityError()
            ?: model.areaCapacityError()
            ?: stringResource(Res.string.p5b_save_check_elements)
        else -> null
    }
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
    fun trySelectSide(side: Int): Boolean {
        if (!enabled || model.promptForDirtyElementBeforeNavigation()) return false
        selected = side
        selectedWork = null
        highlightedWorkId = null
        drawingRelocationTarget = null
        model.clearDrawingSelection()
        return true
    }
    fun selectSideFromField(side: Int) {
        trySelectSide(side)
    }
    fun select(side: Int) {
        if (!trySelectSide(side)) return
        if (!fullscreen) {
            openContext(MeasurementEditContext.CONTOUR)
            scope.launch { contourScroll.scrollTo(0) }
        }
    }
    fun showElements() = openContext(MeasurementEditContext.ELEMENTS)
    fun selectElement(uuid: String, select: (String) -> Unit) {
        if (!enabled || model.promptForDirtyElementBeforeNavigation()) return
        select(uuid)
        if (showAdd) dismissCatalog()
        val placedWork = (model.displayPoints().firstOrNull { it.uuid == uuid }?.elementUuid
            ?: model.displayLines().firstOrNull { it.uuid == uuid }?.elementUuid
            ?: model.displayAreas().firstOrNull { it.uuid == uuid }?.elementUuid)
            ?.takeIf { id -> model.workspaceWorks().any { it.uuid == id } }
        selectedWork = placedWork
        highlightedWorkId = placedWork
        drawingRelocationTarget = placedWork ?: uuid
        drawingSelectionRequest++
        selected = null
        // In fullscreen, the double-tap callback (onOpenSelection) owns the sheet.
        // A single selection or a drag must never expand it.
        if (!fullscreen) showElements()
    }

    val selectedFeature = state.selectedPointUuid ?: state.selectedLineUuid ?: state.area.selected
    val activeElementControls = selectedFeature ?: state.pointElementUuid ?: state.lineElementUuid ?: state.area.tool?.name
    val geometry = validation.geometry
    val diagnostic = remember(draft.outline, validation.geometry) {
        if (geometry == null) contourWorkspacePreview(draft) else null
    }
    val issues = validation.contourIssues
    val contourError = issues.any { it.code != MeasurementContourIssueCode.MISSING }
    val problemWalls = remember(draft.outline, issues) { draft.problemWalls(issues) }
    val contourDescription = if (diagnostic == null) null else stringResource(
        if (contourError) Res.string.p5c_contour_invalid else Res.string.p5c_contour_incomplete)
    val status = when {
        state.checking -> "Перевірка стану заміру. Дочекайтеся результату."
        state.error != null -> state.error
        state.uncertain -> "Результат збереження невідомий."
        state.conflict -> "Збережений замір змінився. Перезавантажте запис."
        overlay is PointOverlayState.Missing -> "Замір або об'єкт недоступний."
        overlay is PointOverlayState.Unavailable -> "Позначки недоступні: захищена база заблокована або помилка читання."
        overlay is PointOverlayState.Loading -> "Завантаження збережених позначок…"
        else -> null
    }
    val statusIsProblem = state.error != null || state.uncertain || state.conflict ||
        overlay is PointOverlayState.Missing || overlay is PointOverlayState.Unavailable
    val editorPadding = measurementEditorPadding(contentPadding, systemTopInset)
    val density = LocalDensity.current
    var tabRowPx by remember { mutableIntStateOf(0) }
    val tabRowHeight = if (tabRowPx == 0) 48.dp else with(density) { tabRowPx.toDp() }
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
            else -> (bodyHeight * 0.60f).coerceAtMost((bodyHeight - 64.dp).coerceAtLeast(0.dp))
        }
    }

    CeilingsWorkspaceScaffold(
        safePadding = editorPadding,
        keyboard = keyboard,
        fullscreen = fullscreen,
        header = { _ ->
            val inlineKeyboardPanel = inlineKeyboardPanel()
            Column(Modifier.fillMaxWidth().testTag("measurement-edit-header")) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MeasurementEditorFab(
                        label = if (state.creating) stringResource(Res.string.p5c_back) else "До заміру",
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        enabled = canLeaveSurface,
                        modifier = Modifier.testTag("measurement-edit-back")
                    ) { if (!surfaceBack()) model.requestExit(onBack) }
                    if (inlineKeyboardPanel) Spacer(Modifier.weight(1f)) else Column(Modifier.weight(1f)) {
                        Text(draft.name.ifBlank { stringResource(Res.string.p5c_ceiling) }, maxLines = 1,
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
                    val showContourIssues = contourDescription != null && !state.conflict && parentReady
                    val feedback = if (showContourIssues) contourDescription else saveBlockedReason
                    LaunchedEffect(feedback, issues, context) { saveBlockOpen = false }
                    if (feedback != null && enabled) {
                        val explanations = if (showContourIssues) issues.map { it.displayMessage(draft) }.distinct()
                        else listOf(feedback)
                        val warningInk = ceilingsStatusColors(MaterialTheme.colorScheme.surface.luminance() < 0.5f).warning
                        MeasurementValidationFeedback(
                            label = if (showContourIssues) feedback else stringResource(Res.string.p5b_save_show_reason),
                            messages = explanations, expanded = saveBlockOpen, onExpanded = { saveBlockOpen = it },
                            tint = if (showContourIssues && !contourError) warningInk else MaterialTheme.colorScheme.error
                        )
                    }
                    MeasurementEditorFab(
                        label = "Зберегти замір",
                        icon = if (saveConfirmed) Icons.Default.Check else Icons.Default.Save,
                        // If the draft is dirty, Save stays tappable so it can explain a
                        // validation blocker. A blocked tap MUST NOT write anything.
                        enabled = enabled && state.dirty,
                        confirmed = saveConfirmed,
                        modifier = Modifier.testTag("measurement-edit-save").semantics {
                            stateDescription = when {
                                state.saving -> "Збереження…"
                                saveConfirmed -> "Збережено"
                                saveBlockedReason != null -> saveBlockedReason
                                state.dirty -> "Незбережено"
                                else -> "Змін немає"
                            }
                            liveRegion = LiveRegionMode.Polite
                        },
                        onClick = { if (canSave) save() else saveBlockOpen = true }
                    )
                }
                MeasurementGeometrySummary(validation.geometry)
            }
        },
        overlay = { headerHeight ->
            val inlineKeyboardPanel = inlineKeyboardPanel()
            if (status != null && (!inlineKeyboardPanel || statusIsProblem)) {
                val position = if (inlineKeyboardPanel) Modifier.align(Alignment.TopEnd).padding(end = 56.dp)
                else Modifier.align(Alignment.TopStart).padding(top = headerHeight)
                Box(position) {
                    val critical = state.conflict || state.uncertain || state.saveFailed
                    Row(
                        modifier = if (critical) Modifier.background(
                            MaterialTheme.colorScheme.errorContainer, CircleShape).padding(end = 8.dp) else Modifier,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MeasurementIconAction("Показати повне повідомлення", Icons.Default.Info,
                            modifier = Modifier.testTag("measurement-edit-status").semantics {
                                stateDescription = status
                                liveRegion = LiveRegionMode.Polite
                            },
                            tint = if (statusIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        ) { statusDetails = true }
                        if (critical) Text(stringResource(when {
                            state.conflict -> Res.string.p5c_save_conflict
                            state.uncertain -> Res.string.p5c_save_uncertain
                            else -> Res.string.p5c_save_failed
                        }), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    }
                }
            }
            val normalPanelHeight = normalPanelHeight(headerHeight)
            // Keep tabs and their camera-fit boundary EXACTLY where they were.
            // Only the scrolling panel grows beneath the transparent OS navigation
            // bar when the keyboard is hidden; the last item gets safe scroll padding.
            val normalNavigationUnderlay =
                if (!fullscreen && !keyboardVisible) editorPadding.calculateBottomPadding() else 0.dp
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
                    val activePlacementTool = state.pointElementUuid
                        ?: state.lineElementUuid ?: state.area.tool?.name
                    var lastPlacementTool by rememberSaveable {
                        mutableStateOf(activePlacementTool)
                    }
                    LaunchedEffect(context, activePlacementTool) {
                        // An actual new placement tool belongs at the top. Selection
                        // changes (from either the drawing or the list) do not.
                        if (context == MeasurementEditContext.ELEMENTS &&
                            activePlacementTool != null && activePlacementTool != lastPlacementTool
                        ) scroll.scrollTo(0)
                        lastPlacementTool = activePlacementTool
                    }
                    CompositionLocalProvider(LocalMeasurementInputFocus provides inputFocus) {
                        val elementsContext = context == MeasurementEditContext.ELEMENTS
                        val panelTag = Modifier.testTag("measurement-edit-context-panel")
                        Column(panelModifier.fillMaxWidth().then(if (elementsContext) panelTag else Modifier)) {
                            if (context == MeasurementEditContext.ELEMENTS) WorkspaceElementsHeader(
                                canvas = model.materials(com.andreyceilings.shared.livepricing.BusinessRole.CANVAS),
                                profile = model.materials(com.andreyceilings.shared.livepricing.BusinessRole.PRIMARY_PROFILE),
                                activeRole = if (showAdd) materialPicker else null,
                                enabled = enabled && overlay is PointOverlayState.Loaded &&
                                    (state.creating || validation.geometry != null),
                                onMaterial = { role ->
                                    if (!model.promptForDirtyElementBeforeNavigation()) {
                                        focus.clearFocus()
                                        materialPicker = role
                                        showAdd = true
                                        if (fullscreen) sheet = MeasurementSheet.EXPANDED
                                        scope.launch { scroll.scrollTo(0) }
                                    }
                                },
                                onAdd = {
                                    if (!model.promptForDirtyElementBeforeNavigation()) {
                                        focus.clearFocus()
                                        materialPicker = null
                                        showAdd = true
                                        if (fullscreen) sheet = MeasurementSheet.EXPANDED
                                        scope.launch { scroll.scrollTo(0) }
                                    }
                                }
                            )
                            val scrollBounds = if (!elementsContext && !fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                            Column(scrollBounds.then(if (elementsContext) Modifier else panelTag)
                                .verticalScroll(scroll)
                                .then(if (elementsContext) Modifier.testTag("p5b-elements-scroll") else Modifier)
                                .padding(top = if (context == MeasurementEditContext.CONTOUR) 14.dp else 6.dp,
                                    bottom = 12.dp + normalNavigationUnderlay),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (context) {
                                MeasurementEditContext.CONTOUR -> {
                                    MeasurementShapePicker(draft.shape, enabled) { shape ->
                                        if (shape != draft.shape && !model.promptForDirtyElementBeforeNavigation()) {
                                            if (draft.outline.hasEnteredDimensions()) pendingShape = shape
                                            else applyShape(shape)
                                        }
                                    }
                                    val nameFocus = remember { FocusRequester() }
                                    val notesFocus = remember { FocusRequester() }
                                    MeasurementSelectedFields(model, selected, validation, enabled, ::selectSideFromField,
                                        followingField = nameFocus)
                                    CompactOutlinedStringField(draft.name,
                                        { model.accept(MeasurementCreateIntent.Name(it), grouped = true) },
                                        label = stringResource(Res.string.p5b_measurement_name),
                                        enabled = enabled,
                                        modifier = measurementFieldFocusModifier().fillMaxWidth()
                                            .testTag("measurement-edit-contour-name").focusRequester(nameFocus)
                                            .onFocusChanged { if (it.isFocused) model.beginInput() else model.commitInput() },
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                        keyboardActions = KeyboardActions(onNext = { notesFocus.requestFocus() }),
                                        isError = validation.nameError != null)
                                    CompactOutlinedStringField(draft.notes,
                                        { model.accept(MeasurementCreateIntent.Notes(it), grouped = true) },
                                        label = stringResource(Res.string.p5b_measurement_notes),
                                        enabled = enabled, maxLines = 3,
                                        modifier = measurementFieldFocusModifier().fillMaxWidth()
                                            .testTag("measurement-edit-contour-notes").focusRequester(notesFocus)
                                            .onFocusChanged { if (it.isFocused) model.beginInput() else model.commitInput() })
                                }
                                MeasurementEditContext.ELEMENTS -> {
                                    val canEdit = enabled && validation.geometry != null && overlay is PointOverlayState.Loaded
                                    if (showAdd) WorkspaceCatalogSearch(model, materialPicker, ::dismissCatalog,
                                        onNestedBackChange = { catalogSubpageBack = it },
                                        onExistingWork = { workId ->
                                            if (!model.promptForDirtyElementBeforeNavigation()) {
                                                model.clearDrawingSelection()
                                                selectedWork = workId
                                                highlightedWorkId = workId
                                                dismissCatalog()
                                            }
                                        }
                                    ) {
                                        selected = null
                                        selectedWork = null
                                        model.clearDrawingSelection()
                                        dismissCatalog()
                                    } else {
                                        WorkspacePlacementTools(model, state, canEdit, lineSnapEnabled) { lineSnapEnabled = it }
                                        // Free obstacles/holes are NOT paid catalog work. They
                                        // must still be selectable in the same Elements list.
                                        val standaloneArea = model.displayAreas().firstOrNull {
                                            it.uuid == state.area.selected && it.elementUuid == null
                                        }
                                        val standaloneEditorOpen = standaloneArea != null && (
                                            state.area.properties?.uuid == standaloneArea.uuid ||
                                            (state.area.mountingIndex != null && state.mountingInput != null)
                                        )
                                        // A single tap shows/highlights the row; only an explicit
                                        // settings action or fullscreen double-tap opens the editor.
                                        if (selectedWork == null && (standaloneArea == null || standaloneEditorOpen))
                                            WorkspaceGeometryPanel(model, state, canEdit, lineSnapEnabled) { lineSnapEnabled = it }
                                        if (activeElementControls == null ||
                                            (selectedFeature != null && selectedWork != null) ||
                                            (standaloneArea != null && !standaloneEditorOpen)) {
                                            WorkspaceWorkList(
                                                model, canEdit, selectedWork, highlightedWorkId,
                                                drawingRelocationTarget, drawingSelectionRequest, normalNavigationUnderlay,
                                                onSelect = { work ->
                                                    if (!model.promptForDirtyElementBeforeNavigation()) {
                                                        // List-origin tap: preserve the exact scroll position.
                                                        drawingRelocationTarget = null
                                                        highlightedWorkId = null
                                                        model.clearDrawingSelection()
                                                        selectedWork = work
                                                    }
                                                },
                                                onOpenGeometry = {
                                                    selectedWork = null
                                                    scope.launch { scroll.animateScrollTo(0) }
                                                }
                                            )
                                            WorkspaceStandaloneAreaList(
                                                model, canEdit, state.area.selected,
                                                drawingRelocationTarget, drawingSelectionRequest, normalNavigationUnderlay,
                                                onSelect = { id ->
                                                    if (!model.promptForDirtyElementBeforeNavigation()) {
                                                        // A note tapped within the list should only highlight;
                                                        // never auto-scroll or return to the beginning.
                                                        drawingRelocationTarget = null
                                                        if (id == null) model.clearDrawingSelection()
                                                        else model.selectArea(id)
                                                        selectedWork = null
                                                        highlightedWorkId = null
                                                    }
                                                },
                                                onOpenGeometry = { id ->
                                                    if (!model.promptForDirtyElementBeforeNavigation()) {
                                                        drawingRelocationTarget = null
                                                        model.selectArea(id)
                                                        model.openAreaProperties()
                                                        selectedWork = null
                                                        scope.launch { scroll.animateScrollTo(0) }
                                                    }
                                                },
                                                onDelete = { id ->
                                                    if (!model.promptForDirtyElementBeforeNavigation()) {
                                                        drawingRelocationTarget = null
                                                        model.selectArea(id)
                                                        model.deleteArea() // Draft + Undo; SQL only on global Save.
                                                        selectedWork = null
                                                        highlightedWorkId = null
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                                MeasurementEditContext.COST -> {
                                    Text("Розрахунок за останнім збереженим заміром", style = MaterialTheme.typography.titleSmall)
                                    if (state.creating) {
                                        Text(stringResource(Res.string.p5c_cost_after_save))
                                    } else if (state.dirty) {
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
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(
                if (fullscreen) Modifier else Modifier
                    .height(normalPanelHeight + normalNavigationUnderlay)
                    .offset(y = normalNavigationUnderlay)
                    .testTag("measurement-edit-normal-panel")
            ).then(if (inlineKeyboardPanel) Modifier.padding(start = 60.dp,
                end = if (statusIsProblem) 112.dp else 60.dp) else Modifier)) {
                if (!fullscreen && !keyboardVisible) Row(
                    Modifier.fillMaxWidth().onSizeChanged { tabRowPx = it.height }.testTag("measurement-edit-tab-row"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val tabLines = if (LocalDensity.current.fontScale >= 1.35f) 2 else 1
                    MeasurementEditContext.entries.forEach { mode ->
                        FilterChip(
                            selected = context == mode,
                            onClick = { if (context != mode) { focus.clearFocus(); openContext(mode) } },
                            label = { Text(mode.displayLabel(), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                maxLines = tabLines, softWrap = tabLines > 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f).testTag("measurement-edit-context-${mode.name.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                // The three tabs remain unchanged: only the content
                                // below them scrolls beneath the OS navigation bar.
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
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
                                label = mode.displayLabel(),
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
                    Surface(Modifier.fillMaxWidth().weight(1f).testTag("measurement-edit-panel-surface"), shape = WorkspacePanelShape,
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
        val tabBleed = if (!fullscreen && !keyboardVisible) minOf(tabRowHeight, normalPanelHeight) else 0.dp
        val headerBleed = if (!fullscreen && !inlineKeyboardPanel) headerHeight else 0.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                MeasurementDrawing(geometry?.let { MeasurementGeometryView.Captured(it) } ?: MeasurementGeometryView.Unavailable,
                    diagnosticContour = diagnostic, diagnosticDescription = contourDescription,
                    problemWalls = problemWalls, workspaceOrientation = true,
                    modifier = Modifier.fillMaxSize(), interactive = true, fillAvailableHeight = true, showSideLegend = false,
                    retainCameraOnResize = true, autoFitOnFullscreenChange = true,
                    verticalViewControls = !keyboardVisible, showWorkspaceGrid = true,
                    // The Spacer below already reserves the panel EXCEPT its
                    // overlaid tab row. Canvas therefore ends at the tab row's
                    // BOTTOM, not its visible TOP. Keep Canvas full sized for the
                    // grid/gestures, but exclude the measured tab-row height from
                    // camera fit, hit testing and viewport controls. Do NOT
                    // subtract normalPanelHeight a second time.
                    viewportPadding = PaddingValues(
                        top = headerBleed,
                        bottom = if (fullscreen) editorPadding.calculateBottomPadding() else tabBleed
                    ),
                    onOpenSelection = if (fullscreen) { wall ->
                        if (enabled) {
                            if (wall) {
                                if (selected != null && !model.promptForDirtyElementBeforeNavigation()) {
                                    openContext(MeasurementEditContext.CONTOUR)
                                    scope.launch { contourScroll.scrollTo(0) }
                                }
                            } else {
                                // A double-tap enters the actual geometry editor.
                                // Single tap only selects/highlights and never expands.
                                if (!model.promptForDirtyElementBeforeNavigation()) {
                                    when {
                                        state.selectedPointUuid != null -> model.openMounting()
                                        state.selectedLineUuid != null -> model.openLineProperties()
                                        state.area.selected != null -> model.openAreaProperties()
                                    }
                                    selectedWork = null
                                    showElements()
                                }
                            }
                        }
                    } else null,
                    fitTop = if (fullscreen) headerHeight else 0.dp,
                    fitBottom = if (fullscreen) 56.dp else 0.dp,
                    cameraFitReady = overlay !is PointOverlayState.Loading && !state.checking,
                    selectedSide = selected, onSelectSide = ::select, fullscreen = fullscreen,
                    onEmptyTap = {
                        if (enabled && !model.promptForDirtyElementBeforeNavigation()) {
                            selected = null
                            selectedWork = null
                            model.clearDrawingSelection()
                            if (fullscreen && sheet != MeasurementSheet.CLOSED) closeSheet()
                        }
                    },
                    pointPlacements = if (overlay is PointOverlayState.Loaded && geometry != null) model.displayPoints() else emptyList(), fitPointPlacements = true,
                    selectedPointUuid=state.selectedPointUuid,onSelectPoint = { selectElement(it, model::selectPoint) },
                    placementMode = enabled && geometry != null && state.pointElementUuid != null,
                    workspacePointPlacement = true,
                    relatedElementUuid = selectedWork,
                    onBeginLineHandleDrag = model::beginLineHandleDrag,
                    onBeginAreaHandleDrag = model::beginAreaHandleDrag,
                    onPlacePoint = { x, y ->
                        if (model.placeWorkspacePoint(x, y)) {
                            selected = null
                            showElements()
                        }
                    },
                    pointEditingEnabled=enabled && overlay is PointOverlayState.Loaded && validation.geometry!=null,editPointsInFullscreen=true,
                    pointDrag=state.drag,onBeginPointDrag=model::beginPointDrag,onPreviewPointDrag=model::previewPointDrag,
                    onFinishPointDrag=model::finishPointDrag,onCancelPointDrag=model::cancelPointDrag,
                    mountingDimensions=if(geometry==null) emptyList() else mountingDimensions(model.displayPoints().find { it.uuid==state.selectedPointUuid },geometry,model.draftWalls())+
                        model.displayLines().find {it.uuid==state.selectedLineUuid}?.takeUnless {it.unresolved}?.let {line ->
                            mountingDimensions(line.endpoint(true),geometry,model.draftWalls())+mountingDimensions(line.endpoint(false),geometry,model.draftWalls())
                        }.orEmpty()+model.displayAreas().find {it.uuid==state.area.selected}?.takeUnless {it.unresolved}?.let {a ->
                            mountingDimensions(a.point(state.area.mountingIndex ?: state.area.moveVertex ?: 0),geometry,model.draftWalls())
                        }.orEmpty(),
                    areaFeatures=if (geometry == null) emptyList() else model.displayAreas() +
                        listOfNotNull(model.areaCreationShape()?.let { shape ->
                            AreaFeature("area-preview", state.draft.recordUuid, shape, state.original.updatedAt, 0, 0, role = state.area.role)
                        }),
                    selectedAreaUuid=state.area.selected,areaPlacementMode=enabled && geometry!=null && state.area.tool!=null,onAreaTap=model::areaTap,onSelectArea = { selectElement(it, model::selectArea) },
                    areaEditingEnabled=enabled && validation.geometry!=null,areaDrag=state.area.drag,areaCreationPoints=state.area.vertices,
                    onBeginAreaDrag=model::beginAreaDrag,onPreviewAreaDrag=model::previewAreaDrag,onFinishAreaDrag=model::finishAreaDrag,onCancelAreaDrag=model::cancelAreaDrag,
                    linePlacements=if (geometry == null) emptyList() else model.displayLines() + listOfNotNull(state.linePreview),selectedLineUuid=state.selectedLineUuid,
                    linePlacementMode=enabled && geometry!=null && state.lineElementUuid!=null,onLineTap=model::lineTap,onSelectLine = { selectElement(it, model::selectLine) },
                    lineCreationKey = state.lineElementUuid,
                    lineSnapEnabled = lineSnapEnabled,
                    onFinishLineCreation = { start, end ->
                        model.finishWorkspaceLine(start, end).also { completed ->
                            if (completed) {
                                selected = null
                                showElements()
                            }
                        }
                    },
                    lineStartPoint=state.lineStart,lineEditingEnabled=enabled && validation.geometry!=null,lineDrag=state.lineDrag,onBeginLineDrag=model::beginLineDrag,
                    onPreviewLineDrag=model::previewLineDrag,onFinishLineDrag=model::finishLineDrag,onCancelLineDrag=model::cancelLineDrag,
                    mountingWallOrdinals=if(validation.geometry==null) emptyList() else model.displayPoints().find { it.uuid==state.selectedPointUuid }?.mounting?.anchor?.let { a ->
                        model.draftWalls().filter { it.uuid==a.firstWall || it.uuid==a.secondWall }.map { it.ordinal } }.orEmpty())
            }
            if (!fullscreen) Spacer(Modifier.height(normalPanelHeight - tabBleed))
        }
    }
    pendingShape?.let { shape ->
        AlertDialog(
            onDismissRequest = { pendingShape = null },
            title = { Text(stringResource(Res.string.p5c_reset_dimensions_title)) },
            text = { Text(stringResource(Res.string.p5c_reset_dimensions_message)) },
            dismissButton = {
                TextButton(onClick = { applyShape(shape) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(Res.string.p5c_reset_dimensions))
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingShape = null }) {
                    Text(stringResource(Res.string.p5c_cancel))
                }
            }
        )
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

    if (state.exitRequested) AlertDialog(onDismissRequest = model::continueEditing,
        title = { Text(stringResource(Res.string.p5b_exit_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.error ?: stringResource(if (canSave) Res.string.p5b_exit_question
                    else Res.string.p5b_exit_blocked), style = MaterialTheme.typography.bodyMedium)
                if (!canSave && saveBlockedReason != null)
                    Text(saveBlockedReason, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { save(exit = true) }, enabled = canSave,
                modifier = Modifier.testTag("measurement-exit-save")) {
                Text(stringResource(Res.string.p5b_exit_save))
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = model::discardAndExit,
                    modifier = Modifier.testTag("measurement-exit-discard"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(Res.string.p5b_exit_discard))
                }
                TextButton(onClick = model::continueEditing,
                    modifier = Modifier.testTag("measurement-exit-continue")) {
                    Text(stringResource(Res.string.p5b_exit_continue))
                }
            }
        })
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
private fun MeasurementSelectedFields(
    model: MeasurementEditModel,
    selected: Int?,
    validation: MeasurementCreateValidation,
    enabled: Boolean,
    selectSideFromField: (Int) -> Unit,
    followingField: FocusRequester?
) {
    val state by model.state.collectAsState()
    val draft = state.draft
    val errors = validation.contourIssues.filter { it.code != MeasurementContourIssueCode.MISSING }
        .flatMap { it.fields }.toSet()
    fun fieldError(key: String) = if (key in errors) key else null
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
                MeasurementDimensionRows(keys.size, columns) { index, cell ->
                    val key = keys[index]
                    val wall = when (outline.shape to key) {
                        "rectangle" to "length", "l_shaped" to "A" -> "AB"
                        "rectangle" to "width", "l_shaped" to "D" -> "BC"
                        "l_shaped" to "B" -> "BC+DE"
                        "l_shaped" to "C" -> "EF"
                        else -> key
                    }
                    MeasurementLengthField(outline.fields.getValue(key), stringResource(Res.string.p5c_wall_metres, wall), fieldError(key), enabled,
                        modifier = cell.focusRequester(requesters[index]),
                        onNext = (requesters.getOrNull(index + 1) ?: followingField)?.let { next -> { next.requestFocus() } },
                        selected = selected != null && key in measurementWallParameters(draft.shape, selected),
                        onFocus = { focused ->
                            inputFocus(focused)
                            if (focused) selectSideFromField(selected?.takeIf { key in measurementWallParameters(draft.shape, it) }
                                ?: measurementParameterWall(draft.shape, key))
                        }, onChange = { model.accept(MeasurementCreateIntent.Dimension(key, it), grouped = true) })
                }
            }
            is MeasurementOutlineDraft.Orthogonal -> {
                val labels = measurementSideLabels(outline.sides.size)
                val ids = outline.sides.map { it.inputId }
                val requesters = remember(ids) { List(ids.size) { FocusRequester() } }
                MeasurementDimensionRows(outline.sides.size, columns) { i, cell ->
                    val side = outline.sides[i]
                    key(side.inputId) {
                        MeasurementLengthField(side.length, stringResource(Res.string.p5c_wall_metres, labels[i]), fieldError(side.inputId), enabled,
                                modifier = cell.focusRequester(requesters[i]),
                            onNext = (requesters.getOrNull(i + 1) ?: followingField)?.let { next -> { next.requestFocus() } },
                            selected = selected == i, onFocus = { focused ->
                                inputFocus(focused)
                                if (focused) selectSideFromField(i)
                            },
                            onChange = { model.accept(MeasurementCreateIntent.SideLength(i, it), grouped = true) })
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

@Composable
private fun MeasurementDimensionRows(count: Int, columns: Int, field: @Composable (Int, Modifier) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        for (start in 0 until count step columns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (index in start until start + columns) {
                    val cell = Modifier.weight(1f)
                    if (index < count) field(index, cell) else Spacer(cell)
                }
            }
        }
    }
}

@Composable
private fun MeasurementGeometrySummary(geometry: FrozenPolygonGeometry?) {
    val area = geometry?.let { formatMeasurementAreaCompact(it.areaSquareMeters) }.orEmpty()
    val perimeter = geometry?.let { formatMeasurementLengthCompact(it.perimeterMeters) }.orEmpty()
    val areaValue = if (geometry == null) stringResource(Res.string.p5b_live_area_unavailable)
        else stringResource(Res.string.p5b_live_area_value, area)
    val perimeterValue = if (geometry == null) stringResource(Res.string.p5b_live_perimeter_unavailable)
        else stringResource(Res.string.p5b_live_perimeter_value, perimeter)
    val color = if (geometry == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().offset(y = (-7).dp).padding(horizontal = 12.dp, vertical = 1.dp)
            .testTag("measurement-live-geometry"),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MeasurementGeometryMetric(Icons.Default.CropSquare, areaValue, stringResource(
            if (geometry == null) Res.string.p5b_live_area_invalid_label else Res.string.p5b_live_area_label, areaValue),
            color, "measurement-live-area")
        MeasurementGeometryMetric(Icons.Default.Straighten, perimeterValue, stringResource(
            if (geometry == null) Res.string.p5b_live_perimeter_invalid_label else Res.string.p5b_live_perimeter_label, perimeterValue),
            color, "measurement-live-perimeter")
    }
}

@Composable
private fun RowScope.MeasurementGeometryMetric(
    icon: ImageVector, value: String, label: String, color: Color, tag: String
) {
    Row(
        Modifier.weight(1f, fill = false).testTag(tag).clearAndSetSemantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text(value, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}
