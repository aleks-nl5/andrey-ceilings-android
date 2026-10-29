package com.andreyceilings.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.ClientEntity
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.RoomMeasurementEntity
import com.andreyceilings.app.data.db.RoomToSqlDelightStagingImporter
import com.andreyceilings.app.data.db.SqlDelightDatabaseProvider
import com.andreyceilings.app.data.security.DatabaseKeyManager
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real encrypted Room schema v2 is exported to a separate encrypted SQLDelight
 * stage. IDs, values, rollback and cascade behavior must survive unchanged.
 * The production Room file and its backup files are never touched.
 */
@RunWith(AndroidJUnit4::class)
class RoomToSqlDelightStagingMigrationTest {
    @Test
    fun importsFiveRoomTablesWithOriginalIdsAndRollsBackInvalidSnapshot() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val roomName = "andrey_migration_legacy_room_test.db"
        val stageName = "andrey_ceilings_sqldelight_stage.db"
        context.deleteDatabase(roomName)
        context.deleteDatabase(stageName)
        val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
        var legacy: CeilingsDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            val source = Room.databaseBuilder(context, CeilingsDatabase::class.java, roomName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                .build()
            legacy = source
            val clientId = source.clients().insert(
                ClientEntity(name = "Migration client", createdAt = 100)
            )
            val projectId = source.projects().insert(
                ProjectEntity(clientId = clientId, title = "Migration project", createdAt = 101, updatedAt = 102)
            )
            source.measurements().insert(
                RoomMeasurementEntity(projectId = projectId, name = "Kitchen", createdAt = 103)
            )
            source.priceItems().upsert(
                PriceItemEntity(code = "P-MIG", title = "Legacy film", category = "material",
                    unit = "m2", price = 24.0, updatedAt = 104)
            )
            val estimateId = source.estimates().insert(
                EstimateEntity(
                    projectId = projectId, name = "Original quote", material = "PVC",
                    profileType = "standard", complexity = "simple",
                    lengthMeters = 2.0, widthMeters = 3.0,
                    areaSquareMeters = 6.0, perimeterMeters = 10.0,
                    canvasCost = 50.0, profileCost = 10.0, complexityCost = 0.0,
                    optionsCost = 0.0, totalCost = 60.0, createdAt = 105
                )
            )
            val snapshot = RoomToSqlDelightStagingImporter.capture(source)
            source.close()
            legacy = null

            SqlDelightDatabaseProvider.openStaging(context).use { stage ->
                val corrupt = snapshot.copy(
                    estimates = snapshot.estimates.map { it.copy(projectId = 9_999_999) }
                )
                val rejected = runCatching {
                    RoomToSqlDelightStagingImporter.importIntoEmptyStage(corrupt, stage)
                }.isFailure
                assertTrue("Broken FK must abort the migration transaction", rejected)
                assertEquals(0L, stage.clients.count())
                assertEquals(0L, stage.projects.count())

                val counts = RoomToSqlDelightStagingImporter.importIntoEmptyStage(snapshot, stage)
                assertEquals(1L, counts.clients)
                assertEquals(1L, counts.projects)
                assertEquals(1L, counts.measurements)
                assertEquals(1L, counts.priceItems)
                assertEquals(1L, counts.estimates)
                assertEquals(clientId, stage.clients.all().single().id)
                assertEquals(projectId, stage.projects.all().single().id)
                assertEquals("Kitchen", stage.measurements.forProject(projectId).single().name)
                assertEquals(24.0, stage.prices.all().single().price, 0.000001)
                assertEquals(estimateId, stage.estimates.find(estimateId)?.id)
            }
            SqlDelightDatabaseProvider.openStaging(context).use { stage ->
                assertEquals("Migration client", stage.clients.all().single().name)
                stage.projects.delete(projectId)
                assertNull(stage.estimates.find(estimateId))
                assertEquals(1L, stage.measurements.count()) // Room parity: no FK here
            }
        } finally {
            legacy?.close()
            passphrase.fill(0)
            context.deleteDatabase(roomName)
            context.deleteDatabase(stageName)
        }
    }
}
