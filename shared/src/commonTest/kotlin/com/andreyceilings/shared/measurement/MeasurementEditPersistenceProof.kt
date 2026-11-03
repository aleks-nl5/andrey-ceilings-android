package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Same CAS/RETURNING proof on SQLite, Windows SQLCipher and iOS Simulator SQLCipher. */
object MeasurementEditPersistenceProof {
    suspend fun run(db: PrototypeDatabase): Long = coroutineScope {
        val projects = ProjectRepository(db)
        val rows = MeasurementRepository(db)
        val project = projects.createReturningId(null, "CMP edit", createdAt = 1)
        val other = projects.createReturningId(null, "Other", createdAt = 2)
        val create = MeasurementCreateStore(db) { 10 }
        val store = MeasurementEditStore(db) { 10 } // Deliberately same clock: versions must still increase.
        for (precision in MeasurementPrecision.entries) for (shape in listOf("rectangle", "l_shaped", "polygon")) {
            val id = create.create(MeasurementCreateFixtures.draft(project, shape, precision))
            val before = assertNotNull(rows.find(id))
            val document = store.load(project, id)
            MeasurementDetailsStore(db).observe(project, id).first { it is MeasurementDetailsState.Loaded }
            assertEquals(before, rows.find(id), "Readonly/load never writes")
            assertFails { store.load(other, id) }
            val model = MeasurementEditModel(store, document)
            // Change real geometry, not just metadata. Polygon length edits are one multi-field operation.
            when (shape) {
                "rectangle" -> model.accept(MeasurementCreateIntent.Dimension("length", "6"), grouped = true)
                "l_shaped" -> model.accept(MeasurementCreateIntent.Dimension("A", "6"), grouped = true)
                else -> {
                    model.accept(MeasurementCreateIntent.SideLength(0, "6"), grouped = true)
                    model.accept(MeasurementCreateIntent.SideLength(2, "4"), grouped = true)
                }
            }
            model.commitInput()
            val changed = model.state.value.draft
            model.undo(); assertEquals(document.draft, model.state.value.draft)
            model.redo(); assertEquals(changed, model.state.value.draft)
            coroutineScope { repeat(8) { launch { model.save() } } }
            assertTrue(model.state.value.saved, model.state.value.error)
            model.save()
            val after = assertNotNull(rows.find(id))
            assertEquals(before.uuid, after.uuid); assertEquals(before.createdAt, after.createdAt)
            assertEquals(before.projectId, after.projectId); assertEquals(11L, after.updatedAt)
            val geometry = MeasurementGeometryCodec.read(after.drawingJson)
            assertEquals(validateMeasurementCreate(changed).geometry, geometry)
            assertEquals(precision.stepMeters, geometry.precisionStepMeters)
            assertEquals(if (shape == "rectangle") 24.0 else 16.0, geometry.areaSquareMeters, 0.000001)
            assertEquals(20.0, geometry.perimeterMeters, 0.000001)
            assertEquals(geometry.areaSquareMeters, after.areaSquareMeters)
            assertEquals(geometry.perimeterMeters, after.perimeterMeters)
            assertEquals(geometry, validateMeasurementCreate(store.load(project, id).draft).geometry)
            val stale = MeasurementEditModel(store, document)
            stale.accept(MeasurementCreateIntent.Name("Stale")); stale.save()
            assertTrue(stale.state.value.conflict)
            assertEquals(after, rows.find(id), "Stale writer cannot overwrite")
            val cancelled = MeasurementEditModel(store, store.load(project, id))
            cancelled.accept(MeasurementCreateIntent.Notes("discard"))
            var left = false
            cancelled.requestExit { left = true }
            cancelled.discardAndExit()
            assertTrue(left); assertEquals(after, rows.find(id))
            val noop = MeasurementEditModel(store, store.load(project, id)); noop.save()
            assertTrue(noop.state.value.saved); assertEquals(after, rows.find(id), "No-op does not bump version")
            val snapshot = MeasurementEditModel(store, store.load(project, id)).snapshot()
            val restored = assertNotNull(MeasurementEditModel.restore(store, snapshot, project, id))
            assertFalse(restored.state.value.saving); assertFalse(restored.state.value.saved)
            assertEquals(after, rows.find(id), "Restoring never executes Save")
            val current = store.load(project, id)
            val wrongOwner = current.copy(draft = current.draft.copy(projectId = other))
            assertFailsWith<MeasurementEditConflict> { store.update(wrongOwner, wrongOwner.draft.copy(name = "foreign")) }
            val deleted = MeasurementEditModel(store, current)
            val deletedNoop = MeasurementEditModel(store, current)
            deleted.accept(MeasurementCreateIntent.Name("Deleted")); rows.delete(id); deleted.save()
            assertTrue(deleted.state.value.conflict); assertNull(rows.find(id))
            deletedNoop.save(); assertTrue(deletedNoop.state.value.conflict, "Even unchanged Save checks the live record")
        }
        val id = create.create(MeasurementCreateFixtures.draft(project, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
        val orphan = MeasurementEditModel(store, store.load(project, id))
        orphan.accept(MeasurementCreateIntent.Name("orphan")); projects.delete(project); orphan.save()
        assertTrue(orphan.state.value.conflict)
        assertFails { store.load(project, id) }
        project
    }
}
