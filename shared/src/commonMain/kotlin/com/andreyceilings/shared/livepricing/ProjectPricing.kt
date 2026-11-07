package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.prices.PriceCatalogRules
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** These records are a trust boundary: a reader must verify ownership, leaf coverage and exact refs.
 * v14 history has no row provenance, so the production reader rejects it instead of manufacturing these.
 */
data class VerifiedFrozenRevision(val projectUuid: String, val seriesUuid: String, val revisionUuid: String,
    val lines: List<LiveEstimateLine>, val contentSignature: String, val isLeaf: Boolean,
    val provenanceVerified: Boolean, val omitted: List<UnresolvedWork> = emptyList())

sealed interface ProjectSourceSelection {
    val sourceId: String
    data class Live(override val sourceId: String, val expectedSignature: String,
        val measurementUuid: String, val basisSourceSignature: String, val basisCatalogSignature: String,
        val includedSourceKeys: Set<String>? = null) : ProjectSourceSelection
    data class Frozen(override val sourceId: String, val seriesUuid: String, val revisionUuid: String,
        val expectedSignature: String) : ProjectSourceSelection
}

@Serializable
data class ProjectWideWork(val uuid: String, val projectUuid: String, val catalogItemUuid: String,
    val expectedCode: String, val unit: String, val quantityMilli: Long, val bucket: PricingBucket,
    val basisCatalogSignature: String, val includePotentialInDocument: Boolean = false)

data class ProjectEstimateSelection(val projectUuid: String, val compositionUuid: String,
    val sources: List<ProjectSourceSelection>, val projectWorks: List<ProjectWideWork> = emptyList()) {
    /** Defined before pricing: excludes preview signatures to avoid a circular override token. */
    val scopeSignature: String get() = contentToken(listOf(projectUuid, compositionUuid) + sources.map { source ->
        when (source) {
            is ProjectSourceSelection.Live -> contentToken(listOf("LIVE", source.sourceId, source.measurementUuid, source.basisSourceSignature, source.basisCatalogSignature) + (source.includedSourceKeys?.sorted() ?: listOf("ALL")))
            is ProjectSourceSelection.Frozen -> contentToken(listOf("FROZEN", source.sourceId, source.seriesUuid, source.revisionUuid))
        }
    } + pricingJson.encodeToString(projectWorks))
}

@Serializable
enum class ExclusionReason { USER_EXCLUDED, UNRESOLVED }
@Serializable
data class ExcludedPricingWork(val sourceId: String, val sourceKey: String, val reason: ExclusionReason,
    val problems: List<PricingProblemCode> = emptyList(), val bucket: PricingBucket)
@Serializable
sealed interface PricingLineOrigin {
    @Serializable data object Live : PricingLineOrigin
    @Serializable data object ProjectWork : PricingLineOrigin
    @Serializable data class Frozen(val seriesUuid: String, val revisionUuid: String) : PricingLineOrigin
}
@Serializable
data class IncludedPricingWork(val sourceId: String, val origin: PricingLineOrigin, val line: LiveEstimateLine, val selectedForDocument: Boolean)

@ConsistentCopyVisibility
data class ProjectPricingPreview internal constructor(val projectUuid: String, val signature: String,
    val included: List<IncludedPricingWork>, val excluded: List<ExcludedPricingWork>, val fatalProblems: List<PricingProblem>,
    val confirmedTotalCents: Long, val potentialTotalCents: Long, val selectedIncludedTotalCents: Long, val mandatoryProblems: List<PricingProblem>) {
    val potentialIncomplete get() = excluded.any { it.reason == ExclusionReason.UNRESOLVED && it.bucket == PricingBucket.POTENTIAL }
    val isPartial get() = excluded.any { it.bucket == PricingBucket.CONFIRMED }
    val canFinalizeFull get() = fatalProblems.isEmpty() && mandatoryProblems.isEmpty() && included.any { it.selectedForDocument } && !isPartial
    val canRequestPartialAcknowledgement get() = fatalProblems.isEmpty() && mandatoryProblems.isEmpty() && isPartial && included.any { it.selectedForDocument }
}

