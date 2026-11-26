package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.EstimateWorkflowStatus.*
import com.andreyceilings.shared.livepricing.PricingOverflow
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import com.andreyceilings.shared.objects.ObjectsClientsStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ConsolidatedEstimateRepositoryTest {
    private val date = LocalDate(2026, 11, 26)
    private val discount = ConsolidatedDiscount(QuoteDiscountMode.FIXED, 101)

    @Test fun versionsUseSharedNumberingAndRemainExactAfterDeletion() = withDatabase { driver, db ->
        val fixture = Fixture(db)
        val first = fixture.individual(common = true)
        val second = fixture.individual()
        val command = fixture.command(listOf(second, first))
        val stored = fixture.consolidated.save(command, date, 750)
        val saved = assertNotNull(fixture.consolidated.read(stored.revisionUuid)).frozen
        assertEquals(1L, stored.version)
        assertEquals(3L, db.estimateIssuanceQueries.selectReservation(stored.seriesUuid).executeAsOne().sequence)
        assertEquals(listOf(second.revisionUuid, first.revisionUuid), saved.members.map { it.ref.revisionUuid })
        assertEquals(first.frozen.amounts.finalCents + second.frozen.amounts.finalCents - 101L, saved.amounts.finalCents)
        assertEquals(stored, fixture.consolidated.save(command, date, 751))
        assertFailsWith<ConsolidatedConflict> {
            fixture.consolidated.save(command.copy(draft = command.draft.copy(title = "Changed")), date, 752)
        }
        assertFailsWith<DuplicateConsolidatedContent> {
            fixture.consolidated.save(command.copy(operationUuid = newRecordUuid(), expectedHead = stored.revisionUuid,
                basedOn = stored.revisionUuid), date, 752)
        }
        val alternative = fixture.command(listOf(first), "Second", stored.revisionUuid, stored.revisionUuid)
        val v2 = fixture.consolidated.save(alternative, date, 753)
        val v3 = fixture.consolidated.save(
            fixture.command(listOf(second), "Branch from v1", v2.revisionUuid, stored.revisionUuid), date, 754)
        assertEquals(v2.revisionUuid, fixture.consolidated.read(v3.revisionUuid)?.frozen?.parentRevisionUuid)
        assertEquals(saved.publicNumber, fixture.consolidated.read(v3.revisionUuid)?.frozen?.publicNumber)
        assertTrue(fixture.consolidated.delete(fixture.projectId,
            listOf(stored, v2, v3).associate { it.revisionUuid to 1L }, true, 760))
        assertNull(fixture.consolidated.read(stored.revisionUuid))
        assertTrue(fixture.individuals.deleteIndividualSeries(second.seriesUuid, mapOf(second.revisionUuid to 1L), 761))
        val historical = assertNotNull(fixture.consolidated.read(stored.revisionUuid, historical = true)).document()
        assertEquals(saved.amounts.finalCents, historical.totalCents)
        assertEquals(listOf(second.frozen.publicNumber, first.frozen.publicNumber), historical.sections.map { it.document.publicNumber })
        assertFailsWith<ConsolidatedConflict> {
            fixture.consolidated.save(fixture.command(listOf(second), "Deleted source", v3.revisionUuid), date, 762)
        }
        assertFailsWith<DuplicateConsolidatedContent> {
            fixture.consolidated.save(alternative.copy(operationUuid = newRecordUuid(), expectedHead = v3.revisionUuid,
                basedOn = null), date, 762)
        }
        val continued = fixture.consolidated.save(fixture.command(listOf(first), "After deletion", v3.revisionUuid),
            LocalDate(2027, 1, 1), 763)
        assertEquals(stored.seriesUuid, continued.seriesUuid)
        assertEquals(4L, continued.version)
        assertEquals(saved.publicNumber, fixture.consolidated.read(continued.revisionUuid)?.frozen?.publicNumber)
        assertEquals(3, db.estimateIssuanceQueries.selectAllReservations().executeAsList().size)
        verifyFrozenEstimateContents(driver)
    }

    @Test fun approvalReplacementDeapprovalAndObjectFinanceAreAtomic() = withDatabase { driver, db ->
        val f = Fixture(db)
        val a = f.individual()
        val b = f.individual()
        val c = f.individual()
        assertTrue(f.individuals.setWorkflowStatus(c.revisionUuid, 1, APPROVED, 701))
        assertTrue(f.individuals.setWorkflowStatus(a.revisionUuid, 1, REJECTED, 701))
        val first = f.consolidated.save(f.command(listOf(a, b)), date, 750)
        val approve = assertNotNull(f.consolidated.confirmation(first.revisionUuid, APPROVED))
        assertTrue(f.consolidated.changeStatus(approve, false, 751))
        assertEquals(APPROVED, f.status(a))
        assertEquals(APPROVED, f.status(b))
        assertEquals(APPROVED, f.status(c))
        val ceiling = assertNotNull(MeasurementRepository(db).findByUuid(a.frozen.source.measurementUuid))
        assertFails { MeasurementRepository(db).delete(ceiling.id) }
        assertFails { ProjectRepository(db).delete(f.projectId) }
        assertEquals(APPROVED, f.status(a))
        val store = ObjectsClientsStore(db) { 754 }
        val summary = runBlocking { store.observeProjectQuoteSummary(f.projectId).filterNotNull().first() }
        assertEquals(a.frozen.amounts.finalCents + b.frozen.amounts.finalCents + c.frozen.amounts.finalCents - 101L,
            summary.approvedCents)
        driver.execute(null, "UPDATE consolidated_revisions SET finalCents = finalCents + 1", 0)
        val damaged = runBlocking { store.observeProjectQuoteSummary(f.projectId).filterNotNull().first() }
        assertTrue(damaged.approvedUnavailable)
        assertNull(damaged.approvedCents)
        driver.execute(null, "UPDATE consolidated_revisions SET finalCents = finalCents - 1", 0)
        runBlocking { store.closeAndAwaitProjectReads() }

        val second = f.consolidated.save(f.command(listOf(a), "Second", first.revisionUuid), date, 752)
        val replacement = assertNotNull(f.consolidated.confirmation(second.revisionUuid, APPROVED))
        assertFalse(f.consolidated.delete(f.projectId, mapOf(first.revisionUuid to 2L, second.revisionUuid to 1L), true, 753))
        assertTrue(f.consolidated.changeStatus(replacement, true, 754))
        assertEquals(SENT, f.consolidated.read(first.revisionUuid)?.status)
        assertEquals(SENT, f.status(b))
        assertEquals(APPROVED, f.status(c))
        val aVersion = assertNotNull(f.individuals.exactIndividual(a.seriesUuid, a.revisionUuid)).statusVersion
        assertFalse(f.individuals.setWorkflowStatus(a.revisionUuid, aVersion, REJECTED, 756))
        assertFalse(f.individuals.setWorkflowStatus(a.revisionUuid, aVersion, REJECTED, 756,
            expectedConsolidatedApproval = first.revisionUuid to 2L))
        assertTrue(f.individuals.setWorkflowStatus(a.revisionUuid, aVersion, REJECTED, 756,
            expectedConsolidatedApproval = second.revisionUuid to 2L))
        assertEquals(SENT, f.consolidated.read(second.revisionUuid)?.status)
        assertEquals(REJECTED, f.status(a))
        assertEquals(APPROVED, f.status(c))

        assertTrue(f.consolidated.changeStatus(assertNotNull(f.consolidated.confirmation(first.revisionUuid, APPROVED)), false, 757))
        assertTrue(f.consolidated.changeStatus(assertNotNull(f.consolidated.confirmation(second.revisionUuid, APPROVED)), false, 758))
        assertEquals(APPROVED, f.status(b))
        assertTrue(f.consolidated.changeStatus(assertNotNull(f.consolidated.confirmation(second.revisionUuid, SENT)), false, 759))
        assertEquals(APPROVED, f.status(a))
        assertTrue(f.consolidated.changeStatus(assertNotNull(f.consolidated.confirmation(second.revisionUuid, APPROVED)), false, 760))
        assertTrue(f.consolidated.changeStatus(assertNotNull(f.consolidated.confirmation(second.revisionUuid, SENT)), true, 761))
        assertEquals(SENT, f.status(a))
        assertEquals(APPROVED, f.status(b))
        assertEquals(APPROVED, f.status(c))
        verifyFrozenEstimateContents(driver)
    }

    @Test fun handoffRejectsStaleOrPartialUpdatesAndIsIdempotent() = withDatabase { driver, db ->
        val f = Fixture(db)
        val a = f.individual()
        val b = f.individual()
        val c = f.individual()
        assertTrue(f.individuals.setWorkflowStatus(c.revisionUuid, 1, REJECTED, 701))
        val entry = f.consolidated.save(f.command(listOf(a, b, c)), date, 750)
        val before = assertNotNull(f.consolidated.confirmation(entry.revisionUuid, SENT))
        assertEquals(DRAFT, f.status(a))
        driver.execute(null, "UPDATE estimate_revision_workflow SET version = 9223372036854775807 WHERE revisionUuid = ?", 1) {
            bindString(0, b.revisionUuid)
        }
        assertFalse(f.consolidated.shareDispatched(before, 751))
        assertFalse(f.consolidated.shareDispatched(assertNotNull(f.consolidated.confirmation(entry.revisionUuid, SENT)), 751))
        assertEquals(DRAFT, f.status(a))
        assertEquals(DRAFT, f.consolidated.read(entry.revisionUuid)?.status)
        driver.execute(null, "UPDATE estimate_revision_workflow SET version = 1 WHERE revisionUuid = ?", 1) {
            bindString(0, b.revisionUuid)
        }
        assertTrue(f.consolidated.shareDispatched(before, 752))
        assertTrue(f.consolidated.shareDispatched(before, 752))
        assertEquals(SENT, f.status(a))
        assertEquals(SENT, f.status(b))
        assertEquals(REJECTED, f.status(c))
        assertEquals(2L, f.consolidated.read(entry.revisionUuid)?.statusVersion)
        verifyFrozenEstimateContents(driver)
    }

    @Test fun proofsBindTheEntireCanonicalInput() {
        assertNotEquals(consolidatedProof("first"), consolidatedProof("second"))
        assertEquals(consolidatedProof("first"), consolidatedProof("first"))
    }

    @Test fun exactDiscountAndOverflow() {
        val percent = ConsolidatedDiscount(QuoteDiscountMode.PERCENT, 1250)
        val amounts = assertNotNull(consolidatedAmounts(listOf(101, 103), percent))
        assertEquals(26, amounts.discountCents.toInt())
        assertEquals(178L, amounts.finalCents)
        assertNull(consolidatedAmounts(listOf(100), discount))
        assertFailsWith<PricingOverflow> { consolidatedAmounts(listOf(Long.MAX_VALUE, 1L), discount) }
    }

    @Test fun concurrentFirstSaveHasOneReservationAndOneImmutableReceipt() {
        val directory = Files.createTempDirectory("consolidated-race")
        val url = "jdbc:sqlite:${directory.resolve("business.db")}"
        val pool = Executors.newFixedThreadPool(2)
        try {
            JdbcSqliteDriver(url).use { driver ->
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val fixture = Fixture(db)
                val individual = fixture.individual()
                val command = fixture.command(listOf(individual))
                val start = CountDownLatch(1)
                val tasks = List(2) {
                    pool.submit<ConsolidatedRevisionIdentity> {
                        JdbcSqliteDriver(url).use { connection ->
                            connection.execute(null, "PRAGMA busy_timeout = 10000", 0)
                            start.await(10, TimeUnit.SECONDS)
                            ConsolidatedEstimateRepository(PrototypeDatabase(connection)).save(command, date, 800)
                        }
                    }
                }
                start.countDown()
                val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(results.first(), results.last())
                assertEquals(2, db.estimateIssuanceQueries.selectAllReservations().executeAsList().size)

                val entry = results.first()
                val next = fixture.appendToInitial(individual)
                assertTrue(fixture.consolidated.changeStatus(
                    assertNotNull(fixture.consolidated.confirmation(entry.revisionUuid, APPROVED)), false, 802))
                val deapprove = assertNotNull(fixture.consolidated.confirmation(entry.revisionUuid, SENT))
                val barrier = CountDownLatch(1)
                fun workflow(action: (PrototypeDatabase) -> Boolean) = pool.submit<Boolean> {
                    JdbcSqliteDriver(url).use { connection ->
                        connection.execute(null, "PRAGMA busy_timeout = 10000", 0)
                        barrier.await(10, TimeUnit.SECONDS)
                        action(PrototypeDatabase(connection))
                    }
                }
                val consolidatedChange = workflow { ConsolidatedEstimateRepository(it).changeStatus(deapprove, false, 803) }
                val individualChange = workflow { writer ->
                    ImmutableEstimateRepository(writer).setWorkflowStatus(next.revisionUuid, 1, APPROVED, 803,
                        expectedApprovals = mapOf(individual.revisionUuid to 2L),
                        expectedConsolidatedApproval = entry.revisionUuid to 2L)
                }
                barrier.countDown()
                val consolidatedWon = consolidatedChange.get(20, TimeUnit.SECONDS)
                assertNotEquals(consolidatedWon, individualChange.get(20, TimeUnit.SECONDS))
                assertEquals(SENT, fixture.consolidated.read(entry.revisionUuid)?.status)
                assertEquals(if (consolidatedWon) APPROVED else SENT, fixture.status(individual))
                assertEquals(if (consolidatedWon) DRAFT else APPROVED, fixture.status(next))
                verifyFrozenEstimateContents(driver)
            }
        } finally {
            pool.shutdownNow()
            directory.toFile().deleteRecursively()
        }
    }

    private fun withDatabase(run: (JdbcSqliteDriver, PrototypeDatabase) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            PriceCatalogSchema.create(driver)
            run(driver, PrototypeDatabase(driver))
        }
    }

    private inner class Fixture(private val db: PrototypeDatabase) {
        val individuals = ImmutableEstimateRepository(db)
        val consolidated = ConsolidatedEstimateRepository(db)
        private val initial = MeasurementPricingFixture.seed(db)
        private val project = assertNotNull(ProjectRepository(db).find(initial.projectId))
        val projectId = project.id
        private var useInitial = true
        fun individual(common: Boolean = false): SavedIndividualQuote {
            if (common) individuals.configureIssuancePolicy(EstimateIssuancePolicy(mode = EstimateCounterMode.COMMON))
            val room = if (useInitial) initial else MeasurementPricingFixture.seed(db, projectId)
            useInitial = false
            val saved = individuals.saveIndividual(FrozenIndividualEstimateProof.command(db, room), date, 700)
            return assertNotNull(individuals.exactIndividual(saved.seriesUuid, saved.initialRevisionUuid))
        }
        fun command(sources: List<SavedIndividualQuote>, title: String = "Consolidated", head: String? = null, base: String? = null) =
            ConsolidatedSave(newRecordUuid(), projectId, project.uuid, head, base,
                ConsolidatedDraft(title, discount, sources.map {
                    ConsolidatedDocumentRef(it.frozen.source.measurementUuid, it.seriesUuid, it.revisionUuid,
                        it.frozen.consolidatedSourceProof())
                }))
        fun appendToInitial(original: SavedIndividualQuote): SavedIndividualQuote {
            val saved = individuals.appendIndividual(FrozenRevisionProof.request(db, initial, original.seriesUuid), 801)
            return assertNotNull(individuals.exactIndividual(saved.seriesUuid, saved.initialRevisionUuid))
        }
        fun status(quote: SavedIndividualQuote) = individuals.exactIndividual(quote.seriesUuid, quote.revisionUuid)?.status
    }
}
