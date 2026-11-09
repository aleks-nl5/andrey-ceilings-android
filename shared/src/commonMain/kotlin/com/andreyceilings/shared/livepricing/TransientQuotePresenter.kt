package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class QuoteSaveState(val title: String? = null, val saving: Boolean = false, val resultSeriesUuid: String? = null, val error: String? = null, val resultRevisionUuid: String? = null)

data class QuoteQuantityEdit(val text: String, val confirmed: Boolean = false)
data class UnavailableSelectedWork(val sourceKey: String, val label: String,
    val problems: List<PricingProblemCode> = emptyList())
data class TransientQuoteState(
    val loading: Boolean = true, val unavailable: PricingProblemCode? = null,
    val source: LiveEstimateSource? = null, val lines: List<LiveEstimateLine> = emptyList(),
    val unresolved: List<UnresolvedWork> = emptyList(), val selected: Set<String> = emptySet(),
    val unavailableSelectedDiscussing: List<UnavailableSelectedWork> = emptyList(),
    val edits: Map<String, QuoteQuantityEdit> = emptyMap(), val discountText: String = "0",
    val discountConfirmed: Boolean = true, val preview: IndividualEstimatePreview? = null,
    val amounts: QuoteAmounts? = null, val fingerprint: String? = null,
    val acknowledgedFingerprint: String? = null
) {
    val acknowledged get() = fingerprint != null && acknowledgedFingerprint == fingerprint
    val canAcknowledge get() = amounts != null && preview?.canRequestPartialAcknowledgement == true
}

