package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.livepricing.*

sealed interface MeasurementPricingState {
    data object Loading : MeasurementPricingState
    data class Unavailable(val reason: PricingProblemCode) : MeasurementPricingState
    data class Loaded(val preview: LiveEstimatePreview, val measurementUpdatedAt: Long? = null,
        val elementVersions: Map<String, Long>? = null) : MeasurementPricingState
}

/** Integer cents only; no locale-sensitive conversion through floating point. */
fun measurementMoney(cents: Long): String = "${cents / 100},${(cents % 100).toString().padStart(2, '0')} грн"

@Composable
fun MeasurementPricingSection(state: MeasurementPricingState, elements: List<MeasurementElement> = emptyList(),
    editing: Boolean = false, compact: Boolean = false, measurementUpdatedAt: Long? = null, onRefresh: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    // This is only a display-basis check; pricing never combines independently observed values.
    val stale = state is MeasurementPricingState.Loaded && (
        measurementUpdatedAt != null && state.measurementUpdatedAt != measurementUpdatedAt ||
            state.elementVersions != null && state.elementVersions != elements.associate { it.uuid to it.updatedAt })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(min = if (compact) 112.dp else 208.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Калькулятор заміру", style = MaterialTheme.typography.titleMedium)
            Text("За збереженим заміром · інформаційно", style = MaterialTheme.typography.bodySmall)
            when {
                editing -> Text("Є незбережені зміни або помилка збереження. Збережіть чи відхиліть чернетку для актуального розрахунку.")
                state == MeasurementPricingState.Loading || stale -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Оновлення розрахунку…")
                }
                state is MeasurementPricingState.Unavailable -> {
                    Text("Розрахунок недоступний: ${pricingProblemText(state.reason)}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) { Text("Оновити розрахунок") }
                }
                state is MeasurementPricingState.Loaded -> {
                    val p = state.preview
                    val invalid = p.fatalProblems.any { it.code !in setOf(PricingProblemCode.ROLE_UNDETERMINED,
                        PricingProblemCode.DUPLICATE_MAIN_CANVAS, PricingProblemCode.DUPLICATE_PRIMARY_PROFILE) }
                    if (invalid) Text("Розрахунок потребує перевірки; суми недоступні.", color = MaterialTheme.colorScheme.error)
                    else {
                        Text("Погоджено: ${measurementMoney(p.confirmedTotalCents)}", style = MaterialTheme.typography.titleMedium)
                        Text("Можливі роботи: ${measurementMoney(p.potentialTotalCents)} · умовна сума")
                        if (p.potentialIncomplete) Text("Сума можливих робіт неповна: є непрораховані позиції.")
                        if (p.unresolvedItems.any { it.bucket == PricingBucket.CONFIRMED }) Text("Погоджена сума неповна: є непрораховані позиції.")
                    }
                    Text("Непрорахованих позицій: ${p.unresolvedItems.size}")
                    Text(if (p.canFinalizeFull) "Готовий до майбутнього індивідуального кошторису"
                        else "До майбутнього індивідуального кошторису не готовий")
                    val problems = (p.fatalProblems + p.mandatoryProblems).distinct()
                    problems.take(if (compact) 1 else problems.size).forEach { problem ->
                        val label = elements.find { "element:${it.uuid}" == problem.sourceKey }?.label
                        Text(listOfNotNull(label, pricingProblemText(problem.code)).joinToString(": "), color = MaterialTheme.colorScheme.error)
                    }
                    if (!compact && (p.unresolvedItems.isNotEmpty() || p.warnings.isNotEmpty())) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Сховати причини" else "Позиції та причини") }
                        if (expanded) {
                            p.unresolvedItems.forEach { row ->
                                Text("${row.label}: ${row.reasons.joinToString("; ") { pricingProblemText(it) }}")
                            }
                            p.warnings.distinct().forEach { warning ->
                                val label = elements.find { "element:${it.uuid}" == warning.sourceKey }?.label
                                Text(listOfNotNull(label, pricingProblemText(warning.code)).joinToString(": "))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun pricingProblemText(code: PricingProblemCode): String = when (code) {
    PricingProblemCode.PARENT_UNAVAILABLE -> "замір або об’єкт недоступний"
    PricingProblemCode.INVALID_GEOMETRY -> "потрібен коректний виміряний контур"
    PricingProblemCode.INVALID_SOURCE -> "дані заміру несумісні; перевірте елементи та креслення"
    PricingProblemCode.STALE_SOURCE, PricingProblemCode.STALE_CATALOG -> "дані змінилися; оновіть розрахунок"
    PricingProblemCode.DATABASE_UNAVAILABLE -> "захищена база недоступна"
    PricingProblemCode.ROLE_UNDETERMINED -> "роль не визначена — оберіть її в редагуванні елемента"
    PricingProblemCode.DUPLICATE_MAIN_CANVAS -> "декілька полотен у замірі"
    PricingProblemCode.DUPLICATE_PRIMARY_PROFILE -> "декілька основних профілів у замірі"
    PricingProblemCode.CATALOG_MISSING -> "позицію або категорію прайсу видалено чи не знайдено"
    PricingProblemCode.METADATA_DRIFT -> "параметри позиції прайсу змінилися"
    PricingProblemCode.UNSUPPORTED_UNIT -> "одиниця не підтримує розрахунок"
    PricingProblemCode.INVALID_PRICE -> "ціна некоректна"
    PricingProblemCode.UNKNOWN_QUANTITY -> "кількість ще невідома"
    PricingProblemCode.RANGE_QUANTITY -> "для розрахунку потрібна точна кількість замість діапазону"
    PricingProblemCode.QUANTITY_PRECISION -> "виміряна кількість потребує точності до тисячних"
    PricingProblemCode.INVALID_QUANTITY -> "кількість несумісна з одиницею або режимом прайсу"
    PricingProblemCode.SELECTION_MISSING -> "побажання ще не прив’язане до прайсу"
    PricingProblemCode.PLACEMENT_REVIEW -> "перевірте розміщення після зміни контуру"
    PricingProblemCode.PLACEMENT_UNPLACED -> "елемент ще не розміщено на кресленні"
    PricingProblemCode.PLACEMENT_APPROXIMATE -> "розміщення приблизне"
    PricingProblemCode.PLACEMENT_PARTIAL -> "розміщено частину заявленої кількості"
    PricingProblemCode.MISSING_MANDATORY_CANVAS -> "додайте позицію прайсу з роллю «Полотно»"
    PricingProblemCode.MISSING_MANDATORY_PRIMARY_PROFILE -> "додайте позицію прайсу з роллю «Основний профіль»"
    PricingProblemCode.INVALID_MANDATORY_CANVAS -> "полотно має бути погоджене, сумісне та прораховане"
    PricingProblemCode.INVALID_MANDATORY_PRIMARY_PROFILE -> "основний профіль має бути погоджений, сумісний та прорахований"
    PricingProblemCode.MANDATORY_BASE_REQUIRED -> "потрібні погоджені полотно та основний профіль"
    PricingProblemCode.OVERFLOW -> "сума перевищує допустиму межу"
    PricingProblemCode.INVALID_OVERRIDE, PricingProblemCode.INVALID_SELECTION -> "склад розрахунку потребує перевірки"
    PricingProblemCode.DUPLICATE_SOURCE, PricingProblemCode.PROVENANCE_OVERLAP,
    PricingProblemCode.PROVENANCE_MISSING, PricingProblemCode.AMBIGUOUS_COVERAGE -> "походження даних потребує перевірки"
    PricingProblemCode.EMPTY_DOCUMENT -> "немає позицій для розрахунку"
    PricingProblemCode.REVISION_UNAVAILABLE -> "збережений документ недоступний"
    PricingProblemCode.CROSS_PROJECT -> "дані належать іншому об’єкту"
}
