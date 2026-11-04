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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
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
    groups: List<PriceCatalogGroup>,
    databaseAvailable: Boolean,
    onSave: suspend (original: PriceCatalogItem?, draft: ConfiguredPriceDraft) -> Unit,
    onQuickPrice: suspend (original: PriceCatalogItem, salePriceText: String) -> Unit,
    onSaveGroup: suspend (original: PriceCatalogGroup?, draft: PriceGroupDraft) -> String,
    onDeleteGroup: suspend (group: PriceCatalogGroup) -> Unit,
    onDelete: suspend (item: PriceCatalogItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp)
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var itemEditor by remember { mutableStateOf<PriceItemEditor?>(null) }
    var managingGroups by remember { mutableStateOf(false) }
    var groupEditor by remember { mutableStateOf<PriceGroupEditor?>(null) }
    var deletingGroup by remember { mutableStateOf<PriceCatalogGroup?>(null) }
    var deleting by remember { mutableStateOf<PriceCatalogItem?>(null) }
    var pending by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    val categories = groups
    LaunchedEffect(groups) { selectedCategory = activePriceFilter(selectedCategory, groups) }
    val unitOptions = remember(items) { PriceCatalogRules.unitOptions(items) }
    val visible = remember(items, groups, search, selectedCategory) {
        visiblePriceItems(items, groups, search, activePriceFilter(selectedCategory, groups))
    }

    fun openEditor(item: PriceCatalogItem?, mode: PriceEditorMode) {
        if (!pending && databaseAvailable) {
            itemEditor = PriceItemEditor.open(item, mode == PriceEditorMode.QUICK)
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
                        items(categories, key = { "cat-${it.uuid}" }) { category ->
                            FilterChip(
                                selected = selectedCategory == category.uuid,
                                onClick = { selectedCategory = category.uuid },
                                label = { Text(category.name) }
                            )
                        }
                    }
                }
                item(key = "manage-categories") {
                    TextButton(enabled = databaseAvailable && !pending, onClick = {
                        editorError = null; managingGroups = true
                    }) { Text("Керувати категоріями") }
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
                    selectedCategory != null && visible.isEmpty() && search.isBlank() -> item(key = "category-empty") {
                        Text("У цій категорії поки немає позицій", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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

    fun runAction(action: suspend () -> Unit) {
        if (!pending && databaseAvailable) {
            pending = true
            editorError = null
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { editorError = catalogErrorMessage(error) }
                finally { pending = false }
            }
        }
    }

    // Exactly one interactive window, including child create and confirmations.
    val child = groupEditor
    val groupToDelete = deletingGroup
    val editor = itemEditor
    when {
        child != null -> PriceGroupEditorDialog(
            editor = child, enabled = databaseAvailable && !pending, pending = pending,
            error = editorError,
            onChange = { groupEditor = it; editorError = null },
            onDismiss = { groupEditor = null; editorError = null },
            onSubmit = {
                runAction {
                    val uuid = onSaveGroup(child.original, child.draft)
                    if (child.fromItem) {
                        val created = PriceCatalogGroup(uuid, child.draft.name.trim(), 0,
                            child.draft.defaultUnit.trim().takeIf { it.isNotEmpty() },
                            child.draft.defaultQuantityMode, child.draft.defaultPlacementKind)
                        itemEditor = itemEditor?.categoryCreated(created)
                    }
                    groupEditor = null
                }
            }
        )
        groupToDelete != null -> AlertDialog(
            onDismissRequest = { if (!pending) { deletingGroup = null; editorError = null } },
            title = { Text("Видалити категорію?") },
            text = { Column {
                Text(groupToDelete.name)
                Text("Можна видалити лише категорію без активних позицій.")
                editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(enabled = databaseAvailable && !pending, onClick = {
                runAction { onDeleteGroup(groupToDelete); deletingGroup = null }
            }) { Text("Видалити") } },
            dismissButton = { TextButton(enabled = !pending, onClick = {
                deletingGroup = null; editorError = null
            }) { Text("Скасувати") } }
        )
        managingGroups -> PriceGroupsDialog(
            groups, enabled = databaseAvailable && !pending,
            onDismiss = { managingGroups = false; editorError = null },
            onCreate = { editorError = null; groupEditor = PriceGroupEditor() },
            onEdit = { editorError = null; groupEditor = PriceGroupEditor(it) },
            onDelete = { editorError = null; deletingGroup = it }
        )
        editor != null -> key(editor.original?.id, editor.quick) {
            PriceEditorDialog(
                editor, groups, unitOptions, pending, databaseAvailable, editorError,
                onChange = { itemEditor = it; editorError = null },
                onDismiss = { itemEditor = null; editorError = null },
                onNewCategory = { editorError = null; groupEditor = PriceGroupEditor(fromItem = true) },
                onSubmit = {
                    runAction {
                        if (editor.quick) onQuickPrice(checkNotNull(editor.original), editor.draft.salePriceText)
                        else onSave(editor.original, editor.draft)
                        itemEditor = null
                    }
                }
            )
        }
        deleting != null -> {
            val item = checkNotNull(deleting)
            AlertDialog(
                onDismissRequest = { if (!pending) deleting = null },
                title = { Text("Видалити позицію?") },
                text = { Column {
                    Text(item.title)
                    Text("Історичні кошториси залишаться незмінними.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } },
                confirmButton = { Button(enabled = databaseAvailable && !pending, onClick = {
                    runAction { onDelete(item); deleting = null }
                }) { Text("Видалити") } },
                dismissButton = { TextButton(enabled = !pending, onClick = { deleting = null }) { Text("Скасувати") } }
            )
        }
    }
}

internal fun catalogErrorMessage(error: Exception): String = when (error) {
    is DuplicateGroupName -> "Категорія з такою назвою вже існує"
    is DuplicateItemTitle -> "Позиція з такою назвою вже існує"
    is PriceCatalogConflict, is PriceGroupNotEmpty -> error.message.orEmpty()
    else -> "Не вдалося зберегти зміни. Перевірте дані та стан БД."
}
