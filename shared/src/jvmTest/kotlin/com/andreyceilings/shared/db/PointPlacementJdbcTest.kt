package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class PointPlacementJdbcTest {
    @Test fun repositoryFlowQuantityGeometryIsolationAndSqlGuards(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            verifyPointPlacementStructure(driver)
            PointPlacementProof.run(driver)
            PointPlacementProof.geometryAndDeletionGuards(driver)
            PointPlacementProof.eligibilityAndIsolation(driver)
        }
    }

    @Test fun populatedAndEmptyV7UpgradeRollbackReopenAndRaces(): Unit = runBlocking {
        PointPlacementFileProof.run { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun freshMatchesMigratedAndProbeRejectsPartialFutureOrMissingGuards() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { fresh ->
            PriceCatalogSchema.create(fresh)
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { upgraded ->
                PointPlacementMigrationProof.v7Schema.create(upgraded)
                PriceCatalogSchema.migrate(upgraded, 7, 10)
                verifyPointPlacementStructure(upgraded)
                fun definitions(driver: SqlDriver) = MeasurementElementMigrationProof.rows(driver,
                    "SELECT type,name,sql FROM sqlite_master WHERE name='point_placements' OR name LIKE 'point_%' OR name LIKE 'index_point_%' ORDER BY name", 3)
                assertEquals(definitions(fresh), definitions(upgraded))
                val original = PriceNameMigrationProof.schema(upgraded)
                upgraded.execute(null, "DROP TRIGGER point_contour_guard", 0)
                assertFails { openDesktopPriceCatalog(upgraded, true) }
                assertNotEquals(original, PriceNameMigrationProof.schema(upgraded))
            }
            fresh.execute(null, "PRAGMA user_version=11", 0)
            assertFails { openDesktopPriceCatalog(fresh, true) }
            assertEquals(11L, PriceNameUniquenessProof.scalar(fresh, "PRAGMA user_version"))
        }
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { partial ->
            PointPlacementMigrationProof.v7Schema.create(partial)
            partial.execute(null, "CREATE TABLE point_placements(uuid TEXT PRIMARY KEY)", 0)
            val before = PriceNameMigrationProof.schema(partial)
            assertFails { openDesktopPriceCatalog(partial, true) }
            assertEquals(before, PriceNameMigrationProof.schema(partial))
        }
    }
}

