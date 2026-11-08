package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class QuoteQuantityEdit(val text: String, val confirmed: Boolean = false)
data class TransientQuoteState(
    val loading: Boolean = true, val unavailable: PricingProblemCode? = null,
    val source: LiveEstimateSource? = null, val lines: List<LiveEstimateLine> = emptyList(),
    val unresolved: List<UnresolvedWork> = emptyList(), val selected: Set<String> = emptySet(),
    val edits: Map<String, QuoteQuantityEdit> = emptyMap(), val discountText: String = "0",
    val discountConfirmed: Boolean = true, val preview: IndividualEstimatePreview? = null,
    val amounts: QuoteAmounts? = null, val fingerprint: String? = null,
    val acknowledgedFingerprint: String? = null
) {
    val acknowledged get() = fingerprint != null && acknowledgedFingerprint == fingerprint
    val canAcknowledge get() = amounts != null && preview?.canRequestPartialAcknowledgement == true
}

/** Main-thread confined, memory only. Every read is fenced by visit + request generation. */
class TransientQuotePresenter(val measurementUuid: String, val sessionId: String = newRecordUuid()) {
    val compositionUuid: String = newRecordUuid()
    private var request = 0L
    private val closedState = MutableStateFlow(false)
    private val closed get() = closedState.value
    private var loaded: PricingReadResult.Loaded? = null
    private var excludedConfirmed = emptySet<String>()
    private var includedDiscussing = emptySet<String>()
    private val mutable = MutableStateFlow(TransientQuoteState())
    val state: StateFlow<TransientQuoteState> = mutable.asStateFlow()

    fun beginRead(): Long {
        request++
        loaded = null
        mutable.value = mutable.value.copy(loading = !closed, unavailable = if (closed) PricingProblemCode.DATABASE_UNAVAILABLE else null,
            source = null, lines = emptyList(), unresolved = emptyList(), preview = null,
            amounts = null, fingerprint = null, acknowledgedFingerprint = null)
        return request
    }

