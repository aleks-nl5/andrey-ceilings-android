package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
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
            val reserved = allocator.issue(EstimateType.CONSOLIDATED, later) { it }
            assertEquals(2L, reserved.sequence)
            assertEquals("КШ/26/0001", repo.series(first.seriesUuid)?.publicNumber)
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
