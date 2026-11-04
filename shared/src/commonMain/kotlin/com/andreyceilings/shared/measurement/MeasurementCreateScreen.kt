package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.ui.CeilingsScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

/** Hosts supply their current precision/session, not platform-specific form or write logic. */
@Composable
fun MeasurementCreateScreen(
    projectId: Long, precision: MeasurementPrecision, store: MeasurementCreateStore?,
    contentPadding: PaddingValues, systemTopInset: Dp,
    onBack: () -> Unit, onSaved: (Long) -> Unit,
    navigationGuard: MeasurementCreateNavigationGuard? = null, backLabel: String = "До об'єкта"
) {
    // Preserve the restored draft/UUID until the unlocked session is available.
    // Consuming it against a transient null store would reset it when startup completes.
    if (store == null) {
        val direction = LocalLayoutDirection.current
        val start = contentPadding.calculateStartPadding(direction)
        val end = contentPadding.calculateEndPadding(direction)
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
                    Text("Новий замір", Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.titleLarge)
                }
            }, bottomGradient = false
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text("Захищена база недоступна або завантажується.")
            }
        }
        return
    }
    key(projectId) {
        val saver = remember(store) { Saver<MeasurementCreateModel, String>(
            save = { it.state.value.draft.snapshot() },
            restore = { snapshot -> MeasurementCreateModel.restore(store, snapshot, projectId) }
        ) }
        // Capture the setting once. A restored draft keeps its original resolution.
        val model = rememberSaveable(store, saver = saver) {
            MeasurementCreateModel(store, MeasurementCreateDraft(projectId, precision))
        }
        // The form registry key stays stable across Activity recreation, while
        // replacing an unlocked session must immediately clear its old parent data.
        val parent = key(store) {
            val parentFlow = remember { store.observeParent(projectId) }
            parentFlow.collectAsState(MeasurementCreateParent.Loading).value
        }
        MeasurementCreateScreen(model, parent, contentPadding, systemTopInset, onBack, onSaved, navigationGuard, backLabel)
    }
}

