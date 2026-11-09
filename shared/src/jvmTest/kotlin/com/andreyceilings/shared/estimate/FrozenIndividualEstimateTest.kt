package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlin.test.*

class FrozenIndividualEstimateTest {
    @Test fun windowsPhysicalV16ToV17RollbackAndReopen() = kotlinx.coroutines.runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val directory = java.nio.file.Files.createTempDirectory("cipher-v17").toFile()
        val file = directory.resolve("quote.db")
        val key = ByteArray(32) { 44 }
        try {
            FrozenRevisionMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                SqlCipherWindowsDriverFactory.open(file, key).also { d ->
                    try {
                        if (schema === PriceCatalogSchema) openDesktopPriceCatalog(d, file.length() > 0)
                        else PrototypeDatabase(d).transaction {
                            val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                            if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                            d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                        }
                    } catch (failure: Throwable) { d.close(); throw failure }
                }
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }
    @Test fun windowsPhysicalV15ToV16RollbackAndReopen() = kotlinx.coroutines.runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val directory = java.nio.file.Files.createTempDirectory("cipher-v16").toFile()
        val file = directory.resolve("quote.db")
        val key = ByteArray(32) { 44 }
        try {
            FrozenEstimateMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                SqlCipherWindowsDriverFactory.open(file, key).also { d ->
                    try {
                        if (schema === PriceCatalogSchema) openDesktopPriceCatalog(d, file.length() > 0)
                        else PrototypeDatabase(d).transaction {
                            val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                            if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                            d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                        }
                    } catch (failure: Throwable) { d.close(); throw failure }
                }
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }
    @Test fun migrationV15RollbackReopenAndSuffix() = kotlinx.coroutines.runBlocking {
        val file = java.nio.file.Files.createTempDirectory("frozen-v16").resolve("quote.db").toFile()
        try {
            FrozenEstimateMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").also { driver ->
                    val version = driver.executeQuery(null, "PRAGMA user_version", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value
                    try {
                        if (version == 0L) schema.create(driver)
                        else if (version < schema.version) schema.migrate(driver, version, schema.version)
                        driver.execute(null, "PRAGMA user_version=${schema.version}", 0)
                    } catch (failure: Throwable) { driver.close(); throw failure }
                }
            }
        } finally { file.parentFile.deleteRecursively() }
    }
    @Test fun atomicExactFrozenV1StatusRetryStalenessAndRollback() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {
            PriceCatalogSchema.create(it)
            it.execute(null, "PRAGMA foreign_keys=ON", 0)
            FrozenIndividualEstimateProof.run(it)
        }
    }
}
