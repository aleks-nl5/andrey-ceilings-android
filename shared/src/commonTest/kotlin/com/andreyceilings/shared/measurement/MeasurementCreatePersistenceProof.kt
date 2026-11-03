package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.objects.ObjectsClientsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.test.*

/** Runs against both real Windows JDBC SQLCipher and iOS Simulator SQLCipher. */
object MeasurementCreatePersistenceProof {
    suspend fun run(database: PrototypeDatabase): Long = coroutineScope {
        val projects = ProjectRepository(database)
        val measurements = MeasurementRepository(database)
        val parent = projects.createReturningId(null, "CMP create parent", createdAt = 100)
        val sibling = projects.createReturningId(null, "Other parent", createdAt = 101)
        val store = MeasurementCreateStore(database) { 200 }
        val objects = ObjectsClientsStore(database) { 201 }
        val counts = Channel<Int>(Channel.UNLIMITED)
        val observer = launch { objects.observeProjectDetails(parent).collect { counts.send(it.measurements.size) } }
        suspend fun awaitCount(expected: Int) = withTimeout(10_000) {
            while (counts.receive() != expected) { /* SQLDelight may conflate intermediate notifications. */ }
        }
        try {
            awaitCount(0)
            var count = 0
            for (precision in MeasurementPrecision.entries) for (shape in listOf("rectangle", "l_shaped", "polygon")) {
                val draft = MeasurementCreateFixtures.draft(parent, shape, precision)
                val model = MeasurementCreateModel(store, draft)
                coroutineScope { repeat(8) { launch { model.save() } } }
                val id = assertNotNull(model.state.value.savedId, model.state.value.error)
                model.save()
                assertEquals(id, model.state.value.savedId)
                assertEquals(++count, measurements.forProject(parent).size)
                awaitCount(count)
                val row = assertNotNull(measurements.find(id))
                assertEquals(parent, row.projectId)
                assertEquals(draft.name.trim(), row.name)
                assertEquals("Примітки", row.notes)
                assertEquals(200L, row.createdAt)
                assertEquals(row.createdAt, row.updatedAt)
                assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(row.uuid))
                assertEquals(validateMeasurementCreate(draft).geometry, MeasurementGeometryCodec.read(row.drawingJson))
                val afterProcessRestore = MeasurementCreateModel(store,
                    assertNotNull(MeasurementCreateDraft.restore(draft.snapshot(), parent)))
                assertNull(afterProcessRestore.state.value.savedId)
                afterProcessRestore.save() // Explicit retry after an uncertain committed result.
                assertEquals(id, afterProcessRestore.state.value.savedId)
                assertEquals(count, measurements.forProject(parent).size)
                val changedRestored = MeasurementCreateModel(store, draft.copy(name = "Changed after restore"))
                changedRestored.save()
                assertNull(changedRestored.state.value.savedId)
                assertTrue(changedRestored.state.value.error.orEmpty().contains("вже збережено"))
                val route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(parent).createMeasurement(parent)
                    .measurementCreated(parent, id)
                assertNull(route.measurementCreate)
                assertEquals(parent, route.back().projectDetails?.projectId)
                val details = assertIs<MeasurementDetailsState.Loaded>(MeasurementDetailsStore(database)
                    .observe(parent, id).first { it !is MeasurementDetailsState.Loading })
                assertEquals(shape, details.data.shape)
            }
            assertTrue(measurements.forProject(sibling).isEmpty())
            val valid = MeasurementCreateFixtures.draft(parent, "polygon", MeasurementPrecision.ONE_MILLIMETER)
            val invalid = MeasurementCreateModel(store, valid.reduce(MeasurementCreateIntent.SideLength(0, "")))
            invalid.save()
            assertNull(invalid.state.value.savedId)
            val cancelled = MeasurementCreateModel(store, valid)
            assertTrue(cancelled.cancel())
            cancelled.save()
            assertNull(cancelled.state.value.savedId)
            val restored = MeasurementCreateModel(store, assertNotNull(MeasurementCreateDraft.restore(valid.snapshot(), parent)))
            assertNull(restored.state.value.savedId)
            assertEquals(count, measurements.forProject(parent).size, "Restore and Cancel never write")
            val deleted = projects.createReturningId(null, "Deleted before Save", createdAt = 300)
            val missing = MeasurementCreateModel(store, valid.copy(projectId = deleted))
            projects.delete(deleted)
            missing.save()
            assertNull(missing.state.value.savedId)
            assertTrue(missing.state.value.error.orEmpty().contains("видалено"))
            assertEquals(MeasurementCreateParent.Missing, store.observeParent(deleted).first { it !is MeasurementCreateParent.Loading })
            assertTrue(measurements.forProject(deleted).isEmpty())
            assertEquals(count, measurements.forProject(parent).size)
            parent
        } finally { observer.cancel(); counts.close() }
    }
}
