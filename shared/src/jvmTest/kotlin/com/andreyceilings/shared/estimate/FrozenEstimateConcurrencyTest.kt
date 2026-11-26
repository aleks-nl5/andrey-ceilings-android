package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class FrozenEstimateConcurrencyTest {
    @Test fun deletedShortYearNumberCannotBeReissuedByAnotherType() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val repo = ImmutableEstimateRepository(db)
            val allocator = EstimateNumberAllocator(db)
            val before = kotlinx.datetime.LocalDate(2026, 12, 31)
            val later = kotlinx.datetime.LocalDate(2126, 1, 1)
            val short = "{prefix}/{yearShort}/{sequence}"
            repo.configureIssuancePolicy(EstimateIssuancePolicy(template = short))
            val room = MeasurementPricingFixture.seed(db)
            val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, room), before, 700)
            assertTrue(repo.deleteIndividualSeries(first.seriesUuid, mapOf(first.initialRevisionUuid to 1L), 701))
            assertFails {
                driver.execute(null, "UPDATE estimate_number_reservations SET sequence=1.5", 0)
            }
            repo.configureIssuancePolicy(EstimateIssuancePolicy(
                individualPrefix = "NEW", consolidatedPrefix = "КШ", template = short
            ))
            assertEquals("КШ/26/0002", repo.previewNumbers(later).consolidated)
            assertFails {
                allocator.issue(EstimateType.CONSOLIDATED, later) { error("Injected document write failure") }
            }
            assertEquals("КШ/26/0002", repo.previewNumbers(later).consolidated)
            val source = issueNumberTestIndividual(db, later)
            val stored = ConsolidatedEstimateRepository(db).save(numberTestConsolidatedCommand(db, source), later, 702)
            val reserved = db.estimateIssuanceQueries.selectReservation(stored.seriesUuid).executeAsOne()
            assertEquals(2L, reserved.sequence)
            assertEquals("КШ/26/0002", reserved.publicNumber)
            assertEquals("КШ/26/0001", repo.series(first.seriesUuid)?.publicNumber)
            verifyFrozenEstimateContents(driver)
        }
    }
    @Test fun exhaustedCommonAnnualSequenceRejectsSaveWithoutPartialWrites() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val repo = ImmutableEstimateRepository(db)
            val allocator = EstimateNumberAllocator(db)
            val date = LocalDate(2026, 11, 26)
            val policy = EstimateIssuancePolicy(mode = EstimateCounterMode.COMMON)
            assertTrue(repo.configureIssuancePolicy(policy))
            val room = MeasurementPricingFixture.seed(db)
            val command = FrozenIndividualEstimateProof.command(db, room)
            // Build real consolidated provenance at the maximum sequence. A temporary
            // synthetic predecessor makes the boundary reachable without billions of writes.
            // The predecessor must never survive into the valid database fixture.
            val previousYear = LocalDate(2025, 11, 26)
            val source = issueNumberTestIndividual(db, previousYear)
            val otherSource = issueNumberTestIndividual(db, previousYear)
            val consolidated = ConsolidatedEstimateRepository(db)
            val consolidatedCommand = numberTestConsolidatedCommand(db, otherSource)
            val resolved = policy.resolve(EstimateType.CONSOLIDATED)
            val issuance = db.estimateIssuanceQueries
            val predecessor = newRecordUuid()
            issuance.insertReservation(
                predecessor, EstimateNumbering.render(resolved, date, Long.MAX_VALUE - 1),
                EstimateType.CONSOLIDATED.name, date.toString(), date.year.toLong(),
                Long.MAX_VALUE - 1, frozenEstimateJson.encodeToString(resolved), policy.mode.name
            )
            assertFailsWith<IllegalStateException> { verifyFrozenEstimateContents(driver) }
            val exhausted = consolidated.save(numberTestConsolidatedCommand(db, source), date, 699)
            assertEquals(Long.MAX_VALUE, issuance.selectReservation(exhausted.seriesUuid).executeAsOne().sequence)
            driver.execute(null, "DELETE FROM estimate_number_reservations WHERE seriesUuid = ?", 1) {
                bindString(0, predecessor)
            }
            verifyFrozenEstimateContents(driver)
            fun documentCounts() = EstimateV22MigrationProof.counts(driver) +
                listOf("consolidated_series", "consolidated_revisions", "consolidated_members", "consolidated_workflow")
                    .associateWith { PriceNameUniquenessProof.scalar(driver, "SELECT count(*) FROM $it") }
            val before = issuance.selectAllReservations().executeAsList()
            val beforeDocuments = documentCounts()
            val beforeConsolidated = assertNotNull(consolidated.read(exhausted.revisionUuid))
            val failure = assertFailsWith<IndividualEstimateSaveFailure> {
                repo.saveIndividual(command, date, 700)
            }
            assertEquals(IndividualEstimateSaveFailure.Reason.SEQUENCE_EXHAUSTED, failure.reason)
            val consolidatedFailure = assertFailsWith<IndividualEstimateSaveFailure> {
                allocator.issue(EstimateType.CONSOLIDATED, date) {
                    fail("An exhausted counter must not invoke the writer")
                }
            }
            assertEquals(IndividualEstimateSaveFailure.Reason.SEQUENCE_EXHAUSTED, consolidatedFailure.reason)
            val actualConsolidatedFailure = assertFailsWith<IndividualEstimateSaveFailure> {
                consolidated.save(consolidatedCommand, date, 700)
            }
            assertEquals(IndividualEstimateSaveFailure.Reason.SEQUENCE_EXHAUSTED, actualConsolidatedFailure.reason)
            assertEquals(before, issuance.selectAllReservations().executeAsList())
            assertEquals(beforeDocuments, documentCounts())
            assertEquals(beforeConsolidated.frozen, consolidated.read(exhausted.revisionUuid)?.frozen)
            assertNull(consolidated.head(otherSource.projectId))
            verifyFrozenEstimateContents(driver)
            // Exhaustion is annual: the same command can succeed in the next year.
            val nextDate = LocalDate(2027, 1, 1)
            val saved = repo.saveIndividual(command, nextDate, 701)
            assertEquals(1L, saved.localSequence)
            assertEquals("КШ-2027-0001", repo.series(saved.seriesUuid)?.publicNumber)
            val nextConsolidated = consolidated.save(consolidatedCommand, nextDate, 702)
            assertEquals(2L, issuance.selectReservation(nextConsolidated.seriesUuid).executeAsOne().sequence)
            verifyFrozenEstimateContents(driver)
        }
    }

    @Test fun distinctOperationsAcrossConnectionsGetDistinctSequentialNumbers() {
        val directory = Files.createTempDirectory("estimate-distinct").toFile()
        val file = directory.resolve("quote.db")
        val pool = Executors.newFixedThreadPool(2)
        val path = "jdbc:sqlite:${file.absolutePath}"
        try {
            JdbcSqliteDriver(path).use { driver ->
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val rooms = List(2) { MeasurementPricingFixture.seed(db) }
                val requests = rooms.map { FrozenIndividualEstimateProof.command(db, it) }
                val start = CountDownLatch(1)
                val tasks = requests.map { request ->
                    pool.submit<CreatedEstimateSeries> {
                        JdbcSqliteDriver(path).use { connection ->
                            connection.execute(null, "PRAGMA busy_timeout = 10000", 0)
                            assertTrue(start.await(10, TimeUnit.SECONDS))
                            ImmutableEstimateRepository(PrototypeDatabase(connection)).saveIndividual(request, 2026, 700)
                        }
                    }
                }
                start.countDown()
                val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(setOf(1L, 2L), results.map { it.localSequence }.toSet())
                assertEquals(2, results.map { it.documentNumber }.distinct().size)
                verifyFrozenEstimateContents(driver)
            }
        } finally {
            pool.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun sameOperationAcrossConnectionsCreatesOneNumberAndOneV1() {
        val directory = Files.createTempDirectory("estimate-race").toFile()
        val file = directory.resolve("quote.db")
        val pool = Executors.newFixedThreadPool(2)
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val command = FrozenIndividualEstimateProof.command(db, fixture)
                val start = CountDownLatch(1)
                val tasks = (1..2).map { pool.submit<CreatedEstimateSeries> {
                    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { d ->
                        d.execute(null, "PRAGMA busy_timeout = 10000", 0)
                        start.await()
                        ImmutableEstimateRepository(PrototypeDatabase(d)).saveIndividual(command, 2026, 700)
                    }
                } }
                start.countDown()
                val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(results.first(), results.last())
                assertEquals(1, db.frozenEstimateQueries.selectFrozenV1List().executeAsList().size)
                assertEquals(2, nextIndividualSequence(db))
            }
        } finally { pool.shutdownNow(); directory.deleteRecursively() }
    }
}

/** Real individual source and matching command for v24 cross-type number tests. */
internal fun issueNumberTestIndividual(db: PrototypeDatabase, date: LocalDate): SavedIndividualQuote {
    val fixture = MeasurementPricingFixture.seed(db)
    val repository = ImmutableEstimateRepository(db)
    val identity = repository.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), date, 600)
    return assertNotNull(repository.exactIndividual(identity.seriesUuid, identity.initialRevisionUuid))
}

internal fun numberTestConsolidatedCommand(db: PrototypeDatabase, source: SavedIndividualQuote): ConsolidatedSave {
    val project = assertNotNull(ProjectRepository(db).find(source.projectId))
    return ConsolidatedSave(newRecordUuid(), project.id, project.uuid, null, null,
        ConsolidatedDraft("Numbering proof",
            ConsolidatedDiscount(com.andreyceilings.shared.livepricing.QuoteDiscountMode.FIXED, 0),
            listOf(ConsolidatedDocumentRef(source.frozen.source.measurementUuid, source.seriesUuid,
                source.revisionUuid, source.frozen.consolidatedSourceProof()))))
}
