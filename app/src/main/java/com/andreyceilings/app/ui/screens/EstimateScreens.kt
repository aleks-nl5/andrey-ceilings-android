package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.app.ui.theme.AppExposedDropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.*
import com.andreyceilings.app.core.EstimateLineItems
import com.andreyceilings.app.core.EstimateLineItem
import com.andreyceilings.shared.calculation.MeasurementPrecision
import org.json.JSONObject
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
                OutlinedTextField(name, { name = it }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences), label = { Text("Назва кімнати / кошторису") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(selectedProjectId, name) }, enabled = name.isNotBlank()) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

/**
 * PDF renderer for the version-specific estimate reconstructed from immutable revision rows.
 */
internal fun writeEstimatePdf(
    context: Context,
    uri: Uri,
    estimate: EstimateEntity,
    projectTitle: String,
    versionNumber: Long = 1L
) {
    require(versionNumber > 0L)
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
        lines += PdfLine("Версія: v$versionNumber")
    }
    lines += PdfLine("Об’єкт: $projectTitle")
    lines += PdfLine("Кошторис: ${estimate.originalDocumentName ?: estimate.name}")
    lines += PdfLine("Дата створення: ${java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date(estimate.createdAt))}")
    lines += PdfLine("")
    lines += PdfLine("ПАРАМЕТРИ ПРИМІЩЕННЯ", 1)
    val polygon = estimate.frozenPolygon
    val frozenL = estimate.frozenGeometryInput
    if (polygon != null) {
        // Print the captured measurements and ordered contour from the exact
        // immutable revision; no shape-specific PDF implementation required.
        val template = com.andreyceilings.shared.calculation.CeilingShapeTemplates
            .template(polygon.shapeType)
        lines += PdfLine("Форма: " + (template?.title ?: "Довільний багатокутник"))
        if (template != null) {
            template.parameterKeys.forEach { key ->
                val mm = checkNotNull(polygon.measurementsMm[key]) {
                    "Неповні збережені параметри контуру"
                }
                lines += PdfLine("${template.parameterLabels.getValue(key)}: " +
                    formatMeasuredLength(mm / 1000.0, estimatePrecision))
            }
        }
        if (polygon.sides.isNotEmpty()) {
            lines += PdfLine("Виміряні сторони й повороти 90°:")
            polygon.sides.forEachIndexed { index, wall ->
                val turn = if (wall.turnAfter ==
                    com.andreyceilings.shared.calculation.OrthogonalTurn.LEFT) {
                    "ліворуч"
                } else "праворуч"
                lines += PdfLine("Сторона ${index + 1}: " +
                    formatMeasuredLength(wall.lengthMm / 1000.0, estimatePrecision) +
                    " м · поворот ${turn}")
            }
        } else {
            // Legacy v2 snapshots keep their original vertex-based record.
            lines += PdfLine("Вершини контуру, мм (за порядком):")
            polygon.vertices.forEachIndexed { index, point ->
                lines += PdfLine("${index + 1}: ${point.xMm}, ${point.yMm}")
            }
        }
    } else if (frozenL == null) {
        lines += PdfLine("Розмір: ${formatMeasuredLength(estimate.lengthMeters, estimatePrecision)} × ${formatMeasuredLength(estimate.widthMeters, estimatePrecision)} м")
    } else {
        // Never label a bounding box as an L-shaped room's full geometry.
        lines += PdfLine("Форма: L-подібна")
        lines += PdfLine("Сторони A–C: " + listOf(frozenL.sideA, frozenL.sideB, frozenL.sideC)
            .joinToString(" / ") { formatMeasuredLength(it, estimatePrecision) } + " м")
        lines += PdfLine("Сторони D–F: " + listOf(frozenL.sideD, frozenL.sideE, frozenL.sideF)
            .joinToString(" / ") { formatMeasuredLength(it, estimatePrecision) } + " м")
    }
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
    lines += PdfLine(
        if (versionNumber == 1L) "Ціни зафіксовано на момент створення кошторису."
        else "Ціни зафіксовано для цієї збереженої версії кошторису.",
        3
    )
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
