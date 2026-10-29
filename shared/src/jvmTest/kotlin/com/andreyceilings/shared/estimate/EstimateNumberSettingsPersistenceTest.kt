package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EstimateNumberSettingsPersistenceTest {
    private fun uuid(n: Int) = "12345678-1234-4abc-8def-${n.toString(16).padStart(12, '0')}"

    private fun frozen() = ImmutableEstimateDraft(
        sourceMeasurementJson = """{"length":3,"width":4}""",
        detailsJson = """{"lineItems":[{"title":"Canvas","total":120}]}""",
        measurementPrecisionMeters = 0.01,
        calculationRuleVersion = "kmp-v1",
        totalCost = 120.0,
        lines = listOf(ImmutableEstimateLine("canvas.pvc", "Canvas", "m2", 12.0, 10.0, 120.0))
    )

    @Test
    fun changingTemplateOnlyAffectsNewDocumentsAndPreservesCounter() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val projectId = ProjectRepository(db).createReturningId(null, "Home", createdAt = 1L)
            val repo = ImmutableEstimateRepository(db)

            assertEquals(EstimateNumberPolicy(), repo.numberPolicy())
            val first = repo.createSeries(
                projectId, 2026, 100L, frozen(),
                newSeriesUuid = { uuid(1) }, newRevisionUuid = { uuid(11) }
            )
            assertEquals("AC-2026-00001-${uuid(1)}", first.documentNumber)
            val custom = EstimateNumberPolicy(
                template = "{year}_{prefix}_{sequence}_{uuid}",
                prefix = "NL",
                sequenceWidth = 3
            )
            val preview = EstimateNumbering.preview(custom)
            assertTrue(preview.contains("2026_NL_001_"))
            // Preview does not advance the persisted sequence.
            assertEquals(1L, first.localSequence)

            repo.configureNumberPolicy(custom)
            assertEquals(custom, repo.numberPolicy())
            assertEquals(first.documentNumber, repo.series(first.seriesUuid)?.documentNumber)
            assertEquals(EstimateNumberPolicy().template,
                repo.series(first.seriesUuid)?.policyTemplate)

            val second = repo.createSeries(
                projectId, 2026, 200L, frozen(),
                newSeriesUuid = { uuid(2) }, newRevisionUuid = { uuid(12) }
            )
            assertEquals("2026_NL_002_${uuid(2)}", second.documentNumber)
            assertEquals(custom.template, repo.series(second.seriesUuid)?.policyTemplate)
            assertEquals(custom.prefix, repo.series(second.seriesUuid)?.policyPrefix)
            assertEquals(custom.sequenceWidth.toLong(),
                repo.series(second.seriesUuid)?.policySequenceWidth)
            assertNotEquals(first.documentNumber, second.documentNumber)

            for (invalid in listOf(
                custom.copy(template = "{year}"),
                custom.copy(template = "{uuid}-{uuid}"),
                custom.copy(prefix = "../../unsafe"),
                custom.copy(sequenceWidth = 13)
            )) {
                assertFailsWith<IllegalArgumentException> {
                    repo.configureNumberPolicy(invalid)
                }
                assertEquals(custom, repo.numberPolicy())
                assertEquals(first.documentNumber, repo.series(first.seriesUuid)?.documentNumber)
            }

            repo.softDeleteSeries(first.seriesUuid, 250L)
            assertEquals(first.seriesUuid, repo.seriesByNumber(first.documentNumber)?.seriesUuid)
            val third = repo.createSeries(
                projectId, 2026, 300L, frozen(),
                newSeriesUuid = { uuid(3) }, newRevisionUuid = { uuid(13) }
            )
            assertEquals("2026_NL_003_${uuid(3)}", third.documentNumber)
            assertEquals(250L, repo.series(first.seriesUuid)?.deletedAt)
            assertEquals(1L, repo.revisions(first.seriesUuid).single().versionNumber)
            assertEquals(1L, repo.revisions(third.seriesUuid).single().versionNumber)
        }
    }

    @Test
    fun printableSettingCreatesNewShortNumbersWithoutModifyingPreviouslyIssuedCopies() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val projectId = ProjectRepository(db).createReturningId(null, "Work", createdAt = 1L)
            val repo = ImmutableEstimateRepository(db)
            val legacy = repo.createSeries(
                projectId, 2026, 100L, frozen(),
                newSeriesUuid = { uuid(20) }, newRevisionUuid = { uuid(21) }
            )
            val before = EstimateNumbering.printable(legacy.documentNumber)
            assertEquals("AC-2026-00001", before)
            val visible = EstimateNumberPolicy("{prefix}-{year}-{sequence}", "КШ", 4)
            repo.configureNumberPolicy(visible)
            val created = repo.createSeries(
                projectId, 2026, 200L, frozen(),
                newSeriesUuid = { uuid(22) }, newRevisionUuid = { uuid(23) }
            )
            assertEquals("КШ-2026-0002", EstimateNumbering.printable(created.documentNumber))
            assertTrue(created.documentNumber.contains(uuid(22)))
            assertEquals(before, EstimateNumbering.printable(repo.series(legacy.seriesUuid)!!.documentNumber))
            assertEquals(visible, repo.numberPolicy())
        }
    }

    @Test
    fun uuidOnlyPresetKeepsStoredSequenceWithoutExposingItInTheDocumentNumber() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val db = PrototypeDatabase(driver)
            val projectId = ProjectRepository(db).createReturningId(null, "Shop", createdAt = 1L)
            val repo = ImmutableEstimateRepository(db)
            repo.configureNumberPolicy(EstimateNumberPolicy("{uuid}", "AC"))
            val created = repo.createSeries(
                projectId, 2026, 100L, frozen(),
                newSeriesUuid = { uuid(5) }, newRevisionUuid = { uuid(15) }
            )
            assertEquals(uuid(5), created.documentNumber)
            assertEquals(1L, created.localSequence)
            assertEquals("{uuid}", repo.series(created.seriesUuid)?.policyTemplate)
        }
    }
}
