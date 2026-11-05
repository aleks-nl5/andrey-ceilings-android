package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Production transaction proof reused on JDBC, Android WAL, Windows and iOS SQLCipher. */
object AreaFeatureProof {
    fun run(driver: SqlDriver) {
        val db=PrototypeDatabase(driver)
        val f=PointPlacementProof.fixture(db,"P3C5 площинні позначки")
        val elements=MeasurementElementRepository(db)
        val prices=PriceRepository(db)
        val item=prices.find(prices.createInGroup("area.${newRecordUuid()}","Область ${newRecordUuid()}",
            PriceGroupRepository(db).all().first().uuid,1.0,6,"м²",QuantityMode.MANUAL,PlacementKind.AREA))!!
        val element=elements.createCatalogSelection(f.element.measurementUuid,item.uuid,ElementQuantity.Range(100,3000),7)
        val lineItem=prices.find(prices.createInGroup("area.line.${newRecordUuid()}","Лінія ${newRecordUuid()}",
            item.groupUuid,1.0,8,"м.п.",QuantityMode.MANUAL,PlacementKind.LINE))!!
        val lineElement=elements.createCatalogSelection(f.element.measurementUuid,lineItem.uuid,ElementQuantity.Unknown,9)
        PointPlacementRepository(db).addPoint(f.element.version,f.contourVersion,4000,2500,10)
        val store=MeasurementEditStore(db){100}
        var doc=store.load(f.project,f.id)
        val beforeArea=MeasurementRepository(db).find(f.id)!!.areaSquareMeters
        val polygon=AreaShape.rectangle(PointPositionMm(500,500),PointPositionMm(1500,1500))
        val annotation=AreaFeature("obstacle-${f.id}",doc.draft.recordUuid,polygon,doc.updatedAt,0,0,role=AreaRole.OBSTACLE,note="Вентканал")
        val circle=AreaFeature("void-${f.id}",doc.draft.recordUuid,AreaShape.Circle(PointPositionMm(2500,1500),200),doc.updatedAt,0,0,
            role=AreaRole.VOID_MARKER,radiusMeasured=true)
        val priced=annotation.copy(uuid="priced-${f.id}",elementUuid=element.uuid,role=AreaRole.GENERAL_AREA)
        val line=LinePlacement("area-line-${f.id}",lineElement.uuid,PointPositionMm(500,600),PointPositionMm(3000,600),doc.updatedAt,0,0)
        val edits=listOf(annotation,circle,priced).associate {it.uuid to AreaFeatureEdit(it)}
        val original=doc
        doc=store.update(doc,doc.draft,operationUuid="area-create-${f.id}",now=100,
            lineEdits=mapOf(line.uuid to LinePlacementEdit(line)),areaEdits=edits)
        assertEquals(doc,store.update(original,original.draft,operationUuid="area-create-${f.id}",now=100,
            lineEdits=mapOf(line.uuid to LinePlacementEdit(line)),areaEdits=edits))
        assertEquals(3,doc.placements.areas.size)
        assertEquals(1000,elements.find(element.uuid)!!.placedQuantityMilli)
        assertEquals(ElementQuantity.Range(100,3000),elements.find(element.uuid)!!.quantity)
        assertEquals(beforeArea,MeasurementRepository(db).find(f.id)!!.areaSquareMeters)
        assertEquals(2,doc.placements.areas.count {it.elementUuid==null})
        assertFails {store.update(original,original.draft.copy(notes="different"),operationUuid="area-create-${f.id}",now=100,areaEdits=edits)}
        val fixed=doc.placements.areas.first {it.uuid==priced.uuid}.let {area -> area.copy(mountings=area.shape.positions.map {p ->
            PointMounting(anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,doc.placements.walls[0].uuid,doc.placements.walls[3].uuid,p.yMm,p.xMm))
        })}
        doc=store.update(doc,doc.draft,now=110,areaEdits=mapOf(fixed.uuid to AreaFeatureEdit(fixed,confirm=true)))
        assertEquals(ElementPlacement.EXACT,elements.find(element.uuid)!!.placement)
        assertEquals(PointPrecision.EXACT,doc.placements.areas.first {it.uuid==fixed.uuid}.precision)
        // A failure after contour/point/line writes must roll the entire operation back.
        val saved=doc
        driver.execute(null,"CREATE TRIGGER reject_area_proof BEFORE UPDATE ON area_features BEGIN SELECT RAISE(ABORT,'injected_area_failure'); END",0)
        try {
            assertFails {store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","2")),now=120)}
            assertEquals(saved,store.load(f.project,f.id))
        } finally {driver.execute(null,"DROP TRIGGER reject_area_proof",0)}
        rejectRawBypasses(driver,db,doc)
        // Overlaps are permitted, independently counted, with no outline deduction.
        val second=priced.copy(uuid="priced-second-${f.id}")
        doc=store.update(doc,doc.draft,now=130,areaEdits=mapOf(second.uuid to AreaFeatureEdit(second)))
        assertEquals(2000,elements.find(element.uuid)!!.placedQuantityMilli)
        assertEquals(beforeArea,MeasurementRepository(db).find(f.id)!!.areaSquareMeters)
        doc=store.update(doc,doc.draft,now=140,declaredAreas=mapOf(element.uuid to 2000))
        assertEquals(ElementQuantity.Exact(2000),elements.find(element.uuid)!!.quantity)
        assertFails {store.update(doc,doc.draft,now=145,areaEdits=mapOf("over-${f.id}" to AreaFeatureEdit(second.copy(uuid="over-${f.id}"))))}
        doc=store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","1")),now=150)
        assertTrue(doc.placements.areas.all {it.unresolved && it.needsReview})
        assertEquals(0,elements.find(element.uuid)!!.placedQuantityMilli)
        assertFails {elements.edit(elements.find(element.uuid)!!.version,"",element.approval,ElementQuantity.Exact(1000),155)}
        doc=store.update(doc,doc.draft,now=160,areaEdits=doc.placements.areas.associate {it.uuid to AreaFeatureEdit(it,defer=true)})
        assertTrue(doc.placements.areas.all {it.mountings.all {m->m.deferredAt==160L}})
        doc=store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","5")),now=170)
        assertTrue(doc.placements.areas.all {it.unresolved},"Expansion never silently resolves a lost footprint")
        val stale=doc
        elements.edit(elements.find(element.uuid)!!.version,"Concurrent",element.approval,ElementQuantity.Exact(2000),175)
        assertFailsWith<MeasurementEditConflict> {store.update(stale,stale.draft.copy(notes="stale"),now=180)}
        doc=store.load(f.project,f.id)
        val repair=doc.placements.areas.first {it.uuid==fixed.uuid}.copy(shape=fixed.shape,mountings=fixed.mountings,unresolved=false)
        doc=store.update(doc,doc.draft,now=190,areaEdits=mapOf(repair.uuid to AreaFeatureEdit(repair,confirm=true,remeasured=repair.shape.positions.indices.toSet())))
        assertFalse(doc.placements.areas.first {it.uuid==repair.uuid}.unresolved)
        assertEquals(1000,elements.find(element.uuid)!!.placedQuantityMilli)
        doc=store.update(doc,doc.draft,now=200,areaEdits=mapOf(annotation.uuid to AreaFeatureEdit(doc.placements.areas.first {it.uuid==annotation.uuid}.copy(deletedAt=0))))
        assertEquals(3,doc.placements.areas.size)
        assertEquals(4,AreaFeatureRepository(db).all().count {it.measurementUuid==doc.draft.recordUuid})
        elements.delete(elements.find(element.uuid)!!.version,210)
        assertTrue(AreaFeatureRepository(db).all().filter {it.elementUuid==element.uuid}.all {it.deletedAt!=null})
        MeasurementRepository(db).delete(f.id)
        assertTrue(AreaFeatureRepository(db).forMeasurement(doc.draft.recordUuid).isEmpty())
        driver.execute(null,"PRAGMA foreign_keys=OFF",0)
        for(sql in listOf("DELETE FROM area_features","DELETE FROM room_measurements WHERE id=${f.id}",
            "DELETE FROM projects WHERE id=${f.project}","UPDATE area_features SET measurementUuid='missing'",
            "DELETE FROM measurement_elements WHERE uuid='${element.uuid}'"))assertFails {driver.execute(null,sql,0)}
        driver.execute(null,"PRAGMA foreign_keys=ON",0)
        assertEquals(0,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM pragma_foreign_key_check"))
    }

    private fun rejectRawBypasses(driver:SqlDriver,db:PrototypeDatabase,doc:MeasurementEditDocument) {
        var payload=""
        db.transaction {
            val area=doc.placements.areas.first()
            CeilingEditRepository(db).save(doc,doc.draft.copy(notes="payload proof"),emptyMap(),"template-${doc.measurementId}","walls",115,
                areaEdits=mapOf(area.uuid to AreaFeatureEdit(area.copy(note="changed"))))
            payload=db.ceilingEditsQueries.selectOperation("template-${doc.measurementId}").executeAsOne().payload
            rollback()
        }
        val source=Json.parseToJsonElement(payload).jsonObject
        val first=source.getValue("areas").jsonArray.first().jsonObject
        val mutations=listOf(
            source+mapOf("areas" to JsonArray(emptyList())),
            source+mapOf("areas" to JsonArray(source.getValue("areas").jsonArray+source.getValue("areas").jsonArray.first())),
            source+mapOf("areas" to JsonArray(listOf(JsonObject(first+mapOf("twiceAreaMm2" to JsonPrimitive(1))))+source.getValue("areas").jsonArray.drop(1))),
            source+mapOf("areas" to JsonArray(listOf(JsonObject(first+mapOf("role" to JsonPrimitive("SUBTRACT"))))+source.getValue("areas").jsonArray.drop(1))),
            source+mapOf("areas" to JsonArray(listOf(JsonObject(first+mapOf("expectedUpdatedAt" to JsonPrimitive(-1))))+source.getValue("areas").jsonArray.drop(1)))
        )
        driver.execute(null,"PRAGMA foreign_keys=OFF",0)
        mutations.forEachIndexed {i,m ->assertFails("raw bypass $i") {db.ceilingEditsQueries.insertOperation("forged-$i-${doc.measurementId}",doc.draft.recordUuid,doc.updatedAt,JsonObject(m).toString(),115)}}
        for(sql in listOf("UPDATE area_features SET twiceAreaMm2=1,updatedAt=updatedAt+1",
            "UPDATE area_features SET mountingsJson='[]',updatedAt=updatedAt+1",
            "UPDATE area_features SET unresolved=0,needsReview=0,updatedAt=updatedAt+1",
            "UPDATE measurement_elements SET placedQuantityMilli=1 WHERE placementKind='area'",
            "UPDATE ceiling_operation_results SET areaVersions='{}'",
            "DELETE FROM ceiling_operations","UPDATE ceiling_walls SET active=0",
            "UPDATE room_measurements SET drawingJson='{}',updatedAt=updatedAt+1 WHERE id=${doc.measurementId}"))assertFails {driver.execute(null,sql,0)}
        driver.execute(null,"PRAGMA foreign_keys=ON",0)
        assertEquals(doc,MeasurementEditStore(db){115}.load(doc.draft.projectId,doc.measurementId))
    }
}
