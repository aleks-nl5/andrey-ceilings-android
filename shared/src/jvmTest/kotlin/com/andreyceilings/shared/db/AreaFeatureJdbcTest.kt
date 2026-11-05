package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class AreaFeatureJdbcTest {
    @Test fun fullAtomicAreaContract() {JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {PriceCatalogSchema.create(it);AreaFeatureProof.run(it)}}
    @Test fun v12PhysicalMigrationRollbackReopenAndFullFingerprint()=runBlocking {
        val file=Files.createTempFile("area-v12-",".db").toFile()
        try {
            AreaFeatureMigrationProof.lifecycle(PriceCatalogSchema) {schema ->
                val d=JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                try {
                    if(schema===PriceCatalogSchema)openDesktopPriceCatalog(d,true)
                    else PrototypeDatabase(d).transaction {
                        val v=PriceNameUniquenessProof.scalar(d,"PRAGMA user_version")
                        if(v==0L)schema.create(d) else if(v<schema.version)schema.migrate(d,v,schema.version)
                        d.execute(null,"PRAGMA user_version=${schema.version}",0)
                    }
                    d
                } catch(t:Throwable){d.close();throw t}
            }
        } finally {file.delete()}
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {fresh ->
            PriceCatalogSchema.create(fresh)
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {migrated ->
                AreaFeatureMigrationProof.v12Schema.create(migrated)
                PriceCatalogSchema.migrate(migrated,12,13)
                assertEquals(AreaFeatureMigrationProof.fingerprint(fresh),AreaFeatureMigrationProof.fingerprint(migrated))
            }
        }
    }
    @Test fun frozenV12PreservesEveryRowAndRejectsHybridBeforeDdl() {
        val file=Files.createTempFile("frozen-v12-",".db").toFile()
        try {
            java.io.File("src/commonMain/sqldelight/12.db").copyTo(file,overwrite=true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {d ->
                PriceCatalogSchema.verifyStructure(d,12)
                assertTrue(PriceNameUniquenessProof.scalar(d,"SELECT count(*) FROM line_placements WHERE deletedAt IS NULL")>0)
                assertTrue(PriceNameUniquenessProof.scalar(d,"SELECT count(*) FROM line_placements WHERE deletedAt IS NOT NULL")>0)
                val before=AreaFeatureMigrationProof.snapshot(d)
                openDesktopPriceCatalog(d,true)
                assertEquals(before,AreaFeatureMigrationProof.snapshot(d))
                PriceCatalogSchema.verifyStructure(d)
            }
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
                AreaFeatureMigrationProof.v12Schema.create(d)
                d.execute(null,"CREATE TABLE area_features(uuid TEXT)",0)
                val before=PriceNameMigrationProof.schema(d)
                assertFails {PriceCatalogSchema.migrate(d,12,13)}
                assertEquals(before,PriceNameMigrationProof.schema(d))
            }
        } finally {file.delete()}
    }
    @Test fun fingerprintPreservesCaseOfSqlEnumLiterals() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            PriceCatalogSchema.create(d)
            val sql=d.executeQuery(null,"SELECT sql FROM sqlite_master WHERE name='area_element_guard'",{c ->c.next();QueryResult.Value(c.getString(0)!!)},0).value
            d.execute(null,"DROP TRIGGER area_element_guard",0)
            d.execute(null,sql.replace("new.placementKind='area'","new.placementKind='AREA'"),0)
            assertFailsWith<SchemaFingerprintMismatch> {PriceCatalogSchema.verifyStructure(d)}
            val before=PriceNameMigrationProof.schema(d)
            assertFails {openDesktopPriceCatalog(d,true)}
            assertEquals(before,PriceNameMigrationProof.schema(d))
        }
    }
    @Test fun productionV12MixedHistoryIsPopulatedBeforeGuardedLineRows() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            AreaFeatureMigrationProof.v12Schema.create(d)
            val legacy=LegacyWallSeedProof.populate(d,false)
            LegacyWallSeedProof.decorateV10(d,legacy)
            AreaFeatureMigrationProof.populate(d)
            val before=AreaFeatureMigrationProof.snapshot(d)
            PriceCatalogSchema.migrate(d,12,13)
            assertEquals(before,AreaFeatureMigrationProof.snapshot(d))
            assertEquals(2,PriceNameUniquenessProof.scalar(d,"SELECT count(*) FROM line_placements"))
            PriceCatalogSchema.verifyStructure(d)
        }
    }
    @Test fun concurrentSameUuidReconcilesAndDifferentPayloadFailsClosed() {
        for(opposite in listOf(false,true)) {
            val file=Files.createTempFile("area-race-",".db").toFile()
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            fun open()=JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                val doc=open().use {d ->openDesktopPriceCatalog(d,false);val db=PrototypeDatabase(d);val f=PointPlacementProof.fixture(db);com.andreyceilings.shared.measurement.MeasurementEditStore(db){100}.load(f.project,f.id)}
                val ready=java.util.concurrent.CountDownLatch(2);val start=java.util.concurrent.CountDownLatch(1)
                val futures=(0..1).map {i ->pool.submit<Result<Unit>> {
                    open().use {d ->
                        d.execute(null,"PRAGMA busy_timeout=15000",0);ready.countDown();check(start.await(30,java.util.concurrent.TimeUnit.SECONDS))
                        runCatching {
                            val shape=com.andreyceilings.shared.measurement.AreaShape.rectangle(com.andreyceilings.shared.measurement.PointPositionMm(500,500),com.andreyceilings.shared.measurement.PointPositionMm(if(opposite && i==1)2000 else 1500,1500))
                            val area=com.andreyceilings.shared.measurement.AreaFeature("race-area",doc.draft.recordUuid,shape,doc.updatedAt,0,0)
                            CeilingEditRepository(PrototypeDatabase(d)).save(doc,doc.draft,emptyMap(),"race-operation","race-walls",100,areaEdits=mapOf(area.uuid to com.andreyceilings.shared.measurement.AreaFeatureEdit(area)))
                        }
                    }
                }}
                assertTrue(ready.await(30,java.util.concurrent.TimeUnit.SECONDS));start.countDown()
                val results=futures.map {it.get(60,java.util.concurrent.TimeUnit.SECONDS)}
                assertEquals(if(opposite)1 else 2,results.count {it.isSuccess})
                open().use {d ->assertEquals(1,PriceNameUniquenessProof.scalar(d,"SELECT count(*) FROM area_features"));assertEquals(1,PriceNameUniquenessProof.scalar(d,"SELECT count(*) FROM ceiling_operations"))}
            } finally {pool.shutdownNow();file.delete()}
        }
    }
    @Test fun encryptedWindowsLifecycle()=runBlocking {
        if(!System.getProperty("os.name").startsWith("Windows",true))return@runBlocking
        val dir=Files.createTempDirectory("area-cipher-").toFile();val key=ByteArray(32){84}
        try {
            val file=dir.resolve("areas.db")
            AreaFeatureMigrationProof.lifecycle(PriceCatalogSchema) {schema ->
                val d=SqlCipherWindowsDriverFactory.open(file,key)
                try {
                    PrototypeDatabase(d).transaction {
                        val v=PriceNameUniquenessProof.scalar(d,"PRAGMA user_version")
                        if(v==0L)schema.create(d) else if(v<schema.version)schema.migrate(d,v,schema.version)
                        d.execute(null,"PRAGMA user_version=${schema.version}",0)
                    };d
                } catch(t:Throwable){d.close();throw t}
            }
            assertFalse(file.readBytes().take(16).toByteArray().contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            assertFails {SqlCipherWindowsDriverFactory.open(file,ByteArray(32){85}).use {AreaFeatureMigrationProof.snapshot(it)}}
        } finally {key.fill(0);dir.deleteRecursively()}
    }
}
