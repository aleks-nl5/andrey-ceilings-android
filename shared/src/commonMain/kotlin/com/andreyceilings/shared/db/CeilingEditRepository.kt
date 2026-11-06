package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import kotlinx.serialization.json.*

/** Wall correspondence is explicit and conservative. An inputId is only a session mapping key. */
fun ceilingDraftWalls(original: MeasurementEditDocument, draft: MeasurementCreateDraft, token: String): List<CeilingWall> {
    val geometry = validateMeasurementCreate(draft).geometry ?: return emptyList()
    if(geometry==validateMeasurementCreate(original.draft).geometry && original.placements.walls.size==geometry.vertices.size)
        return original.placements.walls
    val old = original.draft.outline; val current = draft.outline
    val sameTopology = when {
        old is MeasurementOutlineDraft.Captured && current is MeasurementOutlineDraft.Captured -> old.geometry==current.geometry
        old is MeasurementOutlineDraft.Template && current is MeasurementOutlineDraft.Template -> old.shape == current.shape
        old is MeasurementOutlineDraft.Orthogonal && current is MeasurementOutlineDraft.Orthogonal -> old.sides.map { it.inputId } == current.sides.map { it.inputId }
        else -> false
    }
    return if (sameTopology && original.placements.walls.size == geometry.vertices.size) original.placements.walls
        else geometry.vertices.indices.map { CeilingWall("$token:${draft.shape}:$it",it) }
}

