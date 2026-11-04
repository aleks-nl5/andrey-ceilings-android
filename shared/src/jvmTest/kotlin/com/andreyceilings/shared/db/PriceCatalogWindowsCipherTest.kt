package com.andreyceilings.shared.db

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PriceCatalogWindowsCipherTest {
    @Test fun encryptedMeasurementElementsV7MigrationRacesAndSharedContracts() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val key = ByteArray(32) { 64 }
        try {
            MeasurementElementFileProof.run { SqlCipherWindowsDriverFactory.open(it, key) }
            val file = Files.createTempFile("elements-fresh-cipher-", ".db").toFile()
            try {
                SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                    openDesktopPriceCatalog(driver, false)
                    MeasurementElementsPresentationProof.run(PrototypeDatabase(driver))
                        MeasurementElementProof.run(driver)
                    MeasurementElementProof.atomicParentDelete(driver)
                    MeasurementElementMigrationProof.sqlGuards(driver)
                }
            } finally { file.delete() }
        } finally { key.fill(0) }
    }

    @Test fun encryptedV3UpgradeAndV6Reopen() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val file = Files.createTempFile("price-v3-cipher-", ".db").toFile()
        val key = ByteArray(32) { 54 }
        try {
            val before: List<List<String?>>
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                PriceCatalogMigrationProof.legacySchema.create(driver)
                PriceCatalogMigrationProof.populateLegacy(driver)
                // v3 never tracked payment; unchanged 3.sqm adds 'unknown'
                // for this active legacy project. Build the expected v4+ row.
                driver.execute(null, "UPDATE projects SET paymentStatus='unknown'", 0)
                before = PriceCatalogMigrationProof.untouchedRows(driver)
                driver.execute(null, "DROP INDEX index_projects_paymentStatus", 0)
                driver.execute(null, "ALTER TABLE projects DROP COLUMN paymentStatus", 0)
                driver.execute(null, "PRAGMA user_version=3", 0)
            }
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                openDesktopPriceCatalog(driver, true)
                PriceCatalogMigrationProof.assertReplaced(PrototypeDatabase(driver))
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
                PriceNameUniquenessProof.assertKeys(driver)
            }
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                val schema = PriceNameMigrationProof.schema(driver)
                openDesktopPriceCatalog(driver, true)
                assertEquals(schema, PriceNameMigrationProof.schema(driver))
                assertEquals(PriceCatalogSchema.version, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
            }
        } finally { key.fill(0); file.delete() }
    }

    @Test fun encryptedV5NamesRollbackAndConcurrentConnections() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 57 }
        try {
            PriceNameFileProof.run { SqlCipherWindowsDriverFactory.open(it, key) }
            val file = Files.createTempFile("price-names-crud-", ".db").toFile()
            try {
                SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                    PriceCatalogSchema.create(driver)
                    PriceNameUniquenessProof.crud(driver)
                }
            } finally { file.delete() }
        } finally { key.fill(0) }
    }

    @Test fun encryptedMigrationRollbackCrudAndReopen() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val file = Files.createTempFile("price-p1-cipher-", ".db").toFile()
        val key = ByteArray(32) { 58 }
        try {
            var before = emptyList<List<String?>>()
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                PriceCatalogMigrationProof.legacySchema.create(driver)
                PriceCatalogMigrationProof.populateLegacy(driver)
                before = PriceCatalogMigrationProof.untouchedRows(driver)
                assertFails { PriceCatalogSchema.migrate(PriceCatalogMigrationProof.failing(driver), 4, 6) }
                PriceCatalogMigrationProof.assertOldPrice(driver)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            }
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                openDesktopPriceCatalog(driver, true)
                val db = PrototypeDatabase(driver)
                PriceCatalogMigrationProof.assertReplaced(db)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
                PriceCatalogPersistenceProof.groupsAndParameters(db)
                PriceCatalogP2Proof.run(db)
                PriceCatalogPersistenceProof.removeEverything(db)
            }
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                openDesktopPriceCatalog(driver, true)
                assertEquals(0L, PriceRepository(PrototypeDatabase(driver)).count())
                assertTrue(PriceGroupRepository(PrototypeDatabase(driver)).all().isEmpty())
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            }
        } finally { key.fill(0); file.delete() }
    }
}
