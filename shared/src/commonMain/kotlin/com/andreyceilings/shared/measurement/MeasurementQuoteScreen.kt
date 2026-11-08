package com.andreyceilings.shared.measurement

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.livepricing.*

@Composable
fun MeasurementQuoteScreen(presenter: TransientQuotePresenter, store: MeasurementElementsStore,
    route: ElementRoute, contentPadding: PaddingValues, systemTopInset: Dp, onBack: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(presenter, store, route, retry) {
        var generation = presenter.beginRead()
        store.observeQuotePricing(route).collect { result ->
            if (result == null) generation = presenter.beginRead() else presenter.acceptRead(generation, result)
        }
    }
    val state by presenter.state.collectAsState()
    MeasurementQuoteContent(state, onBack, { retry++ }, presenter::select, presenter::editQuantity,
        presenter::confirmQuantity, presenter::resetQuantity, presenter::editDiscount,
        presenter::confirmDiscount, presenter::acknowledge, contentPadding, systemTopInset)
}

/** Pure visibility fraction: only the actual final-summary bounds and current viewport matter. */
fun quoteSummaryVisibility(top: Int, size: Int, viewportStart: Int, viewportEnd: Int): Float {
    if (size <= 0 || viewportEnd <= viewportStart) return 0f
    val visible = (minOf(top + size, viewportEnd) - maxOf(top, viewportStart)).coerceAtLeast(0)
    return (visible.toFloat() / minOf(size, viewportEnd - viewportStart)).coerceIn(0f, 1f)
}

