package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.prices.QuantityMode
import com.andreyceilings.shared.prices.label

/** Hoisted section and dialog APIs are shared by production hosts and Compose scene tests. */
@Composable
fun MeasurementElementsSection(state: MeasurementElementsState, onAdd: () -> Unit,
    onOpen: (MeasurementElement, ElementEditorAction) -> Unit, onRetry: () -> Unit,
    onPlace: ((MeasurementElement) -> Unit)? = null, placementUnavailable: String? = null,
    selectedElementUuid: String? = null, onPlaceLine: ((MeasurementElement)->Unit)? = null, onPlaceArea: ((MeasurementElement)->Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Елементи стелі", style = MaterialTheme.typography.titleLarge)
        when (state) {
            MeasurementElementsState.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Завантаження елементів…") }
            MeasurementElementsState.Unavailable -> {
                Text("Захищена база недоступна або заблокована.")
                TextButton(onClick = onRetry) { Text("Повторити") }
            }
            MeasurementElementsState.Missing -> Text("Замір або об'єкт уже недоступний.")
            is MeasurementElementsState.Loaded -> {
                if (state.items.isEmpty()) Text("Додайте позицію прайсу або запишіть побажання — кількість можна уточнити пізніше.")
                else Text("Записів: ${state.items.size}", style = MaterialTheme.typography.bodySmall)
                state.items.forEach { element -> key(element.uuid) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = if (element.uuid == selectedElementUuid) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(element.label, style = MaterialTheme.typography.titleMedium)
                            val source = element.source as? ElementSource.CatalogSelection
                            Text(if (source == null) "Побажання" else "З прайсу · ${source.code} · ${element.unit}")
                            Text(formatElementQuantity(element.quantity, element.unit))
                            if (element.note.isNotBlank()) Text(element.note)
                            Text(if (element.approval == ElementApproval.APPROVED) "Погоджено" else "Обговорюється")
                            val capacity = element.pointCapacity()
                            Text(if (capacity != null) "Розміщено: ${element.placedQuantityMilli / 1000} з $capacity" else if(element.supportsLinePlacement()) "Заявлено: ${formatElementQuantity(element.quantity,element.unit)} · На кресленні ${formatElementMilli(element.placedQuantityMilli)} м" else if(element.supportsAreaPlacement()) "Геометрична кількість: ${formatElementMilli(element.placedQuantityMilli)} ${element.unit} · ${element.placement}" else "Не розміщено", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if(element.supportsAreaPlacement() && onPlaceArea!=null) {
                                Text("Заявлено: ${formatElementQuantity(element.quantity,element.unit)} · На кресленні: ${formatElementMilli(element.placedQuantityMilli)} ${element.unit}")
                                TextButton(enabled=placementUnavailable==null,onClick={onPlaceArea(element)}) {Text("Розмістити область")}
                            } else if(element.supportsLinePlacement() && onPlaceLine!=null) {
                                TextButton(enabled=placementUnavailable==null,onClick={onPlaceLine(element)}) {Text("Розмістити лінію")}
                            } else if (onPlace != null) {
                                val explanation = (if(source?.placementKind==com.andreyceilings.shared.prices.PlacementKind.LINE) linePlacementExplanation(element) else pointPlacementExplanation(element)) ?: placementUnavailable
                                if (explanation != null) Text(explanation, style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = explanation == null && capacity != null && element.placedQuantityMilli / 1000 < capacity,
                                    onClick = { onPlace(element) }) { Text("Розмістити") }
                            }
                            if (source?.availability == CatalogAvailability.DELETED_OR_MISSING)
                                Text("Позиція більше недоступна у прайсі", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpen(element, ElementEditorAction.EDIT) }) { Text("Редагувати") }
                                TextButton(onClick = { onOpen(element, ElementEditorAction.DELETE) }) { Text("Видалити") }
                                if (source == null) TextButton(onClick = { onOpen(element, ElementEditorAction.LINK) }) { Text("Прив'язати до прайсу") }
                            }
                        }
                    }
                } }
                Button(onClick = onAdd) { Text("Додати елемент") }
            }
        }
    }
}

