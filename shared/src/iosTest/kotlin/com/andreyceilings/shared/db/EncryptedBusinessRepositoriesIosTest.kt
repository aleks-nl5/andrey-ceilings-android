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
}
