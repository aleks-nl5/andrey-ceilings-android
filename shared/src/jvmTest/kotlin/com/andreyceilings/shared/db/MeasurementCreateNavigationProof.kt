package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual transaction paused after active-parent validation, before INSERT RETURNING. */
object MeasurementCreateNavigationProof {
    suspend fun run(driver: SqlDriver): Unit = coroutineScope {
        val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "Navigation parent", createdAt = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = MeasurementCreateStore(db) {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Test did not release transaction" }
            2L
        }
        val model = MeasurementCreateModel(store,
            MeasurementCreateFixtures.draft(project, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
        val guard = MeasurementCreateNavigationGuard()
        guard.bind(model)
        val route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).createMeasurement(project)
        val save = launch(start = CoroutineStart.UNDISPATCHED) { model.save() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(model.state.value.saving)
            assertTrue(guard.blocksExit(route))
            val attempts = listOf(route.back(), // Android Back / native Escape target
                route.selectTab(AppRoute.PRICES), route.navigate(AppRoute.DASHBOARD),
                route.replace(AppRoute.MEASUREMENTS), route.openProject(project + 1),
                route.openMeasurement(project, 999), route.requestQuickCreate(AppQuickCreate.OBJECT))
            attempts.forEach { assertEquals(route, guard.transition(route, it)) }
            assertFalse(model.cancel())
            model.save() // A second tap must return, not queue another transaction.
        } finally { release.countDown() }
        withTimeout(10_000) { save.join() }
        val id = assertNotNull(model.state.value.savedId, model.state.value.error)
        assertTrue(guard.blocksExit(route), "Guard also covers commit → onSaved delivery")
        assertEquals(route, guard.transition(route, route.back()))
        assertEquals(route, guard.transition(route, route.openMeasurement(project, id + 1)))
        val details = guard.transition(route, route.measurementCreated(project, id))
        assertEquals(MeasurementDetails(project, id), details.measurementDetails)
        assertNull(details.measurementCreate)
        assertFalse(guard.blocksExit(details))
        assertEquals(details.back(), guard.transition(details, details.back()))
        model.save()
        assertEquals(id, MeasurementRepository(db).forProject(project).single().id)
        guard.unbind(model)

        val failed = MeasurementCreateModel(null,
            MeasurementCreateFixtures.draft(project, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
        guard.bind(failed)
        failed.save()
        assertNotNull(failed.state.value.error)
        assertFalse(guard.blocksExit(route), "A failed write re-enables Cancel/Back/tab")
        assertEquals(route.back(), guard.transition(route, route.back()))
        assertEquals(route.selectTab(AppRoute.PRICES), guard.transition(route, route.selectTab(AppRoute.PRICES)))
        assertEquals(id, MeasurementRepository(db).forProject(project).single().id)
        guard.unbind(failed)
    }
}
