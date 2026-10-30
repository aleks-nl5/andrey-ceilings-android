package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.money
import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.shared.estimate.EstimateRecalculationPreview
import com.andreyceilings.shared.estimate.RecalculationProblem

/**
 * Explicit, review-before-save path for a verified immutable revision. The
 * caller only supplies onConfirm when KMP geometry, frozen line items, saved
 * options and regenerated detailsJson have all passed strict validation.
 */
@Composable
internal fun EstimatePreviewDialog(
    preview: EstimateRecalculationPreview,
    printableNumber: String?,
    onDismiss: () -> Unit,
    proposedVersion: Long? = null,
    unavailableReason: String? = null,
    saving: Boolean = false,
    onConfirm: (() -> Unit)? = null
) {
    AppAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Перерахунок — перегляд") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("№ ${printableNumber ?: "Без номера"} · джерело v${preview.baseVersionNumber}")
                Text(
                    "Порівняння цін за збереженими кількостями. Вихідні версії й прайс не змінюються.",
                    style = MaterialTheme.typography.bodySmall
                )
                preview.lines.forEach { row ->
                    Text(row.before.title, style = MaterialTheme.typography.titleSmall)
                    Text("Було: ${money(row.before.lineTotal)} (${money(row.before.unitPrice)} × ${row.before.quantity} ${row.before.unit})")
                    // Cache the cross-module nullable property in a local val:
                    // Kotlin cannot smart-cast row.after across module boundaries.
                    val updatedLine = row.after
                    if (updatedLine != null) {
                        Text("Стало: ${money(updatedLine.lineTotal)} (${money(updatedLine.unitPrice)} × ${updatedLine.quantity} ${updatedLine.unit})")
                        Text("Різниця: ${money(updatedLine.lineTotal - row.before.lineTotal)}", color = MaterialTheme.colorScheme.primary)
                        if (row.before.title != updatedLine.title) {
                            Text("Нова назва: ${updatedLine.title}", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            problemLabel(row.problem),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text("Було разом: ${money(preview.originalTotal)}", style = MaterialTheme.typography.titleMedium)
                preview.proposedTotal?.let { total ->
                    Text("Стало разом: ${money(total)} · зміна ${money(total - preview.originalTotal)}",
                        style = MaterialTheme.typography.titleMedium)
                }
                val ready = preview.canConfirm &&
                    proposedVersion == preview.baseVersionNumber + 1L &&
                    onConfirm != null && unavailableReason == null
                Text(
                    when {
                        !preview.canConfirm ->
                            "Є блокувальні проблеми. Нова версія не створюється без їхнього вирішення."
                        !ready -> unavailableReason
                            ?: "Потрібна повна перевірка збережених замірів, опцій і сум. Нова версія не створюється."
                        else ->
                            "Створення v$proposedVersion потребує вашого підтвердження. Попередні версії залишаться незмінними; поточний прайс повторно перевіряється під час запису."
                    },
                    color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            if (preview.canConfirm && onConfirm != null && proposedVersion != null) {
                Button(
                    onClick = onConfirm,
                    enabled = !saving && unavailableReason == null &&
                        proposedVersion == preview.baseVersionNumber + 1L
                ) {
                    Text(if (saving) "Зберігаю…" else "Створити v$proposedVersion")
                }
            } else {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Закрити") }
            }
        },
        dismissButton = if (onConfirm != null) {
            { TextButton(onClick = onDismiss, enabled = !saving) { Text("Скасувати") } }
        } else null
    )
}

private fun problemLabel(problem: RecalculationProblem?): String = when (problem) {
    RecalculationProblem.MISSING_PRICE -> "Позицію не знайдено в поточному прайсі."
    RecalculationProblem.AMBIGUOUS_PRICE -> "Кілька позицій із тим самим кодом. Потрібен точний UUID."
    RecalculationProblem.CHANGED_CODE -> "Службовий код позиції прайсу змінився — потрібна ручна перевірка."
    RecalculationProblem.CHANGED_UNIT -> "Одиниця виміру змінилася; автоматична заміна заборонена."
    RecalculationProblem.INVALID_PRICE -> "Поточна ціна некоректна."
    RecalculationProblem.HISTORICAL_ADJUSTMENT -> "Історичне коригування/legacy потребує ручного рішення."
    RecalculationProblem.INCONSISTENT_SNAPSHOT -> "Історична сума не збігається із сумою рядків."
    null -> "Не вдалося перерахувати рядок."
}
