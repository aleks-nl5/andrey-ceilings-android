package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class BusinessRole { CANVAS, PRIMARY_PROFILE, ADDITIONAL_WORK }
@Serializable
enum class PricingBucket { CONFIRMED, POTENTIAL }
@Serializable
enum class QuantityOrigin { CAPTURED_AREA, CAPTURED_PERIMETER, DECLARED_EXACT, EXPLICIT_BILLABLE_OVERRIDE }
@Serializable
enum class PricingProblemCode {
    PARENT_UNAVAILABLE, INVALID_GEOMETRY, INVALID_SOURCE, STALE_SOURCE, STALE_CATALOG,
    DATABASE_UNAVAILABLE, ROLE_UNDETERMINED, DUPLICATE_MAIN_CANVAS, DUPLICATE_PRIMARY_PROFILE,
    CATALOG_MISSING, METADATA_DRIFT, UNSUPPORTED_UNIT, INVALID_PRICE, UNKNOWN_QUANTITY,
    RANGE_QUANTITY, QUANTITY_PRECISION, INVALID_QUANTITY, SELECTION_MISSING, PLACEMENT_REVIEW,
    PLACEMENT_UNPLACED, PLACEMENT_APPROXIMATE, PLACEMENT_PARTIAL, INVALID_OVERRIDE, OVERFLOW, DUPLICATE_SOURCE, PROVENANCE_OVERLAP, PROVENANCE_MISSING,
    EMPTY_DOCUMENT, MANDATORY_BASE_REQUIRED, MISSING_MANDATORY_CANVAS, MISSING_MANDATORY_PRIMARY_PROFILE,
    INVALID_MANDATORY_CANVAS, INVALID_MANDATORY_PRIMARY_PROFILE, AMBIGUOUS_COVERAGE, REVISION_UNAVAILABLE, CROSS_PROJECT, INVALID_SELECTION
}
@Serializable
data class PricingProblem(val code: PricingProblemCode, val sourceKey: String? = null)
@Serializable
data class UnresolvedWork(val sourceKey: String, val measurementUuid: String?, val elementUuid: String?,
    val label: String, val bucket: PricingBucket, val reasons: List<PricingProblemCode>)

/** Complete active document, never just the subset with canvas overlays. */
data class LiveEstimateSource(
    val projectUuid: String, val measurementUuid: String, val geometry: FrozenPolygonGeometry,
    val elements: List<MeasurementElement>, val points: List<PointPlacement> = emptyList(),
    val lines: List<LinePlacement> = emptyList(), val areas: List<AreaFeature> = emptyList(),
    val walls: List<CeilingWall> = emptyList(), val versions: Map<String, String> = emptyMap()
) {
    val signature: String get() = contentToken(listOf(projectUuid, measurementUuid,
        // Sort maps explicitly, including caller-created captured polygon geometry.
        MeasurementGeometryCodec.write(geometry.copy(measurementsMm = geometry.measurementsMm.entries.sortedBy { it.key }.associate { it.toPair() })),
        pricingJson.encodeToString(elements.sortedBy { it.uuid }), pricingJson.encodeToString(points.sortedBy { it.uuid }),
        pricingJson.encodeToString(lines.sortedBy { it.uuid }), pricingJson.encodeToString(areas.sortedBy { it.uuid }),
        pricingJson.encodeToString(walls.sortedBy { it.ordinal }), contentToken(versions.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) })))
}

@Serializable
data class ActiveCatalogItem(val uuid: String, val groupUuid: String, val code: String, val title: String,
    val unit: String, val quantityMode: QuantityMode, val placementKind: PlacementKind,
    val salePrice: Double, val updatedAt: Long, val groupUpdatedAt: Long)

data class ActiveCatalogSnapshot(val items: List<ActiveCatalogItem>, val versionToken: String = "") {
    val signature: String get() = contentToken(listOf(versionToken, pricingJson.encodeToString(items.sortedBy { it.uuid })))
}

/** Trusted classification supplied by the measurement integration, never inferred by pricing. */
data class BusinessRoleBindings(val basisSourceSignature: String, val roles: Map<String, BusinessRole>)

@Serializable
data class EstimateQuantityOverride(
    val compositionUuid: String, val projectUuid: String, val measurementUuid: String,
    val measurementElementUuid: String, val expectedBusinessRole: BusinessRole, val quantityMilli: Long,
    val unit: String, val basisSourceSignature: String, val basisCatalogSignature: String,
    val explicitlyConfirmed: Boolean, val basisScopeSignature: String = compositionUuid, val note: String = ""
)

@Serializable
data class LiveEstimateLine(
    val sourceKey: String, val measurementUuid: String?, val elementUuid: String?, val catalogItemUuid: String,
    val stableCode: String, val title: String, val unit: String, val sourceQuantityMilli: Long,
    val effectiveBillableQuantityMilli: Long, val unitPriceCents: Long, val totalCents: Long,
    val quantityOrigin: QuantityOrigin, val bucket: PricingBucket,
    val reviewFlags: List<PricingProblemCode> = emptyList(), val overrideNote: String? = null,
    val businessRole: BusinessRole? = null
)

/** Internal construction prevents callers from supplying a made-up ready preview to composition. */
@ConsistentCopyVisibility
data class LiveEstimatePreview internal constructor(
    val projectUuid: String, val measurementUuid: String, val compositionUuid: String,
    val sourceSignature: String, val catalogSignature: String, val scopeSignature: String, val signature: String,
    val priceableLines: List<LiveEstimateLine>, val unresolvedItems: List<UnresolvedWork>,
    val fatalProblems: List<PricingProblem>, val warnings: List<PricingProblem>,
    val confirmedTotalCents: Long, val potentialTotalCents: Long,
    val businessRoles: Map<String, BusinessRole>, val mandatoryProblems: List<PricingProblem>
) {
    val potentialIncomplete get() = unresolvedItems.any { it.bucket == PricingBucket.POTENTIAL }
    val canFinalizeFull get() = fatalProblems.isEmpty() && mandatoryProblems.isEmpty() && unresolvedItems.none { it.bucket == PricingBucket.CONFIRMED }
    val canRequestPartialAcknowledgement get() = fatalProblems.isEmpty() && mandatoryProblems.isEmpty() &&
        unresolvedItems.any { it.bucket == PricingBucket.CONFIRMED } && priceableLines.any { it.bucket == PricingBucket.CONFIRMED }
}

internal val pricingJson = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }
/** Collision-free, versioned length-prefix encoding, NOT a hash or a write authorization. */
internal fun contentToken(fields: List<String>): String = "p4a-v2:" + fields.joinToString("") { "${it.length}:$it" }
internal fun elementKey(uuid: String) = "element:$uuid"
internal fun ElementApproval.bucket() = if (this == ElementApproval.APPROVED) PricingBucket.CONFIRMED else PricingBucket.POTENTIAL
