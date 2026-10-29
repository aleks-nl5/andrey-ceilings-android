package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.shared.db.ClientRepository
import com.andreyceilings.shared.db.EstimateRepository
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs via :app:connectedDebugAndroidTest (the task actually invoked by GitHub Actions).
 * Covers all five SQLDelight repositories against real Android SQLCipher, not a mock.
 */
@RunWith(AndroidJUnit4::class)
class SqlDelightBusinessRepositoriesInstrumentedTest {
    @Test
    fun realEncryptedSchemaSupportsRoomDaoParityAndForeignKeyCascade() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "andrey_sqldelight_business_integration.db"
        val file = context.getDatabasePath(name)
        val key = ByteArray(32) { 73 }
        val wrongKey = ByteArray(32) { 74 }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val db = PrototypeDatabase(driver)
                val clients = ClientRepository(db)
                val projects = ProjectRepository(db)
                val measurements = MeasurementRepository(db)
                val prices = PriceRepository(db)
                val estimates = EstimateRepository(db)

                val clientId = clients.createReturningId("Olena", createdAt = 100)
                assertTrue(clientId > 0L)
                assertEquals(clientId, clients.findByName("oLeNa")?.id)

                val projectId = projects.createReturningId(clientId, "Ceiling", createdAt = 101)
                projects.updateStatus(projectId, "estimate", 102)
                assertEquals("estimate", projects.find(projectId)?.status)

                val measurementId = measurements.createReturningId(
                    projectId, "Kitchen", createdAt = 103
                )
                assertTrue(measurementId > 0L)

                val priceId = prices.upsertReturningId(
                    code = "film", title = "Film", category = "material",
                    unit = "m2", price = 12.0, updatedAt = 104
                )
                prices.upsertReturningId(
                    id = priceId, code = "film", title = "New Film", category = "material",
                    unit = "m2", price = 14.0, updatedAt = 105
                )
                assertEquals(1L, prices.count())
                assertEquals(14.0, prices.find(priceId)?.price ?: 0.0, 0.000001)

                val estimateId = estimates.createReturningId(
                    projectId, "Quote", "PVC", "standard", "simple",
                    2.0, 3.0, 6.0, 10.0, 50.0, 10.0, 0.0, 0.0, 60.0, createdAt = 106
                )
                assertTrue(estimateId > 0L)
                assertEquals(1L, estimates.count())
                assertEquals(1L, clients.count())
                projects.delete(projectId)
                assertNull(estimates.find(estimateId))
                // Room parity: measurements have an index, not an FK/cascade.
                assertEquals(1L, measurements.count())
            }

            val bytes = file.readBytes()
            assertTrue("Database should not be empty", bytes.size > 16)
            assertTrue("Encrypted database must not expose a SQLite header",
                !bytes.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray()))
            assertTrue("Database must not contain client names in plaintext",
                !bytes.toString(Charsets.ISO_8859_1).contains("Olena"))

            val failed = runCatching {
                createEncryptedPrototypeDriver(context, wrongKey, name).use { driver ->
                    ClientRepository(PrototypeDatabase(driver)).all()
                }
            }.isFailure
            assertTrue("Wrong SQLCipher key must fail closed", failed)
            assertArrayEquals("Wrong-key attempt must preserve encrypted database bytes", bytes, file.readBytes())

            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val db = PrototypeDatabase(driver)
                assertEquals("Olena", ClientRepository(db).all().single().name)
                assertEquals("Kitchen", MeasurementRepository(db).all().single().name)
                assertEquals("New Film", PriceRepository(db).all().single().title)
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
            wrongKey.fill(0)
        }
    }
}
