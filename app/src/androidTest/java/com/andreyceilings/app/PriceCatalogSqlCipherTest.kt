package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class PriceCatalogSqlCipherTest {
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
            }
        } finally { context.deleteDatabase(name); key.fill(0) }
    }
}
