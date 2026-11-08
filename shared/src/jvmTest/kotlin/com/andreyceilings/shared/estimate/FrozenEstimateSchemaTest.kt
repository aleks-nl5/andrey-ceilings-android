package com.andreyceilings.shared.estimate

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlin.test.*

class FrozenEstimateSchemaTest {
    @Test fun wrongPublicNumberIndexAndMissingReceiptConstraintsAreRejected() {
        for (mutation in listOf("index", "receipt", "status")) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                PriceCatalogSchema.create(driver)
                if (mutation == "index") {
                    driver.execute(null, "DROP INDEX index_estimate_series_public_number", 0)
                    driver.execute(null, "CREATE UNIQUE INDEX index_estimate_series_public_number ON estimate_series(seriesUuid)", 0)
                } else {
                    val table = if (mutation == "receipt") "estimate_save_receipts" else "estimate_workflow"
                    val original = driver.executeQuery(null, "SELECT sql FROM sqlite_master WHERE name='$table'", { c -> c.next(); QueryResult.Value(c.getString(0)!!) }, 0).value
                    driver.execute(null, "DROP TABLE $table", 0)
                    val changed = if (mutation == "receipt") original.replace(" UNIQUE", "")
                        else original.replace("CHECK (status IN ('DRAFT','SENT','APPROVED','REJECTED'))", "")
                    assertNotEquals(original, changed)
                    driver.execute(null, changed, 0)
                }
                val before = PriceNameMigrationProof.schema(driver)
                assertFails { PriceCatalogSchema.verifyStructure(driver) }
                assertEquals(before, PriceNameMigrationProof.schema(driver))
            }
        }
    }
}
