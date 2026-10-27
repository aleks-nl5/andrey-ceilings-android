package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.app.ui.theme.AppExposedDropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.*
import com.andreyceilings.app.core.EstimateLineItems
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import android.content.Context
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument

import com.andreyceilings.app.*

@Composable
internal fun PriceListScreen(database: CeilingsDatabase?) {
 val itemsFlow = remember(database) { database?.priceItems()?.observeAll() ?: flowOf<List<PriceItemEntity>>(emptyList()) }
 val priceItems by itemsFlow.collectAsState(initial = emptyList())
 val scope = rememberCoroutineScope()
 var editingItem by remember { mutableStateOf<PriceItemEntity?>(null) }
 var showEditor by remember { mutableStateOf(false) }
 var error by remember { mutableStateOf<String?>(null) }
 // Group and format rows only when the observed price list changes, not on every recomposition.
 val categories = remember(priceItems) {
  priceItems.groupBy { it.category }.toSortedMap().mapValues { (_, entries) ->
   entries.map { item -> item to "${formatPrice(item.price)} ₴ / ${item.unit}" }
  }
 }

 LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
  item {
   Text("Прайс-лист", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
   Text("Демонстраційні ціни в гривнях. Перед використанням перевірте й змініть їх під свої умови.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (database == null) {
   item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Захищена база ще відкривається або недоступна.") }
  } else {
   item { Button(onClick = { editingItem = null; showEditor = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Додати позицію") } }
   if (priceItems.isEmpty()) item { EmptyDataCard("Прайс-лист порожній.") }
   categories.forEach { (category, entries) ->
    item(key = "category-$category") { Text(category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
    items(entries, key = { "price-${it.first.id}" }) { row ->
     val item = row.first
     Card(
      shape = MaterialTheme.shapes.medium,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
     ) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
       Text(item.title, fontWeight = FontWeight.Medium)
       Text(row.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
      }
      IconButton(onClick = { editingItem = item; showEditor = true }) { Icon(Icons.Default.Edit, contentDescription = "Редагувати ціну") }
      IconButton(onClick = { scope.launch { runCatching { database.priceItems().deleteById(item.id) }.onFailure { error = it.message ?: "Не вдалося видалити позицію" } } }) { Icon(Icons.Default.Delete, contentDescription = "Видалити позицію") }
     } }
    }
   }
  }
  item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
 }
 if (showEditor && database != null) PriceItemEditorDialog(initial = editingItem, onDismiss = { showEditor = false }, onSave = { title, category, unit, price ->
  scope.launch {
   runCatching {
    val current = editingItem
    database.priceItems().upsert(PriceItemEntity(id = current?.id ?: 0, code = current?.code ?: "custom.${System.currentTimeMillis()}", title = title.trim(), category = category.trim(), unit = unit.trim(), price = price))
   }.onFailure { error = it.message ?: "Не вдалося зберегти позицію" }
   showEditor = false
  }
 })
}

@Composable
private fun PriceItemEditorDialog(
    initial: PriceItemEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Double) -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category ?: "Додаткові роботи") }
    var unit by remember(initial) { mutableStateOf(initial?.unit ?: "шт.") }
    var price by remember(initial) { mutableStateOf(initial?.price?.let { formatPrice(it) } ?: "0") }
    val parsedPrice = price.replace(',', '.').toDoubleOrNull()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Нова позиція прайсу" else "Редагувати позицію") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Назва *") }, singleLine = true)
                OutlinedTextField(category, { category = it }, label = { Text("Категорія *") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Одиниця виміру *") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Ціна, ₴") }, singleLine = true)
                Text("Приклади одиниць: м², м.п., шт. Ціни є демонстраційними.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank() && category.isNotBlank() && unit.isNotBlank() && parsedPrice != null && parsedPrice >= 0) onSave(title, category, unit, parsedPrice) }, enabled = title.isNotBlank() && category.isNotBlank() && unit.isNotBlank() && parsedPrice != null && parsedPrice >= 0) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

private fun formatPrice(value: Double): String = String.format(Locale.forLanguageTag("uk-UA"), "%.2f", value).trimEnd('0').trimEnd(',').trimEnd('.')