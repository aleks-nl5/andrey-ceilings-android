package com.andreyceilings.shared.dashboard

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardStoreTest {
    @Test
    fun realSqlDelightRowsAndSoftDeletesDriveSummary() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val database = PrototypeDatabase(driver)
            val store = DashboardStore(database)
            val projects = ProjectRepository(database)
            val measurements = MeasurementRepository(database)
            assertEquals(0, store.summary.first().projectCount)

            val projectId = projects.createReturningId(null, "Квартира", createdAt = 100L)
            measurements.createReturningId(projectId, "Кімната 1", areaSquareMeters = 12.25, createdAt = 101L)
            measurements.createReturningId(projectId, "Кімната 2", areaSquareMeters = 5.0, createdAt = 102L)
            assertEquals(DashboardSummary(1, 2, 17.25), store.summary.first())

            projects.delete(projectId)
            assertEquals(DashboardSummary(0, 0, 0.0), store.summary.first())
        }
    }
}
