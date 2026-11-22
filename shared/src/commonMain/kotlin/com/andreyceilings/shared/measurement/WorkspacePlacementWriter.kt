package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.CeilingEditRepository
import com.andreyceilings.shared.db.MeasurementElementRepository
import com.andreyceilings.shared.db.PointPlacementRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** All phases use the caller's one physical transaction. The first receipt fences replays. */
internal class WorkspacePlacementWriter(
    private val db: PrototypeDatabase,
    private val reload: () -> MeasurementEditDocument
) {
    private val ceilings = CeilingEditRepository(db)
    private val elements = MeasurementElementRepository(db)
    private val points = PointPlacementRepository(db)

    fun save(
        original: MeasurementEditDocument,
        draft: MeasurementCreateDraft,
        edits: Map<String, PointMountingEdit>,
        operation: String,
        wallToken: String,
        now: Long,
        lines: Map<String, LinePlacementEdit>,
        lengths: Map<String, Long>,
        areas: Map<String, AreaFeatureEdit>,
        quantities: Map<String, Long>,
        additions: WorkspacePlacementDraft
    ) {
        val measurement = original.draft.recordUuid
        val receipt = buildJsonObject {
            put("draft", draft.snapshot())
            put("walls", wallToken)
            put("additions", mountingCodec.encodeToJsonElement(WorkspacePlacementDraft.serializer(), additions))
            put("pointEdits", mountingCodec.encodeToJsonElement(edits))
            put("lineEdits", mountingCodec.encodeToJsonElement(lines))
            put("lengths", mountingCodec.encodeToJsonElement(lengths))
            put("areaEdits", mountingCodec.encodeToJsonElement(areas))
            put("quantities", mountingCodec.encodeToJsonElement(quantities))
        }
        db.measurementElementsQueries.claimElementWrite("")
        val current = reload()
        val existing = db.ceilingEditsQueries.selectOperation(operation).executeAsOneOrNull()
        if (existing != null) {
            if (existing.measurementUuid != measurement || existing.expectedUpdatedAt != original.updatedAt ||
                existing.createdAt != now || workspaceReceipt(existing.payload) != receipt)
                throw PointPlacementFailure.RetryConflict()
            if (!currentResult(db, operation, measurement, current.updatedAt))
                throw MeasurementEditConflict()
            return
        }
        if (current.updatedAt != original.updatedAt || current.placements != original.placements ||
            current.pointElements != original.pointElements) throw MeasurementEditConflict()

        for ((uuid, element) in additions.elements) {
            require(uuid == element.uuid && element.measurementUuid == measurement)
            val source = element.source as ElementSource.CatalogSelection
            if (elements.find(uuid) != null) throw MeasurementEditConflict()
            val saved = try {
                elements.createCatalogSelection(measurement, source.priceItemUuid, element.quantity, now, uuid,
                    element.note, element.approval, checkNotNull(element.businessRole))
            } catch (_: MeasurementElementFailure.CatalogUnavailable) {
                throw MeasurementEditConflict()
            }
            if (saved.source != source || saved.label != element.label || saved.unit != element.unit) {
                throw MeasurementEditConflict()
            }
        }
        val withCatalog = if (additions.elements.isEmpty()) current else reload()
        ceilings.save(withCatalog, draft, edits - additions.points.keys, operation, wallToken, now,
            lines, lengths, areas, quantities, workspaceReceipt = receipt)
        if (additions.points.isEmpty()) return

        val placedGeometry = reload()
        val geometry = checkNotNull(validateMeasurementCreate(placedGeometry.draft).geometry)
        val pointAnchors = mutableMapOf<String, PointMountingEdit>()
        for ((uuid, point) in additions.points) {
            require(uuid == point.uuid)
            val edit = edits[uuid]
            val target = previewMounting(point, geometry, placedGeometry.placements.walls, false, edit)
            require(!target.mounting.unresolved)
            val element = elements.find(point.elementUuid) ?: throw MeasurementEditConflict()
            points.addPoint(element.version, placedGeometry.updatedAt, target.xMm, target.yMm, now, uuid)
            if (edit?.anchor != null) pointAnchors[uuid] = edit
        }
        val withPoints = reload()
        // A second journal captures every resulting POINT version and applies measured anchors
        // using the same guarded writer as ordinary anchor edits.
        ceilings.save(withPoints, withPoints.draft, pointAnchors, pointReceiptId(operation), wallToken, now,
            workspaceReceipt = buildJsonObject { put("parent", operation) })
    }

    companion object {
        private fun workspaceReceipt(payload: String): JsonObject? =
            mountingCodec.parseToJsonElement(payload).jsonObject["workspace"]?.jsonObject

        private fun pointReceiptId(operation: String) = "$operation:points"

        fun currentResult(db: PrototypeDatabase, operation: String, measurement: String, version: Long): Boolean {
            val first = db.ceilingEditsQueries.selectOperation(operation).executeAsOneOrNull() ?: return false
            if (first.measurementUuid != measurement) return false
            val request = workspaceReceipt(first.payload)
            val repository = CeilingEditRepository(db)
            if (request == null) return maxOf(first.expectedUpdatedAt + 1, first.createdAt) == version &&
                repository.isCurrentResult(operation, measurement, version)

            val additions = mountingCodec.decodeFromJsonElement(
                WorkspacePlacementDraft.serializer(), request.getValue("additions")
            )
            val resultId = if (additions.points.isEmpty()) operation else pointReceiptId(operation)
            if (resultId != operation) {
                val pointOp = db.ceilingEditsQueries.selectOperation(resultId).executeAsOneOrNull() ?: return false
                if (pointOp.measurementUuid != measurement ||
                    workspaceReceipt(pointOp.payload)?.get("parent")?.jsonPrimitive?.content != operation) return false
            }
            if (!repository.isCurrentResult(resultId, measurement, version)) return false
            val firstResult = db.ceilingEditsQueries.selectResult(operation).executeAsOneOrNull() ?: return false
            val finalResult = db.ceilingEditsQueries.selectResult(resultId).executeAsOneOrNull() ?: return false
            // LINE/AREA versions can appear in both receipts. The final version takes priority.
            val versions = mountingCodec.parseToJsonElement(firstResult.elementVersions).jsonObject +
                mountingCodec.parseToJsonElement(finalResult.elementVersions).jsonObject
            val repositoryElements = MeasurementElementRepository(db)
            return additions.elements.keys.all { uuid ->
                val expected = versions[uuid]?.jsonPrimitive?.longOrNull ?: first.createdAt
                repositoryElements.find(uuid)?.let {
                    it.deletedAt == null && it.measurementUuid == measurement && it.updatedAt == expected
                } == true
            }
        }
    }
}
