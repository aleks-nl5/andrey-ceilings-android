package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room DAO behavior required before Android can switch its production repository layer.
 * The JVM in-memory driver is fast; encrypted platform tests separately cover cipher I/O.
 */
class BusinessDaoParityTest {
    @Test
    fun generatedIdsCountsCaseInsensitiveLookupAndStatusUpdate() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            // Plain in-memory SQLite does not enable FK constraints by default.
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val clients = ClientRepository(db)
            val projects = ProjectRepository(db)
            val measurements = MeasurementRepository(db)
            val estimates = EstimateRepository(db)
            val prices = PriceRepository(db)

            assertEquals(0L, clients.count())
            assertEquals(0L, projects.count())
            assertEquals(0L, measurements.count())
            assertEquals(0L, estimates.count())
            assertEquals(0L, prices.count())

            val clientId = clients.createReturningId("Olena", createdAt = 11)
            assertTrue(clientId > 0L)
            assertEquals(clientId, clients.findByName("oLeNa")?.id)
            assertEquals(1L, clients.count())

            val projectId = projects.createReturningId(clientId, "Living room", createdAt = 12)
            assertTrue(projectId > 0L)
            projects.updateStatus(projectId, "estimate", updatedAt = 40)
            assertEquals("estimate", projects.find(projectId)?.status)
            assertEquals(40L, projects.find(projectId)?.updatedAt)

            val measurementId = measurements.createReturningId(projectId, "First", createdAt = 13)
            measurements.createReturningId(projectId, "Second", createdAt = 14)
            assertTrue(measurementId > 0L)
            assertEquals(listOf("Second", "First"), measurements.all().map { it.name })
            assertEquals(2L, measurements.count())

            val estimateId = estimates.createReturningId(
                projectId, "First quote", "PVC", "standard", "simple",
                3.0, 4.0, 12.0, 14.0, 100.0, 20.0, 0.0, 0.0, 120.0, createdAt = 15
            )
            assertTrue(estimateId > 0L)
            assertEquals(1L, estimates.count())

            val priceId = prices.upsertReturningId(code = "P1", title = "Film", category = "material",
                unit = "m2", price = 10.0, updatedAt = 16)
            val updatedId = prices.upsertReturningId(id = priceId, code = "P1", title = "New film",
                category = "material", unit = "m2", price = 12.0, updatedAt = 17)
            assertEquals(priceId, updatedId)
            assertEquals(1L, prices.count())
            assertEquals("New film", prices.find(priceId)?.title)

            projects.delete(projectId)
            assertNull(estimates.find(estimateId), "Foreign-key cascade must match Room")
            // Measurements intentionally mirror Room: projectId has an index, NOT a foreign key.
            assertEquals(2L, measurements.count())
        }
    }

    @Test
    fun roomEquivalentNameAndPriceOrdering() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val clients = ClientRepository(db)
            clients.create("zulu", createdAt = 1)
            clients.create("Alpha", createdAt = 2)
            assertEquals(listOf("Alpha", "zulu"), clients.all().map { it.name })

            val prices = PriceRepository(db)
            prices.create("Z", "Zebra", "material", "m2", 1.0, 1)
            prices.create("A", "Apple", "material", "m2", 2.0, 2)
            assertEquals(listOf("Apple", "Zebra"), prices.all().map { it.title })
        }
    }
}
