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
        quantityUnits(db)
    }

    private fun quantityUnits(db:PrototypeDatabase) {
        val f=PointPlacementProof.fixture(db,"AREA quantity units")
        val prices=PriceRepository(db);val elements=MeasurementElementRepository(db)
        fun price(unit:String)=prices.find(prices.createInGroup("area.unit.${newRecordUuid()}","Area unit ${newRecordUuid()}",PriceGroupRepository(db).all().first().uuid,1.0,300,unit,QuantityMode.MANUAL,PlacementKind.AREA))!!
        val square=price("м²");val count=price("шт.")
        val unknown=elements.createCatalogSelection(f.element.measurementUuid,square.uuid,ElementQuantity.Unknown,301)
        val counted=elements.createCatalogSelection(f.element.measurementUuid,count.uuid,ElementQuantity.Range(1000,3000),302)
        val store=MeasurementEditStore(db){310};var doc=store.load(f.project,f.id)
        fun tiny(id:String,element:String)=AreaFeature(id,doc.draft.recordUuid,AreaShape.rectangle(PointPositionMm(500,500),PointPositionMm(520,520)),doc.updatedAt,0,0,elementUuid=element)
        val one=tiny("tiny-one-${f.id}",unknown.uuid)
        doc=store.update(doc,doc.draft,now=310,areaEdits=mapOf(one.uuid to AreaFeatureEdit(one)))
        assertEquals(0,elements.find(unknown.uuid)!!.placedQuantityMilli)
        assertEquals(ElementPlacement.APPROXIMATE,elements.find(unknown.uuid)!!.placement,"Valid tiny geometry still has a placement")
        val two=one.copy(uuid="tiny-two-${f.id}")
        doc=store.update(doc,doc.draft,now=320,areaEdits=mapOf(two.uuid to AreaFeatureEdit(two)))
        assertEquals(1,elements.find(unknown.uuid)!!.placedQuantityMilli,"Aggregate canonical area must round only once")
        assertEquals(ElementQuantity.Unknown,elements.find(unknown.uuid)!!.quantity)
        elements.edit(elements.find(unknown.uuid)!!.version,"",unknown.approval,ElementQuantity.Range(1,2),321)
        assertEquals(ElementQuantity.Range(1,2),elements.find(unknown.uuid)!!.quantity)
        elements.edit(elements.find(unknown.uuid)!!.version,"",unknown.approval,ElementQuantity.Unknown,322)
        assertEquals(ElementQuantity.Unknown,elements.find(unknown.uuid)!!.quantity)
        elements.edit(elements.find(unknown.uuid)!!.version,"",unknown.approval,ElementQuantity.Exact(1),325)
        doc=store.load(f.project,f.id)
        val three=one.copy(uuid="tiny-three-${f.id}")
        doc=store.update(doc,doc.draft,now=330,areaEdits=mapOf(three.uuid to AreaFeatureEdit(three)))
        assertFails {store.update(doc,doc.draft,now=340,areaEdits=mapOf("tiny-over-${f.id}" to AreaFeatureEdit(one.copy(uuid="tiny-over-${f.id}"))))}
        val counts=(0..2).map {tiny("count-$it-${f.id}",counted.uuid)}
        doc=store.update(doc,doc.draft,now=350,areaEdits=counts.associate {it.uuid to AreaFeatureEdit(it)})
        assertEquals(3000,elements.find(counted.uuid)!!.placedQuantityMilli)
        assertEquals(ElementQuantity.Range(1000,3000),elements.find(counted.uuid)!!.quantity)
        assertFails {store.update(doc,doc.draft,now=360,areaEdits=mapOf("count-over-${f.id}" to AreaFeatureEdit(counts[0].copy(uuid="count-over-${f.id}"))))}
        elements.edit(elements.find(counted.uuid)!!.version,"",counted.approval,ElementQuantity.Range(1000,4000),370)
        elements.edit(elements.find(counted.uuid)!!.version,"",counted.approval,ElementQuantity.Unknown,380)
        assertEquals(ElementQuantity.Unknown,elements.find(counted.uuid)!!.quantity)
        assertEquals(3000,elements.find(counted.uuid)!!.placedQuantityMilli)
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
        fun alteredArea(id:String,fields:Map<String,JsonElement>):Map<String,JsonElement> = source+mapOf("areas" to JsonArray(source.getValue("areas").jsonArray.map {v ->
            val area=v.jsonObject
            if(area.getValue("uuid").jsonPrimitive.content!=id)v else {
                val mountings=Json.parseToJsonElement(area.getValue("mountingsJson").jsonPrimitive.content).jsonArray.map {m ->
                    val mounting=Json.parseToJsonElement(m.jsonPrimitive.content).jsonObject
                    JsonPrimitive(JsonObject(mounting+mapOf("modifiedVersion" to JsonPrimitive(115))).toString())
                }
                JsonObject(area+mapOf("write" to JsonPrimitive(1),"mountingsJson" to JsonPrimitive(JsonArray(mountings).toString()))+fields)
            }
        }))
        val circle=doc.placements.areas.first {it.shape is AreaShape.Circle}
        val tooLarge=AreaShape.Circle((circle.shape as AreaShape.Circle).center,2000)
        val crossed=AreaShape.Polygon(listOf(PointPositionMm(500,500),PointPositionMm(1600,1500),PointPositionMm(500,1500),PointPositionMm(1500,500)))
        val missingAnchor=PointMounting(anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,"missing-wall",doc.placements.walls[3].uuid,1500,2500),
            modifiedVersion=115,anchorGeometryJson=doc.sourceDrawingJson,lastValidGeometryJson=doc.sourceDrawingJson)
        val mutations=listOf(
            source+mapOf("areas" to JsonArray(emptyList())),
            source+mapOf("areas" to JsonArray(source.getValue("areas").jsonArray+source.getValue("areas").jsonArray.first())),
            source+mapOf("areas" to JsonArray(listOf(JsonObject(first+mapOf("twiceAreaMm2" to JsonPrimitive(1))))+source.getValue("areas").jsonArray.drop(1))),
            source+mapOf("areas" to JsonArray(listOf(JsonObject(first+mapOf("role" to JsonPrimitive("SUBTRACT"))))+source.getValue("areas").jsonArray.drop(1))),
            source+mapOf("areas" to JsonArray(listOf(JsonObject(first+mapOf("expectedUpdatedAt" to JsonPrimitive(-1))))+source.getValue("areas").jsonArray.drop(1))),
            alteredArea(circle.uuid,mapOf("radiusMm" to JsonPrimitive(tooLarge.radiusMm),"twiceAreaMm2" to JsonPrimitive(canonicalTwiceAreaMm2(tooLarge)))),
            alteredArea(first.getValue("uuid").jsonPrimitive.content,mapOf("positionsJson" to JsonPrimitive(encodeAreaPositions(crossed)),"twiceAreaMm2" to JsonPrimitive(100000))),
            alteredArea(circle.uuid,mapOf("mountingsJson" to JsonPrimitive(encodeAreaMountings(listOf(missingAnchor))))),
            alteredArea(first.getValue("uuid").jsonPrimitive.content,mapOf("mountingsJson" to JsonPrimitive(JsonArray(
                Json.parseToJsonElement(first.getValue("mountingsJson").jsonPrimitive.content).jsonArray.map {Json.parseToJsonElement(it.jsonPrimitive.content)}).toString())))
        )
        driver.execute(null,"PRAGMA foreign_keys=OFF",0)
        mutations.forEachIndexed {i,m ->assertFails("raw bypass $i") {db.ceilingEditsQueries.insertOperation("forged-$i-${doc.measurementId}",doc.draft.recordUuid,doc.updatedAt,JsonObject(m).toString(),115)}}
        for(sql in listOf("UPDATE area_features SET twiceAreaMm2=1,updatedAt=updatedAt+1",
            "UPDATE area_features SET mountingsJson='[]',updatedAt=updatedAt+1",
            "UPDATE area_features SET unresolved=0,needsReview=0,updatedAt=updatedAt+1",
            "UPDATE measurement_elements SET placedQuantityMilli=1 WHERE placementKind='area'",
            "UPDATE measurement_elements SET priceItemCode='forged' WHERE placementKind='area'",
            "UPDATE measurement_elements SET label='forged' WHERE placementKind='area'",
            "UPDATE ceiling_operation_results SET areaVersions='{}'",
            "UPDATE room_measurements SET updatedAt=updatedAt+1 WHERE id=${doc.measurementId}",
            "DELETE FROM ceiling_operations","UPDATE ceiling_walls SET active=0",
            "UPDATE room_measurements SET drawingJson='{}',updatedAt=updatedAt+1 WHERE id=${doc.measurementId}"))assertFails {driver.execute(null,sql,0)}
        driver.execute(null,"PRAGMA foreign_keys=ON",0)
        assertEquals(doc,MeasurementEditStore(db){115}.load(doc.draft.projectId,doc.measurementId))
    }
}
