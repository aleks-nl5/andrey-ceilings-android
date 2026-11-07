package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LinePlacementEditModelTest {
    @Test fun twoTapsOneUuidHistoryAnchorsDeclaredLengthRestoreAndOneSave()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {driver ->
            PriceCatalogSchema.create(driver)
            val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
            val price=PriceRepository(db)
            val id=price.createInGroup("line.model","Line",PriceGroupRepository(db).all().first().uuid,1.0,5,"м.п.",QuantityMode.MANUAL,PlacementKind.LINE)
            val item=price.find(id)!!
            val element=MeasurementElementRepository(db).createCatalogSelection(f.element.measurementUuid,item.uuid,ElementQuantity.Exact(4500),6, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
            val store=MeasurementEditStore(db){100}
            val model=MeasurementEditModel(store,store.load(f.project,f.id))
            model.beginLine(element.uuid);model.lineTap(500,600)
            assertTrue(model.state.value.dirty);assertTrue(model.displayLines().isEmpty())
            assertEquals(0,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM line_placements"))
            model.lineTap(3000,600)
            val uuid=assertNotNull(model.state.value.linePreview).uuid
            model.acceptLineCreation()
            assertEquals(uuid,model.displayLines().single().uuid)
            model.undo();assertTrue(model.displayLines().isEmpty());model.redo()
            assertEquals(uuid,model.displayLines().single().uuid)
            model.selectLine(uuid)
            for ((first,x) in listOf(true to "0,5",false to "3")) {
                model.openLineMounting(first)
                model.updateMounting {it.copy(firstText="0,6",secondText=x)}
                model.applyMounting()
            }
            assertEquals(PointPrecision.EXACT,model.displayLines().single().precision)
            model.requestDeclaredLength(element.uuid);model.takeDeclaredLength()
            assertEquals(2500,model.state.value.declaredLengths[element.uuid])
            val restored=assertNotNull(MeasurementEditModel.restore(store,model.snapshot(),f.project,f.id))
            restored.reconcile()
            assertEquals(model.displayLines(),restored.displayLines())
            assertEquals(2500,restored.state.value.declaredLengths[element.uuid])
            restored.save()
            assertNull(restored.state.value.error)
            assertTrue(restored.state.value.saved)
            val line=store.load(f.project,f.id).placements.lines.single()
            assertEquals(uuid,line.uuid);assertEquals(2500,line.lengthMm)
            assertEquals(ElementQuantity.Exact(2500),MeasurementElementRepository(db).find(element.uuid)!!.quantity)
            val next=MeasurementEditModel(store,store.load(f.project,f.id))
            next.selectLine(uuid);next.setLineMoveTarget(1);next.beginLineDrag(uuid);next.previewLineDrag(100.0,100.0)
            assertEquals(line,LinePlacementRepository(db).forMeasurement(element.measurementUuid).single())
            next.finishLineDrag()
            assertNull(next.displayLines().single().startMounting.anchor)
            assertNotNull(next.displayLines().single().endMounting.anchor)
            assertEquals(PointPrecision.APPROXIMATE,next.displayLines().single().precision)
            next.undo();assertEquals(line,next.displayLines().single())
            next.requestLineDelete();next.deleteLine();assertTrue(next.displayLines().isEmpty())
            next.undo();assertEquals(uuid,next.displayLines().single().uuid)
        }
    }
}
