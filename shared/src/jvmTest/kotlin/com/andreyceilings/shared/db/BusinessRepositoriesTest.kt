package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class BusinessRepositoriesTest {
    @Test
    fun crudAndForeignKeyCascade() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val clients = ClientRepository(db)
            val projects = ProjectRepository(db)
            val measurements = MeasurementRepository(db)
            val prices = PriceRepository(db)
            val estimates = EstimateRepository(db)

            clients.create("Customer", createdAt = 100)
            val clientId = clients.all().single().id
            clients.update(clientId, "Updated customer", "123", "", "")
            assertEquals("Updated customer", clients.find(clientId)?.name)

            projects.create(clientId, "Ceiling", createdAt = 101)
            val projectId = projects.all().single().id
            projects.update(projectId, clientId, "New ceiling", "Street", "estimate", "", 102)
            assertEquals("New ceiling", projects.find(projectId)?.title)
            assertEquals(1, projects.byClient(clientId).size)

            measurements.create(projectId, "Living room", createdAt = 103)
            val measurementId = measurements.forProject(projectId).single().id
            measurements.update(measurementId, "Bedroom", "rectangle", 3.0, 4.0, 12.0, 14.0, "{}", "")
            assertEquals(12.0, measurements.find(measurementId)?.areaSquareMeters)

            prices.create("P1", "Canvas", "material", "m2", 12.0, 104)
            val priceId = prices.all().single().id
            prices.update(priceId, "P1", "Canvas", "material", "m2", 15.0, 105)
            assertEquals(15.0, prices.find(priceId)?.price)
            assertFails { prices.create("P1", "Duplicate", "material", "m2", 9.0, 106) }

            estimates.create(projectId, "Quote", "PVC", "standard", "simple",
                3.0, 4.0, 12.0, 14.0, 100.0, 20.0, 0.0, 0.0, 120.0, createdAt = 107)
            val estimateId = estimates.forProject(projectId).single().id
            estimates.update(estimateId, "Updated quote", "PVC", "standard", "simple",
                3.0, 4.0, 12.0, 14.0, 100.0, 20.0, 0.0, 0.0, 120.0, "{}")
            assertEquals("Updated quote", estimates.find(estimateId)?.name)

            projects.delete(projectId)
            assertNull(estimates.find(estimateId)) // Room's ON DELETE CASCADE contract
            measurements.delete(measurementId)
            prices.delete(priceId)
            clients.delete(clientId)
            assertEquals(0, clients.all().size)
            assertEquals(0, prices.all().size)
        }
    }
}
