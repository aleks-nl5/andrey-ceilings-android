package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MeasurementElementJdbcTest {
    @Test fun sharedApiPersistenceFlowSqlChecksAndAtomicDelete(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            assertEquals(8L, PriceCatalogSchema.version)
            verifyMeasurementElementStructure(driver)
            MeasurementElementsPresentationProof.run(PrototypeDatabase(driver))
            MeasurementElementProof.run(driver)
            MeasurementElementProof.atomicParentDelete(driver)
            MeasurementElementMigrationProof.sqlGuards(driver)
            val failing = object : SqlDriver by driver {
                override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
                    error("Injected unavailable database")
            }
            assertFailsWith<MeasurementElementFailure.DatabaseUnavailable> {
                MeasurementElementRepository(PrototypeDatabase(failing)).createWish("missing", "No DB", 1)
            }
        }
    }

    @Test fun populatedV6MigrationRollbackReopenAndConcurrentCas() = runBlocking {
        MeasurementElementFileProof.run { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun freshAndMigratedStructuresMatchExactly() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { fresh ->
            PriceCatalogSchema.create(fresh)
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { upgraded ->
                MeasurementElementMigrationProof.v6Schema.create(upgraded)
                PriceCatalogSchema.migrate(upgraded, 6, 7)
                fun definitions(driver: SqlDriver) = MeasurementElementMigrationProof.rows(driver,
                    "SELECT type,name,sql FROM sqlite_master WHERE name IN ('measurement_elements','index_measurement_elements_parent_active_order','measurement_element_records') ORDER BY name", 3)
                assertEquals(definitions(fresh), definitions(upgraded))
                verifyMeasurementElementStructure(upgraded)
            }
        }
    }

    @Test fun windowsProbeRejectsPartialTableAndDoesNotTrustVersionAlone() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            MeasurementElementMigrationProof.v6Schema.create(driver)
            driver.execute(null, "PRAGMA user_version=7", 0)
            openDesktopPriceCatalog(driver, true)
            verifyMeasurementElementStructure(driver)
        }
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            MeasurementElementMigrationProof.v6Schema.create(driver)
            driver.execute(null, "PRAGMA user_version=6", 0)
            driver.execute(null, "CREATE TABLE measurement_elements(uuid TEXT PRIMARY KEY)", 0)
            val before = PriceNameMigrationProof.schema(driver)
            assertFails { openDesktopPriceCatalog(driver, true) }
            assertEquals(before, PriceNameMigrationProof.schema(driver))
        }
    }
}

/** Plain SQLite locally; real encrypted JDBC on Windows CI with the same races. */
object MeasurementElementFileProof {
    suspend fun run(open: (File) -> SqlDriver) {
        val file = Files.createTempFile("elements-v6-", ".db").toFile()
        try {
            val (before, structure) = open(file).use { driver ->
                MeasurementElementMigrationProof.v6Schema.create(driver)
                driver.execute(null, "PRAGMA user_version=6", 0)
                MeasurementElementMigrationProof.populate(driver)
                MeasurementElementMigrationProof.snapshot(driver) to PriceNameMigrationProof.schema(driver)
            }
            open(file).use { driver ->
                assertFails { openDesktopPriceCatalog(MeasurementElementMigrationProof.failing(driver), true) }
                assertEquals(before, MeasurementElementMigrationProof.snapshot(driver))
                assertEquals(structure, PriceNameMigrationProof.schema(driver))
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                // Probe and upgrade must serialize after taking the writer lock.
                val upgrades = (1..2).map { pool.submit {
                    open(file).use { driver ->
                        driver.execute(null, "PRAGMA busy_timeout=15000", 0)
                        openDesktopPriceCatalog(driver, true)
                    }
                } }
                upgrades.forEach { it.get(30, TimeUnit.SECONDS) }
                val row = open(file).use { driver ->
                    assertEquals(8L, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                    assertEquals(before, MeasurementElementMigrationProof.snapshot(driver))
                    verifyMeasurementElementStructure(driver)
                    val db = PrototypeDatabase(driver)
                    val parent = MeasurementRepository(db).all().single().uuid
                    MeasurementElementRepository(db).createWish(parent, "Concurrent", 400, uuid = "race",
                        unit = "м.п.", quantity = ElementQuantity.Exact(2500))
                }
                for (scenario in listOf("edit", "retry", "duplicate")) {
                    val ready = CountDownLatch(2); val start = CountDownLatch(1)
                    val writes = (1..2).map { index -> pool.submit<Result<MeasurementElement>> {
                        open(file).use { driver ->
                            driver.execute(null, "PRAGMA busy_timeout=15000", 0)
                            ready.countDown(); check(start.await(20, TimeUnit.SECONDS))
                            val repo = MeasurementElementRepository(PrototypeDatabase(driver))
                            runCatching { when (scenario) {
                                "edit" -> repo.edit(row.version, "Writer $index", row.approval, ElementQuantity.Unknown, 400,
                                    wishUnitChange = if (index == 1) WishUnitChange.Clear else WishUnitChange.Set("шт."))
                                "retry" -> repo.createWish(row.measurementUuid, "Retry", 400, uuid = "retry")
                                else -> repo.createWish(row.measurementUuid, "Different $index", 400, uuid = "duplicate")
                            } }
                        }
                    } }
                    assertTrue(ready.await(20, TimeUnit.SECONDS)); start.countDown()
                    val results = writes.map { it.get(30, TimeUnit.SECONDS) }
                    if (scenario == "retry") {
                        assertTrue(results.all { it.isSuccess }); assertEquals(results[0].getOrThrow(), results[1].getOrThrow())
                    } else {
                        assertEquals(1, results.count { it.isSuccess })
                        val failure = results.single { it.isFailure }.exceptionOrNull()
                        if (scenario == "edit") {
                            assertIs<MeasurementElementFailure.StaleVersion>(failure)
                            val winner = results.single { it.isSuccess }.getOrThrow()
                            assertEquals(401L, winner.updatedAt)
                            assertEquals(ElementQuantity.Unknown, winner.quantity)
                            assertEquals(if (winner.note == "Writer 1") null else "шт.", winner.unit)
                            open(file).use { driver ->
                                assertEquals(winner, MeasurementElementRepository(PrototypeDatabase(driver)).find(row.uuid),
                                    "The stale writer must not overwrite the winning unit/quantity/note")
                            }
                        } else assertIs<MeasurementElementFailure.RetryConflict>(failure)
                    }
                }
                open(file).use { driver ->
                    val schema = PriceNameMigrationProof.schema(driver)
                    openDesktopPriceCatalog(driver, true)
                    assertEquals(schema, PriceNameMigrationProof.schema(driver))
                    assertEquals(before, MeasurementElementMigrationProof.snapshot(driver))
                    assertEquals(3, MeasurementElementRepository(PrototypeDatabase(driver)).all().size)
                }
            } finally { pool.shutdownNow(); pool.awaitTermination(30, TimeUnit.SECONDS) }
        } finally { file.delete() }
    }
}
