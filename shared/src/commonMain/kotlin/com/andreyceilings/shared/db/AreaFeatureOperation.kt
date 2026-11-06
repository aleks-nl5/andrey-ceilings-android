package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import kotlinx.serialization.json.*

internal fun prepareAreaTargets(original: MeasurementEditDocument, geometry: FrozenPolygonGeometry,
    walls: List<CeilingWall>, changed: Boolean, edits: Map<String,AreaFeatureEdit>, now: Long,
    drawing: String, oldDrawing: String): List<AreaFeature> {
    require(edits.all { (id,e) -> id==e.value.uuid && id.isNotBlank() && e.value.measurementUuid==original.draft.recordUuid &&
        (e.value.elementUuid==null || original.areaElements.any {it.uuid==e.value.elementUuid}) })
    val targets=(original.placements.areas.map {it.uuid}+edits.keys).distinct().map {id ->
        val old=original.placements.areas.find {it.uuid==id}
        val edit=edits[id]
        val base=old ?: checkNotNull(edit).value
        require(base.updatedAt<Long.MAX_VALUE)
        if(old!=null)require(edit==null || edit.value.elementUuid==old.elementUuid)
        val preview=previewArea(base,geometry,walls,changed,edit)
        require(changed || edit==null || edit.value.deletedAt!=null || old==null || old.unresolved || !preview.unresolved) {
            "Недопустима ручна зміна площинного об’єкта. Попередні дані не змінено."
        }
        if(old==null)require(areaStrictlyInside(geometry,preview.shape) && !preview.unresolved && preview.deletedAt==null)
        if(old!=null && preview==old && !changed && edit?.confirm!=true && edit?.defer!=true) old else {
            val version=if(old==null)now else maxOf(old.updatedAt+1,now)
            val mountings=preview.mountings.mapIndexed {i,m ->
                val previous=old?.mountings?.getOrNull(i)
                m.copy(modifiedVersion=version,needsReview=preview.needsReview,unresolved=preview.unresolved,
                    confirmedAt=if(preview.needsReview)null else if(edit?.confirm==true)now else m.confirmedAt,
                    deferredAt=if(edit?.defer==true && preview.needsReview)now else if(changed || !preview.needsReview)null else m.deferredAt,
                    anchorGeometryJson=if(preview.unresolved)previous?.anchorGeometryJson else if(m.anchor!=null)drawing else null,
                    lastValidGeometryJson=if(preview.unresolved)previous?.lastValidGeometryJson ?: oldDrawing else drawing)
            }
            preview.copy(updatedAt=version,deletedAt=if(preview.deletedAt!=null)version else null,mountings=mountings)
        }
    }
    require(targets.count {it.deletedAt==null}<=1000)
    return targets
}

internal fun areaOperationPayload(original: MeasurementEditDocument, targets: List<AreaFeature>,
    edits: Map<String,AreaFeatureEdit>): JsonArray = buildJsonArray {
    targets.forEach {a -> add(buildJsonObject {
        val old=original.placements.areas.find {it.uuid==a.uuid}
        val edit=edits[a.uuid]
        put("uuid",a.uuid); put("elementUuid",a.elementUuid?.let(::JsonPrimitive) ?: JsonNull)
        put("expectedUpdatedAt",old?.updatedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("expectedElementUpdatedAt",a.elementUuid?.let {original.placements.elements.getValue(it)}?.let(::JsonPrimitive) ?: JsonNull)
        put("role",a.role.name);put("shapeKind",if(a.shape is AreaShape.Circle)"CIRCLE" else "POLYGON")
        put("positionsJson",encodeAreaPositions(a.shape));put("radiusMm",(a.shape as? AreaShape.Circle)?.radiusMm?.let(::JsonPrimitive) ?: JsonNull)
        put("twiceAreaMm2",a.twiceAreaMm2);put("mountingsJson",encodeAreaMountings(a.mountings));put("note",a.note)
        put("radiusMeasured",if(a.radiusMeasured)1 else 0);put("needsReview",if(a.needsReview)1 else 0)
        put("unresolved",if(a.unresolved)1 else 0);put("deleted",if(a.deletedAt!=null)1 else 0)
        put("write",if(a==old)0 else 1);put("confirm",if(edit?.confirm==true)1 else 0)
        put("remeasuredRadius",if(edit?.remeasuredRadius==true)1 else 0)
        putJsonArray("remeasured") {edit?.remeasured?.sorted()?.forEach {add(it)}}
    }) }
}
