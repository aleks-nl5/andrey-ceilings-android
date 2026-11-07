package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LinePlacementJdbcTest {
    @Test fun physicalV11UpgradeRollbackAndReopen()=runBlocking {LinePlacementFileProof.run {JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}")}}
    @Test fun committedPhysicalV11FixturePreservesHistoryAnchorsAndReceipts() {
        val file=java.nio.file.Files.createTempFile("frozen-v11-",".db").toFile()
        try {
            java.io.File("src/commonMain/sqldelight/11.db").copyTo(file,overwrite=true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {driver ->
                PriceCatalogSchema.verifyStructure(driver,11)
                val before=LegacyWallSeedProof.snapshot(driver,true)
                assertTrue(PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM point_placements")>0)
                assertTrue(PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations")>0)
                openDesktopPriceCatalog(driver,true)
                assertEquals(before,LegacyWallSeedProof.snapshot(driver,true))
                PriceCatalogSchema.verifyStructure(driver)
            }
        } finally {file.delete()}
    }
    @Test fun concurrentSameUuidIntentCommitsExactlyOnce()=runBlocking {LinePlacementFileProof.concurrent {JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}")}}
    @Test fun atomicLineContract() {JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {PriceCatalogSchema.create(it);LinePlacementProof.run(it)}}
}
object LinePlacementFileProof {
    fun concurrent(open:(java.io.File)->SqlDriver) {
        val file=java.nio.file.Files.createTempFile("line-concurrent-",".db").toFile()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val (document,line)=open(file).use {driver ->
                openDesktopPriceCatalog(driver,false)
                val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
                val prices=PriceRepository(db)
                val item=prices.find(prices.createInGroup("line.race","Line",PriceGroupRepository(db).all().first().uuid,1.0,5,"м.п.",com.andreyceilings.shared.prices.QuantityMode.MANUAL,com.andreyceilings.shared.prices.PlacementKind.LINE))!!
                val element=MeasurementElementRepository(db).createCatalogSelection(f.element.measurementUuid,item.uuid,com.andreyceilings.shared.measurement.ElementQuantity.Exact(4500),6, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
                val doc=com.andreyceilings.shared.measurement.MeasurementEditStore(db){100}.load(f.project,f.id)
                doc to com.andreyceilings.shared.measurement.LinePlacement("concurrent-line",element.uuid,com.andreyceilings.shared.measurement.PointPositionMm(500,600),com.andreyceilings.shared.measurement.PointPositionMm(3000,600),doc.updatedAt,0,0)
            }
            val ready=java.util.concurrent.CountDownLatch(2);val start=java.util.concurrent.CountDownLatch(1)
            val futures=(0..1).map {pool.submit<Result<Unit>> {
                open(file).use {driver ->
                    driver.execute(null,"PRAGMA busy_timeout=15000",0)
                    ready.countDown();check(start.await(30,java.util.concurrent.TimeUnit.SECONDS))
                    runCatching {CeilingEditRepository(PrototypeDatabase(driver)).save(document,document.draft,emptyMap(),"same-line-operation","same-line-walls",100,
                        mapOf(line.uuid to com.andreyceilings.shared.measurement.LinePlacementEdit(line)))}
                }
            }}
            assertTrue(ready.await(30,java.util.concurrent.TimeUnit.SECONDS));start.countDown()
            futures.forEach {it.get(60,java.util.concurrent.TimeUnit.SECONDS).getOrThrow()}
            open(file).use {driver ->
                assertEquals(1,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM line_placements WHERE deletedAt IS NULL"))
                assertEquals(1,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations"))
                assertEquals(2500,MeasurementElementRepository(PrototypeDatabase(driver)).find(line.elementUuid)!!.placedQuantityMilli)
            }
        } finally {pool.shutdownNow();file.delete()}
    }
    suspend fun run(open:(java.io.File)->SqlDriver) {
        val file=java.nio.file.Files.createTempFile("lines-v11-",".db").toFile()
        try {
            LinePlacementMigrationProof.lifecycle(PriceCatalogSchema) {schema ->
                val driver=open(file)
                try {
                    if(schema===PriceCatalogSchema)openDesktopPriceCatalog(driver,true)
                    else PrototypeDatabase(driver).transaction {
                        val version=PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version")
                        if(version==0L)schema.create(driver) else if(version<schema.version)schema.migrate(driver,version,schema.version)
                        driver.execute(null,"PRAGMA user_version=${schema.version}",0)
                    }
                    driver
                } catch(t:Throwable){driver.close();throw t}
            }
        } finally {file.delete()}
    }
}
