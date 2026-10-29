package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class NumberedEstimateCompatibilityTest {
    private fun uuid(n: Int): String = "abcdef00-1234-4abc-8def-${n.toString(16).padStart(12, '0')}"

    private fun snapshot(total: Double = 100.0) = ImmutableEstimateDraft(
        sourceMeasurementJson = """{"name":"Kitchen","width":3,"length":4}""",
        detailsJson = """{"lineItems":[{"code":"canvas.pvc_matte","total":101},{"code":"system.rounding","total":-1}]}""",
        measurementPrecisionMeters = 0.01,
        calculationRuleVersion = "kmp-v1",
        totalCost = total,
        lines = listOf(
            ImmutableEstimateLine("canvas.pvc_matte", "Canvas", "m2", 1.0, 101.0, 101.0),
            ImmutableEstimateLine("system.rounding", "Rounding", "service", 1.0, -1.0, -1.0)
        )
    )

    @Test
    fun legacyProjectionAndRevisionAreAtomicAndRoundingIsSigned() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val projectId = ProjectRepository(db).createReturningId(null, "Office", createdAt = 1L)
            val estimates = EstimateRepository(db)
            val history = ImmutableEstimateRepository(db)
            fun create(number: Int): Long = estimates.createNumberedReturningId(
                projectId = projectId, name = "Kitchen", material = "pvc_matte",
                profileType = "standard_alu", complexity = "simple",
                lengthMeters = 3.0, widthMeters = 4.0, areaSquareMeters = 12.0, perimeterMeters = 14.0,
                canvasCost = 101.0, profileCost = 0.0, complexityCost = 0.0, optionsCost = 0.0,
                totalCost = 100.0, detailsJson = snapshot().detailsJson, createdAt = 2L,
                year = 2026, snapshot = snapshot(),
                newSeriesUuid = { uuid(number) }, newRevisionUuid = { uuid(number + 100) }
            )
            val firstId = create(1)
            val firstNumber = assertNotNull(estimates.documentNumberForId(firstId))
            assertEquals("AC-2026-00001-${uuid(1)}", firstNumber)
            assertEquals(uuid(1), estimates.find(firstId)?.seriesUuid)
            assertEquals(-1.0, history.lines(uuid(101)).last().lineTotalSnapshot)
            val copyId = create(2)
            assertNotEquals(firstNumber, estimates.documentNumberForId(copyId))

            val before = assertNotNull(estimates.find(firstId))
            estimates.update(firstId, "Kitchen renamed", before.material, before.profileType,
                before.complexity, before.lengthMeters, before.widthMeters, before.areaSquareMeters,
                before.perimeterMeters, before.canvasCost, before.profileCost, before.complexityCost,
                before.optionsCost, before.totalCost, before.detailsJson)
            assertEquals(firstNumber, estimates.documentNumberForId(firstId))
            assertEquals("Kitchen renamed", estimates.find(firstId)?.name)
            assertFails {
                estimates.update(firstId, "Kitchen renamed", before.material, before.profileType,
                    before.complexity, before.lengthMeters, before.widthMeters, before.areaSquareMeters,
                    before.perimeterMeters, before.canvasCost, before.profileCost, before.complexityCost,
                    before.optionsCost, 999.0, before.detailsJson)
            }
            assertEquals(100.0, estimates.find(firstId)?.totalCost)

            // A failed numbered creation must not leave an unnumbered legacy row.
            assertFails {
                estimates.createNumberedReturningId(
                    projectId, "Broken", "pvc_matte", "standard_alu", "simple",
                    3.0, 4.0, 12.0, 14.0, 101.0, 0.0, 0.0, 0.0, 100.0,
                    snapshot().detailsJson, 4L, 2026, snapshot(total = 999.0)
                )
            }
            assertEquals(2L, estimates.count())
            assertEquals(2, history.seriesForProject(projectId).size)

            // Fail AFTER the legacy row and series have been inserted: the
            // injected v1 revision UUID already exists. Neither row nor counter
            // may remain after the nested transaction aborts.
            assertFails {
                estimates.createNumberedReturningId(
                    projectId, "Broken revision", "pvc_matte", "standard_alu", "simple",
                    3.0, 4.0, 12.0, 14.0, 101.0, 0.0, 0.0, 0.0, 100.0,
                    snapshot().detailsJson, 4L, 2026, snapshot(),
                    newSeriesUuid = { uuid(9) }, newRevisionUuid = { uuid(101) }
                )
            }
            assertNull(history.series(uuid(9)))
            assertEquals(2L, estimates.count())
            assertEquals(2, history.seriesForProject(projectId).size)

            assertEquals(3L, history.createSeries(projectId, 2026, 5L, snapshot(),
                newSeriesUuid = { uuid(3) }, newRevisionUuid = { uuid(103) }).localSequence)

            estimates.delete(firstId)
            assertNull(estimates.find(firstId))
            assertNotNull(history.seriesByNumber(firstNumber)?.deletedAt)
            assertEquals(1, history.revisions(uuid(1)).size)
        }
    }

    @Test
    fun oldEstimateRecordsStayReadableAfterNewLinkedFields() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val projectId = ProjectRepository(db).createReturningId(null, "Old", createdAt = 10L)
            val repo = EstimateRepository(db)
            val id = repo.createReturningId(
                projectId, "Old quote", "pvc", "standard", "simple", 1.0, 1.0,
                1.0, 4.0, 50.0, 25.0, 0.0, 0.0, 75.0, createdAt = 11L
            )
            assertNull(repo.documentNumberForId(id))
            assertEquals("Old quote", repo.find(id)?.name)
            assertNull(repo.find(id)?.seriesUuid)
        }
    }

    @Test
    fun migratingV2ToV3LinksNumbersButKeepsExistingHistoryAndLegacyRows() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            // Full v2 schema from stage 3A, including all history and estimates.
            // Simulating the live transition from an installed v2 DB to v3.
            driver.execute(null, "CREATE TABLE projects(id INTEGER PRIMARY KEY, title TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO projects(id, title) VALUES (11, 'Existing')", 0)
            driver.execute(null, "CREATE TABLE estimates(id INTEGER PRIMARY KEY, projectId INTEGER NOT NULL, name TEXT NOT NULL, deletedAt INTEGER)", 0)
            driver.execute(null, "INSERT INTO estimates VALUES (21, 11, 'Old quote', NULL)", 0)
            driver.execute(null, "CREATE TABLE estimate_series(seriesUuid TEXT PRIMARY KEY, documentNumber TEXT UNIQUE, projectId INTEGER)", 0)
            driver.execute(null, "CREATE TABLE estimate_revisions(revisionUuid TEXT PRIMARY KEY, seriesUuid TEXT, versionNumber INTEGER)", 0)
            driver.execute(null, """CREATE TABLE estimate_revision_lines(
                revisionUuid TEXT NOT NULL, sortIndex INTEGER NOT NULL, stableCode TEXT NOT NULL,
                priceItemUuid TEXT, titleSnapshot TEXT NOT NULL, unitSnapshot TEXT NOT NULL,
                quantitySnapshot REAL NOT NULL, unitPriceSnapshot REAL NOT NULL CHECK (unitPriceSnapshot >= 0),
                lineTotalSnapshot REAL NOT NULL CHECK (lineTotalSnapshot >= 0),
                PRIMARY KEY(revisionUuid, sortIndex))""", 0)
            driver.execute(null, "INSERT INTO estimate_revision_lines VALUES('rev-a', 0, 'canvas.test', NULL, 'Canvas', 'm²', 2, 10, 20)", 0)
            PrototypeDatabase.Schema.migrate(driver, 2L, 3L)
            val count = driver.executeQuery(null, "SELECT COUNT(*) FROM estimate_revision_lines",
                { cursor -> app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) else 0L
                ) }, 0).value
            assertEquals(1L, count)
            val old = driver.executeQuery(null, "SELECT name FROM estimates WHERE id = 21",
                { cursor -> app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0) else null
                ) }, 0).value
            assertEquals("Old quote", old)
            driver.execute(null, """INSERT INTO estimate_revision_lines
                VALUES('rev-b', 0, 'system.rounding', NULL, 'Rounding', 'service', 1, -1, -1)""", 0)
        }
    }
}
