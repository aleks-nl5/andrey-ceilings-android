package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.measurement.*

/** Capture on the model's UI dispatcher. The persisted basis must come from a coherent reader.
 * All active elements are retained, including ones absent from the editor's overlay projections.
 */
fun PricingReadResult.Loaded.withValidatedDraft(model: MeasurementEditModel, basisSourceSignature: String): PricingReadResult {
    val before = model.state.value
    val original = before.original
    if (basisSourceSignature != source.signature || !model.canSaveMountingDraft() || before.drag != null || before.lineDrag != null || before.area.drag != null ||
        before.mountingInput != null || before.lineStart != null || before.linePreview != null || before.area.tool != null ||
        before.saving || before.checking || before.conflict || before.uncertain || before.saved ||
        before.draft.recordUuid != source.measurementUuid ||
        before.draft.projectId != original.draft.projectId || before.draft.projectId.toString() != source.versions["projectId"] ||
        original.updatedAt.toString() != source.versions["measurementUpdatedAt"] ||
        original.sourceDrawingJson?.let { runCatching { MeasurementGeometryCodec.read(it) }.getOrNull() } != source.geometry ||
        original.placements.points != source.points || original.placements.lines != source.lines || original.placements.areas != source.areas ||
        validateMeasurementCreate(original.draft).geometry != source.geometry ||
        (original.lineElements + original.areaElements).any { old -> source.elements.singleOrNull { it.uuid == old.uuid } != old } ||
        original.placements.elements.any { (id, version) -> source.elements.singleOrNull { it.uuid == id }?.updatedAt != version })
        return PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE)
    if (before.declaredLengths.keys.any { id -> source.elements.singleOrNull { it.uuid == id }?.supportsLinePlacement() != true } ||
        before.area.quantities.keys.any { id -> source.elements.singleOrNull { it.uuid == id }?.supportsAreaPlacement() != true })
        return PricingReadResult.Unavailable(PricingProblemCode.INVALID_SOURCE)
    val validation = validateMeasurementCreate(before.draft)
    if (!validation.canSave || validation.geometry == null) return PricingReadResult.Unavailable(PricingProblemCode.INVALID_GEOMETRY)
    return try {
        val elements = source.elements.map { e ->
            val quantity = before.declaredLengths[e.uuid] ?: before.area.quantities[e.uuid]
            if (quantity == null) e else e.copy(quantity = ElementQuantity.Exact(quantity))
        }
        val draft = source.copy(geometry = validation.geometry, elements = elements,
            points = model.displayPoints(), lines = model.displayLines(), areas = model.displayAreas(), walls = model.draftWalls(),
            versions = source.versions + mapOf("draft" to before.draft.snapshot(), "draftSession" to model.token,
                "draftWallGeneration" to before.wallGeneration))
        if (model.state.value !== before) PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE)
        else PricingReadResult.Loaded(draft, catalog)
    } catch (_: IllegalArgumentException) { PricingReadResult.Unavailable(PricingProblemCode.INVALID_SOURCE) }
}
