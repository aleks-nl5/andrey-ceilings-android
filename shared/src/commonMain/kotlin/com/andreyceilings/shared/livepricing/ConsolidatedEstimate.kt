package com.andreyceilings.shared.livepricing

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Documentary values, copied from an immutable individual revision. No pricing methods by design. */
@Serializable
data class FrozenEstimateRow(val lineKey: String, val title: String, val unit: String,
    val quantityMilli: Long, val unitPriceCents: Long, val totalCents: Long, val note: String = "")
@Serializable
data class FrozenDrawingSnapshot(val formatVersion: String, val payload: String)
@Serializable
data class FrozenIndividualDocument(val roomTitle: String, val documentNumber: String,
    val rows: List<FrozenEstimateRow>, val drawing: FrozenDrawingSnapshot, val subtotalCents: Long,
    val detailsJson: String = "{}", val currency: String = "UAH")

/** Issued only by a trusted immutable-store verifier, never inferred from mutable measurement/catalog.
 * v14 LivePricingReader cannot issue this proof and returns PROVENANCE_MISSING.
 */
data class FrozenRevisionVerification(val projectUuid: String, val measurementUuid: String,
    val seriesUuid: String, val revisionUuid: String, val contentSignature: String)

data class FrozenIndividualRevision(val projectUuid: String, val measurementUuid: String,
    val seriesUuid: String, val revisionUuid: String, val document: FrozenIndividualDocument,
    val sourceSignatureAtSave: String, val catalogSignatureAtSave: String,
    val verification: FrozenRevisionVerification? = null, val isIndividualLeaf: Boolean = true,
    val deleted: Boolean = false) {
    val contentSignature get() = contentToken(listOf("frozen-individual-v1", projectUuid, measurementUuid, seriesUuid,
        revisionUuid, pricingJson.encodeToString(document), sourceSignatureAtSave, catalogSignatureAtSave, isIndividualLeaf.toString()))
}

data class FrozenRoomRevisionRef(val measurementUuid: String, val seriesUuid: String, val revisionUuid: String,
    val expectedContentSignature: String)

/** Ordered, explicit selected-room scope. Rooms absent from this list never gate composition.
 * Exactly one previously saved individual revision is required per selected room.
 */
data class ConsolidatedEstimateSelection(val projectUuid: String, val selectedMeasurementUuids: List<String>,
    val revisions: List<FrozenRoomRevisionRef>)

@Serializable
enum class ConsolidatedProblemCode {
    EMPTY_SELECTION, INVALID_IDENTITY, DUPLICATE_ROOM, DUPLICATE_REVISION, MISSING_ROOM_REVISION,
    AMBIGUOUS_ROOM_REVISION, UNSELECTED_ROOM, REVISION_UNAVAILABLE, CROSS_PROJECT, STALE_REVISION,
    PROVENANCE_MISSING, NOT_INDIVIDUAL_REVISION, FROZEN_SNAPSHOT_UNAVAILABLE, INVALID_FROZEN_TOTAL, CURRENCY_MISMATCH, OVERFLOW
}
@Serializable
data class ConsolidatedProblem(val code: ConsolidatedProblemCode, val measurementUuid: String? = null)

/** A detached frozen room section; its drawing/rows/amounts are the saved document's exact content. */
data class ConsolidatedRoomSection(val measurementUuid: String, val seriesUuid: String, val revisionUuid: String,
    val contentSignature: String, val document: FrozenIndividualDocument,
    val sourceSignatureAtSave: String, val catalogSignatureAtSave: String)

@ConsistentCopyVisibility
data class ConsolidatedEstimatePreview internal constructor(val projectUuid: String,
    val selectedMeasurementUuids: List<String>, val sections: List<ConsolidatedRoomSection>,
    val problems: List<ConsolidatedProblem>, val totalCents: Long?, val currency: String?, val signature: String) {
    val canCompose get() = problems.isEmpty() && sections.isNotEmpty() && sections.size == selectedMeasurementUuids.size && totalCents != null && currency != null
}

