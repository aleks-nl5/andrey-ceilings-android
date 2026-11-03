package com.andreyceilings.shared.db

import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.*

/** Isolated Simulator SQLCipher contract; this is not a runnable iOS host. */
class PriceCatalogIosCipherTest {
    @Test fun encryptedV4MigrationAndDeletedCatalogReopen(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "price-p1-ios-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 59 } }, recovery = SqlCipherRecovery.Fail)
        try {
            val old = factory.create(PriceCatalogMigrationProof.legacySchema, config)
            val before = try {
                val rows = PriceCatalogMigrationProof.untouchedRows(old)
                assertFails { PriceCatalogSchema.migrate(PriceCatalogMigrationProof.failing(old), 4, 5) }
                PriceCatalogMigrationProof.assertOldPrice(old)
                assertEquals(rows, PriceCatalogMigrationProof.untouchedRows(old))
                rows
            } finally { old.close() }
            val migrated = factory.create(PriceCatalogSchema, config)
            try {
                val db = PrototypeDatabase(migrated)
                PriceCatalogMigrationProof.assertReplaced(db)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(migrated))
                PriceCatalogPersistenceProof.groupsAndParameters(db)
                PriceCatalogPersistenceProof.removeEverything(db)
            } finally { migrated.close() }
            val reopened = factory.create(PriceCatalogSchema, config)
            try {
                assertEquals(0L, PriceRepository(PrototypeDatabase(reopened)).count())
                assertTrue(PriceGroupRepository(PrototypeDatabase(reopened)).all().isEmpty())
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(reopened))
            } finally { reopened.close() }
        } finally { factory.deleteDatabase(name) }
    }
}
