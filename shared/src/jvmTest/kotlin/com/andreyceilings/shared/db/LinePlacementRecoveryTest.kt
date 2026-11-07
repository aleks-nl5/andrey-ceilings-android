package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LinePlacementRecoveryTest {
    @Test fun committedLineResponseLossFreezesDraftAndRetryReadsReceiptOnly()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {raw ->
            PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=PointPlacementProof.fixture(db)
            val prices=PriceRepository(db)
            val price=prices.find(prices.createInGroup("line.recovery","Line",PriceGroupRepository(db).all().first().uuid,1.0,5,"м.п.",QuantityMode.MANUAL,PlacementKind.LINE))!!
            val element=MeasurementElementRepository(db).createCatalogSelection(f.element.measurementUuid,price.uuid,ElementQuantity.Exact(4500),6, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
            val fail=java.util.concurrent.atomic.AtomicBoolean(false)
            val driver=object:SqlDriver by raw {
                override fun notifyListeners(vararg queryKeys:String) {
                    raw.notifyListeners(*queryKeys)
                    if("ceiling_operations" in queryKeys && fail.compareAndSet(true,false))throw IllegalArgumentException("lost response")
                }
            }
            val store=MeasurementEditStore(PrototypeDatabase(driver)){100}
            val model=MeasurementEditModel(store,store.load(f.project,f.id))
            model.beginLine(element.uuid);model.lineTap(500,600);model.lineTap(3000,600);model.acceptLineCreation()
            val uuid=model.displayLines().single().uuid
            fail.set(true);model.save()
            assertTrue(model.state.value.uncertain)
            val committed=LinePlacementRepository(db).forMeasurement(element.measurementUuid).single()
            assertEquals(uuid,committed.uuid)
            model.selectLine(uuid);model.deleteLine()
            assertEquals(1,model.displayLines().size,"Uncertain payload must stay frozen")
            model.save()
            assertTrue(model.state.value.saved,model.state.value.error)
            assertEquals(committed,LinePlacementRepository(db).forMeasurement(element.measurementUuid).single())
            assertEquals(1,PriceNameUniquenessProof.scalar(raw,"SELECT count(*) FROM ceiling_operations"))
        }
    }
}
