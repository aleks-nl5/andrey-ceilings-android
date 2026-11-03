package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.prices.StarterPriceCatalog
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PriceCatalogP1Test {
    @Test fun defaultsOverridesCasAndSoftDelete() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            PriceCatalogPersistenceProof.starter(db)
            PriceCatalogPersistenceProof.groupsAndParameters(db)
            PriceCatalogPersistenceProof.removeEverything(db)
        }
    }

    @Test fun interruptedFreshSeedRollsBackStructureAndCanRetry() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            assertFails { PriceCatalogSchema.create(PriceCatalogMigrationProof.failing(driver)) }
            assertEquals(0L, driver.executeQuery(null,
                "SELECT count(*) FROM sqlite_master WHERE name = 'price_catalog_state'", { cursor ->
                    cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
                }, 0).value)
            PriceCatalogSchema.create(driver)
            PriceCatalogPersistenceProof.starter(PrototypeDatabase(driver))
        }
    }

    @Test fun migrationIsAtomicAndLeavesEveryOtherColumnUnchanged() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogMigrationProof.legacySchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val before = PriceCatalogMigrationProof.untouchedRows(driver)
            assertFails { PriceCatalogSchema.migrate(PriceCatalogMigrationProof.failing(driver), 4, 5) }
            PriceCatalogMigrationProof.assertOldPrice(driver)
            assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            PriceCatalogSchema.migrate(driver, 4, 5)
            PriceCatalogMigrationProof.assertReplaced(PrototypeDatabase(driver))
            assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
        }
    }

    @Test fun windowsProbeHandlesV3V4AndReopenWithoutReplacingEdits() {
        for (version in listOf(3L, 4L)) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                PriceCatalogMigrationProof.legacySchema.create(driver)
                if (version == 3L) {
                    driver.execute(null, "DROP INDEX index_projects_paymentStatus", 0)
                    driver.execute(null, "ALTER TABLE projects DROP COLUMN paymentStatus", 0)
                }
                openDesktopPriceCatalog(driver, true)
                val db = PrototypeDatabase(driver)
                PriceCatalogPersistenceProof.starter(db)
                PriceCatalogPersistenceProof.removeEverything(db)
                openDesktopPriceCatalog(driver, true)
                assertEquals(0, PriceRepository(db).all().size)
                assertEquals(0, PriceGroupRepository(db).all().size)
            }
        }
    }

    @Test fun concurrentBootstrapAndReopenNeverDuplicateOrRestoreDeletedCatalog() {
        val file = Files.createTempFile("price-p1-", ".db").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { PrototypeDatabase.Schema.create(it) }
            val tasks = (1..2).map {
                executor.submit {
                    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                        driver.execute(null, "PRAGMA busy_timeout = 10000", 0)
                        StarterPriceCatalog.initialize(PrototypeDatabase(driver))
                    }
                }
            }
            tasks.forEach { it.get() }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val db = PrototypeDatabase(driver)
                PriceCatalogPersistenceProof.starter(db)
                PriceCatalogPersistenceProof.removeEverything(db)
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                StarterPriceCatalog.initialize(PrototypeDatabase(driver))
                assertEquals(0L, PriceRepository(PrototypeDatabase(driver)).count())
                assertTrue(PriceGroupRepository(PrototypeDatabase(driver)).all().isEmpty())
            }
        } finally { executor.shutdownNow(); file.delete() }
    }
}
