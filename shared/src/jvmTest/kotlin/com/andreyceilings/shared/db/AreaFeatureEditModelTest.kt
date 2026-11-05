package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AreaFeatureEditModelTest {
    @Test fun historyProcessRecreationAndDirtyExitKeepIdentityWithoutWriting() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            PriceCatalogSchema.create(d);val db=PrototypeDatabase(d);val f=PointPlacementProof.fixture(db)
            val store=MeasurementEditStore(db){100};val m=MeasurementEditModel(store,store.load(f.project,f.id))
            m.beginArea(AreaRole.OBSTACLE);m.areaTap(500,500);m.areaTap(1500,1500);m.acceptAreaCreation()
            val area=m.displayAreas().single()
            m.undo();assertTrue(m.displayAreas().isEmpty());m.redo();assertEquals(area,m.displayAreas().single())
            var exited=false;m.requestExit {exited=true}
            assertFalse(exited);assertTrue(m.state.value.exitRequested);m.continueEditing()
            val restored=MeasurementEditModel.restore(null,m.snapshot(),f.project,f.id)!!
            assertEquals(area,restored.displayAreas().single());assertTrue(restored.state.value.dirty)
            restored.selectArea(area.uuid);restored.openAreaProperties();restored.changeAreaProperties {it.copy(note="vent")};restored.applyAreaProperties()
            assertEquals("vent",restored.displayAreas().single().note)
            restored.requestAreaDelete();restored.deleteArea();assertTrue(restored.displayAreas().isEmpty())
            restored.undo();assertEquals(area.uuid,restored.displayAreas().single().uuid)
            assertTrue(AreaFeatureRepository(db).all().isEmpty())
        }
    }
    @Test fun lostCommittedResponseReconcilesTheSameUuidAndFreezesAllAreaEdits()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw ->
            PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=PointPlacementProof.fixture(db)
            val lose=java.util.concurrent.atomic.AtomicBoolean(false)
            val driver=object:SqlDriver by raw {
                override fun notifyListeners(vararg queryKeys:String) {
                    raw.notifyListeners(*queryKeys)
                    if("ceiling_operations" in queryKeys && lose.compareAndSet(true,false))error("lost commit response")
                }
            }
            val store=MeasurementEditStore(PrototypeDatabase(driver)){100}
            val m=MeasurementEditModel(store,store.load(f.project,f.id))
            m.beginArea(AreaRole.VOID_MARKER,tool=AreaTool.CIRCLE);m.areaTap(2000,1500);m.setAreaDiameter("0,5");m.acceptAreaCreation()
            val uuid=m.displayAreas().single().uuid
            lose.set(true);m.save();assertTrue(m.state.value.uncertain)
            val persisted=AreaFeatureRepository(db).forMeasurement(m.state.value.draft.recordUuid).single()
            m.selectArea(uuid);m.deleteArea();m.beginArea(AreaRole.OBSTACLE)
            assertEquals(uuid,m.displayAreas().single().uuid)
            assertNull(m.state.value.area.tool)
            m.save();assertTrue(m.state.value.saved,m.state.value.error)
            assertEquals(persisted,AreaFeatureRepository(db).forMeasurement(m.state.value.draft.recordUuid).single())
            assertEquals(1,PriceNameUniquenessProof.scalar(raw,"SELECT count(*) FROM ceiling_operations"))
        }
    }
}
