package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.measurement.*
import kotlin.test.*
import kotlinx.serialization.json.*
import com.andreyceilings.shared.calculation.OrthogonalTurn

/** Real production transactions on every SQLCipher platform, never a substitute DB implementation. */
object WallAnchorProof {
    fun run(driver: SqlDriver) {
        LinePlacementProof.run(driver)
        val db=PrototypeDatabase(driver)
        val f=PointPlacementProof.fixture(db,"Монтаж P3C3")
        val store=MeasurementEditStore(db){100}
        var document=store.load(f.project,f.id)
        document=store.update(document,document.draft.reduce(MeasurementCreateIntent.Dimension("length","4")).reduce(MeasurementCreateIntent.Dimension("width","3")))
        val points=PointPlacementRepository(db);val elements=MeasurementElementRepository(db)
        val first=points.addPoint(elements.find(f.element.uuid)!!.version,document.updatedAt,1000,1000,110)
        val second=points.addPoint(elements.find(f.element.uuid)!!.version,document.updatedAt,3500,2000,111)
        document=store.load(f.project,f.id)
        val originalSecond=points.find(second.uuid)!!
        val anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,document.placements.walls[0].uuid,document.placements.walls[3].uuid,600,1500)
        val anchored=store.update(document,document.draft,mapOf(first.uuid to PointMountingEdit(anchor=anchor)),"anchor-${f.id}","walls-${f.id}",120)
        assertEquals(anchor,points.find(first.uuid)!!.mounting.anchor)
        assertEquals(PointPositionMm(1500,600),points.find(first.uuid)!!.let { PointPositionMm(it.xMm,it.yMm) })
        assertEquals(originalSecond,points.find(second.uuid))
        assertEquals(PointPrecision.EXACT,points.find(first.uuid)!!.precision)
        assertNull(points.find(second.uuid)!!.mounting.anchor)
        val changed=anchored.draft.reduce(MeasurementCreateIntent.Dimension("length","5"))
        val moved=store.update(anchored,changed,operationUuid="resize-${f.id}",wallToken="walls-${f.id}",now=130)
        assertEquals(anchor,points.find(first.uuid)!!.mounting.anchor)
        assertTrue(moved.placements.points.all { it.mounting.needsReview })
        assertEquals(2000L,elements.find(f.element.uuid)!!.placedQuantityMilli)
        // Retry after an uncertain response returns the same actual UUIDs/versions, no replay.
        assertEquals(moved,store.update(anchored,changed,operationUuid="resize-${f.id}",wallToken="walls-${f.id}",now=130))
        assertFailsWith<MeasurementEditConflict> { store.update(anchored,changed.copy(notes="stale")) }
        val later=store.update(moved,moved.draft,moved.placements.points.associate { it.uuid to PointMountingEdit(defer=true) },now=140)
        assertTrue(later.placements.points.all { it.mounting.needsReview && it.mounting.deferredAt==140L })
        val confirmed=store.update(later,later.draft,mapOf(first.uuid to PointMountingEdit(confirm=true)),now=150)
        assertFalse(confirmed.placements.points.first { it.uuid==first.uuid }.mounting.needsReview)
        var shrunk=store.update(confirmed,confirmed.draft.reduce(MeasurementCreateIntent.Dimension("length","2")),now=160)
        assertEquals(1000L,elements.find(f.element.uuid)!!.placedQuantityMilli)
        assertEquals(1,shrunk.placements.points.count { it.mounting.unresolved })
        assertEquals(second.uuid,shrunk.placements.points.single { it.mounting.unresolved }.uuid)
        assertEquals(3500L,points.find(second.uuid)!!.xMm)
        assertFailsWith<IllegalArgumentException> { store.update(shrunk,shrunk.draft,mapOf(second.uuid to PointMountingEdit(confirm=true)),now=170) }
        val unresolvedPoint=points.find(second.uuid)!!
        val unresolvedElement=elements.find(f.element.uuid)!!
        assertFailsWith<PointPlacementFailure.RequiresMeasuredAnchor> {points.movePoint(unresolvedElement.version,unresolvedPoint,shrunk.updatedAt,1000,1500,PointPrecision.APPROXIMATE,175)}
        assertFails {db.pointPlacementsQueries.insertMoveOperation("unresolved-free-${f.id}",second.uuid,unresolvedPoint.updatedAt,unresolvedElement.updatedAt,shrunk.updatedAt,1000,1500,"approximate",175)}
        unresolvedJournalCannotInventMeasuredResolution(db,shrunk,second.uuid)
        val expanded=store.update(shrunk,shrunk.draft.reduce(MeasurementCreateIntent.Dimension("length","5")),now=176)
        assertTrue(expanded.placements.points.single{it.uuid==second.uuid}.mounting.unresolved,"Expanding geometry cannot invent measured confirmation")
        assertEquals(1000L,elements.find(f.element.uuid)!!.placedQuantityMilli)
        shrunk=store.update(expanded,expanded.draft.reduce(MeasurementCreateIntent.Dimension("length","2")),now=177)
        val fixed=store.update(shrunk,shrunk.draft,mapOf(second.uuid to PointMountingEdit(anchor=anchor.copy(firstMm=1200,secondMm=1000))),now=180)
        assertEquals(2000L,elements.find(f.element.uuid)!!.placedQuantityMilli)
        assertFalse(fixed.placements.points.single { it.uuid==second.uuid }.mounting.unresolved)
        val before=points.forMeasurement(f.element.measurementUuid)
        val named=store.update(fixed,fixed.draft.copy(name="Інша назва",notes="Примітка"),now=190)
        assertEquals(before,points.forMeasurement(f.element.measurementUuid))
        assertEquals(named,store.update(named,named.draft))
        assertFails { driver.execute(null,"UPDATE room_measurements SET drawingJson='{}',updatedAt=updatedAt+1 WHERE id=${f.id}",0) }
        assertFails { driver.execute(null,"UPDATE point_placements SET mountingJson='{}',updatedAt=updatedAt+1 WHERE uuid='${first.uuid}'",0) }
        assertEquals(before,points.forMeasurement(f.element.measurementUuid))
        val dragged=points.movePoint(elements.find(f.element.uuid)!!.version,points.find(first.uuid)!!,named.updatedAt,1100,800,PointPrecision.APPROXIMATE,200).point
        assertNull(dragged.mounting.anchor);assertTrue(dragged.mounting.needsReview)
        assertEquals(PointPrecision.APPROXIMATE,dragged.precision)
        directReceiptBypasses(driver,db,f)
        hiddenOffsetSqlProof(db,f)
        staleLegacyMoveAfterCeilingEdit(db)
        staleWholeReceiptAfterElementPointOrReservationChange(db)
        capturedInclinedLegacyCanMountWithoutInventingSides(driver,db,f)
        applyingConstraintRejectIsProvenRollback(driver,db)
        openDesktopPriceCatalog(driver,true)
    }
    private fun applyingConstraintRejectIsProvenRollback(driver:SqlDriver,db:PrototypeDatabase) {
        val f=PointPlacementProof.fixture(db,"Відмова SQL constraint")
        val point=PointPlacementRepository(db).addPoint(f.element.version,f.contourVersion,1000,1000,10)
        val store=MeasurementEditStore(db){100};val document=store.load(f.project,f.id)
        val before=MeasurementRepository(db).find(f.id)
        val element=MeasurementElementRepository(db).find(f.element.uuid)
        val operations=PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations")
        val results=PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operation_results")
        val walls=MeasurementElementMigrationProof.rows(driver,"SELECT * FROM ceiling_walls ORDER BY uuid",9)
        // Reject after the real whole-operation trigger has already staged contour/wall changes.
        driver.execute(null,"CREATE TRIGGER test_ceiling_constraint BEFORE UPDATE ON point_placements WHEN new.uuid='${point.uuid}' BEGIN SELECT RAISE(ABORT,'ceiling_test_constraint'); END",0)
        try {
            val failure=assertFailsWith<MeasurementEditRejected> {
                store.update(document,document.draft.reduce(MeasurementCreateIntent.Dimension("length","6")),operationUuid="rejected-${f.id}")
            }
            assertNotNull(failure.cause)
            assertEquals(before,MeasurementRepository(db).find(f.id))
            assertEquals(point,PointPlacementRepository(db).find(point.uuid))
            assertEquals(element,MeasurementElementRepository(db).find(f.element.uuid))
            assertEquals(document,store.load(f.project,f.id))
            assertEquals(walls,MeasurementElementMigrationProof.rows(driver,"SELECT * FROM ceiling_walls ORDER BY uuid",9))
            assertEquals(operations,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations"))
            assertEquals(results,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operation_results"))
        } finally { driver.execute(null,"DROP TRIGGER test_ceiling_constraint",0) }
        val saved=store.update(document,document.draft.reduce(MeasurementCreateIntent.Dimension("length","7")),operationUuid="corrected-${f.id}")
        assertEquals(7.0,MeasurementRepository(db).find(f.id)!!.lengthMeters)
        assertTrue(saved.placements.points.single().mounting.needsReview)
        assertEquals(operations+1,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations"))
        assertEquals(results+1,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operation_results"))
    }
    private fun directReceiptBypasses(driver:SqlDriver,db:PrototypeDatabase,f:PointPlacementProof.Fixture) {
        val row=MeasurementRepository(db).find(f.id)!!
        val store=MeasurementEditStore(db){300};val doc=store.load(f.project,f.id)
        val beforePoints=doc.placements.points
        val beforeElement=MeasurementElementRepository(db).find(f.element.uuid)
        fun payload(points:List<JsonObject> = beforePoints.map { p -> buildJsonObject {
            put("uuid",p.uuid);put("elementUuid",p.elementUuid);put("expectedUpdatedAt",p.updatedAt)
            put("expectedElementUpdatedAt",doc.placements.elements.getValue(p.elementUuid))
            put("xMm",p.xMm);put("yMm",p.yMm);put("precision",p.precision.value);put("mountingJson",encodeMounting(p.mounting));put("write",0)
        } }):JsonObject=buildJsonObject {
            put("name",row.name+" raw");put("notes",row.notes);put("shape",row.shape)
            put("length",row.lengthMeters);put("width",row.widthMeters);put("area",row.areaSquareMeters);put("perimeter",row.perimeterMeters)
            put("drawingJson",row.drawingJson);put("oldDrawingJson",row.drawingJson)
            put("points",JsonArray(points));put("walls",JsonArray(doc.placements.walls.map {buildJsonObject{put("uuid",it.uuid)}}))
        }
        assertFails {driver.execute(null,"UPDATE room_measurements SET updatedAt=updatedAt-1 WHERE id=${f.id}",0)}
        assertFails {driver.execute(null,"UPDATE ceiling_operation_results SET pointVersions='{}' WHERE operationUuid='anchor-${f.id}'",0)}
        assertFails {driver.execute(null,"DELETE FROM ceiling_operation_results WHERE operationUuid='anchor-${f.id}'",0)}
        val valid=payload();val rows=valid.getValue("points").jsonArray.map {it.jsonObject}
        val anchoredIndex=beforePoints.indexOfFirst {it.mounting.anchor!=null}
        fun modifiedPoint(index:Int=anchoredIndex,change:(JsonObject)->JsonObject):JsonObject {
            val old=beforePoints[index]
            val writable=JsonObject(rows[index]+mapOf("write" to JsonPrimitive(1),"mountingJson" to JsonPrimitive(encodeMounting(old.mounting.copy(modifiedVersion=300)))))
            return payload(rows.mapIndexed { i,p -> if(i==index)change(writable) else p })
        }
        val wrongX=modifiedPoint {JsonObject(it+("xMm" to JsonPrimitive(beforePoints[anchoredIndex].xMm+500)))}
        val invalidBool=modifiedPoint {p ->
            val metadata=Json.parseToJsonElement(p.getValue("mountingJson").jsonPrimitive.content).jsonObject
            JsonObject(p+("mountingJson" to JsonPrimitive(JsonObject(metadata+mapOf("needsReview" to JsonPrimitive(1),"confirmedAt" to JsonNull)).toString())))
        }
        val extraField=modifiedPoint {p ->
            val metadata=Json.parseToJsonElement(p.getValue("mountingJson").jsonPrimitive.content).jsonObject
            JsonObject(p+("mountingJson" to JsonPrimitive(JsonObject(metadata+("invented" to JsonPrimitive(true))).toString())))
        }
        val source=Json.parseToJsonElement(row.drawingJson).jsonObject
        val wrongSource=JsonObject(source+("measurementsMm" to JsonObject(source.getValue("measurementsMm").jsonObject+("width" to JsonPrimitive(9999)))))
        val sourcePoints=rows.mapIndexed {i,p->JsonObject(p+mapOf("write" to JsonPrimitive(1),"mountingJson" to JsonPrimitive(encodeMounting(beforePoints[i].mounting.copy(needsReview=true,modifiedVersion=300,confirmedAt=null,lastValidGeometryJson=wrongSource.toString())))))}
        val invalidSource=JsonObject(payload(sourcePoints)+("drawingJson" to JsonPrimitive(wrongSource.toString())))
        val swappedWalls=valid.getValue("walls").jsonArray.toMutableList().apply {val first=this[0];this[0]=this[1];this[1]=first}
        val attempts=listOf(payload(rows.drop(1)),payload(listOf(rows[0],rows[0])),wrongX,invalidBool,extraField,invalidSource,
            JsonObject(valid+("walls" to JsonArray(swappedWalls))))
        attempts.forEachIndexed {index,p ->
            val uuid="invalid-wall-op-${f.id}-$index"
            assertFails {db.ceilingEditsQueries.insertOperation(uuid,row.uuid,row.updatedAt,p.toString(),300)}
            assertNull(db.ceilingEditsQueries.selectOperation(uuid).executeAsOneOrNull())
            assertEquals(row,MeasurementRepository(db).find(f.id));assertEquals(beforePoints,PointPlacementRepository(db).forMeasurement(row.uuid))
            assertEquals(beforeElement,MeasurementElementRepository(db).find(f.element.uuid));assertEquals(doc.placements.walls,store.load(f.project,f.id).placements.walls)
        }
    }

    private fun hiddenOffsetSqlProof(db:PrototypeDatabase,f:PointPlacementProof.Fixture) {
        val lengths=listOf("6","5","2","3","2","3","2","5")
        val turns=listOf(OrthogonalTurn.RIGHT,OrthogonalTurn.RIGHT,OrthogonalTurn.RIGHT,OrthogonalTurn.LEFT,OrthogonalTurn.LEFT,OrthogonalTurn.RIGHT,OrthogonalTurn.RIGHT,OrthogonalTurn.RIGHT)
        val draft=MeasurementCreateDraft(f.project,com.andreyceilings.shared.calculation.MeasurementPrecision.ONE_MILLIMETER,"U contour","",
            MeasurementOutlineDraft.Orthogonal(lengths.mapIndexed{i,v->MeasurementSideInput(v,turns[i])}))
        val id=MeasurementCreateStore(db){330}.create(draft);val row=MeasurementRepository(db).find(id)!!
        val item=(f.element.source as ElementSource.CatalogSelection).priceItemUuid
        val element=MeasurementElementRepository(db).createCatalogSelection(row.uuid,item,ElementQuantity.Exact(6000),331)
        val point=PointPlacementRepository(db).addPoint(element.version,row.updatedAt,1000,3000,340)
        val store=MeasurementEditStore(db){501};val doc=store.load(f.project,id)
        val hidden=WallAnchor(WallAnchor.Kind.TWO_WALLS,doc.placements.walls[0].uuid,doc.placements.walls[1].uuid,3000,5000)
        val geometry=validateMeasurementCreate(doc.draft).geometry!!
        assertNull(solveWallAnchor(geometry,doc.placements.walls,hidden))
        assertFailsWith<IllegalArgumentException> {store.update(doc,doc.draft,mapOf(point.uuid to PointMountingEdit(anchor=hidden)))}
        val payload=buildJsonObject {
            put("name",row.name);put("notes",row.notes);put("shape",row.shape);put("drawingJson",row.drawingJson);put("oldDrawingJson",row.drawingJson)
            put("length",row.lengthMeters);put("width",row.widthMeters);put("area",row.areaSquareMeters);put("perimeter",row.perimeterMeters)
            putJsonArray("walls"){doc.placements.walls.forEach{add(buildJsonObject{put("uuid",it.uuid)})}}
            putJsonArray("points"){add(buildJsonObject {
                put("uuid",point.uuid);put("elementUuid",point.elementUuid);put("expectedUpdatedAt",point.updatedAt)
                put("expectedElementUpdatedAt",doc.placements.elements.getValue(point.elementUuid));put("xMm",1000);put("yMm",3000);put("precision","exact");put("write",1)
                put("mountingJson",encodeMounting(PointMounting(anchor=hidden,modifiedVersion=501,confirmedAt=501,anchorGeometryJson=row.drawingJson,lastValidGeometryJson=row.drawingJson)))
            })}
        }.toString()
        assertFails {db.ceilingEditsQueries.insertOperation("hidden-offset-${f.id}",row.uuid,row.updatedAt,payload,501)}
        assertEquals(row,MeasurementRepository(db).find(id));assertEquals(point,PointPlacementRepository(db).find(point.uuid))
        val visible=hidden.copy(secondWall=doc.placements.walls[7].uuid,secondMm=1000)
        val saved=store.update(doc,doc.draft,mapOf(point.uuid to PointMountingEdit(anchor=visible)),now=502)
        assertEquals(visible,saved.placements.points.single().mounting.anchor)
    }

    private fun staleLegacyMoveAfterCeilingEdit(db:PrototypeDatabase) {
        val f=PointPlacementProof.fixture(db,"Move before contour")
        val points=PointPlacementRepository(db);val elements=MeasurementElementRepository(db)
        val point=points.addPoint(f.element.version,f.contourVersion,1000,1000,10)
        val originalElement=elements.find(f.element.uuid)!!.version
        val receipt="before-co-edit-${f.id}"
        points.movePoint(originalElement,point,f.contourVersion,1500,1000,PointPrecision.APPROXIMATE,20,receipt)
        val store=MeasurementEditStore(db){30};val doc=store.load(f.project,f.id)
        store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","6")))
        val current=points.find(point.uuid)
        assertFailsWith<PointPlacementFailure.StaleVersion> {points.movePoint(originalElement,point,f.contourVersion,1500,1000,PointPrecision.APPROXIMATE,20,receipt)}
        assertEquals(current,points.find(point.uuid))
    }

    private fun capturedInclinedLegacyCanMountWithoutInventingSides(driver:SqlDriver,db:PrototypeDatabase,f:PointPlacementProof.Fixture) {
        for(reverse in listOf(false,true)) {
            val coordinates=listOf(0L to 0L,4000L to 1000L,5000L to 4000L,1000L to 3000L).let {if(reverse)it.reversed() else it}
                .map{com.andreyceilings.shared.calculation.CeilingPointMm(it.first-6000,it.second-5000)}
            val geometry=com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract.capture("polygon",coordinates,emptyMap(),0.001)
            val json=MeasurementGeometryCodec.write(geometry)
            val id=MeasurementRepository(db).createReturningId(f.project,"Похила стеля",shape="polygon",lengthMeters=5.0000000000001,widthMeters=4.0000000000002,
                areaSquareMeters=geometry.areaSquareMeters,perimeterMeters=geometry.perimeterMeters,drawingJson=json,createdAt=400)
            val row=MeasurementRepository(db).find(id)!!
            fun totals()=MeasurementElementMigrationProof.rows(driver,"SELECT quote(lengthMeters),quote(widthMeters),quote(areaSquareMeters),quote(perimeterMeters) FROM room_measurements WHERE id=$id",4)
            val beforeTotals=totals()
            val element=MeasurementElementRepository(db).createCatalogSelection(row.uuid,(f.element.source as ElementSource.CatalogSelection).priceItemUuid,ElementQuantity.Exact(6000),401)
            val point=PointPlacementRepository(db).addPoint(element.version,row.updatedAt,-4000,-3500,410)
            val store=MeasurementEditStore(db){500};val doc=store.load(f.project,id)
            assertIs<MeasurementOutlineDraft.Captured>(doc.draft.outline)
            assertEquals(doc.draft,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","8")))
            assertEquals(doc.draft,doc.draft.reduce(MeasurementCreateIntent.Shape("polygon")))
            val anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,doc.placements.walls[0].uuid,doc.placements.walls[3].uuid,701,1234)
            val expected=assertNotNull(solveWallAnchor(geometry,doc.placements.walls,anchor))
            val saved=store.update(doc,doc.draft,mapOf(point.uuid to PointMountingEdit(anchor=anchor)))
            val mounted=saved.placements.points.single()
            assertEquals(expected.position,PointPositionMm(mounted.xMm,mounted.yMm));assertEquals(anchor,mounted.mounting.anchor)
            assertEquals(json,MeasurementRepository(db).find(id)!!.drawingJson)
            assertEquals(beforeTotals,totals(),"Mounting never rewrites captured scalar bytes")
            store.update(saved,saved.draft.copy(notes="Лише примітка"),now=510)
            assertEquals(beforeTotals,totals())
            assertEquals(geometry,validateMeasurementCreate(saved.draft).geometry)
            assertEquals(1000L,MeasurementElementRepository(db).find(element.uuid)!!.placedQuantityMilli)
            val captured=MeasurementCreateDraft.restore(saved.draft.snapshot(),f.project)!!
            assertEquals(saved.draft,captured)
        }
    }

    private fun staleWholeReceiptAfterElementPointOrReservationChange(db:PrototypeDatabase) {
        val f=PointPlacementProof.fixture(db,"Whole receipt CAS")
        val points=PointPlacementRepository(db);val elements=MeasurementElementRepository(db)
        val point=points.addPoint(f.element.version,f.contourVersion,1000,1000,10)
        val store=MeasurementEditStore(db){100};val first=store.load(f.project,f.id)
        val anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,first.placements.walls[0].uuid,first.placements.walls[3].uuid,600,1500)
        val edit=mapOf(point.uuid to PointMountingEdit(anchor=anchor))
        store.update(first,first.draft,edit,"whole-cas-${f.id}","same-walls",100)
        var element=elements.find(f.element.uuid)!!
        elements.edit(element.version,"Чужа примітка",element.approval,element.quantity,110)
        assertFailsWith<MeasurementEditConflict> {store.update(first,first.draft,edit,"whole-cas-${f.id}","same-walls",100)}
        val second=store.load(f.project,f.id);val edit2=mapOf(point.uuid to PointMountingEdit(anchor=anchor.copy(firstMm=700)))
        store.update(second,second.draft,edit2,"whole-cas-point-${f.id}","same-walls",120)
        val current=store.load(f.project,f.id)
        element=elements.find(f.element.uuid)!!
        val moved=points.movePoint(element.version,points.find(point.uuid)!!,current.updatedAt,1700,1300,PointPrecision.APPROXIMATE,130).point
        assertFailsWith<MeasurementEditConflict> {store.update(second,second.draft,edit2,"whole-cas-point-${f.id}","same-walls",120)}
        assertEquals(moved,points.find(point.uuid))
        val third=store.load(f.project,f.id)
        store.update(third,third.draft,edit,"whole-cas-reservation-${f.id}","same-walls",140)
        val saved=store.load(f.project,f.id)
        points.addPoint(elements.find(f.element.uuid)!!.version,saved.updatedAt,2000,1000,150)
        assertFailsWith<MeasurementEditConflict> {store.update(third,third.draft,edit,"whole-cas-reservation-${f.id}","same-walls",140)}
        assertEquals(2,points.forMeasurement(f.element.measurementUuid).size)
    }

    private fun unresolvedJournalCannotInventMeasuredResolution(db:PrototypeDatabase,doc:MeasurementEditDocument,uuid:String) {
        val row=MeasurementRepository(db).find(doc.measurementId)!!
        val point=doc.placements.points.single{it.uuid==uuid}
        val target=point.copy(xMm=1000,yMm=1500,mounting=point.mounting.copy(anchor=null,unresolved=false,needsReview=true,
            modifiedVersion=maxOf(point.updatedAt+1,175),confirmedAt=null,lastValidGeometryJson=row.drawingJson))
        val payload=buildJsonObject {
            put("name",row.name);put("notes",row.notes);put("shape",row.shape);put("drawingJson",row.drawingJson);put("oldDrawingJson",row.drawingJson)
            put("length",row.lengthMeters);put("width",row.widthMeters);put("area",row.areaSquareMeters);put("perimeter",row.perimeterMeters)
            putJsonArray("walls"){doc.placements.walls.forEach{add(buildJsonObject{put("uuid",it.uuid)})}}
            putJsonArray("points"){doc.placements.points.forEach {old ->
                val p=if(old.uuid==uuid)target else old
                add(buildJsonObject {
                    put("uuid",p.uuid);put("elementUuid",p.elementUuid);put("expectedUpdatedAt",old.updatedAt)
                    put("expectedElementUpdatedAt",doc.placements.elements.getValue(p.elementUuid));put("xMm",p.xMm);put("yMm",p.yMm)
                    put("precision",p.precision.value);put("mountingJson",encodeMounting(p.mounting));put("write",if(p.uuid==uuid)1 else 0)
                })
            }}
        }.toString()
        val op="unresolved-journal-${doc.measurementId}"
        assertFails {db.ceilingEditsQueries.insertOperation(op,row.uuid,row.updatedAt,payload,175)}
        assertNull(db.ceilingEditsQueries.selectOperation(op).executeAsOneOrNull())
        assertEquals(row,MeasurementRepository(db).find(row.id));assertEquals(point,PointPlacementRepository(db).find(uuid))
    }

}
