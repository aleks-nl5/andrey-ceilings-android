package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlin.test.*

class RevisionProposalListTest {
    @Test fun everyContextListsExactAlternativesAndPreservesTheirIndependentMetadata() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver); val f = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, f), 2026, 600)
            assertTrue(repo.setWorkflowStatus(first.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 601))
            assertTrue(repo.selectRevision(first.seriesUuid, first.initialRevisionUuid, 1, 602))
            val second = repo.appendIndividual(FrozenRevisionProof.request(db, f, first.seriesUuid), 603)
            val third = repo.appendIndividual(FrozenRevisionProof.request(db, f, first.seriesUuid), 604)
            val ids = listOf(third.initialRevisionUuid, second.initialRevisionUuid, first.initialRevisionUuid)
            val store = MeasurementElementsStore(db, { 700 }, Dispatchers.Unconfined)
            try {
                for (rows in listOf(store.savedQuotes(), store.savedQuotes(f.projectId), store.savedQuotes(f.projectId, f.measurementUuid))) {
                    assertEquals(ids, rows.map { it.revisionUuid })
                    assertEquals(listOf(EstimateWorkflowStatus.DRAFT, EstimateWorkflowStatus.DRAFT, EstimateWorkflowStatus.APPROVED), rows.map { it.status })
                    assertTrue(rows.all { it.selectedRevisionUuid == first.initialRevisionUuid })
                }
                assertTrue(store.setQuoteStatus(first.initialRevisionUuid, 2, EstimateWorkflowStatus.SENT))
                assertEquals(EstimateWorkflowStatus.DRAFT, store.savedQuote(first.seriesUuid, third.initialRevisionUuid)!!.status)
                val other = FrozenRevisionProof.withLegacyAlternative(driver, listOf(first.seriesUuid), f.measurementUuid) {
                    repo.saveIndividual(FrozenIndividualEstimateProof.command(db, f), 2026, 605)
                }
                assertFalse(store.selectQuoteRevision(first.seriesUuid, other.initialRevisionUuid, 2))
                assertFalse(store.selectQuoteRevision(first.seriesUuid, third.initialRevisionUuid, 1))
                assertTrue(store.selectQuoteRevision(first.seriesUuid, second.initialRevisionUuid, 2))
                assertEquals(second.initialRevisionUuid, store.savedQuote(first.seriesUuid, first.initialRevisionUuid)!!.selectedRevisionUuid)
                assertEquals(EstimateWorkflowStatus.SENT, store.savedQuote(first.seriesUuid, first.initialRevisionUuid)!!.status)
                verifyFrozenEstimateContents(driver)
            } finally { store.closeAndAwaitWrites() }
        }
    }
}