/** The only v10 contour/anchor writer. An immutable journal INSERT applies the whole transition. */
class CeilingEditRepository(private val database: PrototypeDatabase) {
    fun isCurrentResult(uuid:String,measurement:String,version:Long):Boolean {
        val result=database.ceilingEditsQueries.selectResult(uuid).executeAsOneOrNull() ?: return false
        if(result.measurementVersion!=version)return false
        val expectedPoints=Json.parseToJsonElement(result.pointVersions).jsonObject.mapValues {it.value.jsonPrimitive.long}
        val actualPoints=PointPlacementRepository(database).forMeasurement(measurement).associate {it.uuid to it.updatedAt}
        if(expectedPoints!=actualPoints)return false
        val expectedLines=Json.parseToJsonElement(result.lineVersions).jsonObject.mapValues {it.value.jsonPrimitive.long}
        if(expectedLines!=LinePlacementRepository(database).forMeasurement(measurement).associate {it.uuid to it.updatedAt})return false
        val expectedAreas=Json.parseToJsonElement(result.areaVersions).jsonObject.mapValues {it.value.jsonPrimitive.long}
        if(expectedAreas!=AreaFeatureRepository(database).forMeasurement(measurement).associate {it.uuid to it.updatedAt})return false
        return Json.parseToJsonElement(result.elementVersions).jsonObject.all { (id,v) ->
            MeasurementElementRepository(database).find(id)?.let {it.deletedAt==null && it.updatedAt==v.jsonPrimitive.long}==true
        }
    }
    fun save(original: MeasurementEditDocument, draft: MeasurementCreateDraft, edits: Map<String,PointMountingEdit>,
        operationUuid: String, wallToken: String, now: Long, lineEdits: Map<String,LinePlacementEdit> = emptyMap(), declaredLengths: Map<String,Long> = emptyMap(), areaEdits: Map<String,AreaFeatureEdit> = emptyMap(), declaredAreas: Map<String,Long> = emptyMap()) {
        require(now in 0 until Long.MAX_VALUE && operationUuid.isNotBlank())
        require(draft.projectId==original.draft.projectId && draft.recordUuid==original.draft.recordUuid && draft.precision==original.draft.precision)
        val validated = validateMeasurementCreate(draft); require(validated.canSave)
        val geometry = checkNotNull(validated.geometry)
        val baselineGeometry = checkNotNull(validateMeasurementCreate(original.draft).geometry)
        val changed = geometry!=baselineGeometry
        val walls = ceilingDraftWalls(original,draft,wallToken)
        require(edits.keys.all { id -> original.placements.points.any { it.uuid==id } })
        val oldDrawing = original.sourceDrawingJson ?: MeasurementGeometryCodec.write(baselineGeometry)
        val drawing = if (changed) MeasurementGeometryCodec.write(geometry) else oldDrawing
        val targets = original.placements.points.map { point ->
            val edit = edits[point.uuid]
            if (edit?.confirm==true) require(!point.mounting.unresolved || edit.anchor!=null)
            val preview = previewMounting(point,geometry,walls,changed,edit)
            if (edit?.anchor!=null || edit?.freeX!=null) require(!preview.mounting.unresolved) { "Оберіть інші стіни або коректні відступи." }
            if (preview==point && !changed && edit?.defer!=true && edit?.confirm!=true) point else {
                val version = maxOf(point.updatedAt+1,now)
                preview.copy(updatedAt=version,mounting=preview.mounting.copy(modifiedVersion=version,
                    confirmedAt=if (preview.mounting.needsReview) null else if (edit?.confirm==true || edit?.anchor!=null) now else point.mounting.confirmedAt,
                    deferredAt=if (edit?.defer==true && preview.mounting.needsReview) now else if (changed || !preview.mounting.needsReview) null else point.mounting.deferredAt,
                    anchorGeometryJson=if (edit?.anchor!=null) drawing else point.mounting.anchorGeometryJson,
                    lastValidGeometryJson=if (!preview.mounting.unresolved) drawing else point.mounting.lastValidGeometryJson ?: oldDrawing))
            }
        }
        require(lineEdits.all { (id,edit) -> id==edit.value.uuid && edit.value.uuid.isNotBlank() &&
            original.lineElements.any {it.uuid==edit.value.elementUuid} })
        val lineTargets = (original.placements.lines.map { it.uuid } + lineEdits.keys).distinct().map { id ->
            val old=original.placements.lines.find {it.uuid==id}
            val edit=lineEdits[id]
            val base=old ?: checkNotNull(edit).value
            require(base.updatedAt < Long.MAX_VALUE)
            val preview=previewLine(base,geometry,walls,changed,edit)
            if (old==null && !lineStrictlyInside(geometry,preview.start,preview.end)) throw LinePlacementFailure.InvalidSegment()
            if(edit!=null && edit.value.deletedAt==null && (preview.unresolved && !base.unresolved ||
                old?.unresolved==true && !preview.unresolved && !edit.confirm)) throw LinePlacementFailure.RequiresMeasuredAnchors()
            if(old!=null && preview==old && !changed && edit?.confirm!=true && edit?.defer!=true) old else {
                val version=if(old==null) now else maxOf(old.updatedAt+1,now)
                fun mounting(first:Boolean):PointMounting {
                    val m=if(first)preview.startMounting else preview.endMounting
                    val previous=old?.let {if(first)it.startMounting else it.endMounting}
                    return m.copy(modifiedVersion=version,needsReview=preview.needsReview,unresolved=preview.unresolved,
                        confirmedAt=if(preview.needsReview)null else if(edit?.confirm==true)now else m.confirmedAt,
                        deferredAt=if(edit?.defer==true && preview.needsReview)now else if(changed || !preview.needsReview)null else m.deferredAt,
                        anchorGeometryJson=if(preview.unresolved) previous?.anchorGeometryJson else if(m.anchor!=null)drawing else null,
                        lastValidGeometryJson=if(preview.unresolved)previous?.lastValidGeometryJson ?: oldDrawing else drawing)
                }
                preview.copy(updatedAt=version,deletedAt=if(preview.deletedAt!=null)version else null,
                    startMounting=mounting(true),endMounting=mounting(false))
            }
        }
        original.lineElements.forEach { element ->
            val lines=lineTargets.filter {it.elementUuid==element.uuid && it.deletedAt==null}
            val reserved=lines.sumOf {it.lengthMm}
            val capacity=declaredLengths[element.uuid] ?: element.lineCapacityMm()
            if(capacity!=null && reserved>capacity) throw LinePlacementFailure.CapacityReached()
        }
        require(declaredLengths.all { (id,length) -> length in 1..ElementQuantityRules.MAX_MILLI &&
            original.lineElements.any {it.uuid==id} && lineTargets.filter {it.elementUuid==id && it.deletedAt==null && !it.unresolved}.sumOf {it.lengthMm}==length })
        val areaTargets = prepareAreaTargets(original,geometry,walls,changed,areaEdits,now,drawing,oldDrawing)
        original.areaElements.forEach {element ->
            val capacity=declaredAreas[element.uuid] ?: element.lineCapacityMm()
            require(capacity==null || areaQuantityMilli(areaTargets.filter {it.elementUuid==element.uuid},element.unit!!,true)<=capacity) {"Площа/кількість перевищує заявлену, включно з нерозв'язаними об'єктами."}
        }
        require(declaredAreas.all { (id,quantity) -> quantity in 1..ElementQuantityRules.MAX_MILLI && original.areaElements.any {
            it.uuid==id && areaQuantityMilli(areaTargets.filter {a -> a.elementUuid==id},it.unit!!)==quantity
        } })
        val noChange = areaTargets==original.placements.areas && declaredAreas.isEmpty() && lineTargets==original.placements.lines && declaredLengths.isEmpty() && !changed && targets==original.placements.points && draft.name.trim()==original.draft.name.trim() && draft.notes.trim()==original.draft.notes.trim()
        val payload = buildJsonObject {
            put("name",draft.name.trim());put("notes",draft.notes.trim());put("shape",geometry.shapeType)
            put("drawingJson",drawing);put("oldDrawingJson",oldDrawing)
            put("length",(geometry.vertices.maxOf{it.xMm}-geometry.vertices.minOf{it.xMm})/1000.0)
            put("width",(geometry.vertices.maxOf{it.yMm}-geometry.vertices.minOf{it.yMm})/1000.0)
            put("area",geometry.areaSquareMeters);put("perimeter",geometry.perimeterMeters)
            putJsonArray("walls") { walls.forEach { add(buildJsonObject { put("uuid",it.uuid) }) } }
            putJsonObject("declaredLengths") {declaredLengths.entries.sortedBy {it.key}.forEach { (id,length) ->put(id,length)}}
            putJsonObject("declaredAreas") {declaredAreas.entries.sortedBy {it.key}.forEach { (id,quantity) ->put(id,quantity)}}
            put("areas",areaOperationPayload(original,areaTargets,areaEdits))
            putJsonArray("lines") { lineTargets.forEach { line -> add(buildJsonObject {
                val old=original.placements.lines.find {it.uuid==line.uuid}
                put("uuid",line.uuid);put("elementUuid",line.elementUuid)
                put("expectedUpdatedAt",old?.updatedAt?.let(::JsonPrimitive) ?: JsonNull)
                put("expectedElementUpdatedAt",original.placements.elements.getValue(line.elementUuid))
                put("startX",line.start.xMm);put("startY",line.start.yMm);put("endX",line.end.xMm);put("endY",line.end.yMm)
                put("lengthMm",line.lengthMm);put("widthMm",line.widthMm?.let(::JsonPrimitive) ?: JsonNull);put("note",line.note)
                put("startMounting",encodeMounting(line.startMounting));put("endMounting",encodeMounting(line.endMounting))
                put("needsReview",if(line.needsReview)1 else 0);put("unresolved",if(line.unresolved)1 else 0)
                put("deleted",if(line.deletedAt!=null)1 else 0);put("write",if(line==old)0 else 1)
                put("confirm",if(lineEdits[line.uuid]?.confirm==true)1 else 0)
                put("remeasuredStart",if(lineEdits[line.uuid]?.remeasuredStart==true)1 else 0)
                put("remeasuredEnd",if(lineEdits[line.uuid]?.remeasuredEnd==true)1 else 0)
            }) } }
            putJsonArray("points") { targets.forEachIndexed { i,p -> add(buildJsonObject {
                val old=original.placements.points[i]
                put("uuid",p.uuid);put("elementUuid",p.elementUuid);put("expectedUpdatedAt",old.updatedAt)
                put("expectedElementUpdatedAt",original.placements.elements.getValue(p.elementUuid))
                put("xMm",p.xMm);put("yMm",p.yMm);put("precision",p.precision.value)
                put("mountingJson",encodeMounting(p.mounting));put("write",if(p==old)0 else 1)
            }) } }
        }.toString()
        database.transaction {
            database.measurementElementsQueries.claimElementWrite("")
            val existing=database.ceilingEditsQueries.selectOperation(operationUuid).executeAsOneOrNull()
            val live=MeasurementRepository(database).find(original.measurementId) ?: throw MeasurementEditConflict()
            if(existing!=null) {
                if(existing.measurementUuid!=draft.recordUuid || existing.expectedUpdatedAt!=original.updatedAt || existing.payload!=payload)
                    throw PointPlacementFailure.RetryConflict()
                if(!isCurrentResult(operationUuid,draft.recordUuid,live.updatedAt)) throw MeasurementEditConflict()
                return@transaction
            }
            if(live.uuid!=draft.recordUuid || live.projectId!=draft.projectId || live.updatedAt!=original.updatedAt ||
                ProjectRepository(database).find(draft.projectId)==null) throw MeasurementEditConflict()
            if(AreaFeatureRepository(database).forMeasurement(live.uuid)!=original.placements.areas) throw MeasurementEditConflict()
            if(LinePlacementRepository(database).forMeasurement(live.uuid)!=original.placements.lines) throw MeasurementEditConflict()
            if(PointPlacementRepository(database).forMeasurement(live.uuid)!=original.placements.points) throw MeasurementEditConflict()
            original.placements.elements.forEach { (uuid,version) ->
                if(MeasurementElementRepository(database).find(uuid)?.updatedAt!=version) throw MeasurementEditConflict()
            }
            if(noChange) return@transaction
            database.ceilingEditsQueries.insertOperation(operationUuid,draft.recordUuid,original.updatedAt,payload,now)
        }
    }
}
