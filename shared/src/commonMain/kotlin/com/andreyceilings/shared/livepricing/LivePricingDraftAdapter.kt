package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.PlacementKind

internal sealed interface DraftPricingBasis {
    data class Created(val projectUuid: String, val catalog: ActiveCatalogSnapshot) : DraftPricingBasis
    data class Saved(val read: PricingReadResult.Loaded) : DraftPricingBasis
    data class Unavailable(val reason: PricingProblemCode) : DraftPricingBasis
}

internal sealed interface DraftPricingResult {
    class Loaded(internal val projection: LiveEstimateSource, val preview: LiveEstimatePreview) : DraftPricingResult
    data class Unavailable(val reason: PricingProblemCode) : DraftPricingResult
}

/** Only a persisted read can supply a quote command. This projection never carries receipt proof. */
internal fun projectDraftPricing(
    model: MeasurementEditModel, before: MeasurementEditState, basis: DraftPricingBasis
): DraftPricingResult {
    fun unavailable(reason: PricingProblemCode) = DraftPricingResult.Unavailable(reason)
    if (before !== model.state.value || before.saving || before.checking || before.conflict ||
        before.uncertain || before.saved || before.draft.recordUuid != before.original.draft.recordUuid ||
        before.draft.projectId != before.original.draft.projectId
    ) return unavailable(PricingProblemCode.STALE_SOURCE)
    if (basis is DraftPricingBasis.Unavailable) return unavailable(basis.reason)
    val geometry = validateMeasurementCreate(before.draft).geometry
        ?: return unavailable(PricingProblemCode.INVALID_GEOMETRY)
    val original = before.original
    val projectUuid: String
    val catalog: ActiveCatalogSnapshot
    when (basis) {
        is DraftPricingBasis.Created -> {
            if (!before.creating) return unavailable(PricingProblemCode.STALE_SOURCE)
            projectUuid = basis.projectUuid
            catalog = basis.catalog
        }
        is DraftPricingBasis.Saved -> {
            val (saved, loadedCatalog) = basis.read
            val baseline = (original.pointElements + original.lineElements + original.areaElements +
                original.materialElements + original.otherElements).distinctBy { it.uuid }.associateBy { it.uuid }
            if (before.creating || saved.measurementUuid != before.draft.recordUuid ||
                saved.versions["projectId"] != before.draft.projectId.toString() ||
                saved.versions["measurementUpdatedAt"] != original.updatedAt.toString() ||
                validateMeasurementCreate(original.draft).geometry != saved.geometry ||
                original.placements.points != saved.points || original.placements.lines != saved.lines ||
                original.placements.areas != saved.areas || original.placements.walls != saved.walls ||
                baseline != saved.elements.associateBy { it.uuid }
            ) return unavailable(PricingProblemCode.STALE_SOURCE)
            projectUuid = saved.projectUuid
            catalog = loadedCatalog
        }
        is DraftPricingBasis.Unavailable -> return unavailable(basis.reason)
    }

    return try {
        val points = model.displayPoints()
        val lines = model.displayLines()
        val areas = model.displayAreas()
        val elements = (model.workspaceWorks() + model.materials(BusinessRole.CANVAS) +
            model.materials(BusinessRole.PRIMARY_PROFILE)).map { element ->
            val placed: Long
            val hasPlacement: Boolean
            val exact: Boolean
            when ((element.source as? ElementSource.CatalogSelection)?.placementKind) {
                PlacementKind.POINT -> {
                    val active = points.filter { it.elementUuid == element.uuid && !it.mounting.unresolved }
                    hasPlacement = active.isNotEmpty()
                    placed = active.size * 1000L
                    exact = active.all { it.precision == PointPrecision.EXACT }
                }
                PlacementKind.LINE -> {
                    val active = lines.filter { it.elementUuid == element.uuid && !it.unresolved }
                    hasPlacement = active.isNotEmpty()
                    placed = active.fold(0L) { sum, line -> PricingMath.add(sum, line.lengthMm) }
                    exact = active.all { it.startMounting.anchor != null && it.endMounting.anchor != null }
                }
                PlacementKind.AREA -> {
                    val active = areas.filter { it.elementUuid == element.uuid && !it.unresolved }
                    hasPlacement = active.isNotEmpty()
                    placed = areaQuantityMilli(active, checkNotNull(element.unit))
                    exact = active.all { area ->
                        area.mountings.all { it.anchor != null } && (area.shape !is AreaShape.Circle || area.radiusMeasured)
                    }
                }
                else -> return@map element
            }
            element.copy(placedQuantityMilli = placed, placement = when {
                !hasPlacement -> ElementPlacement.UNPLACED
                exact -> ElementPlacement.EXACT
                else -> ElementPlacement.APPROXIMATE
            })
        }
        val projection = LiveEstimateSource(projectUuid, before.draft.recordUuid, geometry, elements,
            points, lines, areas, model.draftWalls(), mapOf(
                "draft" to before.draft.snapshot(), "draftSession" to model.token,
                "draftWallGeneration" to before.wallGeneration
            ))
        val roles = BusinessRoleBindings(projection.signature,
            elements.mapNotNull { e -> e.businessRole?.let { e.uuid to it } }.toMap())
        val preview = LivePricingEngine.buildPreview(projection, catalog, roles, "draft:${model.token}")
        if (model.state.value !== before) unavailable(PricingProblemCode.STALE_SOURCE)
        else DraftPricingResult.Loaded(projection, preview)
    } catch (_: PricingOverflow) {
        unavailable(PricingProblemCode.OVERFLOW)
    } catch (_: IllegalArgumentException) {
        unavailable(PricingProblemCode.INVALID_SOURCE)
    } catch (_: IllegalStateException) {
        unavailable(PricingProblemCode.INVALID_SOURCE)
    }
}