    fun acceptRead(generation: Long, result: PricingReadResult) {
        if (closed || generation != request) return
        if (result is PricingReadResult.Unavailable) {
            loaded = null
            mutable.value = mutable.value.copy(loading = false, unavailable = result.problem,
                source = null, lines = emptyList(), unresolved = emptyList(), preview = null,
                amounts = null, fingerprint = null, acknowledgedFingerprint = null)
            return
        }
        result as PricingReadResult.Loaded
        if (result.source.measurementUuid != measurementUuid) {
            acceptRead(generation, PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE)); return
        }
        // Changed bases require explicitly confirming billable edits again. Never silently rebind overrides.
        val basis = contentToken(listOf(result.source.signature, result.catalog.signature))
        if (basis != lastBasis) {
            mutable.value = mutable.value.copy(edits = mutable.value.edits.mapValues { it.value.copy(confirmed = false) })
            lastBasis = basis
        }
        loaded = result
        mutable.update { if (closed) it else it.copy(loading = false, unavailable = null, source = result.source,
            acknowledgedFingerprint = null) }
        rebuild()
    }
    private var lastBasis: String? = null

    fun select(sourceKey: String, included: Boolean) {
        val s = mutable.value
        val row = s.lines.find { it.sourceKey == sourceKey } ?: return
        if (row.businessRole != BusinessRole.ADDITIONAL_WORK || s.loading || closed) return
        if (row.bucket == PricingBucket.CONFIRMED) excludedConfirmed =
            if (included) excludedConfirmed - sourceKey else excludedConfirmed + sourceKey
        else includedDiscussing = if (included) includedDiscussing + sourceKey else includedDiscussing - sourceKey
        changed(); rebuild()
    }

    fun editQuantity(elementUuid: String, text: String) {
        val result = loaded ?: return
        val e = result.source.elements.find { it.uuid == elementUuid } ?: return
        if (!BillableOverrideRules.eligible(result.source, e) || mutable.value.lines.none { it.elementUuid == elementUuid }) return
        mutable.value = mutable.value.copy(edits = mutable.value.edits + (elementUuid to QuoteQuantityEdit(text)))
        changed(); rebuild()
    }
    fun confirmQuantity(elementUuid: String) {
        val edit = mutable.value.edits[elementUuid] ?: return
        val e = loaded?.source?.elements?.find { it.uuid == elementUuid } ?: return
        if (quantity(edit.text, e.unit) == null) return
        mutable.value = mutable.value.copy(edits = mutable.value.edits + (elementUuid to edit.copy(confirmed = true)))
        changed(); rebuild()
    }
    fun resetQuantity(elementUuid: String) {
        mutable.value = mutable.value.copy(edits = mutable.value.edits - elementUuid)
        changed(); rebuild()
    }
    fun editDiscount(text: String) {
        if (closed) return
        mutable.value = mutable.value.copy(discountText = text, discountConfirmed = false)
        changed(); rebuild()
    }
    fun confirmDiscount() {
        if (QuoteDiscount.parseBasisPoints(mutable.value.discountText) == null || closed) return
        mutable.value = mutable.value.copy(discountConfirmed = true)
        changed(); rebuild()
    }
    fun acknowledge(checked: Boolean) {
        mutable.update { s -> if (closed) s else s.copy(
            acknowledgedFingerprint = if (checked && s.canAcknowledge) s.fingerprint else null) }
    }
    fun close() { closedState.value = true; beginRead(); excludedConfirmed = emptySet(); includedDiscussing = emptySet()
        mutable.value = TransientQuoteState(loading = false, unavailable = PricingProblemCode.DATABASE_UNAVAILABLE) }

    private fun changed() { mutable.value = mutable.value.copy(acknowledgedFingerprint = null, amounts = null, fingerprint = null) }
    private fun rebuild() {
        val data = loaded ?: return
        val source = data.source
        val roles = BusinessRoleBindings(source.signature, source.elements.mapNotNull { e -> e.businessRole?.let { e.uuid to it } }.toMap())
        val base = LivePricingEngine.buildPreview(source, data.catalog, roles, compositionUuid)
        val keys = base.priceableLines.filter { row ->
            if (row.bucket == PricingBucket.CONFIRMED) row.businessRole != BusinessRole.ADDITIONAL_WORK || row.sourceKey !in excludedConfirmed
            else row.businessRole == BusinessRole.ADDITIONAL_WORK && row.sourceKey in includedDiscussing
        }.map { it.sourceKey }.toSet()
        excludedConfirmed = excludedConfirmed.intersect(base.priceableLines.filter { it.bucket == PricingBucket.CONFIRMED }.map { it.sourceKey }.toSet())
        includedDiscussing = includedDiscussing.intersect(base.priceableLines.filter { it.bucket == PricingBucket.POTENTIAL }.map { it.sourceKey }.toSet())
        var selection = IndividualEstimateSelection(source.projectUuid, measurementUuid, compositionUuid,
            source.signature, data.catalog.signature, "", keys)
        val s = mutable.value
        var inputValid = s.discountConfirmed
        val overrides = s.edits.mapNotNull { (id, edit) ->
            val element = source.elements.find { it.uuid == id }
            val value = quantity(edit.text, element?.unit)
            if (element == null || !edit.confirmed || value == null || !BillableOverrideRules.eligible(source, element) ||
                base.priceableLines.none { it.elementUuid == id }) { inputValid = false; null }
            else EstimateQuantityOverride(compositionUuid, source.projectUuid, measurementUuid, id,
                checkNotNull(element.businessRole), value, checkNotNull(element.unit), source.signature,
                data.catalog.signature, true, selection.scopeSignature)
        }
        val live = LivePricingEngine.buildPreview(source, data.catalog, roles, compositionUuid, overrides, selection.scopeSignature)
        selection = selection.copy(expectedPreviewSignature = live.signature)
        val preview = IndividualEstimatePricing.previewSelection(selection, live)
        val bp = QuoteDiscount.parseBasisPoints(s.discountText)
        val amounts = if (inputValid && bp != null && (preview.canFinalizeFull || preview.canRequestPartialAcknowledgement))
            QuoteDiscount.calculate(preview.selectedIncludedTotalCents, bp) else null
        val fingerprint = amounts?.let { contentToken(listOf(sessionId, preview.signature, s.discountText, bp.toString(),
            it.subtotalCents.toString(), it.discountCents.toString(), it.finalCents.toString())) }
        mutable.update { if (closed) it else s.copy(source = source, lines = live.priceableLines, unresolved = live.unresolvedItems, selected = keys,
            preview = preview, amounts = amounts, fingerprint = fingerprint) }
    }
    companion object {
        fun quantity(text: String, unit: String?): Long? = parseElementMilli(text, unit)?.takeIf {
            unit !in setOf("шт.", "компл.") || it % 1000L == 0L
        }
    }
}
