package com.andreyceilings.shared.dashboard

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.objects.ProjectDraft
import com.andreyceilings.shared.registry.CachedRegistryFlow
import com.andreyceilings.shared.registry.RegistryRead
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

            database.businessSchemaQueries.deleteProject(projectId) // Reproduce a historical orphan.
            assertEquals(DashboardSummary(0, 0, 0.0), store.summary.first())
        }
    }

    @Test
    fun sessionDashboardRefreshesLiveAndKeepsWarmSnapshotUntilNextRead() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val session = ObjectsClientsStore(db) { 100L }
            val rows = session.uiDashboardSummary()
            val dashboard = DashboardStore(session)
            val updates = Channel<DashboardSummary>(Channel.UNLIMITED)
            val collector = launch { dashboard.summary.collect { updates.send(it) } }
            try {
                assertEquals(DashboardSummary(0, 0, 0.0), withTimeout(5_000) { updates.receive() })
                val project = session.saveProject(null, ProjectDraft("Registry test"))
                val measurements = MeasurementRepository(db)
                measurements.createReturningId(project, "First", areaSquareMeters = 12.25, createdAt = 101L)
                val expected = DashboardSummary(1, 1, 12.25)
                withTimeout(5_000) {
                    var current = updates.receive()
                    while (current != expected) current = updates.receive()
                }
                collector.cancelAndJoin()
                val warm = initial(rows)
                assertIs<RegistryRead.Ready<*>>(warm)
                assertEquals(warm, initial(session.uiDashboardSummary()))
                measurements.createReturningId(project, "Second", areaSquareMeters = 5.0, createdAt = 102L)
                // No active collector: reuse the last good value, then refresh from the real query.
                assertEquals(warm, initial(session.uiDashboardSummary()))
                assertEquals(DashboardSummary(1, 2, 17.25), withTimeout(5_000) { DashboardStore(session).summary.first() })
                val refreshed = initial(rows)
                assertTrue(refreshed is RegistryRead.Ready)
                assertEquals(2L, refreshed.data.ceilingCount)
            } finally {
                collector.cancelAndJoin()
                updates.close()
                session.closeAndAwaitProjectReads()
            }
        }
    }

    @Test
    fun closingSessionClearsRegistrySnapshotsAndRejectsLateRepopulation() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val session = ObjectsClientsStore(db) { 100L }
            val project = session.saveProject(null, ProjectDraft("Registry test"))
            MeasurementRepository(db).createReturningId(project, "Ceiling", createdAt = 101L)
            val ceilings = session.uiRegistryCeilings()
            val objects = session.uiObjectsRows()
            val stats = session.uiDashboardSummary()
            val quoteCount = session.uiDashboardQuoteCount()
            val finance = session.uiDashboardFinance()
            val quotes = session.uiRegistryQuotes()
            try {
                assertEquals(1, withTimeout(5_000) { ceilings.first() }?.size)
                assertEquals(1, withTimeout(5_000) { objects.first() }?.projects?.size)
                assertEquals(1L, withTimeout(5_000) { stats.first() }?.ceilingCount)
                assertEquals(0L, withTimeout(5_000) { quoteCount.first() })
                assertEquals(0L, withTimeout(5_000) { finance.first() })
                assertEquals(0, withTimeout(5_000) { quotes.first() }?.size)
                assertIs<RegistryRead.Ready<*>>(initial(ceilings))
                assertIs<RegistryRead.Ready<*>>(initial(objects))
            } finally {
                session.closeAndAwaitProjectReads()
            }
            assertEquals(RegistryRead.Unavailable, initial(ceilings))
            assertEquals(RegistryRead.Unavailable, initial(objects))
            assertEquals(RegistryRead.Unavailable, initial(stats))
            assertEquals(RegistryRead.Unavailable, initial(quoteCount))
            assertEquals(RegistryRead.Unavailable, initial(finance))
            assertEquals(RegistryRead.Unavailable, initial(quotes))
            assertNull(session.cachedRows)
            assertNull(withTimeout(5_000) { objects.first() })
            assertEquals(0, withTimeout(5_000) { session.rows.first() }.projects.size)
            assertEquals(0, withTimeout(5_000) { session.rows.first() }.clients.size)
            assertNull(withTimeout(5_000) { ceilings.first() })
            assertNull(withTimeout(5_000) { stats.first() })
            assertNull(withTimeout(5_000) { quoteCount.first() })
            assertNull(withTimeout(5_000) { finance.first() })
            assertNull(withTimeout(5_000) { quotes.first() })
            assertEquals(RegistryRead.Unavailable, initial(ceilings))
            assertEquals(RegistryRead.Unavailable, initial(objects))
            assertNull(session.cachedRows)
            val replacement = ObjectsClientsStore(db) { 200L }
            try {
                assertEquals(RegistryRead.Loading, initial(replacement.uiRegistryCeilings()))
                assertEquals(RegistryRead.Loading, initial(replacement.uiDashboardSummary()))
            } finally { replacement.closeAndAwaitProjectReads() }
        }
    }

    private fun <T : Any> initial(flow: Flow<T?>): RegistryRead<T> =
        (flow as CachedRegistryFlow<T>).initial
}
