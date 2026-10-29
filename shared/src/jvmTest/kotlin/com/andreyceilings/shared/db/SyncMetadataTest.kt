package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema-level readiness only: there is no transport, cross-device synchronization
 * service, or conflict resolution implemented in this PR.
 */
class SyncMetadataTest {
    @Test
    fun allFiveEntitiesReceiveDistinctStableUuidsAndChangeTimes() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val database = PrototypeDatabase(driver)
            val clients = ClientRepository(database)
            val projects = ProjectRepository(database)
            val measurements = MeasurementRepository(database)
            val prices = PriceRepository(database)
            val estimates = EstimateRepository(database)

            val client = clients.createReturningId("Client", createdAt = 100)
            val project = projects.createReturningId(client, "Project", createdAt = 110)
            val measurement = measurements.createReturningId(project, "Measurement", createdAt = 120)
            val price = prices.upsertReturningId(code = "FILM", title = "Film",
                category = "Materials", unit = "m2", price = 20.0, updatedAt = 130)
            val estimate = estimates.createReturningId(project, "Quote", "PVC", "standard", "simple",
                2.0, 3.0, 6.0, 10.0, 100.0, 25.0, 0.0, 0.0, 125.0, createdAt = 140)

            val originalUuids = listOf(
                clients.find(client)!!.uuid, projects.find(project)!!.uuid,
                measurements.find(measurement)!!.uuid, prices.find(price)!!.uuid,
                estimates.find(estimate)!!.uuid
            )
            assertEquals(5, originalUuids.distinct().size)
            originalUuids.forEach {
                assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(it))
            }
            assertEquals(100L, clients.find(client)!!.updatedAt)
            assertEquals(120L, measurements.find(measurement)!!.updatedAt)
            assertEquals(140L, estimates.find(estimate)!!.updatedAt)

            clients.update(client, "Updated client", "", "", "")
            measurements.update(measurement, "Changed", "rectangle", 2.0, 3.0, 6.0, 10.0, "{}", "")
            estimates.update(estimate, "Changed", "PVC", "standard", "simple",
                2.0, 3.0, 6.0, 10.0, 100.0, 25.0, 0.0, 0.0, 125.0, "{}")
            prices.upsertReturningId(price, "FILM", "Updated film", "Materials", "m2", 30.0, 150)
            // Updating by natural key must preserve both local ID and global UUID.
            assertEquals(price, prices.upsertReturningId(code = "FILM", title = "Updated again",
                category = "Materials", unit = "m2", price = 31.0, updatedAt = 151))
            projects.updateStatus(project, "estimate", 160)

            assertEquals(originalUuids, listOf(
                clients.find(client)!!.uuid, projects.find(project)!!.uuid,
                measurements.find(measurement)!!.uuid, prices.find(price)!!.uuid,
                estimates.find(estimate)!!.uuid
            ))
            assertTrue(clients.find(client)!!.updatedAt > 100L)
            assertTrue(measurements.find(measurement)!!.updatedAt > 120L)
            assertTrue(estimates.find(estimate)!!.updatedAt > 140L)
            assertEquals(151L, prices.find(price)!!.updatedAt)
            assertEquals(160L, projects.find(project)!!.updatedAt)
        }
    }

    @Test
    fun deletionsBecomeTombstonesAndNormalListsHideThem() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val database = PrototypeDatabase(driver)
            val clients = ClientRepository(database)
            val projects = ProjectRepository(database)
            val measurements = MeasurementRepository(database)
            val prices = PriceRepository(database)
            val estimates = EstimateRepository(database)

            val client = clients.createReturningId("Client", createdAt = 1)
            val project = projects.createReturningId(client, "Kitchen", createdAt = 2)
            val measurement = measurements.createReturningId(project, "Ceiling", createdAt = 3)
            val estimate = estimates.createReturningId(project, "Estimate", "PVC", "standard", "simple",
                2.0, 3.0, 6.0, 10.0, 100.0, 25.0, 0.0, 0.0, 125.0, createdAt = 4)
            val price = prices.upsertReturningId(code = "ITEM", title = "Old", category = "Materials",
                unit = "m2", price = 1.0, updatedAt = 5)
            val oldPriceUuid = prices.find(price)!!.uuid

            prices.delete(price)
            assertNull(prices.find(price))
            assertEquals(0L, prices.count())
            assertNotNull(prices.tombstones().single().deletedAt)
            val replacement = prices.upsertReturningId(code = "ITEM", title = "New",
                category = "Materials", unit = "m2", price = 2.0, updatedAt = 6)
            assertNotEquals(oldPriceUuid, prices.find(replacement)!!.uuid)

            projects.delete(project)
            assertNull(projects.find(project))
            assertNull(estimates.find(estimate))
            assertEquals(1, projects.tombstones().size)
            assertEquals(1, estimates.tombstones().size)
            // Preserve existing Room behavior: measurements do not cascade on project removal.
            assertNotNull(measurements.find(measurement))
            measurements.delete(measurement)
            clients.delete(client)
            prices.delete(replacement)

            assertEquals(0L, clients.count())
            assertEquals(0L, measurements.count())
            assertEquals(0L, estimates.count())
            assertEquals(0L, prices.count())
            assertTrue(clients.tombstones().single().deletedAt!! >= 1L)
            assertTrue(measurements.tombstones().single().deletedAt!! >= 3L)
            assertEquals(2, prices.tombstones().size)
        }
    }
}
