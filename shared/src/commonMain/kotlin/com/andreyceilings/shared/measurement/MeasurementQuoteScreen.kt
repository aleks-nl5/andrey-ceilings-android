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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.estimate.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun MeasurementQuoteScreen(presenter: TransientQuotePresenter, store: MeasurementElementsStore,
    route: ElementRoute, contentPadding: PaddingValues, systemTopInset: Dp, onBack: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val saveState by presenter.saveState.collectAsState()
    // Route/configuration restoration may retain a living presenter. Invalidate readiness
    // before collecting its initial state, so its previous total cannot flash before revalidation.
    val initialRead = remember(presenter, store, route, retry) { presenter.beginRead() }
    LaunchedEffect(presenter, store, route, retry) {
        var generation = initialRead
        store.observeQuotePricing(route).collect { result ->
            if (result == null) generation = presenter.beginRead() else presenter.acceptRead(generation, result)
        }
    }
    val state by presenter.state.collectAsState()
    MeasurementQuoteContent(state, { if (!presenter.saveState.value.saving) onBack() },
        { if (!presenter.saveState.value.saving) retry++ }, presenter::select, presenter::editQuantity,
        presenter::confirmQuantity, presenter::resetQuantity, presenter::editDiscount,
        presenter::confirmDiscount, presenter::acknowledge, contentPadding, systemTopInset,
        editingEnabled = !saveState.saving && saveState.resultSeriesUuid == null,
        saveFooter = {
            val actualTitle = saveState.title.orEmpty()
            val canSave = !saveState.saving && saveState.resultSeriesUuid == null && validEstimateTitle(actualTitle) != null && state.amounts != null &&
                (state.preview?.canFinalizeFull == true || state.preview?.canRequestPartialAcknowledgement == true && state.acknowledged)
            OutlinedTextField(actualTitle, presenter::editTitle, label = { Text("Назва кошторису") },
                enabled = !saveState.saving, singleLine = true, isError = actualTitle.isNotEmpty() && validEstimateTitle(actualTitle) == null,
                supportingText = { Text("Від 1 до 160 символів") }, modifier = Modifier.fillMaxWidth())
            if (saveState.saving) Text("Зберігаємо зафіксований кошторис. Дочекайтеся завершення.")
            saveState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!canSave && !saveState.saving) Text(when {
                validEstimateTitle(actualTitle) == null -> "Вкажіть назву кошторису (1–160 символів)."
                state.loading -> "Зачекайте завершення перевірки заміру та прайсу."
                state.unavailable != null -> state.unavailable?.let(::pricingProblemText).orEmpty()
                !state.discountConfirmed -> "Підтвердьте введену знижку."
                state.edits.values.any { !it.confirmed } -> "Підтвердьте введені кількості."
                state.preview?.canRequestPartialAcknowledgement == true && !state.acknowledged -> "Підтвердьте точний перелік неврахованих робіт."
                else -> "Сума недоступна. Перевірте склад і обов’язкові матеріали заміру."
            })
            Button(enabled = canSave, onClick = { store.submitQuote(presenter, route.projectId) }) {
                Text(if (saveState.saving) "Зберігаю…" else "Зберегти кошторис")
            }
        })
    saveState.resultSeriesUuid?.let { id -> SavedIndividualQuoteDialog(store, id) { presenter.consumeSaved(); onBack() } }
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
    contentPadding: PaddingValues = PaddingValues(16.dp), systemTopInset: Dp = 0.dp,
    saveFooter: (@Composable () -> Unit)? = null, editingEnabled: Boolean = true) {
    val list = rememberLazyListState()
    // Hosts reserve bottom space for their floating navigation/system bars. It is not
    // unobscured viewport, even while a lazy item is temporarily laid out behind it.
    val obscuredBottom = with(LocalDensity.current) { contentPadding.calculateBottomPadding().roundToPx() }
    val visibility by remember(list, obscuredBottom) { derivedStateOf {
        val layout = list.layoutInfo
        layout.visibleItemsInfo.find { it.key == "quote-final-summary" }?.let {
            quoteSummaryVisibility(it.offset, it.size, layout.viewportStartOffset, layout.viewportEndOffset - obscuredBottom)
        } ?: 0f
    } }
    // Compose's animation clock observes MotionDurationScale, including the platform zero-motion setting.
    val handoff by animateFloatAsState(visibility, tween(180))
    // Accessibility follows measured visibility immediately: a virtualized bottom item can
    // disappear before the reverse fade has finished, and must not leave the total unannounced.
    val bottomOwnsSemantics = visibility >= 0.5f
    val amount = state.amounts?.let { measurementMoney(it.finalCents) } ?: "Сума недоступна / Перевірте замір"
    val partial = state.preview?.isPartial == true
    val source = state.source
    val omitted = state.preview?.excluded.orEmpty().filter { it.bucket == PricingBucket.CONFIRMED }
    fun label(key: String) = source?.elements?.find { "element:${it.uuid}" == key }?.label ?: "Позиція заміру"
    Column(Modifier.fillMaxSize().padding(top = systemTopInset).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = editingEnabled) { Text("До заміру") }
            Text("Попередній кошторис", style = MaterialTheme.typography.titleMedium)
        }
        // Reserved height prevents a scroll jump when ownership passes to the bottom summary.
        Box(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp)
            .graphicsLayer { alpha = 1f - handoff; translationY = -12f * handoff }
            .then(if (bottomOwnsSemantics) Modifier.clearAndSetSemantics {} else Modifier)) {
            Column {
                Text(amount, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (partial) Text("Частковий", style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("До явного збереження зміни залишаються в пам’яті до виходу із заміру.")
                if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Оновлення розрахунку…") }
                state.unavailable?.let {
                    Text(pricingProblemText(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh, enabled = editingEnabled) { Text("Оновити розрахунок") }
                }
                (state.preview?.fatalProblems.orEmpty() + state.preview?.mandatoryProblems.orEmpty()).distinct().forEach {
                    Text(pricingProblemText(it.code), color = MaterialTheme.colorScheme.error)
                }
            }
            items(state.unavailableSelectedDiscussing, key = { "unavailable-selected:${it.sourceKey}" }) { work ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Вибрана робота потребує перевірки", style = MaterialTheme.typography.titleMedium)
                        Text(work.label)
                        Text(if (work.problems.isEmpty()) "Робота більше не доступна в цьому складі заміру."
                            else work.problems.joinToString("; ", transform = ::pricingProblemText))
                        Text("Сума недоступна до виправлення роботи або вашого явного виключення.")
                        TextButton(onClick = { onSelect(work.sourceKey, false) }, enabled = editingEnabled && source != null) {
                            Text("Не включати «${work.label}»")
                        }
                    }
                }
            }
            item { Text("Погоджені роботи", style = MaterialTheme.typography.titleMedium) }
            items(state.lines.filter { it.bucket == PricingBucket.CONFIRMED }, key = { it.sourceKey }) { row ->
                QuoteLine(row, state, onSelect, onQuantity, onConfirmQuantity, onResetQuantity, editingEnabled)
            }
            item {
                Text("Обговорюється", style = MaterialTheme.typography.titleMedium)
                Text("Додайте потрібну роботу явно. Це не змінює її погодження в замірі.")
            }
            items(state.lines.filter { it.bucket == PricingBucket.POTENTIAL }, key = { it.sourceKey }) { row ->
                QuoteLine(row, state, onSelect, onQuantity, onConfirmQuantity, onResetQuantity, editingEnabled)
            }
            val advisory = state.unresolved.filter { it.bucket == PricingBucket.POTENTIAL && it.sourceKey !in state.selected }
            if (advisory.isNotEmpty()) item {
                Text("Потребують уточнення — обговорюється", style = MaterialTheme.typography.titleSmall)
                advisory.forEach {
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
                    Checkbox(state.acknowledged, onAcknowledge, enabled = editingEnabled && state.canAcknowledge,
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
                    TextButton(onClick = { onResetQuantity(id) }, enabled = editingEnabled) { Text("Прибрати коригування") }
                }
            }
            item {
                DecimalInputField(state.discountText, onDiscount, "Загальна знижка, %",
                    enabled = editingEnabled && source != null, isError = QuoteDiscount.parseBasisPoints(state.discountText) == null)
                Text("Від 0 до 100%, до двох знаків після коми")
                if (!state.discountConfirmed) {
                    Text("Підтвердьте знижку для нового підсумку")
                    OutlinedButton(onClick = onConfirmDiscount, enabled = editingEnabled && QuoteDiscount.parseBasisPoints(state.discountText) != null) {
                        Text("Застосувати знижку")
                    }
                }
            }
            item(key = "quote-final-summary") {
                Card(Modifier.fillMaxWidth().graphicsLayer { alpha = handoff; translationY = (1f - handoff) * 20f }
                    .then(if (!bottomOwnsSemantics) Modifier.clearAndSetSemantics {} else Modifier)) {
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
            if (saveFooter != null) item(key = "quote-save") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { saveFooter() }
            }
        }
    }
}

@Composable
private fun QuoteLine(row: LiveEstimateLine, state: TransientQuoteState,
    onSelect: (String, Boolean) -> Unit, onQuantity: (String, String) -> Unit,
    onConfirm: (String) -> Unit, onReset: (String) -> Unit, editingEnabled: Boolean) {
    val selected = row.sourceKey in state.selected
    val element = state.source?.elements?.find { it.uuid == row.elementUuid }
    val editable = element != null && state.source != null && BillableOverrideRules.eligible(state.source, element)
    val edit = state.edits[row.elementUuid]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, { onSelect(row.sourceKey, it) }, enabled = editingEnabled && row.businessRole == BusinessRole.ADDITIONAL_WORK,
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
                    enabled = editingEnabled,
                    isError = edit != null && TransientQuotePresenter.quantity(edit.text, row.unit) == null,
                    allowDecimalSeparator = row.unit !in setOf("шт.", "компл."))
                if (edit != null) {
                    if (!edit.confirmed) {
                        Text("Підтвердьте кількість для нового підсумку")
                        OutlinedButton(onClick = { onConfirm(element.uuid) },
                            enabled = editingEnabled && TransientQuotePresenter.quantity(edit.text, row.unit) != null) { Text("Підтвердити кількість") }
                    }
                    TextButton(onClick = { onReset(element.uuid) }, enabled = editingEnabled) { Text("Повернути із заміру") }
                }
            } else if (selected && row.businessRole == BusinessRole.ADDITIONAL_WORK) Text("Кількість визначена заміром; коригування цієї роботи недоступне.")
        }
    }
}