/** Main-thread confined, memory only. Every read is fenced by visit + request generation. */
class TransientQuotePresenter(val measurementUuid: String, val sessionId: String = newRecordUuid(),
    val revisionBase: SavedIndividualQuote? = null) {
    val revisionTarget = revisionBase?.let { IndividualRevisionTarget(it.seriesUuid, it.revisionUuid, it.versionNumber, it.statusVersion) }
    val compositionUuid: String = newRecordUuid()
    private var request = 0L
    private val closedState = MutableStateFlow(false)
    private val closed get() = closedState.value
    private val saveMutable = MutableStateFlow(QuoteSaveState())
    val saveState: StateFlow<QuoteSaveState> = saveMutable.asStateFlow()
    private var pendingSave: IndividualEstimateSaveRequest? = null
    private val captured get() = saveMutable.value.saving || saveMutable.value.resultSeriesUuid != null
    private val editable get() = !closed && !captured
    private var deferredRead: PricingReadResult? = null
    private var deferredInvalidation = false
    fun editTitle(value: String) { if (editable) { saveMutable.value = saveMutable.value.copy(title = value); pendingSave = null } }
    fun beginSave(projectId: Long): IndividualEstimateSaveRequest? {
        if (saveMutable.value.saving || saveMutable.value.resultSeriesUuid != null) return null
        val current = saveRequest(projectId, saveMutable.value.title.orEmpty()) ?: return null
        val previous = pendingSave
        val command = if (previous != null && current.copy(operationUuid = previous.operationUuid) == previous) previous else current
        pendingSave = command
        saveMutable.value = saveMutable.value.copy(saving = true, error = null)
        return command
    }
    fun saved(seriesUuid: String, revisionUuid: String? = null) { saveMutable.value = saveMutable.value.copy(saving = false, resultSeriesUuid = seriesUuid, resultRevisionUuid = revisionUuid, error = null) }
    fun saveFailed(error: String) {
        saveMutable.value = saveMutable.value.copy(saving = false, error = error)
        releaseDeferredRead()
    }
    fun consumeSaved() {
        if (saveMutable.value.saving) return
        pendingSave = null
        saveMutable.value = saveMutable.value.copy(resultSeriesUuid = null, resultRevisionUuid = null)
        releaseDeferredRead()
    }
    private var saveSelection: IndividualEstimateSelection? = null
    private var saveOverrides: List<EstimateQuantityOverride> = emptyList()
    private var loaded: PricingReadResult.Loaded? = null
    private var excludedConfirmed = emptySet<String>()
    // Explicit intent survives missing prices/quantities and even removal of the source row.
    // Retain the last known label so the user can identify and explicitly remove a missing work.
    private var includedDiscussing = emptyMap<String, String>()
    private val mutable = MutableStateFlow(TransientQuoteState())
    val state: StateFlow<TransientQuoteState> = mutable.asStateFlow()

    fun beginRead(): Long {
        request++
        if (!closed && captured) {
            deferredRead = null
            deferredInvalidation = true
            return request
        }
        invalidateRead()
        return request
    }

    private fun invalidateRead() {
        loaded = null
        mutable.value = mutable.value.copy(loading = !closed, unavailable = if (closed) PricingProblemCode.DATABASE_UNAVAILABLE else null,
            source = null, lines = emptyList(), unresolved = emptyList(), unavailableSelectedDiscussing = emptyList(), preview = null,
            amounts = null, fingerprint = null, acknowledgedFingerprint = null)
    }

    private fun releaseDeferredRead() {
        val result = deferredRead
        val invalidated = deferredInvalidation
        deferredRead = null
        deferredInvalidation = false
        if (closed) return
        if (result != null) acceptRead(request, result) else if (invalidated) invalidateRead()
    }

    fun acceptRead(generation: Long, result: PricingReadResult) {
        if (closed || generation != request) return
        if (captured) { deferredRead = result; return }
        if (result is PricingReadResult.Unavailable) {
            loaded = null
            mutable.value = mutable.value.copy(loading = false, unavailable = result.problem,
                source = null, lines = emptyList(), unresolved = emptyList(), unavailableSelectedDiscussing = emptyList(), preview = null,
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
        if (saveMutable.value.title == null) saveMutable.value = saveMutable.value.copy(title = result.source.versions["measurementName"].orEmpty())
        mutable.update { if (closed) it else it.copy(loading = false, unavailable = null, source = result.source,
            acknowledgedFingerprint = null) }
        rebuild()
    }
    private var lastBasis: String? = null

    fun select(sourceKey: String, included: Boolean) {
        val s = mutable.value
        if (s.loading || !editable) return
        if (!included && sourceKey in includedDiscussing) {
            includedDiscussing = includedDiscussing - sourceKey
            // A previously discussing work may have become confirmed in the refreshed source.
            if (s.source?.elements?.any { elementKey(it.uuid) == sourceKey &&
                    it.approval == ElementApproval.APPROVED && it.businessRole == BusinessRole.ADDITIONAL_WORK } == true)
                excludedConfirmed = excludedConfirmed + sourceKey
            changed(); rebuild(); return
        }
        val row = s.lines.find { it.sourceKey == sourceKey } ?: return
        if (row.businessRole != BusinessRole.ADDITIONAL_WORK) return
        if (row.bucket == PricingBucket.CONFIRMED) excludedConfirmed =
            if (included) excludedConfirmed - sourceKey else excludedConfirmed + sourceKey
        else if (included) includedDiscussing = includedDiscussing + (sourceKey to
            (s.source?.elements?.find { elementKey(it.uuid) == sourceKey }?.label ?: row.title))
        changed(); rebuild()
    }

    fun editQuantity(elementUuid: String, text: String) {
        if (!editable) return
        val result = loaded ?: return
        val e = result.source.elements.find { it.uuid == elementUuid } ?: return
        if (!BillableOverrideRules.eligible(result.source, e) || mutable.value.lines.none { it.elementUuid == elementUuid }) return
        mutable.value = mutable.value.copy(edits = mutable.value.edits + (elementUuid to QuoteQuantityEdit(text)))
        changed(); rebuild()
    }
    fun confirmQuantity(elementUuid: String) {
        if (!editable) return
        val edit = mutable.value.edits[elementUuid] ?: return
        val e = loaded?.source?.elements?.find { it.uuid == elementUuid } ?: return
        if (quantity(edit.text, e.unit) == null) return
        mutable.value = mutable.value.copy(edits = mutable.value.edits + (elementUuid to edit.copy(confirmed = true)))
        changed(); rebuild()
    }
    fun resetQuantity(elementUuid: String) {
        if (!editable) return
        mutable.value = mutable.value.copy(edits = mutable.value.edits - elementUuid)
        changed(); rebuild()
    }
    fun editDiscount(text: String) {
        if (!editable) return
        mutable.value = mutable.value.copy(discountText = text, discountConfirmed = false)
        changed(); rebuild()
    }
    fun confirmDiscount() {
        if (QuoteDiscount.parseBasisPoints(mutable.value.discountText) == null || !editable) return
        mutable.value = mutable.value.copy(discountConfirmed = true)
        changed(); rebuild()
    }
    fun acknowledge(checked: Boolean) {
        mutable.update { s -> if (!editable) s else s.copy(
            acknowledgedFingerprint = if (checked && s.canAcknowledge) s.fingerprint else null) }
    }
    fun close() { closedState.value = true; beginRead(); excludedConfirmed = emptySet(); includedDiscussing = emptyMap()
        mutable.value = TransientQuoteState(loading = false, unavailable = PricingProblemCode.DATABASE_UNAVAILABLE) }

    /** Captures one explicit intent; calling code retains this object across uncertain retries. */
    fun saveRequest(projectId: Long, title: String): IndividualEstimateSaveRequest? {
        val s = mutable.value
        val selection = saveSelection ?: return null
        val amounts = s.amounts ?: return null
        val fingerprint = s.fingerprint ?: return null
        if (closed || s.loading || s.unavailable != null ||
            !(s.preview?.canFinalizeFull == true || s.preview?.canRequestPartialAcknowledgement == true && s.acknowledged)) return null
        return IndividualEstimateSaveRequest(newRecordUuid(), projectId, validEstimateTitle(title) ?: return null,
            sessionId, selection, saveOverrides.toList(), s.discountText, amounts, fingerprint,
            s.acknowledgedFingerprint, s.preview.excluded.filter { it.bucket == PricingBucket.CONFIRMED })
    }

    /** Explicit, all-or-nothing import of the selected supported decision. Prices never enter this path. */
    fun carryDiscount(): Boolean {
        val old = revisionBase?.frozen ?: return false
        if (!editable || loaded == null || QuoteDiscount.parseBasisPoints(old.discountText) == null) return false
        editDiscount(old.discountText) // Requires the ordinary fresh confirmation.
        return true
    }
    private fun carryMatch(id: String): LiveEstimateLine? {
        val old = revisionBase?.frozen ?: return null
        val current = mutable.value
        val before = old.source.elements.singleOrNull { it.uuid == id } ?: return null
        val after = current.source?.elements?.singleOrNull { it.uuid == id } ?: return null
        val oldCatalog = before.source as? ElementSource.CatalogSelection ?: return null
        val newCatalog = after.source as? ElementSource.CatalogSelection ?: return null
        if (before.measurementUuid != measurementUuid || after.measurementUuid != measurementUuid ||
            oldCatalog.priceItemUuid != newCatalog.priceItemUuid || oldCatalog.quantityMode != newCatalog.quantityMode ||
            oldCatalog.placementKind != newCatalog.placementKind || before.unit != after.unit ||
            before.businessRole != after.businessRole || before.approval != after.approval) return null
        return current.lines.singleOrNull { it.elementUuid == id && it.sourceQuantityMilli > 0 && it.unit == after.unit && it.businessRole == after.businessRole }
    }
    fun carryQuantity(elementUuid: String): Boolean {
        if (!editable) return false
        val old = revisionBase?.frozen?.rows?.singleOrNull { it.line.elementUuid == elementUuid }?.line ?: return false
        val current = carryMatch(elementUuid) ?: return false
        val source = loaded?.source ?: return false
        val e = source.elements.singleOrNull { it.uuid == elementUuid } ?: return false
        if (old.overrideNote == null || !BillableOverrideRules.eligible(source, e) || current.sourceKey !in mutable.value.selected ||
            quantity(formatElementMilli(old.effectiveBillableQuantityMilli), current.unit) == null) return false
        editQuantity(elementUuid, formatElementMilli(old.effectiveBillableQuantityMilli))
        return true // Unconfirmed until the user's ordinary quantity confirmation.
    }
    fun carrySelection(): Boolean {
        if (!editable || loaded == null) return false
        val old = revisionBase?.frozen ?: return false
        val included = old.rows.filter { it.line.bucket == PricingBucket.POTENTIAL }.map { it.line.sourceKey }
        val excluded = old.omissions.filter { it.reason == ExclusionReason.USER_EXCLUDED }.map { it.sourceKey }
        val decisions = (included.map { it to true } + excluded.map { it to false })
        if (decisions.isEmpty()) return false
        val matched = decisions.map { (key, selected) ->
            val row = carryMatch(key.removePrefix("element:")) ?: return false
            if (row.sourceKey != key || row.businessRole != BusinessRole.ADDITIONAL_WORK) return false
            row to selected
        }
        matched.forEach { (row, selected) -> select(row.sourceKey, selected) }
        return true
    }

    private fun changed() { mutable.value = mutable.value.copy(acknowledgedFingerprint = null, amounts = null, fingerprint = null) }
    private fun rebuild() {
        val data = loaded ?: return
        val source = data.source
        val roles = BusinessRoleBindings(source.signature, source.elements.mapNotNull { e -> e.businessRole?.let { e.uuid to it } }.toMap())
        val base = LivePricingEngine.buildPreview(source, data.catalog, roles, compositionUuid)
        val unavailableSelected = includedDiscussing.mapNotNull { (key, label) ->
            if (base.priceableLines.any { it.sourceKey == key }) null
            else UnavailableSelectedWork(key, source.elements.find { elementKey(it.uuid) == key }?.label ?: label,
                base.unresolvedItems.find { it.sourceKey == key }?.reasons.orEmpty())
        }
        val keys = base.priceableLines.filter { row ->
            if (row.bucket == PricingBucket.CONFIRMED) row.businessRole != BusinessRole.ADDITIONAL_WORK || row.sourceKey !in excludedConfirmed
            else row.businessRole == BusinessRole.ADDITIONAL_WORK && row.sourceKey in includedDiscussing
        }.map { it.sourceKey }.toSet() + includedDiscussing.keys
        // A temporarily unpriced row keeps the user's exclusion intent while its stable source still exists.
        excludedConfirmed = excludedConfirmed.intersect(source.elements.filter {
            it.approval == ElementApproval.APPROVED && it.businessRole == BusinessRole.ADDITIONAL_WORK
        }.map { elementKey(it.uuid) }.toSet())
        var selection = IndividualEstimateSelection(source.projectUuid, measurementUuid, compositionUuid,
            source.signature, data.catalog.signature, "", keys)
        val s = mutable.value
        var inputValid = s.discountConfirmed && unavailableSelected.isEmpty()
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
        saveSelection = selection
        saveOverrides = overrides.toList()
        val preview = IndividualEstimatePricing.previewSelection(selection, live)
        val bp = QuoteDiscount.parseBasisPoints(s.discountText)
        val amounts = if (inputValid && bp != null && (preview.canFinalizeFull || preview.canRequestPartialAcknowledgement))
            QuoteDiscount.calculate(preview.selectedIncludedTotalCents, bp) else null
        val fingerprint = amounts?.let { contentToken(listOf(sessionId, preview.signature, s.discountText, bp.toString(),
            it.subtotalCents.toString(), it.discountCents.toString(), it.finalCents.toString())) }
        mutable.update { if (closed) it else s.copy(source = source, lines = live.priceableLines, unresolved = live.unresolvedItems, selected = keys,
            unavailableSelectedDiscussing = unavailableSelected, preview = preview, amounts = amounts, fingerprint = fingerprint) }
    }
    companion object {
        fun quantity(text: String, unit: String?): Long? = parseElementMilli(text, unit)?.takeIf {
            unit !in setOf("шт.", "компл.") || it % 1000L == 0L
        }
    }
}
