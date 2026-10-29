package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * Read-only comparison. No "Save version" action until optimistic-lock
 * confirmation, consistent detailsJson and WAL-safe backup are implemented.
 */
@Composable
internal fun EstimatePreviewDialog(
    preview: EstimateRecalculationPreview,
    printableNumber: String?,
    onDismiss: () -> Unit
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
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
                    if (row.after != null) {
                        Text("Стало: ${money(row.after.lineTotal)} (${money(row.after.unitPrice)} × ${row.after.quantity} ${row.after.unit})")
                        Text("Різниця: ${money(checkNotNull(row.difference))}", color = MaterialTheme.colorScheme.primary)
                        if (row.before.title != row.after.title) {
                            Text("Нова назва: ${row.after.title}", style = MaterialTheme.typography.bodySmall)
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
                Text(
                    if (preview.canConfirm) {
                        "Це попередній перегляд. Збереження нової v2/v3 буде доступне після завершення перевірки актуальності прайсу й запису незмінних версій."
                    } else {
                        "Є блокувальні проблеми. Нова версія не створюється без явного вирішення цих розбіжностей."
                    },
                    color = if (preview.canConfirm) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } }
    )
}

private fun problemLabel(problem: RecalculationProblem?): String = when (problem) {
    RecalculationProblem.MISSING_PRICE -> "Позицію не знайдено в поточному прайсі."
    RecalculationProblem.AMBIGUOUS_PRICE -> "Кілька позицій із тим самим кодом. Потрібен точний UUID."
    RecalculationProblem.CHANGED_UNIT -> "Одиниця виміру змінилася; автоматична заміна заборонена."
    RecalculationProblem.INVALID_PRICE -> "Поточна ціна некоректна."
    RecalculationProblem.HISTORICAL_ADJUSTMENT -> "Історичне коригування/legacy потребує ручного рішення."
    RecalculationProblem.INCONSISTENT_SNAPSHOT -> "Історична сума не збігається із сумою рядків."
    null -> "Не вдалося перерахувати рядок."
}
