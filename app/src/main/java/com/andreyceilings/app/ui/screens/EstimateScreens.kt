package com.andreyceilings.app.ui.screens

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveEstimateDialog(
    projects: List<ProjectEntity>,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (Long, String) -> Unit
) {
    var selectedProjectId by remember(projects) { mutableLongStateOf(projects.first().id) }
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Зберегти кошторис") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = projects.firstOrNull { it.id == selectedProjectId }?.title ?: "Оберіть об’єкт",
                        onValueChange = {}, readOnly = true, label = { Text("Об’єкт") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        projects.forEach { project -> DropdownMenuItem(text = { Text(project.title) }, onClick = { selectedProjectId = project.id; expanded = false }) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Назва кімнати / кошторису") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(selectedProjectId, name) }, enabled = name.isNotBlank()) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EstimatesScreen(database: CeilingsDatabase?) {
    val estimatesFlow = remember(database) { database?.estimates()?.observeAll() ?: flowOf<List<EstimateEntity>>(emptyList()) }
    val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
    val estimates by estimatesFlow.collectAsState(initial = emptyList())
    val projects by projectsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var editingEstimate by remember { mutableStateOf<EstimateEntity?>(null) }
    var pendingPdfEstimate by remember { mutableStateOf<EstimateEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    val projectTotals = estimates.groupBy { it.projectId }.mapValues { (_, rows) -> rows.sumOf { it.totalCost } }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val selected = pendingPdfEstimate
        pendingPdfEstimate = null
        if (uri != null && selected != null) {
            val projectTitle = projects.firstOrNull { it.id == selected.projectId }?.title ?: "Об’єкт #${selected.projectId}"
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { writeEstimatePdf(context, uri, selected, projectTitle) } }
                    .onSuccess { status = "PDF-кошторис збережено." }
                    .onFailure { error = "Не вдалося створити PDF: ${it.message ?: "помилка запису"}" }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Кошториси", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Збережені розрахунки та суми по кожному об’єкту", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (database == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Захищена база ще відкривається або недоступна.")
        } else if (estimates.isEmpty()) {
            EmptyDataCard("Кошторисів поки немає. Відкрийте калькулятор, зробіть розрахунок і натисніть «Зберегти кошторис до об’єкта».")
        } else {
            projects.forEach { project ->
                val projectEstimates = estimates.filter { it.projectId == project.id }
                if (projectEstimates.isNotEmpty()) {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Разом за збереженими кошторисами: ${money(projectTotals[project.id] ?: 0.0)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            estimates.forEach { estimate ->
                val project = projects.firstOrNull { it.id == estimate.projectId }
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(estimate.name, fontWeight = FontWeight.SemiBold)
                                Text(project?.title ?: "Об’єкт #${estimate.projectId}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { renameText = estimate.name; editingEstimate = estimate }) {
                                Icon(Icons.Default.Edit, contentDescription = "Редагувати назву кошторису")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { database.estimates().insert(estimate.copy(id = 0, name = "${estimate.name} (копія)", createdAt = System.currentTimeMillis())) }
                                        .onSuccess { status = "Кошторис продубльовано." }
                                        .onFailure { error = it.message ?: "Не вдалося дублювати кошторис" }
                                }
                            }) { Icon(Icons.Default.ContentCopy, contentDescription = "Дублювати кошторис") }
                            IconButton(onClick = {
                                pendingPdfEstimate = estimate
                                val safeName = estimate.name.replace(Regex("[^A-Za-zА-Яа-яІіЇїЄє0-9_-]"), "_").take(40).ifBlank { "koshtorys" }
                                pdfLauncher.launch("$safeName.pdf")
                            }) { Icon(Icons.Default.PictureAsPdf, contentDescription = "Експортувати PDF") }
                            IconButton(onClick = { scope.launch { runCatching { database.estimates().deleteById(estimate.id) }.onFailure { error = it.message ?: "Не вдалося видалити кошторис" } } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Видалити кошторис")
                            }
                        }
                        Text("${estimate.lengthMeters.format2()} × ${estimate.widthMeters.format2()} м · ${estimate.areaSquareMeters.format2()} м² · ${estimate.material} · ${estimate.profileType}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Полотно ${money(estimate.canvasCost)} · профіль ${money(estimate.profileCost)} · роботи ${money(estimate.complexityCost + estimate.optionsCost)}")
                        val itemizedRows = EstimateLineItems.fromJson(estimate.detailsJson)
                        if (itemizedRows.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Розбивка за позиціями", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            itemizedRows.forEach { line ->
                                Text("${line.title}: ${line.quantity.format2()} ${line.unit} × ${money(line.unitPrice)} = ${money(line.total)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(money(estimate.totalCost), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    editingEstimate?.let { selected ->
        AlertDialog(
            onDismissRequest = { editingEstimate = null },
            title = { Text("Редагувати кошторис") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("Назва") }, singleLine = true) },
            confirmButton = {
                Button(enabled = renameText.isNotBlank(), onClick = {
                    scope.launch {
                        runCatching { database?.estimates()?.update(selected.copy(name = renameText.trim())) }
                            .onSuccess { status = "Назву кошторису оновлено." }
                            .onFailure { error = it.message ?: "Не вдалося оновити кошторис" }
                        editingEstimate = null
                    }
                }) { Text("Зберегти") }
            },
            dismissButton = { TextButton(onClick = { editingEstimate = null }) { Text("Скасувати") } }
        )
    }
}

private fun writeEstimatePdf(context: Context, uri: Uri, estimate: EstimateEntity, projectTitle: String) {
    val document = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 18f }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 12f }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 10f }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 9f; color = android.graphics.Color.DKGRAY }
    data class PdfLine(val text: String, val kind: Int = 0)
    val lines = mutableListOf<PdfLine>()
    lines += PdfLine("КОШТОРИС НАТЯЖНОЇ СТЕЛІ", 2)
    lines += PdfLine("Об’єкт: $projectTitle")
    lines += PdfLine("Кошторис: ${estimate.name}")
    lines += PdfLine("Дата: ${java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date())}")
    lines += PdfLine("")
    lines += PdfLine("ПАРАМЕТРИ ПРИМІЩЕННЯ", 1)
    lines += PdfLine("Розмір: ${estimate.lengthMeters.format2()} × ${estimate.widthMeters.format2()} м")
    lines += PdfLine("Площа: ${estimate.areaSquareMeters.format2()} м²    Периметр: ${estimate.perimeterMeters.format2()} м")
    lines += PdfLine("Матеріал: ${estimate.material}")
    lines += PdfLine("Профіль: ${estimate.profileType}")
    lines += PdfLine("Складність: ${when (estimate.complexity) { "multilevel" -> "Багаторівнева"; "curved" -> "Криволінійна"; else -> "Пряма" }}")
    lines += PdfLine("")
    lines += PdfLine("ДЕТАЛЬНИЙ РОЗРАХУНОК", 1)
    val lineItems = EstimateLineItems.fromJson(estimate.detailsJson)
    if (lineItems.isNotEmpty()) {
        lines += PdfLine("Позиція | Кількість | Ціна за одиницю | Сума", 1)
        lineItems.forEach { item ->
            lines += PdfLine("${item.title} | ${item.quantity.format2()} ${item.unit} | ${money(item.unitPrice)} | ${money(item.total)}")
        }
    } else {
        lines += PdfLine("Полотно: ${money(estimate.canvasCost)}")
        lines += PdfLine("Профіль: ${money(estimate.profileCost)}")
        lines += PdfLine("Складність: ${money(estimate.complexityCost)}")
        lines += PdfLine("Додаткові роботи: ${money(estimate.optionsCost)}")
    }
    lines += PdfLine("")
    lines += PdfLine("РАЗОМ: ${money(estimate.totalCost)}", 2)
    lines += PdfLine("Ціни зафіксовано на момент створення кошторису.", 3)
    lines += PdfLine("Документ сформовано застосунком Andrey Ceilings.", 3)

    val linesPerPage = 35
    val pages = lines.chunked(linesPerPage)
    pages.forEachIndexed { pageIndex, pageLines ->
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
        val canvas = page.canvas
        var y = 44f
        pageLines.forEach { line ->
            val paint = when (line.kind) { 1 -> headingPaint; 2 -> titlePaint; 3 -> mutedPaint; else -> bodyPaint }
            val maxWidth = 515f
            val text = line.text
            if (text.isNotEmpty() && paint.measureText(text) > maxWidth) {
                var remaining = text
                while (remaining.isNotEmpty()) {
                    var endIndex = remaining.length
                    while (endIndex > 1 && paint.measureText(remaining.substring(0, endIndex)) > maxWidth) endIndex--
                    val breakAt = remaining.lastIndexOf(' ', endIndex).takeIf { it > 0 } ?: endIndex
                    canvas.drawText(remaining.substring(0, breakAt), 40f, y, paint)
                    remaining = remaining.substring(breakAt).trimStart()
                    y += if (line.kind == 2) 26f else 17f
                }
            } else {
                canvas.drawText(text, 40f, y, paint)
                y += when (line.kind) { 2 -> 28f; 1 -> 21f; 3 -> 16f; else -> 17f }
            }
        }
        canvas.drawText("Сторінка ${pageIndex + 1} з ${pages.size}", 455f, 820f, mutedPaint)
        document.finishPage(page)
    }
    try {
        context.contentResolver.openOutputStream(uri)?.use { output -> document.writeTo(output) }
            ?: error("Не вдалося відкрити файл для запису")
    } finally { document.close() }
}
