package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImmutableEstimateRepositoryTest {
    private fun draft(amount: Double = 120.0, title: String = "Canvas") = ImmutableEstimateDraft(
        sourceMeasurementJson = """{"length":3,"width":4}""",
        detailsJson = """{"measurementPrecisionMeters":0.01}""",
        measurementPrecisionMeters = 0.01,
        calculationRuleVersion = "kmp-1",
        totalCost = amount,
        lines = listOf(ImmutableEstimateLine("material.pvc", title, "m2", 12.0, amount / 12, amount))
    )

    private fun uniqueUuid(number: Int) =
        "12345678-1234-4abc-8def-${number.toString(16).padStart(12, '0')}"

    private inline fun inDatabase(block: (PrototypeDatabase, Long) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val projectId = ProjectRepository(db).createReturningId(
                clientId = null, title = "Apartment", createdAt = 100L
            )
            block(db, projectId)
        }
    }

    @Test
    fun createsNumberAndFrozenRevisionInOneTransaction() = inDatabase { db, projectId ->
        val repo = ImmutableEstimateRepository(db)
        val first = repo.createSeries(
            projectId, 2026, 200L, draft(),
            newSeriesUuid = { uniqueUuid(1) }, newRevisionUuid = { uniqueUuid(2) }
        )
        assertEquals("AC-2026-00001-${uniqueUuid(1)}", first.documentNumber)
        val root = assertNotNull(repo.series(first.seriesUuid))
        assertEquals(first.documentNumber, root.documentNumber)
        assertEquals("{prefix}-{year}-{sequence}-{uuid}", root.policyTemplate)
        assertEquals(1L, root.localSequence)
        val v1 = repo.revisions(first.seriesUuid).single()
        assertEquals(1L, v1.versionNumber)
        assertNull(v1.parentRevisionUuid)
        assertEquals(120.0, v1.totalCost)
        assertEquals("Canvas", repo.lines(v1.revisionUuid).single().titleSnapshot)
        assertEquals(10.0, repo.lines(v1.revisionUuid).single().unitPriceSnapshot)
    }

    @Test
    fun preservesV1AndNumberWhenPriceChangesAndV2IsConfirmed() = inDatabase { db, projectId ->
        val repo = ImmutableEstimateRepository(db)
        val root = repo.createSeries(
            projectId, 2026, 300L, draft(),
            newSeriesUuid = { uniqueUuid(3) }, newRevisionUuid = { uniqueUuid(4) }
        )
        repo.configureNumberPolicy(EstimateNumberPolicy("{year}-{prefix}-{uuid}", "NEW"))
        val v2uuid = repo.appendRevision(
            root.seriesUuid, root.initialRevisionUuid, 400L, draft(240.0, "New canvas"),
            newRevisionUuid = { uniqueUuid(5) }
        )
        val revisions = repo.revisions(root.seriesUuid)
        assertEquals(listOf(1L, 2L), revisions.map { it.versionNumber })
        assertEquals(root.initialRevisionUuid, revisions.last().parentRevisionUuid)
        assertEquals(v2uuid, revisions.last().revisionUuid)
        assertEquals(120.0, revisions.first().totalCost)
        assertEquals(240.0, revisions.last().totalCost)
        assertEquals("Canvas", repo.lines(root.initialRevisionUuid).single().titleSnapshot)
        assertEquals("New canvas", repo.lines(v2uuid).single().titleSnapshot)
        assertEquals(root.documentNumber, repo.series(root.seriesUuid)?.documentNumber)
        assertFails {
            repo.appendRevision(root.seriesUuid, root.initialRevisionUuid, 500L, draft(300.0))
        }
        assertEquals(2, repo.revisions(root.seriesUuid).size)
        val next = repo.createSeries(
            projectId, 2026, 500L, draft(),
            newSeriesUuid = { uniqueUuid(6) }, newRevisionUuid = { uniqueUuid(7) }
        )
        assertEquals("2026-NEW-${uniqueUuid(6)}", next.documentNumber)
    }

    @Test
    fun intentionalSeriesUuidCollisionRetriesWithoutChangingExistingDocument() = inDatabase { db, projectId ->
        val repo = ImmutableEstimateRepository(db)
        val first = repo.createSeries(
            projectId, 2026, 1L, draft(),
            newSeriesUuid = { uniqueUuid(8) }, newRevisionUuid = { uniqueUuid(9) }
        )
        var calls = 0
        val second = repo.createSeries(
            projectId, 2026, 2L, draft(),
            newSeriesUuid = { if (calls++ == 0) uniqueUuid(8) else uniqueUuid(10) },
            newRevisionUuid = { uniqueUuid(11) }
        )
        assertTrue(calls >= 2)
        assertNotEquals(first.documentNumber, second.documentNumber)
        assertEquals(2L, second.localSequence)
        assertEquals(first.documentNumber, repo.series(first.seriesUuid)?.documentNumber)
        assertEquals(1, repo.revisions(first.seriesUuid).size)
    }

    @Test
    fun failureAfterNumberAllocationRollsBackWholeTransaction() = inDatabase { db, projectId ->
        val repo = ImmutableEstimateRepository(db)
        repo.createSeries(
            projectId, 2026, 1L, draft(),
            newSeriesUuid = { uniqueUuid(12) }, newRevisionUuid = { uniqueUuid(13) }
        )
        assertFails {
            repo.createSeries(
                projectId, 2026, 2L, draft(),
                newSeriesUuid = { uniqueUuid(14) },
                newRevisionUuid = { uniqueUuid(13) } // duplicate revision ID after allocating root
            )
        }
        assertNull(repo.series(uniqueUuid(14)))
        val next = repo.createSeries(
            projectId, 2026, 3L, draft(),
            newSeriesUuid = { uniqueUuid(15) }, newRevisionUuid = { uniqueUuid(16) }
        )
        assertEquals(2L, next.localSequence)
        assertEquals(2, repo.seriesForProject(projectId).size)
    }

    @Test
    fun deletionRetainsNumberReservationAndHistoricalSnapshots() = inDatabase { db, projectId ->
        val repo = ImmutableEstimateRepository(db)
        val first = repo.createSeries(
            projectId, 2026, 2L, draft(),
            newSeriesUuid = { uniqueUuid(17) }, newRevisionUuid = { uniqueUuid(18) }
        )
        repo.softDeleteSeries(first.seriesUuid, 55L)
        assertTrue(repo.seriesForProject(projectId).isEmpty())
        assertEquals(55L, repo.series(first.seriesUuid)?.deletedAt)
        assertEquals(first.seriesUuid, repo.seriesByNumber(first.documentNumber)?.seriesUuid)
        assertEquals(1, repo.revisions(first.seriesUuid).size)
        assertFails { repo.appendRevision(first.seriesUuid, first.initialRevisionUuid, 60L, draft()) }
    }

    @Test
    fun corruptInputOrMissingProjectCannotLeavePartialHistory() = inDatabase { db, projectId ->
        val repo = ImmutableEstimateRepository(db)
        assertFails {
            repo.createSeries(projectId, 2026, 1L, draft().copy(totalCost = 999.0))
        }
        assertFails {
            repo.createSeries(99999L, 2026, 1L, draft(),
                newSeriesUuid = { uniqueUuid(19) })
        }
        assertTrue(repo.seriesForProject(projectId).isEmpty())
        val first = repo.createSeries(
            projectId, 2026, 1L, draft(),
            newSeriesUuid = { uniqueUuid(20) }, newRevisionUuid = { uniqueUuid(21) }
        )
        assertEquals(1L, first.localSequence)
    }

    @Test
    fun additiveSqlDelightMigrationKeepsExistingProjectData() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            // Minimal old schema dependency; migrations must not DROP or rewrite projects.
            driver.execute(null, "CREATE TABLE projects (id INTEGER PRIMARY KEY, title TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO projects(id, title) VALUES (1, 'Before upgrade')", 0)
            PrototypeDatabase.Schema.migrate(driver, 1L, 2L)
            val oldProject = driver.executeQuery(null, "SELECT title FROM projects WHERE id = 1",
                { cursor -> app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0) else null
                ) }, 0).value
            assertEquals("Before upgrade", oldProject)
            val newTableCount = driver.executeQuery(null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'estimate_%'",
                { cursor -> app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) else 0L
                ) }, 0).value
            assertEquals(4L, newTableCount)
        }
    }
}
