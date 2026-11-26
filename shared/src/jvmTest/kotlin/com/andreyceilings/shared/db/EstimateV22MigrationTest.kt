package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test

class EstimateV22MigrationTest {
    @Test fun populatedUpgradeRollsBackAndClearsOnlyPreV22Estimates() = runBlocking {
        exercise { file -> JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}") }
    }

    @Test fun windowsEncryptedUpgradeAndReopen() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val key = ByteArray(32) { 22 }
        try {
            exercise { file -> SqlCipherWindowsDriverFactory.open(file, key) }
        } finally {
            key.fill(0)
        }
    }

    private suspend fun exercise(driverFactory: (java.io.File) -> SqlDriver) {
        val directory = Files.createTempDirectory("estimate-v22").toFile()
        val file = directory.resolve("business.db")
        try {
            EstimateV22MigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                driverFactory(file).also { driver ->
                    try {
                        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                        PrototypeDatabase(driver).transaction {
                            val old = PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version")
                            if (old == 0L) schema.create(driver)
                            else if (old < schema.version) schema.migrate(driver, old, schema.version)
                            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                        }
                    } catch (failure: Throwable) {
                        driver.close()
                        throw failure
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
