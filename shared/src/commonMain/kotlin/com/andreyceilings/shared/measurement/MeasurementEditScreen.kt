package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.ui.CeilingsScreenScaffold
import kotlinx.coroutines.*

@Composable
fun MeasurementEditScreen(
    projectId: Long, measurementId: Long, store: MeasurementEditStore?,
    contentPadding: PaddingValues, systemTopInset: Dp, onBack: () -> Unit,
    onSaved: () -> Unit, navigationGuard: MeasurementCreateNavigationGuard?
) {
    // Do not consume restored form state against Android's temporary null session
    // during Activity startup. The unlocked-session store can reattach a pending write.
    if (store == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(top = systemTopInset)) {
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
                model = MeasurementEditModel(store, document)
            } catch (_: Exception) { error = "Замір недоступний для редагування. Перевірте об'єкт і збережену геометрію." }
        }
        val editor = model
        if (editor == null) Column(Modifier.fillMaxSize().padding(contentPadding).padding(top = systemTopInset)) {
            Text(error ?: "Завантаження заміру…")
            TextButton(onClick = onBack) { Text("До заміру") }
        } else MeasurementEditScreen(editor, contentPadding, systemTopInset, onBack, onSaved, navigationGuard)
    }
}

@Composable
fun MeasurementEditScreen(
    model: MeasurementEditModel, contentPadding: PaddingValues = PaddingValues(16.dp),
    systemTopInset: Dp = 0.dp, onBack: () -> Unit, onSaved: () -> Unit,
    navigationGuard: MeasurementCreateNavigationGuard? = null
) {
    val state by model.state.collectAsState()
    val validation = remember(state.draft) { validateMeasurementCreate(state.draft) }
    val pointFlow = remember(model, state.original.draft.recordUuid) { model.observePoints() }
    val overlay = key(model, state.original.draft.recordUuid) { pointFlow.collectAsState(PointOverlayState.Loading).value }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var fullscreen by rememberSaveable { mutableStateOf(navigationGuard?.fullscreen?.value == true) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var allFields by rememberSaveable { mutableStateOf(false) }
    var reloadConfirm by remember { mutableStateOf(false) }
    val currentSaved by rememberUpdatedState(onSaved)
    val surfaceBack = remember { { if (fullscreen) { fullscreen = false; true } else false } }
    DisposableEffect(model, navigationGuard) {
        navigationGuard?.bindEdit(model); navigationGuard?.bindSurface(surfaceBack)
        onDispose {
            navigationGuard?.unbindEdit(model); navigationGuard?.unbindSurface(surfaceBack)
        }
    }
    SideEffect { navigationGuard?.fullscreen?.value = fullscreen }
    LaunchedEffect(model) { model.reconcile() }
    LaunchedEffect(state.saved) { if (state.saved) { currentSaved(); model.delivered() } }
    val enabled = !state.saving && !state.checking && !state.saved
    fun save() {
        focus.clearFocus(); model.commitInput()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { model.save() }
    }
    fun select(side: Int) { focus.clearFocus(); model.commitInput(); selected = side }
    val draft = state.draft
    LaunchedEffect(draft.shape, (draft.outline as? MeasurementOutlineDraft.Orthogonal)?.sides?.map { it.inputId }) {
        selected = null
    }
    val toolbar: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { if (fullscreen) fullscreen = false else model.requestExit(onBack) }, enabled = enabled) { Text(if (fullscreen) "Згорнути" else "До заміру") }
                if (!fullscreen) TextButton(onClick = { focus.clearFocus(); fullscreen = true }) { Text("На весь екран") }
                TextButton(onClick = { focus.clearFocus(); model.undo() }, enabled = enabled && state.canUndo) { Text("Скасувати дію") }
                TextButton(onClick = { focus.clearFocus(); model.redo() }, enabled = enabled && state.canRedo) { Text("Повторити") }
                TextButton(onClick = { model.requestExit {
                    // Explicit Cancel ends editing; system Back separately exits fullscreen first.
                    fullscreen = false
                    onBack()
                } }, enabled = enabled) { Text("Відхилити") }
                Button(onClick = ::save, enabled = enabled && validation.canSave && !state.conflict) { Text(if (state.saving) "Збереження…" else "Зберегти") }
            }
            if (state.checking) Text("Перевірка стану заміру. Дочекайтеся результату.")
            if (state.saving || state.saved) Text(if (state.saving) "Збереження триває. Дочекайтеся результату." else "Замір збережено. Відкриття…")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.conflict) TextButton(onClick = { reloadConfirm = true }, enabled = enabled) { Text("Перезавантажити запис") }
        }
    }
    val drawing: @Composable (Boolean) -> Unit = { full ->
        Column(if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        val savedGeometry = (overlay as? PointOverlayState.Loaded)?.savedGeometry
            ?: validateMeasurementCreate(state.original.draft).geometry
        val geometry = validation.geometry ?: savedGeometry
        when (val pointState = overlay) {
            PointOverlayState.Loading -> Text("Завантаження збережених позначок…")
            PointOverlayState.Missing -> Text("Замір або об'єкт недоступний; позначки не завантажено.")
            PointOverlayState.Unavailable -> Text("Позначки недоступні: захищена база заблокована або помилка читання.")
            is PointOverlayState.Loaded -> {
                Text("Збережені позначки: ${pointState.points.size}. Тут лише перегляд; положення змінюйте в деталях заміру.")
                if (pointState.points.isNotEmpty() && validation.geometry != savedGeometry)
                    Text("Контур змінено; зберегти його можна після видалення/перегляду точок.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (validation.geometry == null && geometry != null)
            Text("Невалідна чернетка: показано збережений контур і позначки. ${validation.geometryError.orEmpty()}")
        if (geometry == null) Text("Креслення недоступне: ${validation.geometryError.orEmpty()}")
        else MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
            if (full) Modifier.weight(1f) else Modifier.fillMaxWidth(), interactive = true,
            selectedSide = selected, onSelectSide = ::select, fullscreen = full,
            pointPlacements = (overlay as? PointOverlayState.Loaded)?.points.orEmpty(), fitPointPlacements = true)
        }
    }
    if (fullscreen) Column(Modifier.fillMaxSize().padding(start = contentPadding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
        end = contentPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
        bottom = contentPadding.calculateBottomPadding(), top = systemTopInset + 8.dp)) {
        toolbar()
        Box(Modifier.weight(1f)) { drawing(true) }
        MeasurementSelectedFields(model, selected, validation, enabled, { selected = it }, compact = true)
    } else CeilingsScreenScaffold(
        windowInsets = WindowInsets(0, 0, 0, 0), contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
            end = contentPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
            top = 8.dp, bottom = contentPadding.calculateBottomPadding()),
        header = { Spacer(Modifier.height(systemTopInset + 8.dp)) },
        pinned = { Column(Modifier.padding(horizontal = 16.dp)) { Text("Редагування заміру", style = MaterialTheme.typography.titleLarge); toolbar() } },
        bottomGradient = false
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Точність заміру: ${draft.precision.label}") }
            item { OutlinedTextField(draft.name, { model.accept(MeasurementCreateIntent.Name(it), grouped = true) },
                enabled = enabled, label = { Text("Назва заміру *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) model.beginInput() else model.commitInput() },
                isError = validation.nameError != null) }
            item { MeasurementShapePicker(draft.shape, enabled) { focus.clearFocus(); model.accept(MeasurementCreateIntent.Shape(it)); selected = null } }
            item { drawing(false) }
            item { validation.geometry?.let { Text("Площа: ${formatMeasurementArea(it.areaSquareMeters, draft.precision)} м² · Периметр: ${formatMeasurementLength(it.perimeterMeters, draft.precision)} м") } }
            item { MeasurementWallPicker(draft, selected, ::select) }
            item {
                TextButton(onClick = { focus.clearFocus(); allFields = !allFields }) {
                    Text(if (allFields) "Контекстні розміри" else "Усі розміри")
                }
                MeasurementSelectedFields(model, selected, validation, enabled, { selected = it }, compact = !allFields)
            }
            item { OutlinedTextField(draft.notes, { model.accept(MeasurementCreateIntent.Notes(it), grouped = true) },
                enabled = enabled, label = { Text("Примітки") }, modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (it.isFocused) model.beginInput() else model.commitInput()
                }) }
        }
    }
    if (state.exitRequested) AlertDialog(onDismissRequest = model::continueEditing,
        title = { Text("Незбережені зміни") }, text = { Text("Зберегти зміни заміру? Збереження поверне до перегляду цього заміру.") },
        confirmButton = { TextButton(onClick = { model.continueEditing(); save() }, enabled = validation.canSave && !state.conflict) { Text("Зберегти") } },
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
internal fun MeasurementWallPicker(draft: MeasurementCreateDraft, selected: Int?, onSelect: (Int) -> Unit) {
    val count = when (val outline = draft.outline) {
        is MeasurementOutlineDraft.Orthogonal -> outline.sides.size
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
    enabled: Boolean, onSelect: (Int) -> Unit, compact: Boolean = false) {
    val draft = model.state.value.draft
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
            is MeasurementOutlineDraft.Template -> {
                if (selected != null && draft.shape == "l_shaped" && measurementWallParameter(draft.shape, selected) == null)
                    Text(measurementWallRelation(selected), style = MaterialTheme.typography.bodySmall)
                val keys = CeilingShapeTemplates.template(outline.shape)!!.parameterKeys
                keys.filter { !compact || selected != null && it in measurementWallParameters(draft.shape, selected) }.forEach { key ->
                    val side = measurementParameterWall(draft.shape, key)
                    MeasurementLengthField(outline.fields.getValue(key), measurementTemplateFieldLabel(draft.shape, key),
                        validation.fieldErrors[key], enabled,
                        selected = selected != null && key in measurementWallParameters(draft.shape, selected),
                        onFocus = inputFocus(side), onChange = { model.accept(MeasurementCreateIntent.Dimension(key, it), grouped = true) })
                }
            }
            is MeasurementOutlineDraft.Orthogonal -> {
                val labels = measurementSideLabels(outline.sides.size)
                outline.sides.forEachIndexed { i, side ->
                    if (!compact || selected == i) key(side.inputId) {
                        MeasurementLengthField(side.length, "Сторона ${labels[i]}, м", validation.fieldErrors[side.inputId], enabled,
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