object ProjectPricingEngine {
    /** Source order is explicit and preserved. Rows within each source are canonical. No implicit all selection. */
    fun previewComposition(selection: ProjectEstimateSelection, live: Map<String, LiveEstimatePreview>,
        frozen: Map<String, VerifiedFrozenRevision> = emptyMap(), catalog: ActiveCatalogSnapshot? = null): ProjectPricingPreview {
        val activeCatalog = catalog ?: ActiveCatalogSnapshot(emptyList())
        val liveCatalogs = mutableSetOf<String>()
        val fatal = mutableListOf<PricingProblem>()
        val included = mutableListOf<IncludedPricingWork>()
        val excluded = mutableListOf<ExcludedPricingWork>()
        val tokens = mutableListOf(selection.projectUuid, selection.compositionUuid)
        val documentCoverage = mutableSetOf<String>()
        val mandatory = mutableListOf<PricingProblem>()
        val frozenRoles = mutableMapOf<String, MutableList<BusinessRole>>()
        val revisions = mutableSetOf<String>()
        val sourceIds = mutableSetOf<String>()
        val liveIdentities = mutableSetOf<String>()
        fun fail(code: PricingProblemCode, key: String? = null) { fatal += PricingProblem(code, key) }
        fun include(id: String, origin: PricingLineOrigin, line: LiveEstimateLine, selectedForDocument: Boolean = true) {
            // Advisory potential remains visible but never reserves documentary coverage.
            // Actual element/work identity, not the ceiling UUID alone or the catalogue code.
            val provenance = contentToken(listOf(line.measurementUuid.orEmpty(), line.sourceKey))
            if (selectedForDocument && !documentCoverage.add(provenance)) {
                fail(PricingProblemCode.PROVENANCE_OVERLAP, line.sourceKey)
                return
            }
            included += IncludedPricingWork(id, origin, line, selectedForDocument)
        }
        if (selection.projectUuid.isBlank() || selection.compositionUuid.isBlank()) fail(PricingProblemCode.INVALID_SELECTION)
        for (source in selection.sources) {
            if (source.sourceId.isBlank() || !sourceIds.add(source.sourceId)) fail(PricingProblemCode.DUPLICATE_SOURCE, source.sourceId)
            tokens += source.sourceId
            when (source) {
                is ProjectSourceSelection.Live -> {
                    tokens += source.expectedSignature
                    val preview = live[source.sourceId]
                    if (preview == null) { fail(PricingProblemCode.STALE_SOURCE, source.sourceId); continue }
                    tokens += preview.signature
                    if (source.measurementUuid.isBlank() || source.basisSourceSignature.isBlank() || source.basisCatalogSignature.isBlank())
                        fail(PricingProblemCode.INVALID_SELECTION, source.sourceId)
                    if (preview.measurementUuid != source.measurementUuid || preview.sourceSignature != source.basisSourceSignature)
                        fail(PricingProblemCode.STALE_SOURCE, source.sourceId)
                    if (preview.catalogSignature != source.basisCatalogSignature) fail(PricingProblemCode.STALE_CATALOG, source.sourceId)
                    liveCatalogs += preview.catalogSignature
                    if (catalog != null && preview.catalogSignature != catalog.signature) fail(PricingProblemCode.STALE_CATALOG, source.sourceId)
                    val identity = contentToken(listOf(preview.projectUuid, preview.measurementUuid, preview.sourceSignature,
                        contentToken(source.includedSourceKeys?.sorted() ?: listOf("ALL"))))
                    if (!liveIdentities.add(identity)) fail(PricingProblemCode.DUPLICATE_SOURCE, source.sourceId)
                    if (preview.scopeSignature != selection.scopeSignature) fail(PricingProblemCode.STALE_SOURCE, source.sourceId)
                    if (preview.projectUuid != selection.projectUuid) fail(PricingProblemCode.CROSS_PROJECT, source.sourceId)
                    if (preview.compositionUuid != selection.compositionUuid || preview.signature != source.expectedSignature)
                        fail(PricingProblemCode.STALE_SOURCE, source.sourceId)
                    fatal += preview.fatalProblems
                    val keys = preview.priceableLines.map { it.sourceKey }.toSet()
                    if (source.includedSourceKeys?.any { it !in keys } == true) fail(PricingProblemCode.INVALID_SELECTION, source.sourceId)
                    tokens += contentToken(source.includedSourceKeys?.sorted() ?: listOf("ALL"))
                    preview.priceableLines.forEach { line ->
                        if (source.includedSourceKeys == null || line.sourceKey in source.includedSourceKeys) include(source.sourceId, PricingLineOrigin.Live, line, line.bucket == PricingBucket.CONFIRMED || source.includedSourceKeys != null)
                        else excluded += ExcludedPricingWork(source.sourceId, line.sourceKey, ExclusionReason.USER_EXCLUDED, bucket = line.bucket)
                    }
                    mandatory += preview.mandatoryProblems
                    mandatory += mandatoryRoleProblems(preview.measurementUuid, preview.businessRoles.values.toList(),
                        included.filter { it.sourceId == source.sourceId && it.selectedForDocument }.map { it.line })
                    preview.unresolvedItems.forEach {
                        if (it.bucket == PricingBucket.CONFIRMED && !documentCoverage.add(contentToken(listOf(it.measurementUuid.orEmpty(), it.sourceKey)))) fail(PricingProblemCode.PROVENANCE_OVERLAP, it.sourceKey)
                        excluded += ExcludedPricingWork(source.sourceId, it.sourceKey, ExclusionReason.UNRESOLVED, it.reasons, it.bucket) }
                }
                is ProjectSourceSelection.Frozen -> {
                    tokens += listOf(source.seriesUuid, source.revisionUuid, source.expectedSignature)
                    if (!revisions.add(source.revisionUuid)) fail(PricingProblemCode.DUPLICATE_SOURCE, source.revisionUuid)
                    val revision = frozen[source.revisionUuid]
                    if (revision == null) { fail(PricingProblemCode.REVISION_UNAVAILABLE, source.revisionUuid); continue }
                    if (revision.projectUuid != selection.projectUuid) fail(PricingProblemCode.CROSS_PROJECT, source.revisionUuid)
                    if (revision.seriesUuid != source.seriesUuid || revision.revisionUuid != source.revisionUuid ||
                        revision.contentSignature != source.expectedSignature || revision.contentSignature.isBlank()) fail(PricingProblemCode.STALE_SOURCE, source.revisionUuid)
                    if (revision.seriesUuid.isBlank() || revision.revisionUuid.isBlank() || revision.lines.isEmpty()) fail(PricingProblemCode.INVALID_SOURCE, source.revisionUuid)
                    if (!revision.isLeaf) fail(PricingProblemCode.AMBIGUOUS_COVERAGE, source.revisionUuid)
                    if (!revision.provenanceVerified) fail(PricingProblemCode.PROVENANCE_MISSING, source.revisionUuid)
                    tokens += revision.contentSignature
                    revision.lines.filter { it.measurementUuid != null }.groupBy { it.measurementUuid!! }.forEach { (measurement, lines) ->
                        frozenRoles.getOrPut(measurement) { mutableListOf() }.addAll(lines.mapNotNull { it.businessRole })
                        if (lines.any { it.businessRole == null }) fail(PricingProblemCode.PROVENANCE_MISSING, source.revisionUuid)
                    }
                    for (line in revision.lines.sortedBy { it.sourceKey }) {
                        if (!validProvenance(line)) {
                            fail(PricingProblemCode.PROVENANCE_MISSING, line.sourceKey)
                            continue
                        }
                        if (!validFrozenLine(line)) {
                            fail(PricingProblemCode.INVALID_SOURCE, line.sourceKey)
                            continue
                        }
                        include(source.sourceId, PricingLineOrigin.Frozen(source.seriesUuid, source.revisionUuid), line)
                    }
                    revision.omitted.sortedBy { it.sourceKey }.forEach {
                        if (it.bucket == PricingBucket.CONFIRMED && !documentCoverage.add(contentToken(listOf(it.measurementUuid.orEmpty(), it.sourceKey)))) fail(PricingProblemCode.PROVENANCE_OVERLAP, it.sourceKey)
                        excluded += ExcludedPricingWork(source.sourceId, it.sourceKey, ExclusionReason.UNRESOLVED, it.reasons, it.bucket) }
                }
            }
        }
        if (liveCatalogs.size > 1) fail(PricingProblemCode.STALE_CATALOG)
        if (activeCatalog.items.map { it.uuid }.distinct().size != activeCatalog.items.size) fail(PricingProblemCode.INVALID_SOURCE)
        val workIds = mutableSetOf<String>()
        for (work in selection.projectWorks) {
            val key = "project-work:${work.uuid}"
            if (work.uuid.isBlank() || !workIds.add(work.uuid)) fail(PricingProblemCode.DUPLICATE_SOURCE, key)
            if (work.projectUuid != selection.projectUuid) fail(PricingProblemCode.CROSS_PROJECT, key)
            if (work.basisCatalogSignature != activeCatalog.signature) fail(PricingProblemCode.STALE_CATALOG, key)
            val item = activeCatalog.items.singleOrNull { it.uuid == work.catalogItemUuid }
            val reason = when {
                item == null -> PricingProblemCode.CATALOG_MISSING
                item.quantityMode != QuantityMode.MANUAL || item.unit != work.unit || item.code != work.expectedCode -> PricingProblemCode.METADATA_DRIFT
                work.unit !in PriceCatalogRules.standardUnits -> PricingProblemCode.UNSUPPORTED_UNIT
                work.quantityMilli !in 1..9_000_000_000_000_000L || work.unit in listOf("шт.", "компл.") && work.quantityMilli % 1000 != 0L -> PricingProblemCode.INVALID_QUANTITY
                PricingMath.salePriceCents(item.salePrice) == null -> PricingProblemCode.INVALID_PRICE
                else -> null
            }
            if (reason != null) excluded += ExcludedPricingWork(key, key, ExclusionReason.UNRESOLVED, listOf(reason), work.bucket)
            else {
                val price = checkNotNull(PricingMath.salePriceCents(checkNotNull(item).salePrice))
                try {
                    include(key, PricingLineOrigin.ProjectWork, LiveEstimateLine(key, null, null, item.uuid, item.code, item.title, item.unit,
                        work.quantityMilli, work.quantityMilli, price, PricingMath.lineCents(price, work.quantityMilli), QuantityOrigin.DECLARED_EXACT, work.bucket), work.bucket == PricingBucket.CONFIRMED || work.includePotentialInDocument)
                } catch (_: PricingOverflow) { fail(PricingProblemCode.OVERFLOW, key) }
            }
        }
        frozenRoles.forEach { (measurement, declared) ->
            val rows = included.filter { it.selectedForDocument && it.line.measurementUuid == measurement }.map { it.line }
            mandatory += mandatoryRoleProblems(measurement, declared + rows.mapNotNull { it.businessRole }, rows)
        }
        if (selection.sources.none { it is ProjectSourceSelection.Live } && frozenRoles.isEmpty())
            mandatory += PricingProblem(PricingProblemCode.MANDATORY_BASE_REQUIRED)
        if (included.none { it.selectedForDocument }) mandatory += PricingProblem(PricingProblemCode.EMPTY_DOCUMENT)
        var confirmed = 0L
        var potential = 0L
        var selectedTotal = 0L
        try { included.forEach {
            if (it.selectedForDocument) selectedTotal = PricingMath.add(selectedTotal, it.line.totalCents)
            if (it.line.bucket == PricingBucket.CONFIRMED) confirmed = PricingMath.add(confirmed, it.line.totalCents)
            else potential = PricingMath.add(potential, it.line.totalCents) } }
        catch (_: PricingOverflow) { fail(PricingProblemCode.OVERFLOW); confirmed = 0; potential = 0; selectedTotal = 0 }
        tokens += pricingJson.encodeToString(selection.projectWorks)
        if (selection.projectWorks.isNotEmpty()) tokens += activeCatalog.signature
        tokens += pricingJson.encodeToString(included)
        tokens += pricingJson.encodeToString(excluded)
        return ProjectPricingPreview(selection.projectUuid, contentToken(tokens), included, excluded,
            fatal.distinct().sortedWith(compareBy({ it.sourceKey.orEmpty() }, { it.code.name })), confirmed, potential, selectedTotal, mandatory.distinct().sortedWith(compareBy({ it.sourceKey.orEmpty() }, { it.code.name })))
    }

    private fun validProvenance(line: LiveEstimateLine): Boolean =
        if (line.measurementUuid == null && line.elementUuid == null) line.sourceKey.startsWith("project-work:") && line.sourceKey.removePrefix("project-work:").isNotBlank()
        else !line.measurementUuid.isNullOrBlank() && !line.elementUuid.isNullOrBlank() && line.sourceKey == elementKey(line.elementUuid)

    private fun validFrozenLine(line: LiveEstimateLine): Boolean = runCatching {
        line.sourceQuantityMilli in 1..9_000_000_000_000_000L &&
            (line.unit !in listOf("шт.", "компл.") || line.effectiveBillableQuantityMilli % 1000 == 0L) && line.unit in PriceCatalogRules.standardUnits &&
            line.totalCents == PricingMath.lineCents(line.unitPriceCents, line.effectiveBillableQuantityMilli)
    }.getOrDefault(false)
}
