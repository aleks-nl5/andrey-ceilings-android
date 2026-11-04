package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class PointPlacementSqlCipherTest {
    @Test fun realWalV7UpgradeRollbackReopenAndFreshPointContracts(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32) { 77 }
        try {
            for (scenario in listOf("fresh", "empty-v7", "populated-v7")) {
                val name = "points-$scenario-test.db"
                context.deleteDatabase(name)
                try {
                    if (scenario == "fresh") createEncryptedPrototypeDriver(context, key, name,
                        enableWalForTesting = true, initializePriceCatalog = true).use { driver ->
                        assertEquals("wal", MeasurementElementMigrationProof.rows(driver, "PRAGMA journal_mode", 1).single().single())
                        PointPlacementProof.run(driver)
                        PointPlacementProof.geometryAndDeletionGuards(driver)
                        PointPlacementProof.eligibilityAndIsolation(driver)
                    } else PointPlacementMigrationProof.lifecycle(PriceCatalogSchema, scenario == "populated-v7") { schema ->
                        if (schema === PriceCatalogSchema) createEncryptedPrototypeDriver(context, key, name,
                            enableWalForTesting = true, initializePriceCatalog = true)
                        else AndroidSqliteDriver(schema, context, name,
                            factory = SupportOpenHelperFactory(key.copyOf(), null, true)).also { driver ->
                            try { driver.executeQuery(null, "PRAGMA journal_mode=WAL", { cursor ->
                                cursor.next(); QueryResult.Value(cursor.getString(0))
                            }, 0).value } catch (failure: Throwable) { driver.close(); throw failure }
                        }
                    }
                } finally { context.deleteDatabase(name) }
            }
        } finally { key.fill(0) }
    }

    @Test fun verifiedEncryptedSnapshotPortableEnvelopeAndProductionRestoreKeepPointIdentity(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        val originalKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val originalSnapshot = PortableBackupManager.createVerifiedSnapshot(context, CeilingsDatabase.get(context))
        val originalImage = originalSnapshot.readBytes()
        val key = ByteArray(32) { 78 }
        val name = "point-backup-source.db"; val copyName = "point-backup-snapshot.db"
        val password = "point-backup-fixture".toCharArray()
        context.deleteDatabase(name); context.deleteDatabase(copyName)
        try {
            lateinit var f: PointPlacementProof.Fixture
            lateinit var point: PointPlacement
            createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true).use { driver ->
                val db = PrototypeDatabase(driver)
                f = PointPlacementProof.fixture(db)
                point = PointPlacementRepository(db).addPoint(f.element.version, f.contourVersion, 1234, 2345, 10)
                val path = context.getDatabasePath(copyName).absolutePath.replace("'", "''")
                driver.execute(null, "VACUUM INTO '$path'", 0)
            }
            val source = context.getDatabasePath(copyName)
            val before = source.readBytes()
            PortableBackupManager.verifyEncryptedSnapshot(context, source, key)
            assertContentEquals(before, source.readBytes())
            val payload = PortableBackupPayload.encode(before, key)
            val envelope = try { PortableBackupEnvelope.seal(payload, password) } finally { payload.fill(0) }
            val contents = try { PortableBackupManager.validate(context, envelope, password) } finally { envelope.fill(0) }
            try {
                val restored = SqlCipherRestoreManager.restore(context, contents)
                assertEquals(8L, restored.queryLong("PRAGMA user_version"))
                assertEquals("wal", MeasurementElementMigrationProof.rows(restored.driver, "PRAGMA journal_mode", 1).single().single())
                assertEquals(point, PointPlacementRepository(restored.database).find(point.uuid))
                val details = com.andreyceilings.shared.objects.ObjectsClientsStore(restored.database) { 20 }.measurementDetails
                val entries = listOf(
                    AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openMeasurement(f.project, f.id),
                    AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(f.project).openMeasurement(f.project, f.id))
                for (entry in entries) {
                    val route = assertNotNull(entry.measurementDetails)
                    val data = details.observe(route.projectId, route.measurementId).first { it is MeasurementDetailsState.Loaded } as MeasurementDetailsState.Loaded
                    assertEquals(f.element.measurementUuid, data.data.uuid)
                    assertEquals(listOf(point), PointPlacementRepository(restored.database).forMeasurement(data.data.uuid))
                }
            } finally { contents.database.fill(0); contents.passphrase.fill(0); before.fill(0) }
        } finally {
            try {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restore(context, PortableBackupPayload.Contents(originalImage, originalKey))
            } finally {
                originalSnapshot.delete(); context.deleteDatabase(name); context.deleteDatabase(copyName)
                originalImage.fill(0); originalKey.fill(0); key.fill(0); password.fill('\u0000')
            }
        }
    }
}
