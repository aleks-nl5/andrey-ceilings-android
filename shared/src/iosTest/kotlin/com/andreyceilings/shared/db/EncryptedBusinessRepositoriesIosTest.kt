package com.andreyceilings.shared.db

import com.yet.sqlcipher.NativeSqlCipherDriverFactory
import com.yet.sqlcipher.SqlCipherConfig
import com.yet.sqlcipher.SqlCipherKey
import com.yet.sqlcipher.SqlCipherRecovery
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.andreyceilings.shared.measurement.*
import kotlin.test.assertIs

/** Real generated SQLDelight business schema over an encrypted iOS simulator database. */
class EncryptedBusinessRepositoriesIosTest {
    private val factory = NativeSqlCipherDriverFactory()

    private fun config(name: String, byte: Byte) = SqlCipherConfig(
        name = name,
        key = SqlCipherKey { ByteArray(32) { byte } },
        recovery = SqlCipherRecovery.Fail,
    )

    @Test
    fun repositoriesPersistReopenRejectWrongKeyAndCascade(): Unit = runBlocking {
        val name = "andrey-ios-business-${Random.nextLong().toString(16)}.db"
        try {
            factory.create(PrototypeDatabase.Schema, config(name, 31)).let { driver ->
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                    val db = PrototypeDatabase(driver)
                    ClientRepository(db).create("iOS encrypted client", createdAt = 1)
                    val clientId = ClientRepository(db).all().single().id
                    ProjectRepository(db).create(clientId, "iOS project", createdAt = 2)
                    val projectId = ProjectRepository(db).all().single().id
                    MeasurementRepository(db).create(projectId, "Room", createdAt = 3)
                    PriceRepository(db).create("IOS", "Film", "material", "m2", 20.0, 4)
                    EstimateRepository(db).create(projectId, "Quote", "PVC", "standard", "simple",
                        2.0, 3.0, 6.0, 10.0, 50.0, 10.0, 0.0, 0.0, 60.0, createdAt = 5)
                } finally {
                    driver.close()
                }
            }

            assertFails {
                runBlocking { factory.create(PrototypeDatabase.Schema, config(name, 32)).close() }
            }

            factory.create(PrototypeDatabase.Schema, config(name, 31)).let { driver ->
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                    val db = PrototypeDatabase(driver)
                    assertEquals("iOS encrypted client", ClientRepository(db).all().single().name)
                    val projectId = ProjectRepository(db).all().single().id
                    assertEquals("Room", MeasurementRepository(db).forProject(projectId).single().name)
                    assertEquals(20.0, PriceRepository(db).all().single().price)
                    val estimateId = EstimateRepository(db).forProject(projectId).single().id
                    ProjectRepository(db).delete(projectId)
                    assertNull(EstimateRepository(db).find(estimateId))
                } finally {
                    driver.close()
                }
            }
        } finally {
            factory.deleteDatabase(name)
        }
        Unit
    }
    @Test fun ownedMeasurementDetailsReadVersionedGeometryAndReactToDeletion(): Unit = runBlocking {
        val name = "andrey-ios-measurement-${Random.nextLong().toString(16)}.db"
        try {
            val driver = factory.create(PrototypeDatabase.Schema, config(name, 47))
            try {
                val db = PrototypeDatabase(driver)
                val project = ProjectRepository(db).createReturningId(null, "iOS measurement project", createdAt = 1)
                val measurements = MeasurementRepository(db)
                val id = measurements.createReturningId(project, "iOS L room", shape = "polygon",
                    lengthMeters = 5.0, widthMeters = 4.0, areaSquareMeters = 14.0, perimeterMeters = 18.0,
                    drawingJson = AndroidMeasurementFixtures.sidesV3, createdAt = 2)
                val store = MeasurementDetailsStore(db)
                val loaded = assertIs<MeasurementDetailsState.Loaded>(store.observe(project, id)
                    .first { it !is MeasurementDetailsState.Loading })
                val geometry = assertIs<MeasurementGeometryView.Captured>(loaded.data.geometry).geometry
                assertEquals(6, geometry.sides.size)
                assertEquals(AndroidMeasurementFixtures.sidesV3, measurements.find(id)?.drawingJson)
                assertEquals(MeasurementDetailsState.Missing, store.observe(project + 999, id)
                    .first { it !is MeasurementDetailsState.Loading })
                measurements.delete(id)
                assertEquals(MeasurementDetailsState.Missing, store.observe(project, id)
                    .first { it !is MeasurementDetailsState.Loading })
            } finally { driver.close() }
        } finally { factory.deleteDatabase(name) }
        Unit
    }

}
