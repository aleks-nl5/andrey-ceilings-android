package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PointPlacementSessionTest {
    @Test fun concurrentChangeToUnknownExitsModeBeforeAnotherTap(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver); val f = PointPlacementProof.fixture(db)
            val store = MeasurementElementsStore(db, { 20 }, Dispatchers.Unconfined)
            try {
                val model = store.model(ElementRoute(f.project, f.id, f.element.measurementUuid))
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                withTimeout(10_000) { model.points.first { it.available } }
                model.beginPlacement(f.element, f.contourVersion)
                assertEquals(f.element.uuid, model.points.value.modeElementUuid)
                MeasurementElementRepository(db).edit(f.element.version, "", f.element.approval, ElementQuantity.Unknown, 10)
                withTimeout(10_000) { model.points.first { it.modeElementUuid == null && it.selectedElementUuid == null } }
                model.tapPoint(1000, 1000)
                assertTrue(PointPlacementRepository(db).forMeasurement(f.element.measurementUuid).isEmpty())
            } finally { store.closeAndAwaitWrites() }
        }
    }
    @Test fun acceptedPointWriteDrainsBeforeCloseAndCannotReplayInOldOrDifferentRoute(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver); val f = PointPlacementProof.fixture(db)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
            val error = AtomicReference<Throwable?>(null)
            val store = MeasurementElementsStore(db, {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); 10L
            }, Dispatchers.Unconfined)
            val route = ElementRoute(f.project, f.id, f.element.measurementUuid)
            val model = store.model(route)
            val closer = Thread {
                try { runBlocking { store.closeAndAwaitWrites() } }
                catch (failure: Throwable) { error.set(failure) }
                finally { finished.countDown() }
            }
            try {
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                withTimeout(10_000) { model.points.first { it.available } }
                model.beginPlacement(f.element, f.contourVersion)
                model.tapPoint(1000, 1000)
                assertTrue(model.points.value.saving)
                repeat(10) { model.tapPoint(1200, 1200) }
                val guard = MeasurementCreateNavigationGuard().also { it.bindElements(model) }
                val nav = com.andreyceilings.shared.navigation.AppNavigationState()
                    .selectTab(com.andreyceilings.shared.navigation.AppRoute.OBJECTS).openProject(f.project).openMeasurement(f.project, f.id)
                assertEquals(nav, guard.transition(nav, nav.back()))
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                closer.start(); assertFalse(finished.await(200, TimeUnit.MILLISECONDS))
                release.countDown(); assertTrue(finished.await(10, TimeUnit.SECONDS))
                error.get()?.let { throw AssertionError("Point close drain failed", it) }
                assertEquals(1, PointPlacementRepository(db).forMeasurement(f.element.measurementUuid).size)
                assertEquals(PointPlacementState(), model.points.value)
                model.tapPoint(1000, 1000); model.retryPoint()
                assertEquals(1, PointPlacementRepository(db).forMeasurement(f.element.measurementUuid).size)
                val closedModel = store.model(route)
                assertFalse(closedModel.points.value.available)
                val nextStore = MeasurementElementsStore(db, { 20 }, Dispatchers.Unconfined)
                val next = nextStore.model(route.copy(measurementUuid = "wrong-old-uuid"))
                assertEquals(PointPlacementState(), next.points.value.copy(available = false))
                next.beginPlacement(f.element, f.contourVersion); next.tapPoint(2000, 2000)
                assertEquals(1, PointPlacementRepository(db).forMeasurement(f.element.measurementUuid).size)
                nextStore.close()
            } finally { release.countDown(); closer.join(10_000); store.close() }
        }
    }
}
