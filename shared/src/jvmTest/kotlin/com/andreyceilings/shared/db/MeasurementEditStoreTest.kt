package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MeasurementEditStoreTest {
    @Test fun editEveryShapeAndPrecisionAndRejectStaleDeletedOrForeignRows(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            MeasurementEditPersistenceProof.run(PrototypeDatabase(driver))
        }
    }
    @Test fun inFlightSaveCannotBeLostAndActivityRestoreReattachesWithoutWriting(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            MeasurementEditNavigationProof.run(driver)
        }
    }
    @Test fun failedUpdateRollsBackAndUnavailableDbRetainsDraft(): Unit = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PrototypeDatabase.Schema.create(driver)
        val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "P", createdAt = 1)
        val id = MeasurementCreateStore(db) { 2 }.create(MeasurementCreateFixtures.draft(project, "rectangle", MeasurementPrecision.ONE_MILLIMETER))
        val store = MeasurementEditStore(db) { 3 }
        val original = store.load(project, id)
        val before = MeasurementRepository(db).find(id)
        driver.execute(null, "CREATE TRIGGER reject_update BEFORE UPDATE ON room_measurements BEGIN SELECT RAISE(ABORT, 'failure'); END", 0)
        val model = MeasurementEditModel(store, original)
        model.accept(MeasurementCreateIntent.Name("Changed")); model.save()
        assertFalse(model.state.value.saved); assertNotNull(model.state.value.error)
        assertEquals(before, MeasurementRepository(db).find(id))
        driver.execute(null, "DROP TRIGGER reject_update", 0)
        model.save(); assertTrue(model.state.value.saved)
        driver.close()
        val unavailable = MeasurementEditModel(store, original)
        unavailable.accept(MeasurementCreateIntent.Notes("Changed")); unavailable.save()
        assertNotNull(unavailable.state.value.error); assertTrue(unavailable.state.value.dirty)
    }
}

object MeasurementEditNavigationProof {
    suspend fun run(driver: app.cash.sqldelight.db.SqlDriver): Unit = coroutineScope {
        val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "Edit navigation", createdAt = 1)
        val id = MeasurementCreateStore(db) { 2 }.create(MeasurementCreateFixtures.draft(project, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = MeasurementEditStore(db) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); 3 }
        val model = MeasurementEditModel(store, store.load(project, id))
        model.accept(MeasurementCreateIntent.Dimension("length", "6"))
        val guard = MeasurementCreateNavigationGuard().also { it.bindEdit(model) }
        val route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).openMeasurement(project, id).editMeasurement(project, id)
        val write = launch(start = CoroutineStart.UNDISPATCHED) { model.save() }
        var inFlightSnapshot = ""
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS)); assertTrue(guard.blocksExit(route))
            for (target in listOf(route.back(), route.selectTab(AppRoute.PRICES), route.requestQuickCreate(AppQuickCreate.OBJECT)))
                assertEquals(route, guard.transition(route, target))
            var closed = false; guard.requestClose { closed = true }; assertFalse(closed)
            inFlightSnapshot = model.snapshot()
            assertSame(model, MeasurementEditModel.restore(store, inFlightSnapshot, project, id))
            model.save(); model.undo(); model.discardAndExit()
        } finally { release.countDown() }
        withTimeout(10_000) { write.join() }
        assertTrue(model.state.value.saved, model.state.value.error)
        assertEquals(route, guard.transition(route, route.back())) // commit -> delivered guard
        var deliveredRoute: AppNavigationState? = null
        assertTrue(guard.deliverEditResult(route, project, id) { deliveredRoute = it })
        assertEquals(route.measurementSaved(project, id), deliveredRoute)
        assertEquals(3L, MeasurementRepository(db).find(id)?.updatedAt)
        model.delivered()
        val processRestored = assertNotNull(MeasurementEditModel.restore(MeasurementEditStore(db) { 99 }, inFlightSnapshot, project, id))
        processRestored.reconcile()
        assertTrue(processRestored.state.value.saved, processRestored.state.value.error)
        assertEquals(3L, MeasurementRepository(db).find(id)?.updatedAt)
        val recovered = assertNotNull(MeasurementEditModel.restore(store, model.snapshot(), project, id))
        recovered.reconcile()
        // Snapshot after completion contains committed baseline; restoration remains a read, no replay.
        assertEquals(3L, MeasurementRepository(db).find(id)?.updatedAt)
    }
}
