package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EstimateRecalculationRepositoryTest {
    @Test fun previewsCurrentPriceWithoutModifyingSavedV1() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(
                clientId = null, title = "Apartment", createdAt = 100L
            )
            val prices = PriceRepository(db)
            val priceId = prices.upsertReturningId(
                code = "canvas.pvc", title = "Old title", category = "canvas",
                unit = "m2", price = 10.0, updatedAt = 100L
            )
            val history = ImmutableEstimateRepository(db)
            val created = history.createSeries(
                projectId = project, year = 2026, createdAt = 200L,
                firstRevision = ImmutableEstimateDraft(
                    sourceMeasurementJson = """{"area":12}""",
                    detailsJson = """{"measurementPrecisionMeters":0.01}""",
                    measurementPrecisionMeters = 0.01,
                    calculationRuleVersion = "kmp-v1",
                    totalCost = 120.0,
                    lines = listOf(ImmutableEstimateLine(
                        stableCode = "canvas.pvc", title = "Old title",
                        unit = "m2", quantity = 12.0, unitPrice = 10.0,
                        lineTotal = 120.0
                    ))
                )
            )
            prices.upsertReturningId(
                id = priceId, code = "canvas.pvc", title = "Renamed",
                category = "canvas", unit = "m2", price = 20.0, updatedAt = 300L
            )
            val result = EstimateRecalculationRepository(db).previewLatest(created.seriesUuid)
            assertTrue(result.canConfirm)
            assertEquals(120.0, result.originalTotal)
            assertEquals(240.0, result.proposedTotal)
            assertEquals("Renamed", result.lines.single().after?.title)
            assertEquals(1, history.revisions(created.seriesUuid).size)
            assertEquals(120.0, history.revision(created.initialRevisionUuid)?.totalCost)
            assertEquals("Old title", history.lines(created.initialRevisionUuid).single().titleSnapshot)

            prices.delete(priceId)
            val missing = EstimateRecalculationRepository(db).previewLatest(created.seriesUuid)
            assertFalse(missing.canConfirm)
            assertEquals(setOf(RecalculationProblem.MISSING_PRICE), missing.problems)
            assertEquals(1, history.revisions(created.seriesUuid).size)
        }
    }
}
