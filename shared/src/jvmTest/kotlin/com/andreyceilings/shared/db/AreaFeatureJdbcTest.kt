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
