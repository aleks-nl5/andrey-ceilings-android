package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.*
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class MeasurementElementSqlCipherTest {
    @Test fun encryptedWalV6MigrationRollbackRestartAndFreshContracts(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32) { 65 }
        try {
            for (scenario in listOf("migration", "fresh")) {
                val name = "elements-$scenario-test.db"
                context.deleteDatabase(name)
                try {
                    if (scenario == "migration") MeasurementElementMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                        if (schema === PriceCatalogSchema) createEncryptedPrototypeDriver(context, key, name,
                            enableWalForTesting = true, initializePriceCatalog = true)
                        else AndroidSqliteDriver(schema, context, name,
                            factory = SupportOpenHelperFactory(key.copyOf(), null, true)).also { driver ->
                            try { driver.executeQuery(null, "SELECT count(*) FROM sqlite_master", { cursor ->
                                cursor.next(); QueryResult.Value(cursor.getLong(0))
                            }, 0).value } catch (failure: Throwable) { driver.close(); throw failure }
                        }
                    } else createEncryptedPrototypeDriver(context, key, name,
                        enableWalForTesting = true, initializePriceCatalog = true).use { driver ->
                        assertEquals("wal", MeasurementElementMigrationProof.rows(driver, "PRAGMA journal_mode", 1).single().single())
                        MeasurementElementsPresentationProof.run(PrototypeDatabase(driver))
                        MeasurementElementProof.run(driver)
                        MeasurementElementProof.atomicParentDelete(driver)
                        MeasurementElementMigrationProof.sqlGuards(driver)
                    }
                } finally { context.deleteDatabase(name) }
            }
        } finally { key.fill(0) }
    }

    @Test fun productionRestorePreservesElementsAndUpgradesV6(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val originalKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val originalSnapshot = PortableBackupManager.createVerifiedSnapshot(context, CeilingsDatabase.get(context))
        val originalImage = originalSnapshot.readBytes()
        val key = ByteArray(32) { 67 }
        val name = "elements-production-restore-test.db"
        try {
            for (version in listOf(6, 7)) {
                context.deleteDatabase(name)
                val driver = if (version == 6) AndroidSqliteDriver(MeasurementElementMigrationProof.v6Schema,
                    context, name, factory = SupportOpenHelperFactory(key.copyOf(), null, true))
                else createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
                val (before, element) = driver.use {
                    MeasurementElementMigrationProof.populate(it)
                    val before = MeasurementElementMigrationProof.snapshot(it)
                    val db = PrototypeDatabase(it)
                    val element = if (version == 7) MeasurementElementRepository(db).createWish(
                        MeasurementRepository(db).all().single().uuid, "Restored wish", 400, uuid = "restored-wish") else null
                    before to element
                }
                val image = context.getDatabasePath(name).readBytes()
                try {
                    val restored = SqlCipherRestoreManager.restore(context, PortableBackupPayload.Contents(image, key))
                    assertEquals(7L, restored.queryLong("PRAGMA user_version"))
                    assertEquals(before, MeasurementElementMigrationProof.snapshot(restored.driver))
                    assertEquals(element, MeasurementElementRepository(restored.database).find("restored-wish"))
                    val storedKey = DatabaseKeyManager.getOrCreatePassphrase(context)
                    try { assertContentEquals(key, storedKey) } finally { storedKey.fill(0) }
                } finally { image.fill(0) }
            }
        } finally {
            try {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restore(context, PortableBackupPayload.Contents(originalImage, originalKey))
            } finally {
                originalSnapshot.delete(); context.deleteDatabase(name)
                originalImage.fill(0); originalKey.fill(0); key.fill(0)
            }
        }
    }

    @Test fun encryptedPortableSnapshotIncludesElementsAndV6SourceUpgradesWithoutMutation(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32) { 66 }
        val password = "elements-backup-fixture".toCharArray()
        try {
            for (version in listOf(6, 7)) {
                val name = "elements-v$version-backup-test.db"
                val copyName = "elements-v$version-copy-test.db"
                context.deleteDatabase(name); context.deleteDatabase(copyName)
                try {
                    val driver = if (version == 6) AndroidSqliteDriver(MeasurementElementMigrationProof.v6Schema,
                        context, name, factory = SupportOpenHelperFactory(key.copyOf(), null, true))
                    else createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
                    val (oldRows, element) = driver.use {
                        MeasurementElementMigrationProof.populate(it)
                        val oldRows = MeasurementElementMigrationProof.snapshot(it)
                        val db = PrototypeDatabase(it)
                        val element = if (version == 7) MeasurementElementRepository(db).createWish(
                            MeasurementRepository(db).all().single().uuid, "Backup wish", 400, uuid = "backup-wish") else null
                        val path = context.getDatabasePath(copyName).absolutePath.replace("'", "''")
                        it.execute(null, "VACUUM INTO '$path'", 0)
                        oldRows to element
                    }
                    val source = context.getDatabasePath(copyName)
                    val before = source.readBytes()
                    PortableBackupManager.verifyEncryptedSnapshot(context, source, key)
                    val payload = PortableBackupPayload.encode(before, key)
                    val envelope = try { PortableBackupEnvelope.seal(payload, password) } finally { payload.fill(0) }
                    val contents = try { PortableBackupManager.validate(context, envelope, password) } finally { envelope.fill(0) }
                    try {
                        assertContentEquals(before, contents.database)
                        assertContentEquals(before, source.readBytes(), "Preflight upgrades only its private copy")
                        // The same payload bytes installed by restore open through the production upgrader.
                        source.writeBytes(contents.database)
                        createEncryptedPrototypeDriver(context, contents.passphrase, copyName,
                            enableWalForTesting = true, initializePriceCatalog = true).use { restored ->
                            assertEquals(7L, PriceNameUniquenessProof.scalar(restored, "PRAGMA user_version"))
                            assertEquals(oldRows, MeasurementElementMigrationProof.snapshot(restored))
                            assertEquals(element, MeasurementElementRepository(PrototypeDatabase(restored)).find("backup-wish"))
                        }
                    } finally { contents.database.fill(0); contents.passphrase.fill(0); before.fill(0) }
                } finally { context.deleteDatabase(name); context.deleteDatabase(copyName) }
            }
        } finally { key.fill(0); password.fill('\u0000') }
    }
}