/** Same physical multi-connection races run on real encrypted JDBC on Windows CI. */
object PointPlacementFileProof {
    suspend fun run(open: (File) -> SqlDriver) {
        for (populated in listOf(false, true)) {
            val file = Files.createTempFile("point-upgrade-", ".db").toFile()
            try {
                PointPlacementMigrationProof.lifecycle(PriceCatalogSchema, populated) { schema ->
                    val driver = open(file)
                    try {
                        driver.execute(null, "PRAGMA busy_timeout=15000", 0)
                        if (schema === PriceCatalogSchema) openDesktopPriceCatalog(driver, true)
                        else PrototypeDatabase(driver).transaction {
                            val old = PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version")
                            if (old == 0L) schema.create(driver) else if (old < schema.version) schema.migrate(driver, old, schema.version)
                            driver.execute(null, "PRAGMA user_version=${schema.version}", 0)
                        }
                        driver
                    } catch (failure: Throwable) { driver.close(); throw failure }
                }
            } finally { file.delete() }
        }
        val file = Files.createTempFile("point-race-", ".db").toFile()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val f = open(file).use { driver -> openDesktopPriceCatalog(driver, false); PointPlacementProof.fixture(PrototypeDatabase(driver)) }
            fun race(left: (PrototypeDatabase) -> Any, right: (PrototypeDatabase) -> Any): List<Result<Any>> {
                val ready = CountDownLatch(2); val start = CountDownLatch(1)
                val results = listOf(left, right).map { action -> pool.submit<Result<Any>> {
                    open(file).use { driver ->
                        driver.execute(null, "PRAGMA busy_timeout=15000", 0)
                        ready.countDown(); check(start.await(15, TimeUnit.SECONDS))
                        runCatching { action(PrototypeDatabase(driver)) }
                    }
                } }
                assertTrue(ready.await(15, TimeUnit.SECONDS)); start.countDown()
                return results.map { it.get(30, TimeUnit.SECONDS) }
            }
            val same = race(
                { PointPlacementRepository(it).addPoint(f.element.version, f.contourVersion, 1000, 1000, 10, "same-tap") },
                { PointPlacementRepository(it).addPoint(f.element.version, f.contourVersion, 1000, 1000, 10, "same-tap") })
            assertTrue(same.all { it.isSuccess }); assertEquals(same[0].getOrThrow(), same[1].getOrThrow())
            val current = open(file).use { driver -> MeasurementElementRepository(PrototypeDatabase(driver)).find(f.element.uuid)!! }
            val adds = race(
                { PointPlacementRepository(it).addPoint(current.version, f.contourVersion, 2000, 1000, 11) },
                { PointPlacementRepository(it).addPoint(current.version, f.contourVersion, 2100, 1000, 11) })
            assertEquals(1, adds.count { it.isSuccess })
            assertIs<PointPlacementFailure.StaleVersion>(adds.single { it.isFailure }.exceptionOrNull())
            val two = open(file).use { driver -> MeasurementElementRepository(PrototypeDatabase(driver)).find(f.element.uuid)!! }
            val onePoint = open(file).use { driver -> PointPlacementRepository(PrototypeDatabase(driver)).forMeasurement(f.element.measurementUuid).first() }
            val reduceRemove = race(
                { PointPlacementRepository(it).removePoint(two.version, onePoint, 20) },
                { MeasurementElementRepository(it).edit(two.version, "Reduce", two.approval, ElementQuantity.Exact(1000), 20) })
            assertTrue(reduceRemove[0].isSuccess)
            assertTrue(reduceRemove[1].exceptionOrNull() is MeasurementElementFailure.StaleVersion ||
                reduceRemove[1].exceptionOrNull() is MeasurementElementFailure.PlacementsInUse)
            val d = open(file).use { PointPlacementProof.fixture(PrototypeDatabase(it), "Race delete") }
            val addDelete = race(
                { PointPlacementRepository(it).addPoint(d.element.version, d.contourVersion, 1000, 1000, 10) },
                { MeasurementElementRepository(it).delete(d.element.version, 10) })
            assertEquals(1, addDelete.count { it.isSuccess })
            open(file).use { driver ->
                val db = PrototypeDatabase(driver)
                val persisted = MeasurementElementRepository(db).find(d.element.uuid)!!
                assertEquals(if (persisted.deletedAt == null) 1000L else 0L, persisted.placedQuantityMilli)
                assertEquals(persisted.placedQuantityMilli / 1000, PointPlacementRepository(db).forMeasurement(d.element.measurementUuid).size.toLong())
            }
            // Concurrent geometry edit / first point: exactly one operation wins.
            val g = open(file).use { PointPlacementProof.fixture(PrototypeDatabase(it), "Race geometry") }
            val geometryDocument=open(file).use { MeasurementEditStore(PrototypeDatabase(it)){10}.load(g.project,g.id) }
            val geometryRace = race(
                { PointPlacementRepository(it).addPoint(g.element.version, g.contourVersion, 1000, 1000, 10) },
                { db -> val edit = MeasurementEditStore(db) { 10 }; val doc = geometryDocument
                    edit.update(doc, doc.draft.reduce(MeasurementCreateIntent.Dimension("length", "8"))) })
            assertEquals(1, geometryRace.count { it.isSuccess })
            val failure = geometryRace.single { it.isFailure }.exceptionOrNull()
            assertTrue(failure is MeasurementEditConflict || failure is PointPlacementFailure.StaleContour, "$failure")
            open(file).use { driver ->
                openDesktopPriceCatalog(driver, true); verifyPointPlacementStructure(driver)
                assertEquals(1, PointPlacementRepository(PrototypeDatabase(driver)).forMeasurement(f.element.measurementUuid).size)
            }
        } finally { pool.shutdownNow(); pool.awaitTermination(30, TimeUnit.SECONDS); file.delete() }
    }
}
