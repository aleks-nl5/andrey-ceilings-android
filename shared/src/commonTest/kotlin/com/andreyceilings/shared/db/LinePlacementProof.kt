package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*
import kotlinx.serialization.json.*

/** Same production transaction proof on Linux, real Windows SQLCipher, Android WAL and iOS SQLCipher. */
object LinePlacementProof {
    fun run(driver:SqlDriver) {
        val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db,"Лінії P3C4")
        val prices=PriceRepository(db);val elements=MeasurementElementRepository(db)
        val item=prices.find(prices.createInGroup("line.${newRecordUuid()}","Лінія ${newRecordUuid()}",PriceGroupRepository(db).all().first().uuid,
            1.0,6,"м.п.",QuantityMode.ENTERED_LENGTH,PlacementKind.LINE))!!
        val element=elements.createCatalogSelection(f.element.measurementUuid,item.uuid,ElementQuantity.Exact(4500),7, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val point=PointPlacementRepository(db).addPoint(f.element.version,f.contourVersion,4000,2500,8)
        val store=MeasurementEditStore(db){100}
        var doc=store.load(f.project,f.id)
        val line=LinePlacement("line-${f.id}",element.uuid,PointPositionMm(500,600),PointPositionMm(3000,600),doc.updatedAt,0,0)
        val intent=mapOf(line.uuid to LinePlacementEdit(line))
        val first=store.update(doc,doc.draft,operationUuid="line-create-${f.id}",now=100,lineEdits=intent)
        assertEquals(first,store.update(doc,doc.draft,operationUuid="line-create-${f.id}",now=100,lineEdits=intent))
        assertEquals(2500,elements.find(element.uuid)!!.placedQuantityMilli)
        assertEquals(ElementQuantity.Exact(4500),elements.find(element.uuid)!!.quantity)
        assertEquals(point,PointPlacementRepository(db).find(point.uuid))
        assertEquals(ElementPlacement.APPROXIMATE,elements.find(element.uuid)!!.placement)
        assertEquals(2500,first.placements.lines.single().lengthMm)
        doc=first
        val saved=doc.placements.lines.single()
        val walls=doc.placements.walls
        fun mounting(x:Long)=PointMounting(anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,walls[0].uuid,walls[3].uuid,600,x))
        val exact=saved.copy(startMounting=mounting(500),endMounting=mounting(3000))
        doc=store.update(doc,doc.draft,now=110,lineEdits=mapOf(exact.uuid to LinePlacementEdit(exact,confirm=true)))
        assertEquals(ElementPlacement.EXACT,elements.find(element.uuid)!!.placement)
        rejectInventedMountingContext(db,doc)
        val second=line.copy(uuid="line-second-${f.id}",start=PointPositionMm(500,1600),end=PointPositionMm(2000,1600))
        doc=store.update(doc,doc.draft,now=120,lineEdits=mapOf(second.uuid to LinePlacementEdit(second)))
        assertEquals(4000,elements.find(element.uuid)!!.placedQuantityMilli)
        assertEquals(ElementPlacement.APPROXIMATE,elements.find(element.uuid)!!.placement)
        val before=doc
        val receiptsBefore=PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations")
        driver.execute(null,"CREATE TRIGGER reject_line_proof BEFORE UPDATE ON line_placements BEGIN SELECT RAISE(ABORT,'injected_line_reject'); END",0)
        try {
            assertFailsWith<MeasurementEditRejected> {store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","2.5")),now=123)}
            assertEquals(before,store.load(f.project,f.id),"Contour, points, line rows and summaries must all roll back")
            assertEquals(receiptsBefore,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations"))
        } finally {driver.execute(null,"DROP TRIGGER reject_line_proof",0)}
        assertFails {store.update(doc,doc.draft,now=125,lineEdits=mapOf("too-long" to LinePlacementEdit(second.copy(uuid="too-long"))))}
        assertEquals(before,store.load(f.project,f.id))
        val reduced=store.update(doc,doc.draft,now=130,declaredLengths=mapOf(element.uuid to 4000))
        assertEquals(ElementQuantity.Exact(4000),elements.find(element.uuid)!!.quantity)
        doc=store.update(reduced,reduced.draft.reduce(MeasurementCreateIntent.Dimension("length","2.5")),now=140)
        assertEquals(1,doc.placements.lines.count {it.unresolved})
        assertEquals(1500,elements.find(element.uuid)!!.placedQuantityMilli)
        assertEquals(4000,doc.placements.lines.sumOf {it.lengthMm})
        assertTrue(doc.placements.lines.all {it.needsReview})
        val reserved=elements.find(element.uuid)!!
        assertFails {elements.edit(reserved.version,"",reserved.approval,ElementQuantity.Exact(2000),145)}
        doc=store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","5.1")),now=150)
        assertTrue(doc.placements.lines.single {it.uuid==line.uuid}.unresolved,"Expansion cannot confirm lost measurements")
        for(sql in listOf(
            "UPDATE line_placements SET endX=endX+1,updatedAt=updatedAt+1",
            "UPDATE line_placements SET needsReview=0,unresolved=0,updatedAt=updatedAt+1",
            "DELETE FROM line_placements",
            "UPDATE room_measurements SET drawingJson='{}',updatedAt=updatedAt+1 WHERE id=${f.id}",
            "UPDATE measurement_elements SET placedQuantityMilli=1 WHERE uuid='${element.uuid}'")) assertFails {driver.execute(null,sql,0)}
        val unresolved=doc.placements.lines.single {it.uuid==line.uuid}
        val fixed=unresolved.copy(start=PointPositionMm(500,600),end=PointPositionMm(3000,600),startMounting=mounting(500),endMounting=mounting(3000),unresolved=false,needsReview=false)
        doc=store.update(doc,doc.draft,now=160,lineEdits=mapOf(line.uuid to LinePlacementEdit(fixed,confirm=true,remeasuredStart=true,remeasuredEnd=true)))
        assertFalse(doc.placements.lines.any {it.unresolved})
        assertEquals(4000,elements.find(element.uuid)!!.placedQuantityMilli)
        val old=doc
        elements.edit(elements.find(element.uuid)!!.version,"Конкурент",element.approval,ElementQuantity.Exact(4500),170)
        assertFailsWith<MeasurementEditConflict> {store.update(old,old.draft.copy(notes="stale"),now=180)}
        doc=store.load(f.project,f.id)
        val delete=doc.placements.lines.single {it.uuid==second.uuid}.copy(deletedAt=0)
        doc=store.update(doc,doc.draft,now=190,lineEdits=mapOf(delete.uuid to LinePlacementEdit(delete)))
        assertEquals(2500,elements.find(element.uuid)!!.placedQuantityMilli)
        assertEquals(1,doc.placements.lines.size)
        assertEquals(2,LinePlacementRepository(db).all().count {it.elementUuid==element.uuid})
        elements.delete(elements.find(element.uuid)!!.version,200)
        assertTrue(LinePlacementRepository(db).forMeasurement(element.measurementUuid).isEmpty())
        assertTrue(LinePlacementRepository(db).all().filter {it.elementUuid==element.uuid}.all {it.deletedAt!=null})
        // Tombstoned LINE history cannot be orphaned/reparented, even with FK checking disabled.
        driver.execute(null,"PRAGMA foreign_keys=OFF",0)
        for(sql in listOf(
            "DELETE FROM measurement_elements WHERE uuid='${element.uuid}'",
            "DELETE FROM room_measurements WHERE id=${f.id}",
            "DELETE FROM projects WHERE id=${f.project}",
            "UPDATE measurement_elements SET measurementUuid='missing-parent' WHERE uuid='${element.uuid}'")) assertFails {driver.execute(null,sql,0)}
        driver.execute(null,"PRAGMA foreign_keys=ON",0)
        val unknown=elements.createCatalogSelection(element.measurementUuid,item.uuid,ElementQuantity.Unknown,210, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        doc=store.load(f.project,f.id)
        val manual=line.copy(uuid="unknown-line-${f.id}",elementUuid=unknown.uuid,start=PointPositionMm(500,600),end=PointPositionMm(1500,600))
        doc=store.update(doc,doc.draft,now=220,lineEdits=mapOf(manual.uuid to LinePlacementEdit(manual)))
        assertEquals(ElementQuantity.Unknown,elements.find(unknown.uuid)!!.quantity)
        assertEquals(1000,elements.find(unknown.uuid)!!.placedQuantityMilli)
        val ranged=elements.createCatalogSelection(element.measurementUuid,item.uuid,ElementQuantity.Range(500,1500),230, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        doc=store.load(f.project,f.id)
        val rangeLine=manual.copy(uuid="range-line-${f.id}",elementUuid=ranged.uuid)
        doc=store.update(doc,doc.draft,now=240,lineEdits=mapOf(rangeLine.uuid to LinePlacementEdit(rangeLine)))
        assertEquals(ElementQuantity.Range(500,1500),elements.find(ranged.uuid)!!.quantity)
        assertFails {store.update(doc,doc.draft,now=250,lineEdits=mapOf("over-range-${f.id}" to LinePlacementEdit(rangeLine.copy(uuid="over-range-${f.id}"))))}
        doc=store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","1")),now=260)
        assertTrue(doc.placements.lines.all {it.unresolved})
        doc=store.update(doc,doc.draft,now=270,lineEdits=doc.placements.lines.associate {it.uuid to LinePlacementEdit(it,defer=true)})
        assertTrue(doc.placements.lines.all {it.needsReview && it.startMounting.deferredAt==270L && it.endMounting.deferredAt==270L})
        assertEquals(0,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM pragma_foreign_key_check"))
    }
    private fun rejectInventedMountingContext(db:PrototypeDatabase,doc:MeasurementEditDocument) {
        val line=doc.placements.lines.single()
        val operation=db.ceilingEditsQueries.selectOperation(line.lastCeilingEditUuid!!).executeAsOne()
        val source=Json.parseToJsonElement(operation.payload).jsonObject
        for (field in listOf("anchorGeometryJson","lastValidGeometryJson")) {
            val payload=JsonObject(source+mapOf("lines" to JsonArray(source.getValue("lines").jsonArray.map {value ->
                val item=value.jsonObject
                fun mounting(name:String)=Json.parseToJsonElement(item.getValue(name).jsonPrimitive.content).jsonObject.let {old ->
                    JsonObject(old+mapOf("modifiedVersion" to JsonPrimitive(115),field to JsonPrimitive("{}"))).toString()
                }
                JsonObject(item+mapOf("expectedUpdatedAt" to JsonPrimitive(line.updatedAt),
                    "expectedElementUpdatedAt" to JsonPrimitive(MeasurementElementRepository(db).find(line.elementUuid)!!.updatedAt),
                    "startMounting" to JsonPrimitive(mounting("startMounting")),"endMounting" to JsonPrimitive(mounting("endMounting")),"write" to JsonPrimitive(1)))
            }))).toString()
            assertFails {db.ceilingEditsQueries.insertOperation("invalid-line-context-${line.uuid}-$field",doc.draft.recordUuid,doc.updatedAt,payload,115)}
            assertEquals(doc.placements.lines,LinePlacementRepository(db).forMeasurement(doc.draft.recordUuid))
        }
    }

}
