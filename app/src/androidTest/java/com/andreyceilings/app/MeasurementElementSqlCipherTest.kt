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
    @Test fun encryptedWalFreshElementContracts(): Unit = runBlocking { elementScenarios(listOf("fresh")) }

    // Archived pre-v21 upgrade proof; current-schema assertions run separately.
    @org.junit.Ignore("Archived pre-v21 migration proof")
    @Test fun encryptedWalV6MigrationRollbackRestartAndFreshContracts(): Unit = runBlocking { elementScenarios(listOf("migration")) }

    private suspend fun elementScenarios(scenarios: List<String>) {
        val context: Context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32) { 65 }
        try {
            for (scenario in scenarios) {
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
                        LivePricingProof.run(driver)
                        MeasurementElementsPresentationProof.run(PrototypeDatabase(driver))
                        MeasurementElementProof.run(driver)
                        MeasurementElementProof.atomicParentDelete(driver)
                        MeasurementElementMigrationProof.sqlGuards(driver)
                    }
                } finally { context.deleteDatabase(name) }
            }
        } finally { key.fill(0) }
    }

    // Archived pre-v21 upgrade proof; see CI historical DB policy.
    @org.junit.Ignore("Archived pre-v21 migration proof")
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
                val driver = openHistoricalBackupSource(context, key, name, version)
                val (before, element) = driver.use {
                    MeasurementElementMigrationProof.populate(it)
                    assertEquals(version.toLong(), PriceNameUniquenessProof.scalar(it, "PRAGMA user_version"))
                    assertTrue(PriceNameUniquenessProof.scalar(it, "SELECT count(*) FROM estimate_revisions") > 0L)
                    val before = MeasurementElementMigrationProof.snapshot(it)
                    val db = if (version == 7) historicalElementDatabase(it) else PrototypeDatabase(it)
                    val element = if (version == 7) MeasurementElementRepository(db).createWish(
                        MeasurementRepository(db).all().single().uuid, "Restored wish", 400, uuid = "restored-wish") else null
                    before to element
                }
                val image = context.getDatabasePath(name).readBytes()
                try {
                    val restored = SqlCipherRestoreManager.restore(context, PortableBackupPayload.Contents(image, key))
                    assertEquals(PriceCatalogSchema.version, restored.queryLong("PRAGMA user_version"))
                    assertEquals(withoutPreV22Estimates(before), MeasurementElementMigrationProof.snapshot(restored.driver))
                    assertTrue(EstimateV22MigrationProof.counts(restored.driver).values.all { it == 0L })
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

    // Archived pre-v21 upgrade proof; see CI historical DB policy.
    @org.junit.Ignore("Archived pre-v21 migration proof")
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
                    val driver = openHistoricalBackupSource(context, key, name, version)
                    val (oldRows, element) = driver.use {
                        MeasurementElementMigrationProof.populate(it)
                        assertEquals(version.toLong(), PriceNameUniquenessProof.scalar(it, "PRAGMA user_version"))
                        assertTrue(PriceNameUniquenessProof.scalar(it, "SELECT count(*) FROM estimate_revisions") > 0L)
                        val oldRows = MeasurementElementMigrationProof.snapshot(it)
                        val db = if (version == 7) historicalElementDatabase(it) else PrototypeDatabase(it)
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
                        assertTrue(contents.clearsPreV22Estimates)
                        assertContentEquals(before, contents.database)
                        assertContentEquals(before, source.readBytes(), "Preflight upgrades only its private copy")
                        // The same payload bytes installed by restore open through the production upgrader.
                        source.writeBytes(contents.database)
                        createEncryptedPrototypeDriver(context, contents.passphrase, copyName,
                            enableWalForTesting = true, initializePriceCatalog = true).use { restored ->
                            assertEquals(PriceCatalogSchema.version, PriceNameUniquenessProof.scalar(restored, "PRAGMA user_version"))
                            assertEquals(withoutPreV22Estimates(oldRows), MeasurementElementMigrationProof.snapshot(restored))
                            assertTrue(EstimateV22MigrationProof.counts(restored).values.all { it == 0L })
                            assertEquals(element, MeasurementElementRepository(PrototypeDatabase(restored)).find("backup-wish"))
                        }
                    } finally { contents.database.fill(0); contents.passphrase.fill(0); before.fill(0) }
                } finally { context.deleteDatabase(name); context.deleteDatabase(copyName) }
            }
        } finally { key.fill(0); password.fill('\u0000') }
    }

    // Open the actual source version. Current-schema databases must never be
    // populated with historical estimate rows that bypass the issuance ledger.
    private fun openHistoricalBackupSource(context: Context, key: ByteArray, name: String, version: Int): SqlDriver {
        val schema = when (version) {
            6 -> MeasurementElementMigrationProof.v6Schema
            7 -> PointPlacementMigrationProof.v7Schema
            else -> error("Unsupported historical fixture")
        }
        return AndroidSqliteDriver(schema, context, name,
            factory = SupportOpenHelperFactory(key.copyOf(), null, true))
    }

}
