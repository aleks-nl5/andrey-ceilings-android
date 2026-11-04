package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class PointPositionJdbcTest {
    @Test fun sharedMoveAndEditorReadOnlyContracts(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver); driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            PointPositionProof.run(driver)
        }
    }
    @Test fun v8RollbackReopenAndConcurrentMoveDelete() = runBlocking {
        PointPositionFileProof.run { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }
    @Test fun sessionMoveUndoRedoDirtyBackAndStaleHistory(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver); val db = PrototypeDatabase(driver)
            val f = PointPlacementProof.fixture(db)
            val repo = PointPlacementRepository(db)
            val p = repo.addPoint(f.element.version, f.contourVersion, 1000, 1000, 10)
            val store = MeasurementElementsStore(db, { 20 }, Dispatchers.Unconfined)
            val model = store.model(ElementRoute(f.project, f.id, f.element.measurementUuid))
            suspend fun loaded() {
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                withTimeout(10_000) { model.points.first { it.available && it.points.isNotEmpty() } }
            }
            try {
                loaded(); model.selectPoint(p.uuid)
                model.beginPointDrag(p.uuid, f.contourVersion); model.previewPointDrag(1800.0, 1300.0)
                assertEquals(p, repo.find(p.uuid)); model.cancelPointDrag(); assertEquals(p, repo.find(p.uuid))
                model.beginPointDrag(p.uuid, f.contourVersion); model.previewPointDrag(1800.0, 1300.0); model.finishPointDrag()
                withTimeout(10_000) { model.points.first { !it.saving && it.undo != null && it.points.single().xMm == 1800L } }
                model.undoPointMove()
                withTimeout(10_000) { model.points.first { !it.saving && it.redo != null && it.points.single().xMm == 1000L } }
                model.redoPointMove()
                withTimeout(10_000) { model.points.first { !it.saving && it.undo != null && it.points.single().xMm == 1800L } }
                val geometry = MeasurementGeometryCodec.read(MeasurementRepository(db).find(f.id)!!.drawingJson)
                model.openPosition(geometry, f.contourVersion); model.updatePosition { it.copy(x = "2,", y = "1.5") }
                assertEquals("2,", model.points.value.positionEditor!!.draft.x)
                assertNull(model.points.value.positionEditor!!.draft.position())
                var exited = false
                model.requestPositionExit { exited = true }
                assertFalse(exited); assertTrue(model.points.value.positionEditor!!.exitRequested)
                model.continuePosition(); model.updatePosition { it.copy(x = "2,5") }; model.savePosition()
                withTimeout(10_000) { model.points.first { !it.saving && it.positionEditor == null && it.points.single().precision == PointPrecision.EXACT } }
                model.beginPointDrag(p.uuid, f.contourVersion); model.previewPointDrag(2500.1, 1500.1); model.finishPointDrag()
                withTimeout(10_000) { model.points.first { !it.saving && it.points.single().precision == PointPrecision.APPROXIMATE } }
                assertEquals(2500L, repo.find(p.uuid)!!.xMm)
                val live = repo.find(p.uuid)!!; val element = MeasurementElementRepository(db).find(f.element.uuid)!!
                repo.movePoint(element.version, live, f.contourVersion, 2600, 1600, PointPrecision.EXACT, 30)
                withTimeout(10_000) { model.points.first { it.undo == null && it.error?.contains("історію") == true } }
                model.undoPointMove(); assertEquals(2600L, repo.find(p.uuid)!!.xMm)
                model.leavePointRoute(); assertNull(model.points.value.redo)
            } finally { store.closeAndAwaitWrites() }
        }
    }
}

object PointPositionFileProof {
    suspend fun run(open: (File) -> SqlDriver) {
        for (populated in listOf(false, true)) {
            val file = Files.createTempFile("point-v8-", ".db").toFile()
            try {
                PointPositionMigrationProof.lifecycle(PriceCatalogSchema, populated) { schema ->
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
        val file = Files.createTempFile("point-move-race-", ".db").toFile()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val f = open(file).use { driver -> openDesktopPriceCatalog(driver, false); PointPlacementProof.fixture(PrototypeDatabase(driver)) }
            val point = open(file).use { PointPlacementRepository(PrototypeDatabase(it)).addPoint(f.element.version, f.contourVersion, 1000, 1000, 10) }
            fun current() = open(file).use { MeasurementElementRepository(PrototypeDatabase(it)).find(f.element.uuid)!! }
            fun race(left: (PrototypeDatabase) -> Any, right: (PrototypeDatabase) -> Any): List<Result<Any>> {
                val ready = CountDownLatch(2); val start = CountDownLatch(1)
                val futures = listOf(left, right).map { action -> pool.submit<Result<Any>> {
                    open(file).use { driver ->
                        driver.execute(null, "PRAGMA busy_timeout=15000", 0)
                        ready.countDown(); check(start.await(20, TimeUnit.SECONDS))
                        runCatching { action(PrototypeDatabase(driver)) }
                    }
                } }
                assertTrue(ready.await(20, TimeUnit.SECONDS)); start.countDown()
                return futures.map { it.get(30, TimeUnit.SECONDS) }
            }
            val element = current()
            val results = race(
                { PointPlacementRepository(it).movePoint(element.version, point, f.contourVersion, 1500, 1500, PointPrecision.EXACT, 20, "same-move") },
                { PointPlacementRepository(it).movePoint(element.version, point, f.contourVersion, 1500, 1500, PointPrecision.EXACT, 20, "same-move") })
            assertTrue(results.all { it.isSuccess }); assertEquals(results[0].getOrThrow(), results[1].getOrThrow())
            val moved = (results[0].getOrThrow() as PointMoveResult).point
            val next = current()
            val deletes = race(
                { PointPlacementRepository(it).movePoint(next.version, moved, f.contourVersion, 1800, 1500, PointPrecision.APPROXIMATE, 30) },
                { PointPlacementRepository(it).removePoint(next.version, moved, 30) })
            assertEquals(1, deletes.count { it.isSuccess })
            assertIs<PointPlacementFailure.StaleVersion>(deletes.single { it.isFailure }.exceptionOrNull())
            open(file).use { driver ->
                val db = PrototypeDatabase(driver); openDesktopPriceCatalog(driver, true)
                val points = PointPlacementRepository(db).forMeasurement(f.element.measurementUuid)
                assertEquals(points.size * 1000L, current().placedQuantityMilli)
                verifyPointPlacementStructure(driver)
            }
        } finally { pool.shutdownNow(); pool.awaitTermination(30, TimeUnit.SECONDS); file.delete() }
    }
}
