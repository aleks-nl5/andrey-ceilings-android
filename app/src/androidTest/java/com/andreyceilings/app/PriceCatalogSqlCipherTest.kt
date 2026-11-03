package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.PortableBackupEnvelope
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class PriceCatalogSqlCipherTest {
    @Test fun encryptedV5BackupPreflightBackfillsOrReportsCollisionWithoutChangingSource(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32) { 53 }
        val password = "price-v5-backup-test".toCharArray()
        try {
            for (conflict in listOf(null, CatalogNameKind.GROUP, CatalogNameKind.ITEM)) {
                val name = "price-v5-backup-${conflict?.name ?: "valid"}-test.db"
                context.deleteDatabase(name)
                try {
                    AndroidSqliteDriver(PriceNameMigrationProof.v5Schema, context, name,
                        factory = SupportOpenHelperFactory(key.copyOf(), null, true)).use { driver ->
                        PriceNameMigrationProof.populate(driver, conflict)
                    }
                    val source = context.getDatabasePath(name)
                    val before = source.readBytes()
                    val payload = PortableBackupPayload.encode(before, key)
                    val envelope = try { PortableBackupEnvelope.seal(payload, password) } finally { payload.fill(0) }
                    try {
                        if (conflict == null) {
                            PortableBackupManager.verifyEncryptedSnapshot(context, source, key)
                            val validated = PortableBackupManager.validate(context, envelope, password)
                            try { assertContentEquals(before, validated.database) }
                            finally { validated.database.fill(0); validated.passphrase.fill(0) }
                        } else {
                            val failure = assertFailsWith<CatalogNameMigrationConflict> {
                                PortableBackupManager.validate(context, envelope, password)
                            }
                            assertEquals(conflict, failure.kind)
                            assertTrue(failure.recordUuids.isNotEmpty())
                        }
                        assertContentEquals(before, source.readBytes(), "Backup preflight must not modify the v5 source")
                    } finally { envelope.fill(0); before.fill(0) }
                } finally { context.deleteDatabase(name) }
            }
        } finally { key.fill(0); password.fill('\u0000') }
    }

    @Test fun encryptedWalV5UnicodeUpgradeCollisionsAndInterruptedBackfill(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32) { 55 }
        try {
            for (scenario in listOf("ok", "group", "item", "empty", "interrupted")) {
                val name = "price-names-$scenario-test.db"
                context.deleteDatabase(name)
                try {
                    PriceNameMigrationProof.lifecycle(scenario, PriceCatalogSchema) { schema ->
                        if (schema === PriceCatalogSchema) {
                            createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
                        } else {
                            val driver = AndroidSqliteDriver(schema, context, name,
                                factory = SupportOpenHelperFactory(key.copyOf(), null, true))
                            try {
                                driver.executeQuery(null, "SELECT count(*) FROM sqlite_master", { cursor ->
                                    cursor.next(); QueryResult.Value(cursor.getLong(0))
                                }, 0).value
                                driver
                            } catch (failure: Throwable) { driver.close(); throw failure }
                        }.also { driver ->
                            assertEquals("wal", driver.executeQuery(null, "PRAGMA journal_mode", { cursor ->
                                cursor.next(); QueryResult.Value(cursor.getString(0))
                            }, 0).value)
                        }
                    }
                } finally { context.deleteDatabase(name) }
            }
        } finally { key.fill(0) }
    }

    @Test fun productionSchemaMigratesAtomicallyInWalAndNeverReseeds(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "price-p1-migration-test.db"
        val restoredName = "price-p1-restored-test.db"
        val key = ByteArray(32) { 61 }
        context.deleteDatabase(restoredName)
        context.deleteDatabase(name)
        System.loadLibrary("sqlcipher")
        fun openWith(schema: SqlSchema<QueryResult.Value<Unit>>) = AndroidSqliteDriver(
            schema = schema, context = context, name = name,
            factory = SupportOpenHelperFactory(key.copyOf(), null, true)
        ).also { it.executeQuery(null, "SELECT count(*) FROM sqlite_master", { cursor ->
            cursor.next(); QueryResult.Value(cursor.getLong(0))
        }, 0).value }
        val failingSchema = object : SqlSchema<QueryResult.Value<Unit>> by PriceCatalogSchema {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
                vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
                PriceCatalogSchema.migrate(PriceCatalogMigrationProof.failing(driver), oldVersion, newVersion, *callbacks)
        }
        try {
            val before = openWith(PriceCatalogMigrationProof.legacySchema).use {
                PriceCatalogMigrationProof.populateLegacy(it)
                PriceCatalogMigrationProof.untouchedRows(it)
            }
            assertFails { openWith(failingSchema).close() }
            openWith(PriceCatalogMigrationProof.legacySchema).use {
                PriceCatalogMigrationProof.assertOldPrice(it)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(it))
            }
            fun openProduction() = createEncryptedPrototypeDriver(context, key, name,
                enableWalForTesting = true, initializePriceCatalog = true)
            openProduction().use { driver ->
                assertEquals("wal", driver.executeQuery(null, "PRAGMA journal_mode", { cursor ->
                    cursor.next(); QueryResult.Value(cursor.getString(0))
                }, 0).value)
                val db = PrototypeDatabase(driver)
                PriceCatalogMigrationProof.assertReplaced(db)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
                PriceCatalogPersistenceProof.groupsAndParameters(db)
                PriceCatalogPersistenceProof.removeEverything(db)
                val destination = context.getDatabasePath(restoredName).absolutePath.replace("'", "''")
                driver.execute(null, "VACUUM INTO '$destination'", 0)
            }
            createEncryptedPrototypeDriver(context, key, restoredName, enableWalForTesting = true,
                initializePriceCatalog = true).use { driver ->
                com.andreyceilings.shared.prices.StarterPriceCatalog.initialize(PrototypeDatabase(driver))
                assertEquals(1L, PrototypeDatabase(driver).priceGroupsQueries.selectSeedVersion().executeAsOne())
                assertEquals(0L, PriceRepository(PrototypeDatabase(driver)).count())
                assertTrue(PriceGroupRepository(PrototypeDatabase(driver)).all().isEmpty())
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            }
            openProduction().use { driver ->
                val db = PrototypeDatabase(driver)
                assertEquals(0L, PriceRepository(db).count())
                assertTrue(PriceGroupRepository(db).all().isEmpty())
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            }
        } finally { context.deleteDatabase(name); context.deleteDatabase(restoredName); key.fill(0) }
    }

    @Test fun freshProductionCatalogHasExactStarterData(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "price-p1-fresh-test.db"
        val key = ByteArray(32) { 62 }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true,
                initializePriceCatalog = true).use {
                PriceCatalogPersistenceProof.starter(PrototypeDatabase(it))
                PriceNameUniquenessProof.crud(it)
            }
        } finally { context.deleteDatabase(name); key.fill(0) }
    }
}
