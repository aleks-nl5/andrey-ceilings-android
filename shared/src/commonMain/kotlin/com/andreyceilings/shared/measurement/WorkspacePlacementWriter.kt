package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.CeilingEditRepository
import com.andreyceilings.shared.db.MeasurementElementRepository
import com.andreyceilings.shared.db.PointPlacementRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.PriceGroupRepository
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

        for ((uuid, version) in additions.replacedMaterials) {
            val row = elements.find(uuid) ?: throw MeasurementEditConflict()
            if (row.version != version || row.businessRole?.isWorkspaceMaterial() != true ||
                row.measurementUuid != measurement || row.placedQuantityMilli != 0L ||
                current.placements.points.any { it.elementUuid == uuid } ||
                current.placements.lines.any { it.elementUuid == uuid } ||
                current.placements.areas.any { it.elementUuid == uuid }) throw MeasurementEditConflict()
            elements.delete(version, now)
        }
        for ((uuid, version) in additions.removedWorks) {
            val row = elements.find(uuid) ?: throw MeasurementEditConflict()
            if (row.version != version || row.measurementUuid != measurement ||
                row.businessRole?.isWorkspaceMaterial() == true) throw MeasurementEditConflict()
            elements.delete(version, now)
        }
        val pointElementVersions = current.pointElements.associate { it.uuid to it.version }.toMutableMap()
        for ((uuid, point) in additions.removedPoints) {
            require(uuid == point.uuid && point in original.placements.points)
            val expected = pointElementVersions[point.elementUuid] ?: throw MeasurementEditConflict()
            val live = elements.find(point.elementUuid) ?: throw MeasurementEditConflict()
            if (live.version != expected) throw MeasurementEditConflict()
            points.removePoint(live.version, point, now)
            pointElementVersions[point.elementUuid] = checkNotNull(elements.find(point.elementUuid)).version
        }
        for ((uuid, work) in additions.editedWorks) {
            require(uuid == work.uuid && work.measurementUuid == measurement)
            val baseline = (original.pointElements + original.lineElements + original.areaElements + original.otherElements + original.materialElements)
                .find { it.uuid == uuid } ?: throw MeasurementEditConflict()
            val live = elements.find(uuid) ?: throw MeasurementEditConflict()
            if (baseline.version != work.version ||
                (live.version != work.version && additions.removedPoints.values.none { it.elementUuid == uuid }))
                throw MeasurementEditConflict()
            if (work.businessRole?.isWorkspaceMaterial() == true) {
                if (live != baseline || work != baseline.copy(approval = ElementApproval.APPROVED))
                    throw MeasurementEditConflict()
                val source = baseline.source as? ElementSource.CatalogSelection ?: throw MeasurementEditConflict()
                val price = PriceRepository(db).all().find { it.uuid == source.priceItemUuid }
                    ?: throw MeasurementEditConflict()
                if (PriceGroupRepository(db).find(price.groupUuid) == null || price.unit != baseline.unit ||
                    price.code != source.code || price.quantityMode != source.quantityMode.value ||
                    price.placementKind != source.placementKind.value) throw MeasurementEditConflict()
            }
            elements.edit(live.version, work.note, work.approval, work.quantity, now)
        }
        for ((uuid, element) in additions.elements) {
            if (element.businessRole?.isWorkspaceMaterial() == true) {
                val role = element.businessRole
                val expected = original.materialElements.filter { it.businessRole == role }
                val actual = current.materialElements.filter { it.businessRole == role }
                if (expected != actual || expected.size > 1 ||
                    expected.any { it.version !in additions.replacedMaterials.values }) throw MeasurementEditConflict()
            }
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
        val withCatalog = reload()
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
            if ((additions.replacedMaterials + additions.removedWorks).any { (uuid, version) ->
                val row = repositoryElements.find(uuid)
                row == null || row.deletedAt != maxOf(version.expectedUpdatedAt + 1, first.createdAt)
            }) return false
            val pointTombstones = if (additions.removedPoints.isEmpty()) emptyMap()
                else PointPlacementRepository(db).tombstones().associateBy { it.uuid }
            if (additions.removedPoints.any { (uuid, point) ->
                pointTombstones[uuid]?.deletedAt != maxOf(point.updatedAt + 1, first.createdAt)
            }) return false
            if (additions.editedWorks.any { (uuid, work) ->
                val row = repositoryElements.find(uuid)
                val declared = request["lengths"]?.jsonObject?.get(uuid)?.jsonPrimitive?.longOrNull
                    ?: request["quantities"]?.jsonObject?.get(uuid)?.jsonPrimitive?.longOrNull
                val expectedQuantity = declared?.let { ElementQuantity.Exact(it) } ?: work.quantity
                row == null || row.deletedAt != null || row.quantity != expectedQuantity || row.approval != work.approval ||
                    row.updatedAt != (versions[uuid]?.jsonPrimitive?.longOrNull ?: (maxOf(work.updatedAt + 1, first.createdAt) +
                        additions.removedPoints.values.count { it.elementUuid == uuid }))
            }) return false
            return additions.elements.keys.all { uuid ->
                val expected = versions[uuid]?.jsonPrimitive?.longOrNull ?: first.createdAt
                repositoryElements.find(uuid)?.let {
                    it.deletedAt == null && it.measurementUuid == measurement && it.updatedAt == expected
                } == true
            }
        }
    }
}
