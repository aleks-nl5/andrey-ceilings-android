package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlin.test.*

class FrozenRevisionTest {
    @Test fun v16MigrationRollbackReopenAndV1Preservation() = kotlinx.coroutines.runBlocking {
        val file = java.nio.file.Files.createTempDirectory("frozen-v17").resolve("quote.db").toFile()
        try {
            FrozenRevisionMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").also { d ->
                    try {
                        val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                        if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                        d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                    } catch (failure: Throwable) { d.close(); throw failure }
                }
            }
        } finally { file.parentFile.deleteRecursively() }
    }

    @Test fun exactRevisionsUseCurrentMeasurementWithAtomicCasAndRetry() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            FrozenRevisionProof.run(driver)
        }
    }
}
