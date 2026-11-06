package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DetailsDocumentObservationTest {
    @Test fun childOnlyMutationEmitsOneCompleteDocumentWithoutChangingMeasurementVersion()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw->
            PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val original=store.load(f.project,f.id)
            val first=CompletableDeferred<MeasurementEditDocument>()
            val result=async {withTimeout(10000){store.observeDocument(f.project,f.id,original.draft.recordUuid).filterNotNull()
                .onEach {first.complete(it)}.take(2).toList()}}
            assertEquals(original,withTimeout(10000){first.await()})
            val element=MeasurementElementRepository(db).find(original.placements.points.single().elementUuid)!!
            val added=PointPlacementRepository(db).addPoint(element.version,original.updatedAt,2200,2500,150)
            val observed=result.await().last()
            assertEquals(original.updatedAt,observed.updatedAt)
            assertEquals(original.placements.points+added,observed.placements.points)
            assertEquals(original.placements.lines,observed.placements.lines);assertEquals(original.placements.areas,observed.placements.areas)
            assertEquals(MeasurementElementRepository(db).find(element.uuid)!!.updatedAt,observed.placements.elements[element.uuid])
            store.closeAndAwaitPointReads()
        }
    }
    @Test fun automaticRefreshPreservesSelectionAndNeverReplacesDirtyDraft()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw->
            PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val original=store.load(f.project,f.id);val model=MeasurementEditModel(store,original)
            val point=original.placements.points.single();model.selectPoint(point.uuid)
            val elements=MeasurementElementRepository(db);val points=PointPlacementRepository(db)
            val added=points.addPoint(elements.find(point.elementUuid)!!.version,original.updatedAt,2200,2500,150)
            model.reloadDrawing()
            assertEquals(point.uuid,model.state.value.selectedPointUuid);assertTrue(model.displayPoints().any {it.uuid==added.uuid})
            model.beginPointDrag(point.uuid);model.previewPointDrag(3200.0,2200.0);model.finishPointDrag()
            val dirty=model.state.value
            points.addPoint(elements.find(point.elementUuid)!!.version,original.updatedAt,2500,2500,160)
            model.reloadDrawing();assertEquals(dirty,model.state.value,"A source notification cannot discard staged geometry")
            val before=OverlayDragFixture.snapshot(raw);model.save(keepEditing=true)
            assertEquals(before,OverlayDragFixture.snapshot(raw));assertTrue(model.state.value.dirty)
            store.closeAndAwaitPointReads()
        }
    }
}
