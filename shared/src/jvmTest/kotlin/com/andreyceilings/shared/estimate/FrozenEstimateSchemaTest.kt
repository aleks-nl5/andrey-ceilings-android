package com.andreyceilings.shared.estimate

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlin.test.*

class FrozenEstimateSchemaTest {
    @Test fun exactColumnsRejectFractionalSqlStorage() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
            for (values in listOf("1.5,0,0,1.5", "1,0.5,0,1", "1,0,0.5,0.5")) {
                assertFails { driver.execute(null, "INSERT INTO estimate_v1_frozen VALUES('bad','$values',$values)", 0) }
            }
            driver.execute(null, "INSERT INTO estimate_v1_frozen VALUES('valid','{}',1,0,0,1)", 0)
            assertFails { driver.execute(null, "INSERT INTO estimate_workflow VALUES('bad','DRAFT',1.5,1)", 0) }
            assertFails { driver.execute(null, "INSERT INTO estimate_workflow VALUES('bad','DRAFT',1,1.5)", 0) }
        }
    }

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
