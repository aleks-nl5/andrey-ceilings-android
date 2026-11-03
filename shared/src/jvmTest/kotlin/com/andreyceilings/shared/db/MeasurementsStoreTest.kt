package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class MeasurementsStoreTest {
    @Test fun globalListAndCalculatorReadSavedShapesAndPrecisionWithoutWrites(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val projects = ProjectRepository(db)
            val rows = MeasurementRepository(db)
            val store = MeasurementsStore(db)
            suspend fun loaded() = store.observe().filterIsInstance<MeasurementsState.Loaded>().first()
            assertTrue(loaded().projects.isEmpty())
            val project = projects.createReturningId(null, "Chosen project", createdAt = 1)
            assertEquals(project, loaded().projects.single().id)
            assertTrue(loaded().measurements.isEmpty())
            val other = projects.createReturningId(null, "Another active project", createdAt = 1)
            assertEquals(setOf(project, other), loaded().projects.map { it.id }.toSet())
            val create = MeasurementCreateStore(db) { 2 }
            val editStore = MeasurementEditStore(db) { 3 }
            for (shape in listOf("rectangle", "l_shaped", "polygon")) for (precision in MeasurementPrecision.entries) {
                val draft = MeasurementCreateFixtures.draft(project, shape, precision)
                val nextList = async(start = CoroutineStart.UNDISPATCHED) {
                    store.observe().filterIsInstance<MeasurementsState.Loaded>()
                        .first { state -> state.measurements.any { it.uuid == draft.recordUuid } }
                }
                val id = create.create(draft)
                assertEquals(id, withTimeout(10_000) { nextList.await() }.measurements.first { it.id == id }.id)
                val before = rows.all()
                val source = store.geometry(project, id)
                assertEquals(validateMeasurementCreate(draft).geometry, source)
                assertEquals(precision.stepMeters, source.precisionStepMeters)
                assertEquals(before, rows.all(), "Calculate source is read-only")
                assertFails { store.geometry(other, id) }
                assertFails { store.delete(other, id) }
                assertEquals(before, rows.all(), "Wrong-parent actions cannot mutate a measurement")
                val model = MeasurementEditModel(editStore, editStore.load(project, id))
                val originalRoute = AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openMeasurement(project, id)
                val route = originalRoute.editMeasurement(project, id)
                val guard = MeasurementCreateNavigationGuard().also { it.bindEdit(model) }
                model.accept(MeasurementCreateIntent.Name("Edited $shape"))
                var approved: AppNavigationState? = null
                assertEquals(route, guard.transition(route, route.back()) { approved = it })
                assertNull(approved, "Dirty Back waits for a decision")
                model.continueEditing()
                model.save()
                guard.fullscreen.value = true
                guard.bindSurface { true }
                assertTrue(guard.deliverEditResult(route, project, id) { approved = it })
                assertEquals(originalRoute, approved)
                assertFalse(guard.deliverEditResult(route, project, id) {}, "Result is one-shot")
                model.delivered()
                assertEquals("Edited $shape", loaded().measurements.first { it.id == id }.name)
                assertEquals(source, store.geometry(project, id))
                store.delete(project, id)
                assertFalse(loaded().measurements.any { it.id == id })
                assertNotNull(rows.tombstones().firstOrNull { it.id == id })
                assertFails { store.geometry(project, id) }
            }
            projects.delete(project)
            projects.delete(other)
            assertTrue(loaded().projects.isEmpty())
            assertTrue(loaded().measurements.isEmpty())
            driver.close()
            assertEquals(MeasurementsState.Unavailable, store.observe().first { it !is MeasurementsState.Loading })
        }
    }
}
