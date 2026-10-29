package com.andreyceilings.shared.db

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/** Instrumented SQLCipher test: real generated business schema, not a mock database. */
class EncryptedBusinessRepositoriesAndroidTest {
    @Test
    fun businessRepositoriesSurviveReopenAndRejectWrongKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "andrey-business-encrypted-test.db"
        context.deleteDatabase(name)
        val key = ByteArray(32) { 31 }
        val wrongKey = ByteArray(32) { 32 }
        fun open(password: ByteArray) = AndroidSqliteDriver(
            schema = PrototypeDatabase.Schema,
            context = context,
            name = name,
            factory = SupportOpenHelperFactory(password.copyOf()),
        )
        try {
            open(key).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                val db = PrototypeDatabase(driver)
                ClientRepository(db).create("Android client", createdAt = 1)
                val clientId = ClientRepository(db).all().single().id
                ProjectRepository(db).create(clientId, "Android project", createdAt = 2)
                val projectId = ProjectRepository(db).all().single().id
                MeasurementRepository(db).create(projectId, "Kitchen", createdAt = 3)
                PriceRepository(db).create("AND", "Film", "material", "m2", 15.0, 4)
                EstimateRepository(db).create(projectId, "Quote", "PVC", "standard", "simple",
                    2.0, 3.0, 6.0, 10.0, 50.0, 10.0, 0.0, 0.0, 60.0, createdAt = 5)
            }
            val file = context.getDatabasePath(name)
            val header = file.inputStream().use { it.readNBytes(16) }
            assertEquals(false, header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            assertFails { open(wrongKey).use { ClientRepository(PrototypeDatabase(it)).all() } }
            open(key).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                val db = PrototypeDatabase(driver)
                assertEquals("Android client", ClientRepository(db).all().single().name)
                val projectId = ProjectRepository(db).all().single().id
                assertEquals("Kitchen", MeasurementRepository(db).forProject(projectId).single().name)
                assertEquals(15.0, PriceRepository(db).all().single().price)
                val estimateId = EstimateRepository(db).forProject(projectId).single().id
                ProjectRepository(db).delete(projectId)
                assertNull(EstimateRepository(db).find(estimateId))
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
            wrongKey.fill(0)
        }
    }
}