object ConsolidatedEstimateEngine {
    /** Frozen-only input: no LiveEstimatePreview, catalog, projectWorks, overrides or writer exist here.
     * Totals are SUM(saved individual subtotals). Never multiply unit prices, recompute geometry,
     * regenerate drawings, or apply today's individual-estimate mandatory-role policy to history.
     */
    fun buildPreview(selection: ConsolidatedEstimateSelection,
        revisions: Map<String, FrozenIndividualRevision>): ConsolidatedEstimatePreview {
        val problems = mutableListOf<ConsolidatedProblem>()
        val sections = mutableListOf<ConsolidatedRoomSection>()
        fun fail(code: ConsolidatedProblemCode, room: String? = null) { problems += ConsolidatedProblem(code, room) }
        val selected = selection.selectedMeasurementUuids
        if (selection.projectUuid.isBlank() || selected.any { it.isBlank() } || selection.revisions.any {
                it.measurementUuid.isBlank() || it.seriesUuid.isBlank() || it.revisionUuid.isBlank() || it.expectedContentSignature.isBlank()
            }) fail(ConsolidatedProblemCode.INVALID_IDENTITY)
        if (selected.isEmpty()) fail(ConsolidatedProblemCode.EMPTY_SELECTION)
        if (selected.distinct().size != selected.size) fail(ConsolidatedProblemCode.DUPLICATE_ROOM)
        if (selection.revisions.map { it.revisionUuid }.distinct().size != selection.revisions.size) fail(ConsolidatedProblemCode.DUPLICATE_REVISION)
        selection.revisions.filter { it.measurementUuid !in selected }.forEach { fail(ConsolidatedProblemCode.UNSELECTED_ROOM, it.measurementUuid) }
        for (room in selected.distinct()) {
            val refs = selection.revisions.filter { it.measurementUuid == room }
            if (refs.isEmpty()) { fail(ConsolidatedProblemCode.MISSING_ROOM_REVISION, room); continue }
            if (refs.size != 1) { fail(ConsolidatedProblemCode.AMBIGUOUS_ROOM_REVISION, room); continue }
            val ref = refs.single()
            val stored = revisions[ref.revisionUuid]
            if (stored == null || stored.deleted) { fail(ConsolidatedProblemCode.REVISION_UNAVAILABLE, room); continue }
            // Detach the section from a caller's mutable builder list before checking its receipt.
            val source = stored.copy(document = stored.document.copy(rows = stored.document.rows.toList()))
            val before = problems.size
            if (source.projectUuid != selection.projectUuid) fail(ConsolidatedProblemCode.CROSS_PROJECT, room)
            if (source.measurementUuid != room || source.seriesUuid != ref.seriesUuid || source.revisionUuid != ref.revisionUuid ||
                source.contentSignature != ref.expectedContentSignature) fail(ConsolidatedProblemCode.STALE_REVISION, room)
            if (!source.isIndividualLeaf) fail(ConsolidatedProblemCode.NOT_INDIVIDUAL_REVISION, room)
            val proof = source.verification
            if (proof == null || proof.projectUuid != source.projectUuid || proof.measurementUuid != source.measurementUuid ||
                proof.seriesUuid != source.seriesUuid || proof.revisionUuid != source.revisionUuid || proof.contentSignature != source.contentSignature)
                fail(ConsolidatedProblemCode.PROVENANCE_MISSING, room)
            val document = source.document
            if (document.rows.isEmpty() || document.rows.any { it.lineKey.isBlank() } ||
                document.rows.map { it.lineKey }.distinct().size != document.rows.size || document.drawing.formatVersion.isBlank() ||
                document.drawing.payload.isBlank() || document.currency.isBlank()) fail(ConsolidatedProblemCode.FROZEN_SNAPSHOT_UNAVAILABLE, room)
            if (document.subtotalCents < 0) fail(ConsolidatedProblemCode.INVALID_FROZEN_TOTAL, room)
            if (problems.size == before) sections += ConsolidatedRoomSection(room, source.seriesUuid, source.revisionUuid,
                source.contentSignature, document, source.sourceSignatureAtSave, source.catalogSignatureAtSave)
        }
        val currencies = sections.map { it.document.currency }.distinct()
        if (currencies.size > 1) fail(ConsolidatedProblemCode.CURRENCY_MISMATCH)
        val currency = currencies.singleOrNull()
        var total: Long? = null
        if (problems.isEmpty()) {
            try { total = sections.fold(0L) { sum, section -> PricingMath.add(sum, section.document.subtotalCents) } }
            catch (_: PricingOverflow) { fail(ConsolidatedProblemCode.OVERFLOW) }
        }
        val signature = contentToken(listOf("consolidated-frozen-v1", selection.projectUuid, contentToken(selected)) +
            selection.revisions.map { contentToken(listOf(it.measurementUuid, it.seriesUuid, it.revisionUuid,
                it.expectedContentSignature, revisions[it.revisionUuid]?.contentSignature.orEmpty())) })
        return ConsolidatedEstimatePreview(selection.projectUuid, selected.toList(), sections,
            problems.distinct().sortedWith(compareBy({ it.measurementUuid.orEmpty() }, { it.code.name })), total, currency, signature)
    }
}

/** Optional warning data, deliberately outside the consolidated financial API. */
data class CurrentMeasurementPricingBasis(val projectUuid: String, val measurementUuid: String,
    val sourceSignature: String, val catalogSignature: String)
enum class FrozenSourceChange { MEASUREMENT_CHANGED, CATALOG_CHANGED, CURRENT_BASIS_UNAVAILABLE, SAVED_BASIS_UNAVAILABLE }
data class FrozenSourceWarning(val measurementUuid: String, val revisionUuid: String, val changes: List<FrozenSourceChange>)

fun frozenSourceWarnings(preview: ConsolidatedEstimatePreview,
    current: Map<String, CurrentMeasurementPricingBasis>): List<FrozenSourceWarning> = preview.sections.mapNotNull { section ->
    val basis = current[section.measurementUuid]
    val changes = buildList {
        if (basis == null || basis.projectUuid != preview.projectUuid || basis.measurementUuid != section.measurementUuid)
            add(FrozenSourceChange.CURRENT_BASIS_UNAVAILABLE)
        else if (section.sourceSignatureAtSave.isBlank() || section.catalogSignatureAtSave.isBlank())
            add(FrozenSourceChange.SAVED_BASIS_UNAVAILABLE)
        else {
            if (basis.sourceSignature != section.sourceSignatureAtSave) add(FrozenSourceChange.MEASUREMENT_CHANGED)
            if (basis.catalogSignature != section.catalogSignatureAtSave) add(FrozenSourceChange.CATALOG_CHANGED)
        }
    }
    if (changes.isEmpty()) null else FrozenSourceWarning(section.measurementUuid, section.revisionUuid, changes)
}
