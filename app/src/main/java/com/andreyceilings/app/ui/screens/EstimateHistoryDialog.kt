package com.andreyceilings.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.money
import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Only immutable SQLDelight revision history is shown. Never use the mutable
 * legacy estimate list or current price list to render older versions.
 * Internal revision UUIDs are not displayed or copied to printed documents.
 */
private data class HistoryLine(
    val title: String,
    val unit: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double
)

private data class HistoryVersion(
    /** Never shown to the user or printed; used to select the exact row. */
    val revisionUuid: String,
    val number: Long,
    val createdAt: Long,
    val total: Double,
    val lines: List<HistoryLine>
)

@Composable
internal fun EstimateHistoryDialog(
    database: CeilingsDatabase,
    seriesUuid: String,
    printableNumber: String,
    onDismiss: () -> Unit
 ) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPdf by remember(database, seriesUuid) {
        mutableStateOf<HistoryVersion?>(null)
    }
    var exportingPdf by remember(database, seriesUuid) { mutableStateOf(false) }
    var exportStatus by remember(database, seriesUuid) { mutableStateOf<String?>(null) }
    var exportError by remember(database, seriesUuid) { mutableStateOf<String?>(null) }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val requested = pendingPdf
        pendingPdf = null
        if (uri != null && requested != null) {
            exportingPdf = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        // Resolve the immutable snapshot AFTER Android's SAF
                        // picker returns. Never use preview or cached prices.
                        val pdf = loadImmutableEstimatePdfSnapshot(
                            database.database, seriesUuid, requested.revisionUuid
                        )
                        check(pdf.versionNumber == requested.number) {
                            "Збережена версія змінилася."
                        }
                        writeEstimatePdf(
                            context, uri, pdf.estimate, pdf.projectTitle,
                            pdf.versionNumber
                        )
                    }
                }.onSuccess {
                    exportError = null
                    exportStatus = "PDF v${requested.number} збережено."
                }.onFailure {
                    exportStatus = null
                    exportError = "Не вдалося створити PDF v${requested.number}: " +
                        (it.message ?: "невідома помилка")
                }
                exportingPdf = false
            }
        }
    }
    var versions by remember(database, seriesUuid) {
        mutableStateOf<List<HistoryVersion>?>(null)
    }
    var loadError by remember(database, seriesUuid) { mutableStateOf<String?>(null) }
    LaunchedEffect(database, seriesUuid) {
        runCatching {
            withContext(Dispatchers.IO) {
                val history = ImmutableEstimateRepository(database.database)
                val series = checkNotNull(history.series(seriesUuid)) {
                    "Історію кошторису не знайдено."
                }
                check(series.deletedAt == null) { "Кошторис видалено." }
                history.revisions(seriesUuid).map { revision ->
                    HistoryVersion(
                        revisionUuid = revision.revisionUuid,
                        number = revision.versionNumber,
                        createdAt = revision.createdAt,
                        total = revision.totalCost,
                        lines = history.lines(revision.revisionUuid).map { line ->
                            HistoryLine(
                                title = line.titleSnapshot,
                                unit = line.unitSnapshot,
                                quantity = line.quantitySnapshot,
                                unitPrice = line.unitPriceSnapshot,
                                total = line.lineTotalSnapshot
                            )
                        }
                    )
                }
            }
        }.onSuccess { versions = it }
            .onFailure { loadError = it.message ?: "Помилка читання історії." }
    }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    AppAlertDialog(
        onDismissRequest = { if (!exportingPdf) onDismiss() },
        title = { Text("Історія версій") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("№ $printableNumber", style = MaterialTheme.typography.titleMedium)
                exportStatus?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                exportError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                val message = loadError
                if (message != null) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                } else if (versions == null) {
                    Text("Завантажую збережені версії…")
                } else {
                    versions.orEmpty().forEach { version ->
                        HorizontalDivider()
                        Text(
                            "v${version.number} · ${dateFormat.format(Date(version.createdAt))}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Збережена сума: ${money(version.total)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        version.lines.forEach { line ->
                            Text(
                                "${line.title}: ${line.quantity} ${line.unit} × " +
                                    "${money(line.unitPrice)} = ${money(line.total)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(
                            enabled = !exportingPdf && pendingPdf == null,
                            onClick = {
                                exportStatus = null
                                exportError = null
                                pendingPdf = version
                                val filename = printableNumber.replace(
                                    Regex("[^A-Za-zА-Яа-яІіЇїЄєҐґ0-9_-]"), "_"
                                ).take(48).ifBlank { "koshtorys" }
                                pdfLauncher.launch("${filename}_v${version.number}.pdf")
                            }
                        ) { Text("Експортувати PDF v${version.number}") }
                    }
                    Text(
                        "Кожен PDF формується зі збереженого знімка обраної " +
                            "версії; зміни поточного прайсу не впливають на нього.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !exportingPdf) {
                Text("Закрити")
            }
        }
    )
}
