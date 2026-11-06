package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DetailsSaveExitTest {
    private fun dirty(store:MeasurementEditStore,f:OverlayDragFixture.Fixture)=MeasurementEditModel(store,store.load(f.project,f.id)).also {
        it.selectArea(f.polygon);it.beginAreaDrag(f.polygon);it.previewAreaDrag(300.0,200.0);it.finishAreaDrag()
    }
    @Test fun confirmedSaveDeliversExactActionOnceAfterCommit()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw ->
            PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val m=dirty(store,f);var calls=0
            m.requestExit {
                assertEquals(PointPositionMm(800,1200),store.load(f.project,f.id).placements.areas.first {it.uuid==f.polygon}.shape.positions.first())
                calls++
            }
            m.saveAndExit();assertEquals(1,calls);assertFalse(m.state.value.dirty);assertFalse(m.state.value.exitReady)
            val saved=OverlayDragFixture.snapshot(raw)
            m.saveAndExit();m.deliverPendingExit();assertEquals(1,calls);assertEquals(saved,OverlayDragFixture.snapshot(raw))
            assertEquals(2,PriceNameUniquenessProof.scalar(raw,"SELECT count(*) FROM ceiling_operations"))
        }
    }
    @Test fun provenRollbackKeepsIntentAndDraftThenRetrySavesOnce()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw ->
            PriceCatalogSchema.create(raw);val f=OverlayDragFixture.create(PrototypeDatabase(raw));val fault=DetailsExitFaultDriver(raw)
            val store=MeasurementEditStore(PrototypeDatabase(fault)){200};val m=dirty(store,f);val draft=m.state.value.area.edits
            val before=OverlayDragFixture.snapshot(raw);var calls=0;m.requestExit {calls++}
            fault.failNext(DetailsExitFaultDriver.Fault.ROLLBACK);m.saveAndExit()
            assertEquals(0,calls);assertTrue(m.state.value.exitRequested);assertFalse(m.state.value.uncertain);assertNotNull(m.state.value.error)
            assertEquals(draft,m.state.value.area.edits);assertEquals(before,OverlayDragFixture.snapshot(raw))
            m.saveAndExit();assertEquals(1,calls);assertEquals(2,PriceNameUniquenessProof.scalar(raw,"SELECT count(*) FROM ceiling_operations"))
        }
    }
    @Test fun lostResponseNeverNavigatesUntilSameReceiptIsConfirmed()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw ->
            PriceCatalogSchema.create(raw);val f=OverlayDragFixture.create(PrototypeDatabase(raw));val fault=DetailsExitFaultDriver(raw)
            val store=MeasurementEditStore(PrototypeDatabase(fault)){200};val m=dirty(store,f);val draft=m.state.value.area.edits
            var calls=0;m.requestExit {calls++};fault.failNext(DetailsExitFaultDriver.Fault.LOST_RESPONSE);m.saveAndExit()
            assertTrue(m.state.value.uncertain);assertTrue(m.state.value.exitRequested);assertEquals(0,calls);assertEquals(draft,m.state.value.area.edits)
            val committed=OverlayDragFixture.snapshot(raw);m.saveAndExit()
            assertEquals(1,calls);assertEquals(committed,OverlayDragFixture.snapshot(raw));assertFalse(m.state.value.uncertain)
            m.deliverPendingExit();assertEquals(1,calls)
        }
    }
    @Test fun conflictAndContinueDoNotLoseDraftOrRunCancelledActionAndDiscardStaysSeparate()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw ->
            PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val m=dirty(store,f);val draft=m.state.value.area.edits
            val doc=store.load(f.project,f.id);store.update(doc,doc.draft.copy(notes="Concurrent update"),now=150)
            val before=OverlayDragFixture.snapshot(raw);var cancelled=0;var discarded=0
            m.requestExit {cancelled++};m.saveAndExit();assertTrue(m.state.value.conflict);assertTrue(m.state.value.exitRequested)
            assertEquals(draft,m.state.value.area.edits);assertEquals(0,cancelled);assertEquals(before,OverlayDragFixture.snapshot(raw))
            m.saveAndExit();assertEquals(0,cancelled);m.continueEditing();assertFalse(m.state.value.exitRequested)
            m.saveAndExit();assertEquals(0,cancelled);assertEquals(draft,m.state.value.area.edits)
            m.requestExit {discarded++};m.discardAndExit();m.discardAndExit()
            assertEquals(0,cancelled);assertEquals(1,discarded);assertEquals(before,OverlayDragFixture.snapshot(raw))
        }
    }
}
