package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
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
        var phase = "create v4 fixture"
        try {
            val old = factory.create(PriceCatalogMigrationProof.legacySchema, config)
            val before = try {
                phase = "populate v4 fixture"
                PriceCatalogMigrationProof.populateLegacy(old)
                PriceCatalogMigrationProof.untouchedRows(old)
            } finally { old.close() }
            phase = "rollback inside SQLiter upgrade callback"
            val failingSchema = object : SqlSchema<QueryResult.Value<Unit>> by IosPriceCatalogSchema {
                override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
                    vararg callbacks: AfterVersion) = PriceCatalogSchema.migrateInTransaction(
                        PriceCatalogMigrationProof.failing(driver), oldVersion, newVersion, *callbacks)
            }
            assertFails { factory.create(failingSchema, config).close() }
            val afterFailure = factory.create(PriceCatalogMigrationProof.legacySchema, config)
            try {
                PriceCatalogMigrationProof.assertOldPrice(afterFailure)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(afterFailure))
            } finally { afterFailure.close() }
            phase = "successful upgrade callback"
            val migrated = factory.create(IosPriceCatalogSchema, config)
            try {
                val db = PrototypeDatabase(migrated)
                PriceCatalogMigrationProof.assertReplaced(db)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(migrated))
                phase = "group/item CRUD"
                PriceCatalogPersistenceProof.groupsAndParameters(db)
                PriceCatalogPersistenceProof.removeEverything(db)
            } finally { migrated.close() }
            phase = "reopen deleted catalog"
            val reopened = factory.create(IosPriceCatalogSchema, config)
            try {
                assertEquals(0L, PriceRepository(PrototypeDatabase(reopened)).count())
                assertTrue(PriceGroupRepository(PrototypeDatabase(reopened)).all().isEmpty())
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(reopened))
            } finally { reopened.close() }
        } catch (failure: Throwable) {
            throw AssertionError("P1 iOS [$phase]: ${failure.message}", failure)
        } finally { factory.deleteDatabase(name) }
    }

    @Test fun freshCatalogUsesTheSameSharedSeed(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "price-p1-ios-fresh-${Random.nextLong()}.db"
        try {
            val driver = factory.create(IosPriceCatalogSchema, SqlCipherConfig(name = name,
                key = SqlCipherKey { ByteArray(32) { 60 } }, recovery = SqlCipherRecovery.Fail))
            try { PriceCatalogPersistenceProof.starter(PrototypeDatabase(driver)) }
            finally { driver.close() }
        } finally { factory.deleteDatabase(name) }
    }
}
