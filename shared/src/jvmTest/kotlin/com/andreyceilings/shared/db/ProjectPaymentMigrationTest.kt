package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ProjectPaymentMigrationTest {
    private fun status(driver: JdbcSqliteDriver, id: Long): Pair<String?, String?> =
        driver.executeQuery(null, "SELECT status, paymentStatus FROM projects WHERE id = ?",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0) to cursor.getString(1)
                    else null to null
                )
            }, 1, binders = { bindLong(0, id) }).value

    @Test
    fun existingPaymentWasUnknownUnlessLegacyStatusSaidPaid() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            // Just the pre-v4 table needed by 3.sqm; the migration must stay additive.
            driver.execute(null, """
                CREATE TABLE projects(
                    id INTEGER PRIMARY KEY,
                    status TEXT NOT NULL DEFAULT 'measurement'
                )
            """.trimIndent(), 0)
            driver.execute(null, "INSERT INTO projects(id, status) VALUES(1, 'in_progress')", 0)
            driver.execute(null, "INSERT INTO projects(id, status) VALUES(2, 'paid')", 0)
            driver.execute(null, "INSERT INTO projects(id, status) VALUES(3, 'cancelled')", 0)

            PrototypeDatabase.Schema.migrate(driver, 3L, 4L)

            assertEquals("in_progress" to "unknown", status(driver, 1L))
            assertEquals("paid" to "paid", status(driver, 2L))
            assertEquals("cancelled" to "unknown", status(driver, 3L))
            driver.execute(null, "INSERT INTO projects(id, status) VALUES(4, 'measurement')", 0)
            assertEquals("measurement" to "unpaid", status(driver, 4L))
        }
    }

    @Test
    fun newProjectPaymentCanChangeWithoutTouchingWorkStage() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val projects = ProjectRepository(PrototypeDatabase(driver))
            val id = projects.createReturningId(null, "Kitchen", status = "in_progress", createdAt = 100L)
            assertEquals("unpaid", assertNotNull(projects.find(id)).paymentStatus)
            projects.updatePaymentStatus(id, "partial", updatedAt = 110L)
            assertEquals("partial", assertNotNull(projects.find(id)).paymentStatus)
            assertEquals("in_progress", projects.find(id)?.status)
            projects.updatePaymentStatus(id, "paid", updatedAt = 90L)
            assertEquals("paid", projects.find(id)?.paymentStatus)
            assertEquals(111L, projects.find(id)?.updatedAt)
            assertFailsWith<IllegalArgumentException> {
                projects.updatePaymentStatus(id, "not_a_status", updatedAt = 200L)
            }
            assertEquals("paid", projects.find(id)?.paymentStatus)
        }
    }
}