/** Stateful presentation consumes exactly one draft; drawing/totals are derived, never editable copies. */
@Composable
fun MeasurementCreateScreen(
    model: MeasurementCreateModel, parent: MeasurementCreateParent,
    contentPadding: PaddingValues = PaddingValues(16.dp), systemTopInset: Dp = 0.dp,
    onBack: () -> Unit, onSaved: (Long) -> Unit,
    navigationGuard: MeasurementCreateNavigationGuard? = null, backLabel: String = "До об'єкта"
) {
    DisposableEffect(model, navigationGuard) {
        navigationGuard?.bind(model)
        onDispose { navigationGuard?.unbind(model) }
    }
    val state by model.state.collectAsState()
    val draft = state.draft
    val validation = remember(draft) { validateMeasurementCreate(draft) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val currentSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(state.savedId) { state.savedId?.let { currentSaved(it); model.delivered() } }
    val enabled = !state.saving && state.savedId == null && !state.cancelled
    val direction = LocalLayoutDirection.current
    val start = contentPadding.calculateStartPadding(direction)
    val end = contentPadding.calculateEndPadding(direction)
    fun cancel() { if (model.cancel()) { focus.clearFocus(); onBack() } }
    CeilingsScreenScaffold(
        windowInsets = WindowInsets(0, 0, 0, 0),
        contentPadding = PaddingValues(start = start, end = end, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding()),
        header = { Spacer(Modifier.height(systemTopInset + 8.dp)) },
        pinned = {
            Column(Modifier.fillMaxWidth().padding(start = start, end = end),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CeilingsFab(onClick = ::cancel, actionLabel = backLabel,
                        enabled = enabled, profile = CeilingsFabProfile.COMPACT) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                    Text("Новий замір", Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.titleLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::cancel, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Скасувати") }
                    Button(onClick = {
                        focus.clearFocus()
                        // Set saving before this input callback returns, so an immediate
                        // system Back/Escape/tab cannot outrun the shared exit guard.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { model.save() }
                    },
                        enabled = enabled && validation.canSave && parent is MeasurementCreateParent.Active,
                        modifier = Modifier.weight(1f)) { Text(if (state.saving) "Збереження…" else "Зберегти") }
                }
                if (state.saving) Text("Збереження триває. Дочекайтеся результату.",
                    style = MaterialTheme.typography.bodySmall)
                else if (state.savedId != null) Text("Замір збережено. Відкриття…",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        bottomGradient = false
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("project") {
                Text(when (parent) {
                    MeasurementCreateParent.Loading -> "Завантаження об'єкта…"
                    MeasurementCreateParent.Missing -> "Об'єкт не знайдено або його видалено. Збереження недоступне."
                    MeasurementCreateParent.Unavailable -> "Захищена база недоступна або заблокована. Збереження недоступне."
                    is MeasurementCreateParent.Active -> "Об'єкт: ${parent.title}"
                })
                Text("Точність заміру: ${draft.precision.label}", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item("name") {
                OutlinedTextField(draft.name, { model.accept(MeasurementCreateIntent.Name(it)) },
                    label = { Text("Назва заміру *") }, singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), supportingText = {
                        validation.nameError?.let { Text(it) }
                    })
            }
            item("shape") {
                MeasurementShapePicker(draft.shape, enabled) { model.accept(MeasurementCreateIntent.Shape(it)) }
            }
            item("drawing") {
                val geometry = validation.geometry
                if (geometry == null) Text("Креслення недоступне: ${validation.geometryError.orEmpty()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    MeasurementDrawing(MeasurementGeometryView.Captured(geometry), Modifier.fillMaxWidth())
                    Text("Площа: ${formatMeasurementArea(geometry.areaSquareMeters, draft.precision)} м² · " +
                        "Периметр: ${formatMeasurementLength(geometry.perimeterMeters, draft.precision)} м")
                }
            }
            when (val outline = draft.outline) {
                is MeasurementOutlineDraft.Captured -> item("captured-source") { Text("Збережений контур без виміряних сторін. Оберіть форму й явно введіть вимірювання, щоб замінити його.") }
                is MeasurementOutlineDraft.Template -> {
                    val template = requireNotNull(CeilingShapeTemplates.template(outline.shape))
                    items(template.parameterKeys.size, key = { "dimension:${outline.shape}:${template.parameterKeys[it]}" }) { i ->
                        val key = template.parameterKeys[i]
                        MeasurementLengthField(outline.fields.getValue(key),
                            measurementTemplateFieldLabel(outline.shape, key), validation.fieldErrors[key], enabled,
                            onChange = { model.accept(MeasurementCreateIntent.Dimension(key, it)) })
                    }
                }
                is MeasurementOutlineDraft.Orthogonal -> {
                    val labels = measurementSideLabels(outline.sides.size)
                    item("instructions") {
                        Text("Введіть усі виміряні сторони за порядком обходу, включно з останньою. " +
                            "Укажіть поворот після кожної сторони. Контур має замкнутися без доданих сторін.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    itemsIndexed(outline.sides, key = { _, side -> side.inputId }) { i, side ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MeasurementLengthField(side.length, "Сторона ${labels[i]}, м",
                                    validation.fieldErrors[side.inputId], enabled, Modifier.weight(1f),
                                    onChange = { model.accept(MeasurementCreateIntent.SideLength(i, it)) })
                                IconButton(onClick = { model.accept(MeasurementCreateIntent.RemoveSide(i)) },
                                    enabled = enabled && outline.sides.size > 4) {
                                    Icon(Icons.Default.Delete, "Видалити сторону ${labels[i]}")
                                }
                            }
                            Text("Поворот після ${labels[i]}", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OrthogonalTurn.entries.forEach { turn ->
                                    FilterChip(selected = side.turn == turn,
                                        onClick = { model.accept(MeasurementCreateIntent.SideTurn(i, turn)) },
                                        enabled = enabled, label = {
                                            Text(if (turn == OrthogonalTurn.LEFT) "↶ Ліворуч 90°" else "↷ Праворуч 90°")
                                        })
                                }
                            }
                        }
                    }
                    item("add-side") {
                        OutlinedButton(onClick = { model.accept(MeasurementCreateIntent.AddSide) },
                            enabled = enabled && outline.sides.size < CeilingPolygonCalculator.MAX_VERTICES) { Text("Додати сторону") }
                    }
                }
            }
            item("notes") {
                OutlinedTextField(draft.notes, { model.accept(MeasurementCreateIntent.Notes(it)) },
                    enabled = enabled, label = { Text("Примітки (необов'язково)") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun MeasurementLengthField(value: String, label: String, error: String?, enabled: Boolean,
    modifier: Modifier = Modifier, selected: Boolean = false, onFocus: (Boolean) -> Unit = {}, onChange: (String) -> Unit) {
    // A decimal IME hint does not guarantee the user's keyboard offers comma or dot.
    // Keep an accessible separator action and preserve the actual caret selection.
    val requester = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    LaunchedEffect(value) {
        if (field.text != value) {
            field = field.copy(text = value, selection = TextRange(
                field.selection.start.coerceIn(0, value.length), field.selection.end.coerceIn(0, value.length)))
        }
    }
    OutlinedTextField(value = field, onValueChange = { next ->
        field = next
        onChange(next.text)
    }, label = { Text(label) }, enabled = enabled, singleLine = true,
        modifier = modifier.fillMaxWidth().focusRequester(requester)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)) else Modifier)
            .onFocusChanged { onFocus(it.isFocused) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = {
            IconButton(onClick = {
                val a = field.selection.min.coerceIn(0, field.text.length)
                val b = field.selection.max.coerceIn(0, field.text.length)
                val unselected = field.text.substring(0, a) + field.text.substring(b)
                if ('.' !in unselected && ',' !in unselected) {
                    val text = field.text.substring(0, a) + "," + field.text.substring(b)
                    field = TextFieldValue(text, selection = TextRange(a + 1))
                    onChange(text)
                }
                requester.requestFocus()
            }, enabled = enabled, modifier = Modifier.semantics {
                contentDescription = "Вставити десяткову кому"
            }) { Text(",") }
        },
        isError = value.isNotEmpty() && error != null,
        supportingText = { error?.let { Text(it) } })
}

@Composable
internal fun MeasurementShapePicker(shape: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Форма: ${CeilingShapeTemplates.template(shape)?.title ?: "Багатокутник (прямі кути)"}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            (CeilingShapeTemplates.templates.map { it.shapeType to it.title } +
                ("polygon" to "Багатокутник (прямі кути)")).forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onChange(id); expanded = false })
            }
        }
    }
}
