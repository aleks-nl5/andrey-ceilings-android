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

    @Test fun previewTokenRejectsStalePriceAndSourceStateWithoutWritingAnything() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(
                clientId = null, title = "Token project", createdAt = 100L
            )
            val prices = PriceRepository(db)
            val item = prices.upsertReturningId(
                code = "canvas.test", title = "Original", category = "canvas",
                unit = "m2", price = 50.0, updatedAt = 100L
            )
            val history = ImmutableEstimateRepository(db)
            val draft = ImmutableEstimateDraft(
                sourceMeasurementJson = """{"area":2}""",
                detailsJson = """{"measurementPrecisionMeters":0.01}""",
                measurementPrecisionMeters = 0.01,
                calculationRuleVersion = "kmp-v1",
                totalCost = 100.0,
                lines = listOf(ImmutableEstimateLine(
                    stableCode = "canvas.test", title = "Original",
                    unit = "m2", quantity = 2.0, unitPrice = 50.0, lineTotal = 100.0
                ))
            )
            val series = history.createSeries(
                projectId = project, year = 2026, createdAt = 200L, firstRevision = draft
            )
            val recalculation = EstimateRecalculationRepository(db)
            val preview = recalculation.previewLatest(series.seriesUuid)
            assertTrue(preview.canConfirm)
            assertTrue(preview.priceListStateToken.isNotBlank())
            assertTrue(recalculation.isPreviewCurrent(series.seriesUuid, preview))

            // Same updatedAt but different values must still invalidate it.
            prices.upsertReturningId(
                id = item, code = "canvas.test", title = "Renamed",
                category = "canvas", unit = "m2", price = 51.0, updatedAt = 100L
            )
            assertFalse(recalculation.isPreviewCurrent(series.seriesUuid, preview))
            val renamed = recalculation.previewLatest(series.seriesUuid)
            assertTrue(recalculation.isPreviewCurrent(series.seriesUuid, renamed))

            // An unrelated new price entry also invalidates the catalog token.
            val unrelated = prices.upsertReturningId(
                code = "lights.test", title = "Light", category = "lights",
                unit = "pcs", price = 25.0, updatedAt = 100L
            )
            assertFalse(recalculation.isPreviewCurrent(series.seriesUuid, renamed))
            val withExtra = recalculation.previewLatest(series.seriesUuid)
            prices.delete(unrelated)
            assertFalse(recalculation.isPreviewCurrent(series.seriesUuid, withExtra))

            // Source revision is checked separately from price-list state.
            val current = recalculation.previewLatest(series.seriesUuid)
            assertTrue(recalculation.isPreviewCurrent(series.seriesUuid, current))
            history.appendRevision(
                seriesUuid = series.seriesUuid,
                expectedLatestRevisionUuid = series.initialRevisionUuid,
                createdAt = 300L,
                snapshot = draft
            )
            assertFalse(recalculation.isPreviewCurrent(series.seriesUuid, current))
            assertEquals(2, history.revisions(series.seriesUuid).size)
        }
    }

    @Test fun transactionRejectsStaleAndModifiedPreviewsBeforeAnyRevisionWrite() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(
                clientId = null, title = "Guarded preview", createdAt = 100L
            )
            val prices = PriceRepository(db)
            val item = prices.upsertReturningId(
                code = "canvas.safe", title = "Old", category = "canvas",
                unit = "m2", price = 10.0, updatedAt = 100L
            )
            val history = ImmutableEstimateRepository(db)
            val snapshot = ImmutableEstimateDraft(
                sourceMeasurementJson = """{"area":3}""",
                detailsJson = """{"measurementPrecisionMeters":0.01}""",
                measurementPrecisionMeters = 0.01,
                calculationRuleVersion = "kmp-v1",
                totalCost = 30.0,
                lines = listOf(ImmutableEstimateLine(
                    "canvas.safe", "Old", "m2", 3.0, 10.0, 30.0
                ))
            )
            val series = history.createSeries(
                projectId = project, year = 2026,
                createdAt = 200L, firstRevision = snapshot
            )
            val recalculation = EstimateRecalculationRepository(db)
            val preview = recalculation.previewLatest(series.seriesUuid)
            assertTrue(preview.canConfirm)

            // A changed preview cannot be smuggled through even when the
            // latest revision and entire price-list token are still valid.
            val tampered = preview.copy(proposedTotal = 999.0)
            kotlin.test.assertFails {
                recalculation.withVerifiedPreviewTransaction(series.seriesUuid, tampered) {
                    history.appendRevision(
                        series.seriesUuid, preview.baseRevisionUuid, 250L, snapshot
                    )
                }
            }
            assertEquals(1, history.revisions(series.seriesUuid).size)

            // Change title and price but keep the timestamp equal. A
            // timestamp-only optimistic check would miss this edit.
            prices.upsertReturningId(
                id = item, code = "canvas.safe", title = "New",
                category = "canvas", unit = "m2",
                price = 20.0, updatedAt = 100L
            )
            kotlin.test.assertFails {
                recalculation.withVerifiedPreviewTransaction(series.seriesUuid, preview) {
                    history.appendRevision(
                        series.seriesUuid, preview.baseRevisionUuid, 250L, snapshot
                    )
                }
            }
            assertEquals(1, history.revisions(series.seriesUuid).size)

            // A valid preview can enter the guarded transaction. Inject
            // failure AFTER the nested append; the outer transaction must
            // still roll back its revision and every revision line.
            val updated = recalculation.previewLatest(series.seriesUuid)
            val revisedSnapshot = snapshot.copy(
                totalCost = 60.0,
                lines = listOf(ImmutableEstimateLine(
                    "canvas.safe", "New", "m2", 3.0, 20.0, 60.0
                ))
            )
            val deliberate = IllegalStateException("Simulated failed confirmation")
            val failed = runCatching {
                recalculation.withVerifiedPreviewTransaction(series.seriesUuid, updated) {
                    history.appendRevision(
                        series.seriesUuid, updated.baseRevisionUuid, 300L, revisedSnapshot
                    )
                    throw deliberate
                }
            }
            assertTrue(failed.exceptionOrNull() === deliberate)
            assertEquals(1, history.revisions(series.seriesUuid).size)
            assertEquals(30.0, history.revision(series.initialRevisionUuid)?.totalCost)

            // Successful KMP transaction appends exactly one immutable
            // child. PDF/detailsJson migration is deliberately deferred.
            val newRevisionId = recalculation.withVerifiedPreviewTransaction(
                series.seriesUuid, updated
            ) {
                history.appendRevision(
                    series.seriesUuid, updated.baseRevisionUuid, 400L, revisedSnapshot
                )
            }
            assertEquals(2, history.revisions(series.seriesUuid).size)
            assertEquals(60.0, history.revision(newRevisionId)?.totalCost)
            assertEquals(30.0, history.revision(series.initialRevisionUuid)?.totalCost)
            assertFalse(recalculation.isPreviewCurrent(series.seriesUuid, updated))
            kotlin.test.assertFails {
                recalculation.withVerifiedPreviewTransaction(series.seriesUuid, updated) {
                    history.appendRevision(
                        series.seriesUuid, updated.baseRevisionUuid, 500L, revisedSnapshot
                    )
                }
            }
            assertEquals(2, history.revisions(series.seriesUuid).size)
        }
    }
    @Test fun sameUuidWithChangedPriceCodeBlocksTransactionalRevisionInsert() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(
                clientId = null, title = "Semantic price identity", createdAt = 100L
            )
            val prices = PriceRepository(db)
            val priceId = prices.upsertReturningId(
                code = "canvas.pvc", title = "Canvas PVC", category = "canvas",
                unit = "m²", price = 100.0, updatedAt = 100L
            )
            val savedPrice = prices.find(priceId)!!
            val history = ImmutableEstimateRepository(db)
            val source = ImmutableEstimateDraft(
                sourceMeasurementJson = """{"shape":"rectangle","area":3}""",
                detailsJson = """{"measurementPrecisionMeters":0.01}""",
                measurementPrecisionMeters = 0.01,
                calculationRuleVersion = "kmp-v1",
                totalCost = 300.0,
                lines = listOf(ImmutableEstimateLine(
                    "canvas.pvc", "Canvas PVC", "m²", 3.0, 100.0, 300.0,
                    priceItemUuid = savedPrice.uuid
                ))
            )
            val series = history.createSeries(
                projectId = project, year = 2026, createdAt = 200L,
                firstRevision = source
            )
            val recalculation = EstimateRecalculationRepository(db)
            val validPreview = recalculation.previewLatest(series.seriesUuid)
            assertTrue(validPreview.canConfirm)

            // The *same* catalog row/UUID now represents another material.
            // Even if the numeric price and unit stay equal, v2 is unsafe.
            prices.upsertReturningId(
                id = priceId, code = "canvas.fabric", title = "Different fabric",
                category = "canvas", unit = "m²", price = 100.0, updatedAt = 100L
            )
            val blocked = recalculation.previewLatest(series.seriesUuid)
            assertFalse(blocked.canConfirm)
            assertEquals(setOf(RecalculationProblem.CHANGED_CODE), blocked.problems)
            val attempted = runCatching {
                recalculation.withVerifiedPreviewTransaction(series.seriesUuid, blocked) {
                    history.appendRevision(
                        series.seriesUuid, blocked.baseRevisionUuid, 300L, source
                    )
                }
            }
            assertTrue(attempted.isFailure)
            assertFalse(recalculation.isPreviewCurrent(series.seriesUuid, validPreview))
            assertEquals(1, history.revisions(series.seriesUuid).size)
            assertEquals(300.0, history.revision(series.initialRevisionUuid)?.totalCost)
        }
    }

}
