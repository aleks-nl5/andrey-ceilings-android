package com.andreyceilings.shared.db

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real shared SQLDelight schema and repositories through the
 * Windows SQLCipher driver, rather than the unencrypted in-memory JDBC driver.
 */
class EncryptedBusinessRepositoriesTest {
    @Test
    fun allRepositoriesPersistEncryptedDataAndRejectWrongKey() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        val directory = Files.createTempDirectory("andrey-business-cipher-").toFile()
        val file = File(directory, "business.db")
        val key = ByteArray(32) { 31 }
        val wrongKey = ByteArray(32) { 47 }
        try {
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                PrototypeDatabase.Schema.create(driver)
                val foreignKeys = driver.executeQuery(null, "PRAGMA foreign_keys", { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
                }, 0).value
                assertEquals(1L, foreignKeys, "Every SQLCipher JDBC connection must enforce foreign keys")
                val db = PrototypeDatabase(driver)
                ClientRepository(db).create("Encrypted client", createdAt = 100)
                val clientId = ClientRepository(db).all().single().id
                ProjectRepository(db).create(clientId, "Encrypted project", createdAt = 101)
                val projectId = ProjectRepository(db).all().single().id
                MeasurementRepository(db).create(projectId, "Kitchen", createdAt = 102)
                PriceRepository(db).create("CIPHER", "Film", "material", "m2", 25.0, 103)
                EstimateRepository(db).create(projectId, "Estimate", "PVC", "standard", "simple",
                    3.0, 4.0, 12.0, 14.0, 100.0, 20.0, 0.0, 0.0, 120.0, createdAt = 104)
            }

            val encryptedBytes = file.readBytes()
            assertTrue(encryptedBytes.size > 16)
            assertTrue(!encryptedBytes.copyOfRange(0, 16)
                .contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            assertTrue(!encryptedBytes.decodeToString(throwOnInvalidSequence = false)
                .contains("Encrypted client"), "Client name must not appear in raw database bytes")

            assertFails {
                SqlCipherWindowsDriverFactory.open(file, wrongKey).use { driver ->
                    ClientRepository(PrototypeDatabase(driver)).all()
                }
            }
            assertTrue(encryptedBytes.contentEquals(file.readBytes()),
                "Wrong-key attempt must not change encrypted file")

            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                val foreignKeys = driver.executeQuery(null, "PRAGMA foreign_keys", { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
                }, 0).value
                assertEquals(1L, foreignKeys, "Every SQLCipher JDBC connection must enforce foreign keys")
                val db = PrototypeDatabase(driver)
                val clients = ClientRepository(db)
                val projects = ProjectRepository(db)
                val measurements = MeasurementRepository(db)
                val prices = PriceRepository(db)
                val estimates = EstimateRepository(db)
                assertEquals("Encrypted client", clients.all().single().name)
                val project = projects.all().single()
                assertEquals("Encrypted project", project.title)
                assertEquals("Kitchen", measurements.forProject(project.id).single().name)
                assertEquals(25.0, prices.all().single().price)
                val estimateId = estimates.forProject(project.id).single().id
                assertEquals("Estimate", estimates.find(estimateId)?.name)

                projects.delete(project.id)
                assertNull(estimates.find(estimateId), "Cascade must delete estimates")
                assertEquals(0, projects.all().size)
                clients.delete(clients.all().single().id)
                prices.delete(prices.all().single().id)
                measurements.delete(measurements.forProject(project.id).single().id)
                assertEquals(0, clients.all().size)
            }
        } finally {
            key.fill(0)
            wrongKey.fill(0)
            directory.deleteRecursively()
        }
    }
}
