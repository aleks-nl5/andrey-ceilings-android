package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.app.ui.theme.AppExposedDropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
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
import com.andreyceilings.app.core.EstimateLineItem
import com.andreyceilings.shared.calculation.MeasurementPrecision
import org.json.JSONObject
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import android.os.SystemClock
import androidx.compose.runtime.withFrameNanos
import com.andreyceilings.app.data.PerformanceDiagnosticsLog
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

private fun precisionForEstimate(detailsJson: String): MeasurementPrecision {
    val step = runCatching { JSONObject(detailsJson).optDouble("measurementPrecisionMeters", 0.01) }.getOrDefault(0.01)
    return MeasurementPrecision.fromStep(step) ?: MeasurementPrecision.ONE_CENTIMETER
}

private fun formattedQuantity(line: EstimateLineItem, precision: MeasurementPrecision): String = when (line.unit) {
    "м²" -> formatMeasuredArea(line.quantity, precision)
    "м.п.", "м" -> formatMeasuredLength(line.quantity, precision)
    else -> line.quantity.format2()
}


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
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Зберегти кошторис") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = projects.firstOrNull { it.id == selectedProjectId }?.title ?: "Оберіть об’єкт",
                        onValueChange = {}, readOnly = true, label = { Text("Об’єкт") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
                    )
                    AppExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
    val context = LocalContext.current
    val screenOpenedAt = remember(database) { SystemClock.elapsedRealtime() }
    val estimatesFlow = remember(database, context) {
        val requestedAt = SystemClock.elapsedRealtime()
        val source = database?.estimates()?.observeAll() ?: flowOf<List<EstimateEntity>>(emptyList())
        source.onEach { rows ->
            PerformanceDiagnosticsLog.record(
                context,
                "estimates_data_emitted count=${rows.size} elapsed_ms=${SystemClock.elapsedRealtime() - requestedAt}"
            )
        }
    }
    val projectsFlow = remember(database, context) {
        val requestedAt = SystemClock.elapsedRealtime()
        val source = database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList())
        source.onEach { rows ->
            PerformanceDiagnosticsLog.record(
                context,
                "estimates_projects_emitted count=${rows.size} elapsed_ms=${SystemClock.elapsedRealtime() - requestedAt}"
            )
        }
    }
    val estimates by estimatesFlow.collectAsState(initial = emptyList())
    val projects by projectsFlow.collectAsState(initial = emptyList())
    LaunchedEffect(estimates.size, projects.size, database) {
        if (database != null && (estimates.isNotEmpty() || projects.isNotEmpty())) {
            withFrameNanos { }
            PerformanceDiagnosticsLog.record(
                context,
                "estimates_list_frame estimates_count=${estimates.size} projects_count=${projects.size} elapsed_ms=${SystemClock.elapsedRealtime() - screenOpenedAt}"
            )
        }
    }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var editingEstimate by remember { mutableStateOf<EstimateEntity?>(null) }
    var pendingPdfEstimate by remember { mutableStateOf<EstimateEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    val estimatesByProject = remember(estimates) { estimates.groupBy { it.projectId } }
    val projectsById = remember(projects) { projects.associateBy { it.id } }
    val projectTotals = remember(estimatesByProject) { estimatesByProject.mapValues { (_, rows) -> rows.sumOf { it.totalCost } } }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val selected = pendingPdfEstimate
        pendingPdfEstimate = null
        if (uri != null && selected != null) {
            val projectTitle = projectsById[selected.projectId]?.title ?: "Об’єкт #${selected.projectId}"
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { writeEstimatePdf(context, uri, selected, projectTitle) } }
                    .onSuccess { status = "PDF-кошторис збережено." }
                    .onFailure { error = "Не вдалося створити PDF: ${it.message ?: "помилка запису"}" }
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
  top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 16.dp,
  bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp + 16.dp
 ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Кошториси", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Збережені розрахунки та суми по кожному об’єкту", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (database == null) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Захищена база ще відкривається або недоступна.")
            }
        } else if (estimates.isEmpty()) {
            item { EmptyDataCard("Кошторисів поки немає. Відкрийте калькулятор, зробіть розрахунок і натисніть «Зберегти кошторис до об’єкта».") }
        } else {
            projects.forEach { project ->
                val projectEstimates = estimatesByProject[project.id].orEmpty()
                if (projectEstimates.isNotEmpty()) {
                    item(key = "project-total-${project.id}") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Разом за збереженими кошторисами: ${money(projectTotals[project.id] ?: 0.0)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            items(estimates, key = { "estimate-${it.id}" }) { estimate ->
                val project = projectsById[estimate.projectId]
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                                val numberPart = estimate.printableNumber?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                                val filename = listOfNotNull(safeName, numberPart, if (numberPart != null) "v1" else null)
                                    .joinToString("_") + ".pdf"
                                pdfLauncher.launch(filename)
                            }) { Icon(Icons.Default.PictureAsPdf, contentDescription = "Експортувати PDF") }
                            IconButton(onClick = { scope.launch { runCatching { database.estimates().deleteById(estimate.id) }.onFailure { error = it.message ?: "Не вдалося видалити кошторис" } } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Видалити кошторис")
                            }
                        }
                        estimate.printableNumber?.let { number ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("№ $number · v1", modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Номер кошторису", number))
                                    status = "Номер кошторису скопійовано."
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Копіювати номер кошторису")
                                }
                            }
                        }
                        val estimatePrecision = remember(estimate.detailsJson) { precisionForEstimate(estimate.detailsJson) }
                        Text("${formatMeasuredLength(estimate.lengthMeters, estimatePrecision)} × ${formatMeasuredLength(estimate.widthMeters, estimatePrecision)} м · ${formatMeasuredArea(estimate.areaSquareMeters, estimatePrecision)} м² · ${materialDisplayName(estimate.material)} · ${profileDisplayName(estimate.profileType)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Полотно ${money(estimate.canvasCost)} · профіль ${money(estimate.profileCost)} · роботи ${money(estimate.complexityCost + estimate.optionsCost)}")
                        val itemizedRows = remember(estimate.detailsJson) { EstimateLineItems.fromJson(estimate.detailsJson) }
                        if (itemizedRows.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Розбивка за позиціями", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            itemizedRows.forEach { line ->
                                Text("${line.title}: ${formattedQuantity(line, estimatePrecision)} ${line.unit} × ${money(line.unitPrice)} = ${money(line.total)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(money(estimate.totalCost), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { status?.let { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
    editingEstimate?.let { selected ->
        AppAlertDialog(
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
    val estimatePrecision = precisionForEstimate(estimate.detailsJson)
    val document = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 18f }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 12f }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 10f }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 9f; color = android.graphics.Color.DKGRAY }
    data class PdfLine(val text: String, val kind: Int = 0)
    val lines = mutableListOf<PdfLine>()
    lines += PdfLine("КОШТОРИС НАТЯЖНОЇ СТЕЛІ", 2)
    if (estimate.printableNumber != null) {
        lines += PdfLine("№ документа: ${estimate.printableNumber}")
        lines += PdfLine("Версія: v1")
    }
    lines += PdfLine("Об’єкт: $projectTitle")
    lines += PdfLine("Кошторис: ${estimate.originalDocumentName ?: estimate.name}")
    lines += PdfLine("Дата створення: ${java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date(estimate.createdAt))}")
    lines += PdfLine("")
    lines += PdfLine("ПАРАМЕТРИ ПРИМІЩЕННЯ", 1)
    lines += PdfLine("Розмір: ${formatMeasuredLength(estimate.lengthMeters, estimatePrecision)} × ${formatMeasuredLength(estimate.widthMeters, estimatePrecision)} м")
    lines += PdfLine("Площа: ${formatMeasuredArea(estimate.areaSquareMeters, estimatePrecision)} м²    Периметр: ${formatMeasuredLength(estimate.perimeterMeters, estimatePrecision)} м")
    lines += PdfLine("Матеріал: ${materialDisplayName(estimate.material)}")
    lines += PdfLine("Профіль: ${profileDisplayName(estimate.profileType)}")
    lines += PdfLine("Складність: ${when (estimate.complexity) { "multilevel" -> "Багаторівнева"; "curved" -> "Криволінійна"; else -> "Пряма" }}")
    lines += PdfLine("")
    lines += PdfLine("ДЕТАЛЬНИЙ РОЗРАХУНОК", 1)
    val lineItems = EstimateLineItems.fromJson(estimate.detailsJson)
    if (lineItems.isNotEmpty()) {
        lines += PdfLine("Позиція | Кількість | Ціна за одиницю | Сума", 1)
        lineItems.forEach { item ->
            lines += PdfLine("${item.title} | ${formattedQuantity(item, estimatePrecision)} ${item.unit} | ${money(item.unitPrice)} | ${money(item.total)}")
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
