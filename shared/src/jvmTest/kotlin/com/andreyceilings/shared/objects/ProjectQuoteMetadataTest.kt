package com.andreyceilings.shared.objects

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.estimate.EstimateWorkflowStatus
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import com.andreyceilings.shared.measurement.CeilingHasApprovedQuote
import com.andreyceilings.shared.measurement.MeasurementsStore
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectQuoteMetadataTest {
    @Test
    fun projectMetadataRefreshesAfterSaveStatusAndDeletionAndIgnoresOtherOwners() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val store = ObjectsClientsStore(db) { 50L }
            val project = store.saveProject(null, ProjectDraft("A"))
            val other = store.saveProject(null, ProjectDraft("B"))
            val measurements = MeasurementRepository(db)
            val ceiling = measurements.createReturningId(project, "Ceiling", createdAt = 1)
            val ceilingUuid = assertNotNull(measurements.find(ceiling)).uuid
            val otherCeiling = measurements.createReturningId(other, "Other ceiling", createdAt = 1)
            val otherUuid = assertNotNull(measurements.find(otherCeiling)).uuid
            val updates = Channel<ProjectQuoteSummary?>(Channel.UNLIMITED)
            // Raw SQL invalidations may yield identical projected summaries, especially
            // when unrelated projects are modified. Observe semantic changes rather
            // than assuming exactly one emission for each write.
            val collector = launch {
                store.observeProjectQuoteSummary(project).distinctUntilChanged().collect { updates.send(it) }
            }

            suspend fun next(): ProjectQuoteSummary = assertNotNull(withTimeout(5_000) { updates.receive() })
            assertTrue(next().ceilings.isEmpty())
            // Metadata fixtures intentionally lack immutable receipts. Opening a document
            // still runs the separate verified-document path.
            db.saveFixtureQuote(project, ceilingUuid, "series", 1, 101)
            assertEquals(CeilingQuoteSummary(101, EstimateWorkflowStatus.DRAFT), next().ceilings[ceiling])
            db.saveFixtureQuote(project, ceilingUuid, "series", 2, 305)
            assertEquals(305L, next().ceilings[ceiling]?.finalCents)
            db.revisionWorkflowQueries.updateRevisionWorkflow("APPROVED", 20, "series-1", 1).executeAsOne()
            val approved = next()
            assertEquals(101L, approved.approvedCents)
            assertEquals(101L, approved.ceilings[ceiling]?.finalCents)
            db.revisionWorkflowQueries.updateRevisionWorkflow("APPROVED", 21, "series-2", 1).executeAsOne()
            val ambiguous = next()
            assertTrue(ambiguous.approvedUnavailable)
            assertEquals(setOf(ceiling), ambiguous.unavailableCeilings)
            db.revisionWorkflowQueries.updateRevisionWorkflow("SENT", 22, "series-1", 2).executeAsOne()
            assertEquals(305L, next().approvedCents)
            db.revisionWorkflowQueries.updateRevisionWorkflow("DRAFT", 23, "series-2", 2).executeAsOne()
            assertEquals(305L, next().ceilings[ceiling]?.finalCents)
            db.revisionWorkflowQueries.updateRevisionWorkflow("REJECTED", 24, "series-2", 3).executeAsOne()
            assertEquals(101L, next().ceilings[ceiling]?.finalCents)
            db.frozenRevisionQueries.insertRevisionTombstone(25, "series-1", "series", 3).executeAsOne()
            assertTrue(next().ceilings.isEmpty())

            db.transaction {
                db.saveFixtureQuote(other, otherUuid, "other", 1, 700)
                db.saveFixtureQuote(project, otherUuid, "wrong-owner", 1, 800)
                db.saveFixtureQuote(project, ceilingUuid, "legacy", 1, null)
            }
            // Check the excluded fixtures independently before introducing the valid
            // quote; a duplicate empty emission cannot satisfy the next assertion.
            assertTrue(assertNotNull(withTimeout(5_000) {
                store.observeProjectQuoteSummary(project).first()
            }).ceilings.isEmpty())
            db.saveFixtureQuote(project, ceilingUuid, "deleted-series", 1, 900)
            assertEquals(900L, next().ceilings[ceiling]?.finalCents)
            db.estimateHistoryQueries.softDeleteEstimateSeries(26, "deleted-series").executeAsOne()
            assertTrue(next().ceilings.isEmpty())
            assertEquals(setOf(otherCeiling), store.observeProjectQuoteSummary(other).first()?.ceilings?.keys)

            db.saveFixtureQuote(project, ceilingUuid, "series", 3, 407)
            assertEquals(407L, next().ceilings[ceiling]?.finalCents)
            measurements.delete(ceiling)
            assertTrue(next().ceilings.isEmpty())
            ProjectRepository(db).delete(other)
            assertTrue(assertNotNull(store.observeProjectQuoteSummary(other).first()).ceilings.isEmpty())

            store.closeAndAwaitProjectReads()
            assertNull(withTimeout(5_000) { updates.receive() })
            assertNull(store.observeProjectQuoteSummary(project).first())
            collector.cancelAndJoin()
        }
    }

    @Test
    fun deleteCeilingRequiresMatchingOwnerAndUuidAndRejectsApprovedQuote(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val store = ObjectsClientsStore(db) { 50L }
            val project = store.saveProject(null, ProjectDraft("A"))
            val other = store.saveProject(null, ProjectDraft("B"))
            val measurements = MeasurementRepository(db)
            val id = measurements.createReturningId(project, "Ceiling", createdAt = 1)
            val uuid = assertNotNull(measurements.find(id)).uuid
            db.saveFixtureQuote(project, uuid, "series", 1, 101)
            // The metadata fixture omits the choice row required by exact historical reads.
            db.revisionWorkflowQueries.insertRevisionChoice("series")
            val history = ImmutableEstimateRepository(db)
            db.saveFixtureQuote(other, uuid, "wrong-owner", 1, 102)
            assertFalse(history.setWorkflowStatus("wrong-owner-1", 1, EstimateWorkflowStatus.APPROVED, 19))
            assertTrue(history.setWorkflowStatus("series-1", 1, EstimateWorkflowStatus.APPROVED, 20))
            assertFailsWith<CeilingHasApprovedQuote> { store.deleteCeiling(project, id, uuid) }
            assertFailsWith<CeilingHasApprovedQuote> { MeasurementsStore(db).delete(project, id) }
            assertNotNull(measurements.find(id))
            assertTrue(history.setWorkflowStatus("series-1", 2, EstimateWorkflowStatus.SENT, 21))
            assertFailsWith<IllegalStateException> { store.deleteCeiling(other, id, uuid) }
            assertFailsWith<IllegalStateException> { store.deleteCeiling(project, id, "wrong-uuid") }
            assertNotNull(measurements.find(id))
            store.deleteCeiling(project, id, uuid)
            assertNull(measurements.find(id))
            assertTrue(measurements.tombstones().any { it.id == id })
            assertTrue(assertNotNull(store.observeProjectQuoteSummary(project).first()).ceilings.isEmpty())
            // Delete first, then attempt approval through the shared document writer.
            val before = db.frozenRevisionQueries.selectHistoricalExactRevision("series", "series-1").executeAsOne()
            assertFalse(history.setWorkflowStatus("series-1", 3, EstimateWorkflowStatus.APPROVED, 22))
            assertEquals(before, db.frozenRevisionQueries.selectHistoricalExactRevision("series", "series-1").executeAsOne())
            assertEquals(1, db.revisionWorkflowQueries.selectActiveSeriesWorkflow("series").executeAsList().size)
            store.closeAndAwaitProjectReads()
        }
    }

    @Test
    fun projectTotalsAreExactAndAmbiguousOrIncompatibleApprovalsAreUnavailable() {
        fun approved(id: Long, cents: Long, currency: String = "UAH") =
            ProjectQuoteCandidate(id, "APPROVED", currency, cents)

        val exact = projectQuoteSummary(listOf(approved(1, Long.MAX_VALUE - 2), approved(2, 2),
            ProjectQuoteCandidate(3, "SENT", "UAH", 22)))
        assertEquals(Long.MAX_VALUE, exact.approvedCents)
        assertFalse(exact.approvedUnavailable)
        assertEquals(22L, exact.ceilings[3]?.finalCents)

        for (candidates in listOf(
            listOf(approved(1, 100), approved(1, 100)),
            listOf(approved(1, Long.MAX_VALUE), approved(2, 1)),
            listOf(approved(1, 100), approved(2, 99, "EUR")),
            listOf(approved(1, -1)),
            listOf(ProjectQuoteCandidate(1, "", "UAH", -1))
        )) {
            val summary = projectQuoteSummary(candidates)
            assertTrue(summary.approvedUnavailable)
            assertNull(summary.approvedCents)
        }

        assertNull(projectQuoteSummary(emptyList()).approvedCents)
        assertEquals(0L, projectQuoteSummary(listOf(approved(1, 0))).approvedCents)
        assertEquals(setOf(1L), projectQuoteSummary(listOf(approved(1, 1), approved(1, 2))).unavailableCeilings)
    }

    private fun PrototypeDatabase.saveFixtureQuote(
        project: Long, ceilingUuid: String, series: String, version: Long, cents: Long?
    ) = transaction {
        if (version == 1L) {
            estimateHistoryQueries.insertEstimateSeriesIfFree(
                series, "number-$series", project, ceilingUuid, "", "", 1, 1, 10, null
            ).executeAsOne()
        }
        val revision = "$series-$version"
        estimateHistoryQueries.insertImmutableEstimateRevision(
            revision, series, if (version == 1L) null else "$series-${version - 1}", version,
            version + 10, "{}", "{}", 0.01, "test", "UAH", "test", 0.0
        )
        if (cents != null) {
            if (version == 1L) frozenEstimateQueries.insertFrozenV1(revision, "{}", cents, 0, 0, cents)
            else frozenRevisionQueries.insertFrozenRevision(revision, "{}", cents, 0, 0, cents)
            revisionWorkflowQueries.insertRevisionWorkflow(revision, version + 10)
        }
    }
}
