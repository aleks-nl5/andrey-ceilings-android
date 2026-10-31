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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
            verticalArrangement = Arrangement.spacedBy(if (desktopLayout) 8.dp else 12.dp)
        ) {
            item(key = "heading") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Прайс-лист", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("Ціна продажу використовується в нових кошторисах.",
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
                        Card(Modifier.fillMaxWidth()) {
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
                                    TextButton(onClick = { deleting = item }) { Text("Видалити") }
                                }
                            } else {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            openEditor(item, PriceEditorMode.FULL)
                                        })
                                    Text(item.category, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.unit, style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f))
                                        TextButton(onClick = { openEditor(item, PriceEditorMode.QUICK) }) {
                                            Text("${PriceCatalogRules.formatSalePrice(item.price)} ₴")
                                        }
                                        TextButton(onClick = { openEditor(item, PriceEditorMode.FULL) }) {
                                            Text("Змінити")
                                        }
                                        TextButton(onClick = { deleting = item }) { Text("Видалити") }
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
                categories = categories,
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
    categories: List<String>,
    pending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (PriceCatalogDraft) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category ?: "Додаткові роботи") }
    var unit by remember { mutableStateOf(initial?.unit ?: "шт.") }
    var price by remember { mutableStateOf(initial?.price?.let(PriceCatalogRules::formatSalePrice) ?: "") }
    var showCategories by remember { mutableStateOf(false) }
    var confirmUnitChange by remember { mutableStateOf(false) }
    val errorMessage = if (mode == PriceEditorMode.QUICK) {
        if (PriceCatalogRules.parseSalePrice(price) == null) "Перевірте ціну продажу." else null
    } else PriceCatalogRules.validate(title, category, unit, price)

    val submit = {
        val parsed = PriceCatalogRules.parseSalePrice(price)
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
                    OutlinedTextField(value = category, onValueChange = { category = it },
                        label = { Text("Категорія *") }, singleLine = true, enabled = !pending)
                    if (categories.isNotEmpty()) {
                        TextButton(onClick = { showCategories = !showCategories }, enabled = !pending) {
                            Text("Вибрати наявну категорію")
                        }
                        if (showCategories) {
                            categories.forEach { existing ->
                                TextButton(onClick = { category = existing; showCategories = false }) {
                                    Text(existing)
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = unit, onValueChange = { unit = it },
                        label = { Text("Одиниця вимірювання *") },
                        singleLine = true, enabled = !pending)
                } else {
                    Text(initial?.title.orEmpty())
                }
                OutlinedTextField(value = price, onValueChange = { price = it },
                    label = { Text("Ціна продажу, ₴ *") },
                    singleLine = true, enabled = !pending)
                Text("Зміни прайсу не змінюють історичні кошториси.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
