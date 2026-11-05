package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test

class WallAnchorJdbcTest {
    @Test fun unsupportedV9DrawingsAndDeployedV10SurviveCorrectiveUpgrade()=kotlinx.coroutines.runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            WallAnchorMigrationProof.v9Schema.create(driver);LegacyWallSeedProof.populate(driver)
            PriceCatalogSchema.migrate(driver,9,10);verifyPointPlacementStructure(driver,10)
            PriceCatalogSchema.migrate(driver,10,PriceCatalogSchema.version);verifyPointPlacementStructure(driver)
        }
        LegacyWallSeedFileProof.run { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }
    @Test fun v9FilesMigrationRollbackReopenAndConcurrentIdempotentSave()=kotlinx.coroutines.runBlocking {
        WallAnchorFileProof.run { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun atomicMountingGeometryReviewCountsAndRetry() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {
            PriceCatalogSchema.create(it);it.execute(null,"PRAGMA foreign_keys=ON",0)
            WallAnchorProof.run(it)
        }
    }
}

object LegacyWallSeedFileProof {
    suspend fun run(open:(java.io.File)->app.cash.sqldelight.db.SqlDriver) {
        for(version in listOf(9L,10L)) {
            val file=java.nio.file.Files.createTempFile("legacy-walls-",".db").toFile()
            try {
                LegacyWallSeedProof.lifecycle(PriceCatalogSchema,version) { schema ->
                    val driver=open(file)
                    try {
                        if(schema===PriceCatalogSchema)openDesktopPriceCatalog(driver,true)
                        else PrototypeDatabase(driver).transaction {
                            val version=PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version")
                            if(version==0L) schema.create(driver) else if(version<schema.version) schema.migrate(driver,version,schema.version)
                            driver.execute(null,"PRAGMA user_version=${schema.version}",0)
                        }
                        driver
                    } catch(failure:Throwable){driver.close();throw failure}
                }
            } finally { file.delete() }
        }
    }
}

/** Physical v9 encrypted upgrade/rollback/reopen and separate-connection CAS are also run on Windows. */
object WallAnchorFileProof {
    suspend fun run(open:(java.io.File)->app.cash.sqldelight.db.SqlDriver) {
        for(populated in listOf(false,true)) {
            val file=java.nio.file.Files.createTempFile("wall-v9-",".db").toFile()
            try {
                WallAnchorMigrationProof.lifecycle(PriceCatalogSchema,populated) { schema ->
                    val driver=open(file)
                    try {
                        if(schema===PriceCatalogSchema) openDesktopPriceCatalog(driver,true)
                        else PrototypeDatabase(driver).transaction {
                            val version=PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version")
                            if(version==0L)schema.create(driver) else if(version<schema.version)schema.migrate(driver,version,schema.version)
                            driver.execute(null,"PRAGMA user_version=${schema.version}",0)
                        }
                        driver
                    } catch(error:Throwable) {driver.close();throw error}
                }
            } finally {file.delete()}
        }
        val file=java.nio.file.Files.createTempFile("wall-cas-",".db").toFile()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val document=open(file).use { driver ->
                openDesktopPriceCatalog(driver,false)
                val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
                PointPlacementRepository(db).addPoint(f.element.version,f.contourVersion,1000,1000,10)
                com.andreyceilings.shared.measurement.MeasurementEditStore(db){20}.load(f.project,f.id)
            }
            val point=document.placements.points.single()
            val anchor=com.andreyceilings.shared.measurement.WallAnchor(com.andreyceilings.shared.measurement.WallAnchor.Kind.TWO_WALLS,
                document.placements.walls[0].uuid,document.placements.walls[3].uuid,600,1500)
            val edits=mapOf(point.uuid to com.andreyceilings.shared.measurement.PointMountingEdit(anchor=anchor))
            val ready=java.util.concurrent.CountDownLatch(2);val start=java.util.concurrent.CountDownLatch(1)
            val futures=(0..1).map { pool.submit<Result<Unit>> {
                open(file).use { driver ->
                    driver.execute(null,"PRAGMA busy_timeout=15000",0)
                    ready.countDown();check(start.await(30,java.util.concurrent.TimeUnit.SECONDS))
                    runCatching { CeilingEditRepository(PrototypeDatabase(driver)).save(document,document.draft,edits,"same-whole-edit","same-walls",20) }
                }
            } }
            kotlin.test.assertTrue(ready.await(30,java.util.concurrent.TimeUnit.SECONDS));start.countDown()
            futures.forEach { it.get(60,java.util.concurrent.TimeUnit.SECONDS).getOrThrow() }
            open(file).use { driver ->
                openDesktopPriceCatalog(driver,true)
                val saved=PointPlacementRepository(PrototypeDatabase(driver)).find(point.uuid)!!
                kotlin.test.assertEquals(anchor,saved.mounting.anchor)
                kotlin.test.assertEquals(1L,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations WHERE uuid='same-whole-edit'"))
            }
        } finally {pool.shutdownNow();pool.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS);file.delete()}
    }
}
