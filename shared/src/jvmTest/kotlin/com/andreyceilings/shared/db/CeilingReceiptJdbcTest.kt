package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class CeilingReceiptJdbcTest {
    @Test fun replaceAndFakeReceiptsFailWithForeignKeysAndRecursiveTriggersOff() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->PriceCatalogSchema.create(d);CeilingReceiptMigrationProof.populate(d);CeilingReceiptMigrationProof.rejectReplace(d)}
    }
    @Test fun physicalV13RollbackFullFingerprintAndReopen()=runBlocking {
        val file=Files.createTempFile("receipt-v13-",".db").toFile()
        try {CeilingReceiptFileProof.run(file) {JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}")}}
        finally {file.delete()}
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {fresh ->
            PriceCatalogSchema.create(fresh)
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {m ->
                CeilingReceiptMigrationProof.v13Schema.create(m);PriceCatalogSchema.migrate(m,13,PriceCatalogSchema.version)
                assertEquals(AreaFeatureMigrationProof.fingerprint(fresh),AreaFeatureMigrationProof.fingerprint(m))
            }
        }
    }
    @Test fun frozenDistributedV13RetainsAllAreaDataAndRejectsHybrid() {
        val file=Files.createTempFile("frozen-v13-",".db").toFile()
        try {
            java.io.File("src/commonMain/sqldelight/13.db").copyTo(file,overwrite=true)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {d ->
                PriceCatalogSchema.verifyStructure(d,13)
                assertTrue(PriceNameUniquenessProof.scalar(d,"SELECT count(*) FROM area_features WHERE deletedAt IS NULL")>0)
                assertTrue(PriceNameUniquenessProof.scalar(d,"SELECT count(*) FROM area_features WHERE deletedAt IS NOT NULL")>0)
                val before=CeilingReceiptMigrationProof.snapshot(d);openDesktopPriceCatalog(d,true)
                assertEquals(before,CeilingReceiptMigrationProof.snapshot(d));PriceCatalogSchema.verifyStructure(d)
                CeilingReceiptMigrationProof.rejectReplace(d)
            }
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
                CeilingReceiptMigrationProof.v13Schema.create(d)
                d.execute(null,"CREATE TRIGGER ceiling_result_insert_guard BEFORE INSERT ON ceiling_operation_results BEGIN SELECT 1; END",0)
                val before=PriceNameMigrationProof.schema(d)
                assertFails {PriceCatalogSchema.migrate(d,13,PriceCatalogSchema.version)}
                assertEquals(before,PriceNameMigrationProof.schema(d))
            }
        } finally {file.delete()}
    }
    @Test fun encryptedWindowsPhysicalV13Lifecycle()=runBlocking {
        if(!System.getProperty("os.name").startsWith("Windows",true))return@runBlocking
        val dir=Files.createTempDirectory("receipt-v14-cipher-").toFile();val key=ByteArray(32){91}
        try {CeilingReceiptFileProof.run(dir.resolve("receipt.db")) {SqlCipherWindowsDriverFactory.open(it,key)}}
        finally {key.fill(0);dir.deleteRecursively()}
    }
}
private object CeilingReceiptFileProof {
    suspend fun run(file:java.io.File,open:(java.io.File)->SqlDriver) {
        CeilingReceiptMigrationProof.lifecycle(PriceCatalogSchema) {schema ->
            val d=open(file)
            try {
                if(schema===PriceCatalogSchema)openDesktopPriceCatalog(d,true)
                else PrototypeDatabase(d).transaction {
                    val v=PriceNameUniquenessProof.scalar(d,"PRAGMA user_version")
                    if(v==0L)schema.create(d) else if(v<schema.version)schema.migrate(d,v,schema.version)
                    d.execute(null,"PRAGMA user_version=${schema.version}",0)
                };d
            } catch(t:Throwable){d.close();throw t}
        }
    }
}
