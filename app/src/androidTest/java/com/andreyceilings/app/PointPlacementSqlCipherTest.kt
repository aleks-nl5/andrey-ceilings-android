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
            for (scenario in listOf("fresh", "empty-v7", "populated-v7", "empty-v8", "populated-v8", "empty-v9", "populated-v9", "legacy-v9", "deployed-v10", "initial-v10", "populated-v11")) {
                val name = "points-$scenario-test.db"
                context.deleteDatabase(name)
                try {
                    if (scenario == "fresh") createEncryptedPrototypeDriver(context, key, name,
                        enableWalForTesting = true, initializePriceCatalog = true).use { driver ->
                        assertEquals("wal", MeasurementElementMigrationProof.rows(driver, "PRAGMA journal_mode", 1).single().single())
                        PointPlacementProof.run(driver)
                        PointPlacementProof.geometryAndDeletionGuards(driver)
                        PointPlacementProof.eligibilityAndIsolation(driver)
                        PointPositionProof.run(driver)
                        WallAnchorProof.run(driver)
                    } else {
                        val open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver = { schema ->
                        if (schema === PriceCatalogSchema) createEncryptedPrototypeDriver(context, key, name,
                            enableWalForTesting = true, initializePriceCatalog = true)
                        else AndroidSqliteDriver(schema, context, name,
                            factory = SupportOpenHelperFactory(key.copyOf(), null, true)).also { driver ->
                            try { driver.executeQuery(null, "PRAGMA journal_mode=WAL", { cursor ->
                                cursor.next(); QueryResult.Value(cursor.getString(0))
                            }, 0).value } catch (failure: Throwable) { driver.close(); throw failure }
                        }
                        }
                        if(scenario=="populated-v11")LinePlacementMigrationProof.lifecycle(PriceCatalogSchema,open)
                        else if(scenario=="initial-v10")LegacyWallSeedProof.initialV10Lifecycle(PriceCatalogSchema,open)
                        else if(scenario=="legacy-v9" || scenario=="deployed-v10") LegacyWallSeedProof.lifecycle(PriceCatalogSchema,if(scenario=="legacy-v9")9L else 10L,open)
                        else if (scenario.endsWith("v9")) WallAnchorMigrationProof.lifecycle(PriceCatalogSchema, scenario == "populated-v9", open)
                        else if (scenario.endsWith("v8")) PointPositionMigrationProof.lifecycle(PriceCatalogSchema, scenario == "populated-v8", open)
                        else PointPlacementMigrationProof.lifecycle(PriceCatalogSchema, scenario == "populated-v7", open)
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
            for (version in listOf(8, 9, 10, 11, 12, 13, 14)) {
                context.deleteDatabase(name); context.deleteDatabase(copyName)
                lateinit var f: PointPlacementProof.Fixture
                lateinit var expectedPoints: List<PointPlacement>
                var expectedAreas=emptyList<AreaFeature>()
                val sourceDriver = if (version < 14) AndroidSqliteDriver(when(version){8->PointPositionMigrationProof.v8Schema;9->WallAnchorMigrationProof.v9Schema;10->WallAnchorMigrationProof.v10Schema;12->AreaFeatureMigrationProof.v12Schema;13->CeilingReceiptMigrationProof.v13Schema;else->LinePlacementMigrationProof.v11Schema}, context, name,
                    factory = SupportOpenHelperFactory(key.copyOf(), null, true))
                    else createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
                sourceDriver.use { driver ->
                    driver.executeQuery(null, "SELECT count(*) FROM sqlite_master", { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0)) }, 0).value
                    assertEquals(version.toLong(), PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                    val db = PrototypeDatabase(driver)
                    com.andreyceilings.shared.prices.StarterPriceCatalog.initialize(db)
                    f = if(version==9) WallAnchorMigrationProof.populateV9(driver) else PointPlacementProof.fixture(db)
                    if (version == 8) {
                        driver.execute(null, "INSERT INTO point_placements(uuid,elementUuid,xMm,yMm,contourUpdatedAt,createdAt,updatedAt) VALUES('old-backup-point','${f.element.uuid}',1234,2345,${f.contourVersion},10,10)", 0)
                        expectedPoints = listOf(PointPlacement("old-backup-point", f.element.uuid,1234,2345,f.contourVersion,10,10,null))
                    } else if(version==9) {
                        expectedPoints=listOf(PointPlacement("v9-point",f.element.uuid,1500,1200,f.contourVersion,10,20,null,PointPrecision.EXACT,"v9-move"))
                    } else {
                        val initial = PointPlacementRepository(db).addPoint(f.element.version, f.contourVersion, 1234, 2345, 10)
                        val element = MeasurementElementRepository(db).find(f.element.uuid)!!
                        val moved = PointPlacementRepository(db).movePoint(element.version, initial, f.contourVersion, 2234, 1345, PointPrecision.EXACT, 11).point
                        val store=HistoricalPointEditStore(db){12}
                        var document=store.load(f.project,f.id)
                        val anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,document.placements.walls[0].uuid,document.placements.walls[3].uuid,600,1500)
                        document=store.update(document,document.draft,mapOf(moved.uuid to PointMountingEdit(anchor=anchor)),now=12)
                        PointPlacementRepository(db).addPoint(MeasurementElementRepository(db).find(f.element.uuid)!!.version,document.updatedAt,3500,2000,13)
                        document=store.load(f.project,f.id)
                        document=store.update(document,document.draft.reduce(MeasurementCreateIntent.Dimension("length","2")),now=14)
                        document=store.update(document,document.draft,document.placements.points.associate { it.uuid to PointMountingEdit(defer=true) },now=15)
                        expectedPoints=document.placements.points
                        assertEquals(1,expectedPoints.count { it.mounting.unresolved })
                        assertTrue(expectedPoints.all { it.mounting.needsReview && it.mounting.deferredAt==15L })
                    }
                    if(version>=13) {
                        val areaStore=MeasurementEditStore(db){20}
                        val doc=areaStore.load(f.project,f.id)
                        val area=AreaFeature("backup-area",doc.draft.recordUuid,AreaShape.rectangle(PointPositionMm(500,500),PointPositionMm(1000,1000)),doc.updatedAt,0,0,role=AreaRole.OBSTACLE,note="Backup obstacle")
                        val circle=area.copy(uuid="backup-circle",shape=AreaShape.Circle(PointPositionMm(1500,1500),100),mountings=listOf(PointMounting()),role=AreaRole.VOID_MARKER,radiusMeasured=true)
                        expectedAreas=areaStore.update(doc,doc.draft,areaEdits=mapOf(area.uuid to AreaFeatureEdit(area),circle.uuid to AreaFeatureEdit(circle))).placements.areas
                    }
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
                    assertEquals(PriceCatalogSchema.version, restored.queryLong("PRAGMA user_version"))
                    assertEquals("wal", MeasurementElementMigrationProof.rows(restored.driver, "PRAGMA journal_mode", 1).single().single())
                    assertEquals(expectedAreas,AreaFeatureRepository(restored.database).forMeasurement(f.element.measurementUuid))
                    expectedPoints.forEach { point -> assertEquals(point, PointPlacementRepository(restored.database).find(point.uuid)) }
                    val details = com.andreyceilings.shared.objects.ObjectsClientsStore(restored.database) { 20 }.measurementDetails
                    val entries = listOf(
                        AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openMeasurement(f.project, f.id),
                        AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(f.project).openMeasurement(f.project, f.id))
                    for (entry in entries) {
                        val route = assertNotNull(entry.measurementDetails)
                        val data = details.observe(route.projectId, route.measurementId).first { it is MeasurementDetailsState.Loaded } as MeasurementDetailsState.Loaded
                        assertEquals(f.element.measurementUuid, data.data.uuid)
                        assertEquals(expectedPoints, PointPlacementRepository(restored.database).forMeasurement(data.data.uuid))
                    }
                } finally { contents.database.fill(0); contents.passphrase.fill(0); before.fill(0) }
            }
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
