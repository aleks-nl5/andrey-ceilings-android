package com.andreyceilings.shared.livepricing

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Ephemeral selection for a NEW individual estimate of exactly one measurement.
 * This is neither a saved revision nor an object-level consolidated source.
 */
data class IndividualEstimateSelection(val projectUuid: String, val measurementUuid: String,
    val compositionUuid: String, val basisSourceSignature: String, val basisCatalogSignature: String,
    val expectedPreviewSignature: String, val includedSourceKeys: Set<String>? = null) {
    val scopeSignature get() = contentToken(listOf("individual-live-v1", projectUuid, measurementUuid, compositionUuid,
        basisSourceSignature, basisCatalogSignature) + (includedSourceKeys?.sorted() ?: listOf("ALL")))
}

@Serializable
enum class ExclusionReason { USER_EXCLUDED, UNRESOLVED }
@Serializable
data class ExcludedIndividualWork(val sourceKey: String, val reason: ExclusionReason,
    val problems: List<PricingProblemCode> = emptyList(), val bucket: PricingBucket)
@Serializable
data class IncludedIndividualWork(val line: LiveEstimateLine, val selectedForDocument: Boolean)

@ConsistentCopyVisibility
data class IndividualEstimatePreview internal constructor(val projectUuid: String, val measurementUuid: String,
    val signature: String, val included: List<IncludedIndividualWork>, val excluded: List<ExcludedIndividualWork>,
    val fatalProblems: List<PricingProblem>, val mandatoryProblems: List<PricingProblem>,
    val confirmedTotalCents: Long, val potentialTotalCents: Long, val selectedIncludedTotalCents: Long) {
    val potentialIncomplete get() = excluded.any { it.reason == ExclusionReason.UNRESOLVED && it.bucket == PricingBucket.POTENTIAL }
    val isPartial get() = excluded.any { it.bucket == PricingBucket.CONFIRMED }
    val canFinalizeFull get() = fatalProblems.isEmpty() && mandatoryProblems.isEmpty() && included.any { it.selectedForDocument } && !isPartial
    val canRequestPartialAcknowledgement get() = fatalProblems.isEmpty() && mandatoryProblems.isEmpty() && isPartial && included.any { it.selectedForDocument }
}

object IndividualEstimatePricing {
    /** No frozen revisions, other measurements, catalog lookup or project-wide work can enter this API. */
    fun previewSelection(selection: IndividualEstimateSelection, live: LiveEstimatePreview): IndividualEstimatePreview {
        val fatal = live.fatalProblems.toMutableList()
        fun fail(code: PricingProblemCode) { fatal += PricingProblem(code, selection.measurementUuid) }
        if (selection.projectUuid.isBlank() || selection.measurementUuid.isBlank() || selection.compositionUuid.isBlank())
            fail(PricingProblemCode.INVALID_SELECTION)
        if (selection.projectUuid != live.projectUuid) fail(PricingProblemCode.CROSS_PROJECT)
        if (selection.measurementUuid != live.measurementUuid || selection.compositionUuid != live.compositionUuid ||
            selection.basisSourceSignature != live.sourceSignature || selection.expectedPreviewSignature != live.signature ||
            selection.scopeSignature != live.scopeSignature) fail(PricingProblemCode.STALE_SOURCE)
        if (selection.basisCatalogSignature != live.catalogSignature) fail(PricingProblemCode.STALE_CATALOG)
        val keys = live.priceableLines.map { it.sourceKey }.toSet()
        if (selection.includedSourceKeys?.any { it !in keys } == true) fail(PricingProblemCode.INVALID_SELECTION)
        val included = mutableListOf<IncludedIndividualWork>()
        val excluded = mutableListOf<ExcludedIndividualWork>()
        live.priceableLines.forEach { line ->
            if (selection.includedSourceKeys == null || line.sourceKey in selection.includedSourceKeys)
                included += IncludedIndividualWork(line, line.bucket == PricingBucket.CONFIRMED || selection.includedSourceKeys != null)
            else excluded += ExcludedIndividualWork(line.sourceKey, ExclusionReason.USER_EXCLUDED, bucket = line.bucket)
        }
        live.unresolvedItems.forEach { excluded += ExcludedIndividualWork(it.sourceKey, ExclusionReason.UNRESOLVED, it.reasons, it.bucket) }
        val mandatory = live.mandatoryProblems + mandatoryRoleProblems(live.measurementUuid, live.businessRoles.values.toList(),
            included.filter { it.selectedForDocument }.map { it.line })
        if (included.none { it.selectedForDocument }) fail(PricingProblemCode.EMPTY_DOCUMENT)
        var confirmed = 0L
        var potential = 0L
        var selected = 0L
        try {
            included.forEach {
                if (it.line.bucket == PricingBucket.CONFIRMED) confirmed = PricingMath.add(confirmed, it.line.totalCents)
                else potential = PricingMath.add(potential, it.line.totalCents)
                if (it.selectedForDocument) selected = PricingMath.add(selected, it.line.totalCents)
            }
        } catch (_: PricingOverflow) { fail(PricingProblemCode.OVERFLOW); confirmed = 0; potential = 0; selected = 0 }
        val signature = contentToken(listOf(selection.scopeSignature, live.signature,
            pricingJson.encodeToString(included), pricingJson.encodeToString(excluded)))
        return IndividualEstimatePreview(selection.projectUuid, selection.measurementUuid, signature, included, excluded,
            fatal.distinct().sortedWith(compareBy({ it.sourceKey.orEmpty() }, { it.code.name })),
            mandatory.distinct().sortedWith(compareBy({ it.sourceKey.orEmpty() }, { it.code.name })), confirmed, potential, selected)
    }
}