@Composable
fun MeasurementQuoteContent(state: TransientQuoteState, onBack: () -> Unit, onRefresh: () -> Unit,
    onSelect: (String, Boolean) -> Unit, onQuantity: (String, String) -> Unit,
    onConfirmQuantity: (String) -> Unit, onResetQuantity: (String) -> Unit,
    onDiscount: (String) -> Unit, onConfirmDiscount: () -> Unit, onAcknowledge: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues(16.dp), systemTopInset: Dp = 0.dp) {
    val list = rememberLazyListState()
    val visibility by remember { derivedStateOf {
        val layout = list.layoutInfo
        layout.visibleItemsInfo.find { it.key == "quote-final-summary" }?.let {
            quoteSummaryVisibility(it.offset, it.size, layout.viewportStartOffset, layout.viewportEndOffset)
        } ?: 0f
    } }
    // Compose's animation clock observes MotionDurationScale, including the platform zero-motion setting.
    val handoff by animateFloatAsState(visibility, tween(180))
    val amount = state.amounts?.let { measurementMoney(it.finalCents) } ?: "Сума недоступна / Перевірте замір"
    val partial = state.preview?.isPartial == true
    val source = state.source
    val omitted = state.preview?.excluded.orEmpty().filter { it.bucket == PricingBucket.CONFIRMED }
    fun label(key: String) = source?.elements?.find { "element:${it.uuid}" == key }?.label ?: "Позиція заміру"
    Column(Modifier.fillMaxSize().padding(top = systemTopInset).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("До заміру") }
            Text("Попередній кошторис", style = MaterialTheme.typography.titleMedium)
        }
        // Reserved height prevents a scroll jump when ownership passes to the bottom summary.
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp)
            .graphicsLayer { alpha = 1f - handoff; translationY = -12f * handoff }
            .then(if (handoff >= 0.5f) Modifier.clearAndSetSemantics {} else Modifier)) {
            Column {
                Text(amount, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (partial) Text("Частковий", style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Лише для цього перегляду. Зміни зберігаються в пам’яті до виходу із заміру.")
                if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Оновлення розрахунку…") }
                state.unavailable?.let {
                    Text(pricingProblemText(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) { Text("Оновити розрахунок") }
                }
                (state.preview?.fatalProblems.orEmpty() + state.preview?.mandatoryProblems.orEmpty()).distinct().forEach {
                    Text(pricingProblemText(it.code), color = MaterialTheme.colorScheme.error)
                }
            }
            item { Text("Погоджені роботи", style = MaterialTheme.typography.titleMedium) }
            items(state.lines.filter { it.bucket == PricingBucket.CONFIRMED }, key = { it.sourceKey }) { row ->
                QuoteLine(row, state, onSelect, onQuantity, onConfirmQuantity, onResetQuantity)
            }
            item {
                Text("Обговорюється", style = MaterialTheme.typography.titleMedium)
                Text("Додайте потрібну роботу явно. Це не змінює її погодження в замірі.")
            }
            items(state.lines.filter { it.bucket == PricingBucket.POTENTIAL }, key = { it.sourceKey }) { row ->
                QuoteLine(row, state, onSelect, onQuantity, onConfirmQuantity, onResetQuantity)
            }
            if (state.unresolved.any { it.bucket == PricingBucket.POTENTIAL }) item {
                Text("Потребують уточнення — обговорюється", style = MaterialTheme.typography.titleSmall)
                state.unresolved.filter { it.bucket == PricingBucket.POTENTIAL }.forEach {
                    Text("${it.label}: ${it.reasons.joinToString("; ", transform = ::pricingProblemText)}")
                }
            }
            if (omitted.isNotEmpty()) item {
                Text("Частковий — не враховано", style = MaterialTheme.typography.titleMedium)
                omitted.forEach {
                    Text("${label(it.sourceKey)}: " + if (it.reason == ExclusionReason.USER_EXCLUDED) "виключено вами"
                        else it.problems.joinToString("; ", transform = ::pricingProblemText))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.acknowledged, onAcknowledge, enabled = state.canAcknowledge,
                        modifier = Modifier.semantics { contentDescription = "Підтверджую цей перелік неврахованих робіт" })
                    Text("Підтверджую цей перелік неврахованих робіт")
                }
            }
            state.edits.keys.filter { id ->
                val element = source?.elements?.find { it.uuid == id }
                state.lines.none { it.elementUuid == id && it.sourceKey in state.selected } ||
                    source == null || element == null || !BillableOverrideRules.eligible(source, element)
            }.forEach { id ->
                item(key = "inactive-edit:$id") {
                    Text("Коригування «${label("element:$id") }» більше не відповідає включеній позиції. Приберіть його для розрахунку.")
                    TextButton(onClick = { onResetQuantity(id) }) { Text("Прибрати коригування") }
                }
            }
            item {
                DecimalInputField(state.discountText, onDiscount, "Загальна знижка, %",
                    enabled = source != null, isError = QuoteDiscount.parseBasisPoints(state.discountText) == null)
                Text("Від 0 до 100%, до двох знаків після коми")
                if (!state.discountConfirmed) {
                    Text("Підтвердьте знижку для нового підсумку")
                    OutlinedButton(onClick = onConfirmDiscount, enabled = QuoteDiscount.parseBasisPoints(state.discountText) != null) {
                        Text("Застосувати знижку")
                    }
                }
            }
            item(key = "quote-final-summary") {
                Card(Modifier.fillMaxWidth().graphicsLayer { alpha = handoff; translationY = (1f - handoff) * 20f }
                    .then(if (handoff < 0.5f) Modifier.clearAndSetSemantics {} else Modifier)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Підсумок", style = MaterialTheme.typography.titleMedium)
                        state.amounts?.let {
                            Text("До знижки: ${measurementMoney(it.subtotalCents)}")
                            Text("Знижка ${state.discountText}%: ${measurementMoney(it.discountCents)}")
                        }
                        Text(amount, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (partial) Text(if (state.acknowledged) "Частковий · перелік підтверджено" else "Частковий · перевірте невраховані роботи")
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteLine(row: LiveEstimateLine, state: TransientQuoteState,
    onSelect: (String, Boolean) -> Unit, onQuantity: (String, String) -> Unit,
    onConfirm: (String) -> Unit, onReset: (String) -> Unit) {
    val selected = row.sourceKey in state.selected
    val element = state.source?.elements?.find { it.uuid == row.elementUuid }
    val editable = element != null && state.source != null && BillableOverrideRules.eligible(state.source, element)
    val edit = state.edits[row.elementUuid]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, { onSelect(row.sourceKey, it) }, enabled = row.businessRole == BusinessRole.ADDITIONAL_WORK,
                    modifier = Modifier.semantics { contentDescription = "Включити ${element?.label ?: row.title}" })
                Column(Modifier.weight(1f)) {
                    Text(element?.label ?: row.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (row.bucket == PricingBucket.POTENTIAL) "Обговорюється · ${if (selected) "включено явно" else "не включено"}"
                        else if (row.businessRole == BusinessRole.ADDITIONAL_WORK) "Погоджено" else "Обов’язкова основа")
                }
            }
            Text("Із заміру: ${formatElementMilli(row.sourceQuantityMilli)} ${row.unit}")
            Text(if (edit != null && !edit.confirmed) "До оплати: очікує підтвердження кількості"
                else "До оплати: ${formatElementMilli(row.effectiveBillableQuantityMilli)} ${row.unit}")
            Text("Ціна прайсу: ${measurementMoney(row.unitPriceCents)} / ${row.unit}")
            if (state.amounts != null && selected) Text("Сума: ${measurementMoney(row.totalCents)}")
            row.reviewFlags.forEach { Text(pricingProblemText(it)) }
            if (editable && element != null && selected) {
                DecimalInputField(edit?.text ?: formatElementMilli(row.effectiveBillableQuantityMilli),
                    { onQuantity(element.uuid, it) }, "Розрахункова кількість, ${row.unit}",
                    isError = edit != null && TransientQuotePresenter.quantity(edit.text, row.unit) == null,
                    allowDecimalSeparator = row.unit !in setOf("шт.", "компл."))
                if (edit != null) {
                    if (!edit.confirmed) {
                        Text("Підтвердьте кількість для нового підсумку")
                        OutlinedButton(onClick = { onConfirm(element.uuid) },
                            enabled = TransientQuotePresenter.quantity(edit.text, row.unit) != null) { Text("Підтвердити кількість") }
                    }
                    TextButton(onClick = { onReset(element.uuid) }) { Text("Повернути із заміру") }
                }
            } else if (selected && row.businessRole == BusinessRole.ADDITIONAL_WORK) Text("Кількість визначена заміром; коригування цієї роботи недоступне.")
        }
    }
}
