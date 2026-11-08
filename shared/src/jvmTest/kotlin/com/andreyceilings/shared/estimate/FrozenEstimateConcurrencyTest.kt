package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class FrozenEstimateConcurrencyTest {
    @Test fun restoredCounterCollisionRetriesPublicTokenAndExhaustionRollsBack() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val command = FrozenIndividualEstimateProof.command(db, fixture)
            val history = ImmutableEstimateRepository(db)
            val first = history.saveIndividual(command, 2026, 700) { "ABCDEF1234" }
            driver.execute(null, "UPDATE estimate_number_settings SET nextSequence=1.5", 0)
            assertEquals(IndividualEstimateSaveFailure.Reason.SEQUENCE_EXHAUSTED,
                assertFailsWith<IndividualEstimateSaveFailure> {
                    history.saveIndividual(command.copy(operationUuid = newRecordUuid()), 2026, 701)
                }.reason)
            assertEquals("real", driver.executeQuery(null, "SELECT typeof(nextSequence) FROM estimate_number_settings", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)) }, 0).value)
            driver.execute(null, "UPDATE estimate_number_settings SET nextSequence=1", 0)
            val before = history.frozenIndividuals().size
            assertEquals(IndividualEstimateSaveFailure.Reason.NUMBER_COLLISION,
                assertFailsWith<IndividualEstimateSaveFailure> {
                    history.saveIndividual(command.copy(operationUuid = newRecordUuid()), 2026, 701) { "ABCDEF1234" }
                }.reason)
            assertEquals(1, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
            assertEquals(before, history.frozenIndividuals().size)
            var calls = 0
            val second = history.saveIndividual(command.copy(operationUuid = newRecordUuid()), 2026, 702) {
                if (calls++ == 0) "ABCDEF1234" else "ABCDE12345"
            }
            assertEquals(2, calls)
            assertEquals(first.localSequence, second.localSequence)
            assertNotEquals(first.documentNumber, second.documentNumber)
            assertEquals(2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
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
                assertEquals(2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
            }
        } finally { pool.shutdownNow(); directory.deleteRecursively() }
    }
}
