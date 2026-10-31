package com.andreyceilings.shared.prices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class PriceEditorMode { FULL, QUICK }

/**
 * Real CMP price-list UI. Does not own a database, Android Context, or a platform-specific
 * file API; Android and desktop hosts supply a SQLDelight-backed list and suspend actions.
 */
@Composable
fun PriceCatalogScreen(
    items: List<PriceCatalogItem>,
    databaseAvailable: Boolean,
    onSave: suspend (original: PriceCatalogItem?, draft: PriceCatalogDraft) -> Unit,
    onDelete: suspend (item: PriceCatalogItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp)
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PriceCatalogItem?>(null) }
    var editorMode by remember { mutableStateOf<PriceEditorMode?>(null) }
    var deleting by remember { mutableStateOf<PriceCatalogItem?>(null) }
    var pending by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    val categories = remember(items) { PriceCatalogRules.categories(items) }
    val categoryOptions = remember(items) { PriceCatalogRules.categoryOptions(items) }
    val unitOptions = remember(items) { PriceCatalogRules.unitOptions(items) }
    val visible = remember(items, search, selectedCategory) {
        PriceCatalogRules.filter(items, search, selectedCategory)
    }

    fun openEditor(item: PriceCatalogItem?, mode: PriceEditorMode) {
        if (!pending) {
            editing = item
            editorMode = mode
            editorError = null
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val desktopLayout = maxWidth >= 760.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(if (desktopLayout) 6.dp else 7.dp)
        ) {
            item(key = "heading") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Прайс-лист", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("Ціни для нових кошторисів",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { openEditor(null, PriceEditorMode.FULL) },
                        enabled = databaseAvailable && !pending) { Text("Додати") }
                }
            }
            if (!databaseAvailable) {
                item(key = "unavailable") {
                    Text("Захищена база ще відкривається або недоступна. Зміни прайсу тимчасово вимкнено.",
                        color = MaterialTheme.colorScheme.error)
                }
            } else {
                item(key = "search") {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        label = { Text("Пошук за назвою або кодом") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                item(key = "categories") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all") {
                            FilterChip(selected = selectedCategory == null,
                                onClick = { selectedCategory = null }, label = { Text("Усі") })
                        }
                        items(categories, key = { "cat-$it" }) { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                label = { Text(category) }
                            )
                        }
                    }
                }
                if (desktopLayout && visible.isNotEmpty()) {
                    item(key = "columns") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Назва", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("Од.", modifier = Modifier.width(76.dp), fontWeight = FontWeight.SemiBold)
                            Text("Ціна продажу", modifier = Modifier.width(126.dp),
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(156.dp))
                        }
                    }
                }
                when {
                    items.isEmpty() -> item(key = "empty") {
                        Text("Прайс порожній. Додайте першу позицію.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    visible.isEmpty() -> item(key = "filtered-empty") {
                        Text("За цими умовами нічого не знайдено.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> items(visible, key = { "price-${it.id}" }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            if (desktopLayout) {
                                Row(Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable {
                                        openEditor(item, PriceEditorMode.FULL)
                                    }) {
                                        Text(item.title, fontWeight = FontWeight.Medium)
                                        Text(item.category, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(item.unit, modifier = Modifier.width(76.dp))
                                    TextButton(
                                        onClick = { openEditor(item, PriceEditorMode.QUICK) },
                                        modifier = Modifier.width(126.dp)
                                    ) { Text("${PriceCatalogRules.formatSalePrice(item.price)} ₴") }
                                    TextButton(onClick = { openEditor(item, PriceEditorMode.FULL) }) {
                                        Text("Змінити")
                                    }
                                    TextButton(onClick = { editorError = null; deleting = item }) { Text("Видалити") }
                                }
                            } else {
                                var actionsExpanded by remember(item.id) { mutableStateOf(false) }
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column(
                                        Modifier.weight(1f).clickable { openEditor(item, PriceEditorMode.FULL) },
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "${item.category} · ${item.unit}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    TextButton(onClick = { openEditor(item, PriceEditorMode.QUICK) }) {
                                        Text("${PriceCatalogRules.formatSalePrice(item.price)} ₴")
                                    }
                                    androidx.compose.foundation.layout.Box {
                                        IconButton(
                                            onClick = { actionsExpanded = true },
                                            modifier = Modifier.semantics {
                                                contentDescription = "Дії з позицією ${item.title}"
                                            }
                                        ) {
                                            Text("⋮", style = MaterialTheme.typography.titleMedium)
                                        }
                                        DropdownMenu(
                                            expanded = actionsExpanded,
                                            onDismissRequest = { actionsExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Редагувати") },
                                                onClick = {
                                                    actionsExpanded = false
                                                    openEditor(item, PriceEditorMode.FULL)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Видалити") },
                                                onClick = {
                                                    actionsExpanded = false
                                                    editorError = null
                                                    deleting = item
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val mode = editorMode
    if (mode != null) {
        key(editing?.id, mode) {
            PriceEditorDialog(
                initial = editing,
                mode = mode,
                categoryOptions = categoryOptions,
                unitOptions = unitOptions,
                pending = pending,
                error = editorError,
                onDismiss = { if (!pending) editorMode = null },
                onSubmit = { draft ->
                    if (!pending) {
                        pending = true
                        editorError = null
                        scope.launch {
                            try {
                                onSave(editing, draft)
                                editorMode = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                editorError = "Не вдалося зберегти позицію. Перевірте дані та стан БД."
                            } finally {
                                pending = false
                            }
                        }
                    }
                }
            )
        }
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!pending) deleting = null },
            title = { Text("Видалити позицію?") },
            text = {
                Column {
                    Text(item.title)
                    Text("Історичні кошториси залишаться незмінними.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !pending, onClick = {
                    if (!pending) {
                        pending = true
                        editorError = null
                        scope.launch {
                            try {
                                onDelete(item)
                                deleting = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                editorError = "Не вдалося видалити позицію."
                            } finally {
                                pending = false
                            }
                        }
                    }
                }) { Text("Видалити") }
            },
            dismissButton = {
                TextButton(enabled = !pending, onClick = { deleting = null }) { Text("Скасувати") }
            }
        )
    }
}

@Composable
private fun PriceEditorDialog(
    initial: PriceCatalogItem?,
    mode: PriceEditorMode,
    categoryOptions: List<String>,
    unitOptions: List<String>,
    pending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (PriceCatalogDraft) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category ?: "Додаткові роботи") }
    var unit by remember { mutableStateOf(initial?.unit ?: "шт.") }
    var price by remember {
        mutableStateOf(TextFieldValue(initial?.price?.let(PriceCatalogRules::formatSalePrice) ?: ""))
    }
    var showCategories by remember { mutableStateOf(false) }
    var customCategory by remember {
        mutableStateOf(initial?.category?.let { it !in categoryOptions } == true)
    }
    var showUnits by remember { mutableStateOf(false) }
    var customUnit by remember { mutableStateOf(initial?.unit?.let { it !in unitOptions } == true) }
    var confirmUnitChange by remember { mutableStateOf(false) }
    var priceTapRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(priceTapRequests) {
        if (priceTapRequests > 0) {
            // TextField's tap handler can position the cursor AFTER onFocusChanged.
            // Reselect once the tap has been fully processed, including repeated taps
            // while the field already has focus. Don't disturb typing/IME edits.
            withFrameNanos { }
            price = price.copy(selection = TextRange(0, price.text.length))
        }
    }
    val errorMessage = if (mode == PriceEditorMode.QUICK) {
        if (PriceCatalogRules.parseSalePrice(price.text) == null) "Перевірте ціну продажу." else null
    } else PriceCatalogRules.validate(title, category, unit, price.text)

    val submit = {
        val parsed = PriceCatalogRules.parseSalePrice(price.text)
        if (parsed != null && errorMessage == null) {
            onSubmit(PriceCatalogDraft(title.trim(), category.trim(), unit.trim(), parsed))
        }
    }

    AlertDialog(
        onDismissRequest = { if (!pending) onDismiss() },
        title = { Text(when {
            mode == PriceEditorMode.QUICK -> "Змінити ціну продажу"
            initial == null -> "Нова позиція прайсу"
            else -> "Редагувати позицію"
        }) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mode == PriceEditorMode.FULL) {
                    OutlinedTextField(value = title, onValueChange = { title = it },
                        label = { Text("Назва *") }, singleLine = true, enabled = !pending)
                    Text("Категорія *", style = MaterialTheme.typography.labelMedium)
                    if (customCategory) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Власна категорія") },
                            singleLine = true,
                            enabled = !pending,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = {
                            customCategory = false
                            category = ""
                        }, enabled = !pending) {
                            Text("Обрати зі списку")
                        }
                    } else {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showCategories = true },
                                enabled = !pending,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        category.ifBlank { "Оберіть категорію" },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("▾")
                                }
                            }
                            DropdownMenu(
                                expanded = showCategories,
                                onDismissRequest = { showCategories = false }
                            ) {
                                categoryOptions.forEach { option ->
                                    DropdownMenuItem(text = { Text(option) }, onClick = {
                                        category = option
                                        showCategories = false
                                    })
                                }
                                DropdownMenuItem(text = { Text("Інша категорія…") }, onClick = {
                                    showCategories = false
                                    customCategory = true
                                    category = ""
                                })
                            }
                        }
                    }
                    Text("Одиниця вимірювання *", style = MaterialTheme.typography.labelMedium)
                    if (customUnit) {
                        OutlinedTextField(
                            value = unit, onValueChange = { unit = it },
                            label = { Text("Власна одиниця") },
                            singleLine = true, enabled = !pending,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = { customUnit = false; unit = "" }, enabled = !pending) {
                            Text("Обрати зі списку")
                        }
                    } else {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showUnits = true },
                                enabled = !pending,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(unit.ifBlank { "Оберіть одиницю" }, modifier = Modifier.weight(1f))
                                    Text("▾")
                                }
                            }
                            DropdownMenu(
                                expanded = showUnits,
                                onDismissRequest = { showUnits = false }
                            ) {
                                unitOptions.forEach { option ->
                                    DropdownMenuItem(text = { Text(option) }, onClick = {
                                        unit = option
                                        showUnits = false
                                    })
                                }
                                DropdownMenuItem(text = { Text("Інша одиниця…") }, onClick = {
                                    showUnits = false
                                    customUnit = true
                                    unit = ""
                                })
                            }
                        }
                    }
                } else {
                    Text(initial?.title.orEmpty())
                }
                OutlinedTextField(
                    value = price, onValueChange = { price = it },
                    label = { Text("Ціна продажу, ₴ *") },
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { focus ->
                            if (focus.isFocused) priceTapRequests++
                        }
                        .pointerInput(initial?.id, mode) {
                            // Observe touch/mouse release without consuming it: TextField
                            // still opens the keyboard and handles normal input itself.
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                do {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Final)
                                    val pressed = event.changes.any { it.pressed }
                                } while (pressed)
                                priceTapRequests++
                            }
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, enabled = !pending
                )
                Text("Зміни прайсу не змінюють історичні кошториси.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !pending && errorMessage == null,
                onClick = {
                    if (initial != null && mode == PriceEditorMode.FULL && unit.trim() != initial.unit) {
                        confirmUnitChange = true
                    } else submit()
                }
            ) { Text(if (pending) "Збереження…" else "Зберегти") }
        },
        dismissButton = {
            TextButton(enabled = !pending, onClick = onDismiss) { Text("Скасувати") }
        }
    )
    if (confirmUnitChange) {
        AlertDialog(
            onDismissRequest = { confirmUnitChange = false },
            title = { Text("Змінити одиницю вимірювання?") },
            text = { Text("Це може заблокувати майбутній перерахунок старих кошторисів. Історичні дані не змінюються. Автоматичної конвертації кількостей немає.") },
            confirmButton = {
                Button(onClick = { confirmUnitChange = false; submit() }) { Text("Підтвердити зміну") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnitChange = false }) { Text("Повернутися") }
            }
        )
    }
}
