package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.*

class EstimateNumberSettingsPersistenceTest {
    private val date = LocalDate(2026, 11, 26)

    @Test fun settingsAndReservationsSurviveReopenWithoutRenumbering() {
        val file = Files.createTempFile("estimate-numbering", ".db").toFile()
        val path = "jdbc:sqlite:${file.absolutePath}"
        try {
            val first = JdbcSqliteDriver(path).use { driver ->
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val repo = ImmutableEstimateRepository(db)
                val fixture = MeasurementPricingFixture.seed(db)
                val defaults = repo.issuancePolicy()
                assertEquals(EstimateIssuancePolicy(), defaults)
                assertEquals(EstimateNumberPreview("КШ-2026-0001", "ЗК-2026-0001", false), repo.previewNumbers(date))
                assertTrue(db.estimateIssuanceQueries.selectAllReservations().executeAsList().isEmpty())
                val issued = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), date, 1000)
                val custom = defaults.copy(template = "{prefix}//{day}.{month}.{yearShort}-{sequence}", sequenceWidth = 2)
                assertTrue(repo.configureIssuancePolicy(custom, defaults))
                assertFalse(repo.configureIssuancePolicy(defaults, defaults))
                assertFailsWith<IllegalArgumentException> {
                    repo.configureIssuancePolicy(custom.copy(consolidatedPrefix = "кш"))
                }
                issued
            }
            JdbcSqliteDriver(path).use { driver ->
                val db = PrototypeDatabase(driver)
                val repo = ImmutableEstimateRepository(db)
                verifyFrozenEstimateContents(driver)
                assertEquals("КШ-2026-0001", repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid)?.frozen?.publicNumber)
                assertEquals("2026-11-26", db.estimateIssuanceQueries.selectReservation(first.seriesUuid).executeAsOne().issueDate)
                assertEquals("КШ//26.11.26-02", repo.previewNumbers(date).individual)
                val room = MeasurementPricingFixture.seed(db)
                val second = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, room), date, 1001)
                assertEquals(2L, second.localSequence)
                assertEquals("КШ//26.11.26-02", repo.series(second.seriesUuid)?.publicNumber)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun bothTypesShareHighWaterOnlyInCommonMode() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val repo = ImmutableEstimateRepository(db)
            val consolidatedRepository = ConsolidatedEstimateRepository(db)
            fun individual(): Long {
                val room = MeasurementPricingFixture.seed(db)
                return repo.saveIndividual(FrozenIndividualEstimateProof.command(db, room), date, 1000).localSequence
            }
            fun consolidated(): Long {
                // Source numbers belong to a different year and cannot alter the tested annual counters.
                val source = issueNumberTestIndividual(db, LocalDate(2025, 11, 26))
                val saved = consolidatedRepository.save(numberTestConsolidatedCommand(db, source), date, 1001)
                return db.estimateIssuanceQueries.selectReservation(saved.seriesUuid).executeAsOne().sequence
            }
            assertEquals(1L, individual())
            assertEquals(1L, consolidated())
            assertEquals(2L, consolidated())
            assertTrue(repo.configureIssuancePolicy(repo.issuancePolicy().copy(mode = EstimateCounterMode.COMMON)))
            assertEquals(EstimateNumberPreview("КШ-2026-0003", "ЗК-2026-0003", true), repo.previewNumbers(date))
            assertEquals(3L, individual())
            assertEquals(4L, consolidated())
            assertTrue(repo.configureIssuancePolicy(EstimateIssuancePolicy()))
            assertEquals(4L, individual())
            assertEquals(5L, consolidated())
            assertEquals(EstimateNumberPreview("КШ-2027-0001", "ЗК-2027-0001", false),
                repo.previewNumbers(LocalDate(2027, 1, 1)))
            verifyFrozenEstimateContents(driver)
        }
    }
}