@Composable
fun MeasurementElementEditor(
    state: ElementEditorState, catalog: ElementCatalogState, parentAvailable: Boolean,
    onChange: ((ElementEditorDraft) -> ElementEditorDraft) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit, onRefreshCatalog: () -> Unit,
    onContinue: () -> Unit, onDiscard: () -> Unit,
    onRequestReload: () -> Unit, onCancelReload: () -> Unit, onReload: () -> Unit
) {
    if (state.exitRequested) {
        AlertDialog(onDismissRequest = onContinue, title = { Text("Відхилити зміни?") },
            text = { Text("Чернетку буде втрачено. Якщо результат попереднього створення невідомий, спочатку перевірте список елементів.") },
            confirmButton = { TextButton(onClick = onDiscard) { Text("Відхилити зміни") } },
            dismissButton = { TextButton(onClick = onContinue) { Text("Продовжити") } })
        return
    }
    if (state.reloadRequested) {
        AlertDialog(onDismissRequest = onCancelReload, title = { Text("Замінити чернетку актуальними даними?") },
            text = { Text("Введені зміни буде замінено записом із бази. Перезавантаження нічого не зберігає.") },
            confirmButton = { TextButton(onClick = onReload) { Text("Замінити чернетку") } },
            dismissButton = { TextButton(onClick = onCancelReload) { Text("Скасувати") } })
        return
    }
    val draft = state.draft
    val enabled = !state.saving && !state.conflict && !state.attemptedCreate && parentAvailable
    val focus = LocalFocusManager.current
    val title = when (draft.action) {
        ElementEditorAction.CHOOSE -> "Додати елемент"
        ElementEditorAction.CREATE_WISH -> "Нове побажання"
        ElementEditorAction.CREATE_CATALOG -> "Елемент із прайсу"
        ElementEditorAction.EDIT -> "Редагувати елемент"
        ElementEditorAction.LINK -> "Прив'язати до прайсу"
        ElementEditorAction.DELETE -> "Видалити елемент?"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!parentAvailable) Text("Замір або захищена база недоступні. Збереження заблоковано.")
                if (state.saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Збереження…") }
                if (draft.action == ElementEditorAction.CHOOSE) {
                    Button(enabled = enabled, onClick = { onChange { it.copy(action = ElementEditorAction.CREATE_CATALOG) } }) { Text("З прайсу") }
                    OutlinedButton(enabled = enabled, onClick = { onChange { it.copy(action = ElementEditorAction.CREATE_WISH) } }) { Text("Побажання") }
                } else if (draft.action == ElementEditorAction.DELETE) {
                    Text("Видалити «${draft.label}»? Сам замір залишиться.")
                } else {
                    if (draft.action == ElementEditorAction.CREATE_CATALOG || draft.action == ElementEditorAction.LINK)
                        ElementCatalogPicker(catalog, draft.selection, enabled, onRefreshCatalog) { item -> onChange { it.copy(selection = item) } }
                    if (draft.action == ElementEditorAction.LINK) {
                        Text("Побажання: ${draft.original?.label}")
                        Text("Поточна кількість: ${formatElementQuantity(checkNotNull(draft.original).quantity, draft.original.unit)}")
                        if (!draft.compatibleKeep && draft.selection != null) Text("Кількість або одиниця несумісні. Потрібна явна заміна кількості.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(!draft.replaceQuantity, enabled = enabled && draft.compatibleKeep,
                                onClick = { onChange { it.copy(replaceQuantity = false) } }, label = { Text("Зберегти кількість") })
                            FilterChip(draft.replaceQuantity, enabled = enabled,
                                onClick = { onChange { it.copy(replaceQuantity = true) } }, label = { Text("Задати нову кількість") })
                        }
                        Text("Прив'язування збереже UUID, примітку й погодження. Підтвердіть вибрану позицію та кількість.")
                    } else {
                        if (!draft.catalog) {
                            OutlinedTextField(draft.label, { value -> onChange { it.copy(label = value) } },
                                enabled = enabled, label = { Text("Назва побажання") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.unit, { value -> onChange { it.copy(unit = value) } },
                                enabled = enabled, label = { Text("Одиниця (шт., м.п., м² або власна)") }, modifier = Modifier.fillMaxWidth())
                            if (draft.unit.isNotBlank() && draft.input.kind == ElementQuantityKind.UNKNOWN)
                                TextButton(enabled = enabled, onClick = { onChange { it.copy(unit = "") } }) { Text("Очистити одиницю") }
                        } else if (draft.action == ElementEditorAction.EDIT) {
                            Text(draft.label)
                            Text("${(draft.original?.source as? ElementSource.CatalogSelection)?.code} · ${draft.unit}")
                            if ((draft.original?.source as? ElementSource.CatalogSelection)?.availability == CatalogAvailability.DELETED_OR_MISSING)
                                Text("Позиція більше недоступна у прайсі. Збережені параметри залишаються чинними.")
                        }
                        OutlinedTextField(draft.note, { value -> onChange { it.copy(note = value) } },
                            enabled = enabled, label = { Text("Примітка") }, modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ElementApproval.entries.forEach { approval ->
                                FilterChip(draft.approval == approval, enabled = enabled,
                                    onClick = { onChange { it.copy(approval = approval) } },
                                    label = { Text(if (approval == ElementApproval.APPROVED) "Погоджено" else "Обговорюється") })
                            }
                        }
                    }
                    if (draft.action != ElementEditorAction.LINK || draft.replaceQuantity)
                        ElementQuantityFields(draft.input, draft.quantityUnit, draft.mode,
                            enabled && (!draft.catalog || draft.action == ElementEditorAction.EDIT || draft.selection != null)) { input ->
                            onChange { it.copy(input = input) }
                        }
                    draft.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (state.attemptedCreate) Text("Результат створення невідомий. Повторити можна тільки ті самі дані з тим самим UUID; або скасуйте й перевірте список.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.conflict) TextButton(enabled = !state.saving, onClick = onRequestReload) { Text("Перезавантажити актуальні дані") }
            }
        },
        confirmButton = {
            if (draft.action != ElementEditorAction.CHOOSE) TextButton(onClick = { focus.clearFocus(); onSave() },
                enabled = parentAvailable && !state.saving && !state.conflict && draft.validationError == null) {
                Text(when (draft.action) {
                    ElementEditorAction.DELETE -> "Підтвердити видалення"
                    ElementEditorAction.LINK -> "Підтвердити прив'язування"
                    else -> if (state.attemptedCreate) "Повторити збереження" else "Зберегти"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Скасувати") } })
}

@Composable
private fun ElementQuantityFields(input: ElementQuantityInput, unit: String?, mode: QuantityMode?, enabled: Boolean,
    onChange: (ElementQuantityInput) -> Unit) {
    if (mode == QuantityMode.CEILING_AREA || mode == QuantityMode.CEILING_PERIMETER) {
        Text(checkNotNull(mode).label)
        return
    }
    Text(if (mode == QuantityMode.ENTERED_LENGTH) "Довжина, м.п." else "Кількість${unit?.let { ", $it" }.orEmpty()}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ElementQuantityKind.entries.forEach { kind -> FilterChip(input.kind == kind,
            enabled = enabled, onClick = { onChange(input.copy(kind = kind)) }, label = { Text(kind.label) }) }
    }
    @Composable fun field(text: String, label: String, change: (String) -> Unit) {
        // Keep the raw draft verbatim, including a trailing comma/dot. Validation never edits text.
        OutlinedTextField(text, change, enabled = enabled, label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = enabled && text.isNotEmpty() && text.all { it in '0'..'9' },
                onClick = { change("$text,") }) { Text("Вставити кому") }
            TextButton(enabled = enabled && text.isNotEmpty() && text.all { it in '0'..'9' },
                onClick = { change("$text.") }) { Text("Вставити крапку") }
        }
    }
    when (input.kind) {
        ElementQuantityKind.UNKNOWN -> Unit
        ElementQuantityKind.EXACT -> field(input.exact, "Точна кількість") { onChange(input.copy(exact = it)) }
        ElementQuantityKind.RANGE -> {
            field(input.minimum, "Від") { onChange(input.copy(minimum = it)) }
            field(input.maximum, "До") { onChange(input.copy(maximum = it)) }
        }
    }
}

@Composable
private fun ElementCatalogPicker(state: ElementCatalogState, selection: ElementCatalogItem?, enabled: Boolean,
    onRefresh: () -> Unit, onSelect: (ElementCatalogItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var groupUuid by remember { mutableStateOf<String?>(null) }
    var groupsExpanded by remember { mutableStateOf(false) }
    when (state) {
        ElementCatalogState.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Завантаження каталогу…") }
        ElementCatalogState.Unavailable -> {
            Text("Каталог недоступний")
            TextButton(onClick = onRefresh, enabled = enabled) { Text("Оновити каталог") }
        }
        is ElementCatalogState.Loaded -> {
            OutlinedTextField(query, { query = it }, enabled = enabled, label = { Text("Пошук за назвою або кодом") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Box {
                OutlinedButton(onClick = { groupsExpanded = true }, enabled = enabled) {
                    Text(state.groups.find { it.uuid == groupUuid }?.name ?: if (groupUuid == null) "Усі категорії" else "Категорія недоступна")
                }
                DropdownMenu(groupsExpanded, onDismissRequest = { groupsExpanded = false }) {
                    DropdownMenuItem(text = { Text("Усі категорії") }, onClick = { groupUuid = null; groupsExpanded = false })
                    state.groups.forEach { group -> DropdownMenuItem(text = { Text(group.name) },
                        onClick = { groupUuid = group.uuid; groupsExpanded = false }) }
                }
            }
            val items = state.filtered(query, groupUuid)
            if (items.isEmpty()) Text(if (query.isNotBlank()) "Не знайдено" else "Немає позицій")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items, key = { it.uuid }) { item ->
                    OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = { onSelect(item) }) {
                        Column { Text(item.title); Text("${item.unit} · ${item.mode.label} · ${state.groups.find { it.uuid == item.groupUuid }?.name.orEmpty()}") }
                    }
                }
            }
            selection?.let {
                Text("Обрано: ${it.title} · ${it.unit} · ${it.mode.label}")
                if (state.items.find { item -> item.uuid == it.uuid } != it)
                    Text("Позиція змінилася або недоступна. Оновіть вибір явно; збереження перевірить актуальні дані.")
            }
            TextButton(enabled = enabled, onClick = onRefresh) { Text("Оновити каталог") }
        }
    }
}
