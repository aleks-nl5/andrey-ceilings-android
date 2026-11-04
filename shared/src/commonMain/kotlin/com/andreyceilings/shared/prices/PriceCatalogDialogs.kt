package com.andreyceilings.shared.prices

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class PriceGroupEditor(
    val original: PriceCatalogGroup? = null,
    val fromItem: Boolean = false,
    val initial: PriceGroupDraft = original?.let(PriceGroupDraft::from) ?: PriceGroupDraft(),
    val draft: PriceGroupDraft = initial
)

private enum class EditorConfirmation { DISCARD, UNIT }

@Composable
internal fun PriceEditorDialog(
    editor: PriceItemEditor,
    groups: List<PriceCatalogGroup>,
    unitOptions: List<String>,
    pending: Boolean,
    databaseAvailable: Boolean,
    error: String?,
    onChange: (PriceItemEditor) -> Unit,
    onDismiss: () -> Unit,
    onNewCategory: () -> Unit,
    onSubmit: () -> Unit
) {
    val draft = editor.draft
    val enabled = databaseAvailable && !pending
    var confirmation by remember { mutableStateOf<EditorConfirmation?>(null) }
    val requestDismiss = {
        if (!pending) {
            if (draft.hasChanges(editor.initial, editor.quick)) confirmation = EditorConfirmation.DISCARD
            else onDismiss()
        }
    }
    when (confirmation) {
        EditorConfirmation.DISCARD -> {
            CatalogConfirmation("Відкинути зміни?", "Введені зміни цієї позиції ще не збережено.",
                "Відкинути", onDismiss, { confirmation = null })
            return
        }
        EditorConfirmation.UNIT -> {
            CatalogConfirmation("Змінити одиницю вимірювання?",
                "Це може заблокувати майбутній перерахунок старих кошторисів. Історичні дані не змінюються. Автоматичної конвертації кількостей немає.",
                "Підтвердити зміну", { confirmation = null; onSubmit() }, { confirmation = null }, enabled)
            return
        }
        null -> Unit
    }
    val validation = if (editor.quick) {
        if (PriceCatalogRules.parseSalePrice(draft.salePriceText) == null)
            "Вкажіть ціну продажу від 0 до 999 999 999,99 (до 2 знаків після коми)." else null
    } else draft.validationError(groups)
    val focusRequesters = remember { listOf("title", "unit", "price", "category", "quantity", "placement")
        .associateWith { FocusRequester() } }
    // Restores the last field after a sequential child form/confirmation, without stealing focus on edits.
    LaunchedEffect(Unit) { focusRequesters[editor.focusField]?.requestFocus() }
    fun fieldFocus(field: String) = Modifier.focusRequester(checkNotNull(focusRequesters[field]))
        .onFocusChanged { if (it.isFocused && editor.focusField != field) onChange(editor.copy(focusField = field)) }
    fun change(value: ConfiguredPriceDraft) = onChange(editor.copy(draft = value))
    val group = groups.find { it.uuid == draft.groupUuid }
    var price by remember { mutableStateOf(TextFieldValue(draft.salePriceText)) }
    var priceTapRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(priceTapRequests) {
        if (priceTapRequests > 0) {
            withFrameNanos { }
            price = price.copy(selection = TextRange(0, price.text.length))
        }
    }
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(when {
            editor.quick -> "Змінити ціну продажу"
            editor.original == null -> "Нова позиція прайсу"
            else -> "Редагувати позицію"
        }) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editor.quick) Text(editor.original?.title.orEmpty()) else {
                    CatalogChoice("Категорія *", group?.name ?: if (draft.groupUuid.isBlank()) "Оберіть категорію" else "Категорію видалено",
                        groups, { it.name }, { change(draft.selectGroup(it)) }, enabled, fieldFocus("category"))
                    TextButton(enabled = enabled, onClick = onNewCategory) { Text("Нова категорія…") }
                    OutlinedTextField(value = draft.title, onValueChange = { change(draft.copy(title = it)) },
                        label = { Text("Назва *") }, singleLine = true, enabled = enabled,
                        isError = error == "Позиція з такою назвою вже існує" || draft.title.isBlank(),
                        modifier = Modifier.fillMaxWidth().then(fieldFocus("title")))
                    CatalogUnitField(draft.unit, unitOptions, enabled, fieldFocus("unit")) {
                        change(draft.copy(unit = it, parametersEdited = true))
                    }
                    CatalogChoice("Кількість", draft.quantityMode.label, QuantityMode.entries, { it.label },
                        { change(draft.copy(quantityMode = it, parametersEdited = true)) }, enabled, fieldFocus("quantity"))
                    CatalogChoice("Розміщення", draft.placementKind.label, PlacementKind.entries, { it.label },
                        { change(draft.copy(placementKind = it, parametersEdited = true)) }, enabled, fieldFocus("placement"))
                    if (group != null) TextButton(enabled = enabled,
                        onClick = { change(draft.withDefaults(group)) }) { Text("Взяти типові параметри категорії") }
                    Text("Це параметри майбутніх замірів.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = price,
                    onValueChange = { price = it; change(draft.copy(salePriceText = it.text)) },
                    label = { Text("Ціна продажу, ₴ *") }, singleLine = true, enabled = enabled,
                    isError = PriceCatalogRules.parseSalePrice(draft.salePriceText) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().then(fieldFocus("price"))
                        .onFocusChanged { if (it.isFocused) priceTapRequests++ }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                do {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Final)
                                    val pressed = event.changes.any { it.pressed }
                                } while (pressed)
                                priceTapRequests++
                            }
                        })
                Text("Зміни прайсу не змінюють історичні кошториси.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!databaseAvailable) Text("Захищена база недоступна. Збереження тимчасово вимкнено.",
                    color = MaterialTheme.colorScheme.error)
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(enabled = enabled && validation == null, onClick = {
            if (!editor.quick && editor.original != null && draft.unit.trim() != editor.original.unit)
                confirmation = EditorConfirmation.UNIT
            else onSubmit()
        }) { Text(if (pending) "Збереження…" else "Зберегти") } },
        dismissButton = { TextButton(enabled = !pending, onClick = requestDismiss) { Text("Скасувати") } }
    )
}

