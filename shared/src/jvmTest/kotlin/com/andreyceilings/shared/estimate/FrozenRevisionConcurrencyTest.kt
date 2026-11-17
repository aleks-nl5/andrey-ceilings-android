package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class FrozenRevisionConcurrencyTest {
    @Test fun explicitChoiceCasSerializesAcrossPhysicalConnections() {
        val directory = Files.createTempDirectory("choice-race").toFile()
        val file = directory.resolve("quote.db")
        val pool = Executors.newFixedThreadPool(2)
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val repo = ImmutableEstimateRepository(db)
                val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
                val second = repo.appendIndividual(FrozenRevisionProof.request(db, fixture, first.seriesUuid), 601)
                val start = CountDownLatch(1)
                val tasks = listOf(first.initialRevisionUuid, second.initialRevisionUuid).map { revision -> pool.submit<Pair<String, Boolean>> {
                    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { d ->
                        d.execute(null, "PRAGMA busy_timeout = 10000", 0)
                        start.await()
                        revision to ImmutableEstimateRepository(PrototypeDatabase(d)).selectRevision(first.seriesUuid, revision, 1, 700)
                    }
                } }
                start.countDown()
                val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.second })
                val chosen = results.single { it.second }.first
                assertTrue(repo.individualHistory(first.seriesUuid).all { it.selectedRevisionUuid == chosen && it.choiceVersion == 2L })
                assertTrue(repo.individualHistory(first.seriesUuid).all { it.status == EstimateWorkflowStatus.DRAFT && it.statusVersion == 1L })
                verifyFrozenEstimateContents(driver)
            }
        } finally { pool.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun competingAppendsAndStatusAreSerializedAcrossPhysicalConnections() {
        for (scenario in listOf("same-operation", "different-operation", "status-race")) {
            val directory = Files.createTempDirectory("revision-race").toFile()
            val file = directory.resolve("quote.db")
            val pool = Executors.newFixedThreadPool(2)
            try {
                JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                    PriceCatalogSchema.create(driver)
                    val db = PrototypeDatabase(driver)
                    val fixture = MeasurementPricingFixture.seed(db)
                    val history = ImmutableEstimateRepository(db)
                    val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 700)
                    val command = FrozenRevisionProof.request(db, fixture, first.seriesUuid)
                    val start = CountDownLatch(1)
                    val tasks = (1..2).map { index -> pool.submit<String> {
                        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { d ->
                            d.execute(null, "PRAGMA busy_timeout = 10000", 0)
                            val repo = ImmutableEstimateRepository(PrototypeDatabase(d))
                            start.await()
                            if (scenario == "status-race" && index == 2) {
                                if (repo.setWorkflowStatus(first.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 701)) "status" else "stale"
                            } else try {
                                val request = if (scenario == "different-operation" && index == 2)
                                    command.copy(calculation = command.calculation.copy(operationUuid = newRecordUuid())) else command
                                repo.appendIndividual(request, 701).initialRevisionUuid
                            } catch (failure: IndividualEstimateSaveFailure) {
                                assertTrue(failure.reason in listOf(IndividualEstimateSaveFailure.Reason.STALE_REVISION, IndividualEstimateSaveFailure.Reason.STALE_STATUS))
                                "stale"
                            }
                        }
                    } }
                    start.countDown()
                    val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
                    if (scenario == "same-operation") assertEquals(results.first(), results.last())
                    else if (scenario == "different-operation") assertEquals(1, results.count { it == "stale" })
                    else assertTrue("status" in results) // Old alternative remains independently actionable.
                    val latest = history.frozenIndividual(first.seriesUuid)!!
                    assertEquals(if (latest.versionNumber == 1L) 2L else 1L, latest.statusVersion)
                    assertEquals(if ("stale" in results && scenario == "status-race") 1 else 2, history.revisions(first.seriesUuid).size)
                    assertEquals(history.revisions(first.seriesUuid).size, history.frozenIndividuals().size)
                    assertEquals(2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
                    verifyFrozenEstimateContents(driver)
                }
            } finally { pool.shutdownNow(); directory.deleteRecursively() }
        }
    }
}
