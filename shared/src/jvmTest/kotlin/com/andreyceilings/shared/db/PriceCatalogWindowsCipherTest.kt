package com.andreyceilings.shared.db

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PriceCatalogWindowsCipherTest {
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
                assertFails { PriceCatalogSchema.migrate(PriceCatalogMigrationProof.failing(driver), 4, 5) }
                PriceCatalogMigrationProof.assertOldPrice(driver)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            }
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                openDesktopPriceCatalog(driver, true)
                val db = PrototypeDatabase(driver)
                PriceCatalogMigrationProof.assertReplaced(db)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
                PriceCatalogPersistenceProof.groupsAndParameters(db)
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