@Composable
internal fun PriceGroupsDialog(
    groups: List<PriceCatalogGroup>, enabled: Boolean,
    onDismiss: () -> Unit, onCreate: () -> Unit,
    onEdit: (PriceCatalogGroup) -> Unit, onDelete: (PriceCatalogGroup) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Категорії") },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (groups.isEmpty()) item { Text("Категорій поки немає. Створіть першу категорію.") }
            items(groups, key = { it.uuid }) { group ->
                Column(Modifier.fillMaxWidth()) {
                    Text(group.name, style = MaterialTheme.typography.titleSmall)
                    Text("${group.defaultUnit ?: "Одиниця вручну"} · ${group.defaultQuantityMode.label} · ${group.defaultPlacementKind.label}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(enabled = enabled, onClick = { onEdit(group) }) { Text("Змінити") }
                        TextButton(enabled = enabled, onClick = { onDelete(group) }) { Text("Видалити") }
                    }
                }
            }
        } },
        confirmButton = { Button(enabled = enabled, onClick = onCreate) { Text("Нова категорія…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрити") } }
    )
}

@Composable
internal fun PriceGroupEditorDialog(
    editor: PriceGroupEditor, enabled: Boolean, pending: Boolean, error: String?,
    onChange: (PriceGroupEditor) -> Unit, onDismiss: () -> Unit, onSubmit: () -> Unit
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestDismiss = {
        if (!pending) {
            if (editor.draft != editor.initial) confirmDiscard = true else onDismiss()
        }
    }
    if (confirmDiscard) {
        CatalogConfirmation("Відкинути зміни?", "Зміни категорії ще не збережено.", "Відкинути",
            onDismiss, { confirmDiscard = false })
        return
    }
    val draft = editor.draft
    val validation = draft.validationError()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    fun change(value: PriceGroupDraft) = onChange(editor.copy(draft = value))
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (editor.original == null) "Нова категорія" else "Редагувати категорію") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft.name, onValueChange = { change(draft.copy(name = it)) },
                label = { Text("Назва *") }, singleLine = true, enabled = enabled,
                isError = error == "Категорія з такою назвою вже існує" || draft.name.isBlank(),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
            CatalogUnitField(draft.defaultUnit, PriceCatalogRules.standardUnits, enabled,
                label = "Типова одиниця (необов’язково)") { change(draft.copy(defaultUnit = it)) }
            CatalogChoice("Типова кількість", draft.defaultQuantityMode.label, QuantityMode.entries, { it.label },
                { change(draft.copy(defaultQuantityMode = it)) }, enabled)
            CatalogChoice("Типове розміщення", draft.defaultPlacementKind.label, PlacementKind.entries, { it.label },
                { change(draft.copy(defaultPlacementKind = it)) }, enabled)
            Text("Типові параметри копіюються лише в нові чернетки. Наявні позиції не змінюються.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Це параметри майбутніх замірів.", style = MaterialTheme.typography.bodySmall)
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(enabled = enabled && validation == null, onClick = onSubmit) {
            Text(if (pending) "Збереження…" else "Зберегти")
        } },
        dismissButton = { TextButton(enabled = !pending, onClick = requestDismiss) { Text("Скасувати") } }
    )
}

@Composable
private fun CatalogUnitField(
    value: String, options: List<String>, enabled: Boolean, modifier: Modifier = Modifier,
    label: String = "Одиниця *", onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
            singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().then(modifier),
            trailingIcon = { TextButton(enabled = enabled, onClick = { expanded = true }) { Text("▾") } })
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) },
                onClick = { expanded = false; onChange(option) }) }
        }
    }
}

@Composable
private fun <T> CatalogChoice(
    label: String, value: String, options: List<T>, name: (T) -> String, onSelect: (T) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().then(modifier)) {
                Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾")
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(name(option)) },
                    onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}

@Composable
private fun CatalogConfirmation(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, enabled: Boolean = true
) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(enabled = enabled, onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Продовжити редагування") } })
}
