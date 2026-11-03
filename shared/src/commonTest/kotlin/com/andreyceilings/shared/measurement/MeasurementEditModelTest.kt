package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.navigation.*
import kotlin.test.*

class MeasurementEditModelTest {
    private fun original() = MeasurementEditDocument(2, 100,
        MeasurementCreateFixtures.draft(1, "rectangle", MeasurementPrecision.ONE_MILLIMETER))

    @Test fun textInputIsOneUndoOperationAndBranchingClearsRedo() {
        val model = MeasurementEditModel(null, original())
        val before = model.state.value.draft
        for (value in listOf("", "6", "6,", "6,1")) model.accept(MeasurementCreateIntent.Dimension("length", value), grouped = true)
        model.commitInput()
        model.undo()
        assertEquals(before, model.state.value.draft)
        assertFalse(model.state.value.canUndo)
        model.redo()
        assertEquals("6,1", (model.state.value.draft.outline as MeasurementOutlineDraft.Template).fields["length"])
        model.undo()
        model.accept(MeasurementCreateIntent.Shape("polygon"))
        assertFalse(model.state.value.canRedo)
        model.accept(MeasurementCreateIntent.AddSide)
        assertEquals(5, (model.state.value.draft.outline as MeasurementOutlineDraft.Orthogonal).sides.size)
        model.undo()
        assertEquals(4, (model.state.value.draft.outline as MeasurementOutlineDraft.Orthogonal).sides.size)
        model.undo()
        assertEquals(before, model.state.value.draft)
    }
    @Test fun dirtyNavigationNeedsExplicitChoiceAndRestoreContainsNoWriteCommand() {
        val model = MeasurementEditModel(null, original())
        val route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(1).openMeasurement(1, 2).editMeasurement(1, 2)
        val guard = MeasurementCreateNavigationGuard().also { it.bindEdit(model) }
        model.accept(MeasurementCreateIntent.Notes("changed"), grouped = true)
        var target: AppNavigationState? = null
        assertEquals(route, guard.transition(route, route.selectTab(AppRoute.PRICES)) { target = it })
        assertTrue(model.state.value.exitRequested)
        assertNull(target)
        model.continueEditing()
        assertTrue(model.state.value.dirty)
        val restored = assertNotNull(MeasurementEditModel.restore(null, model.snapshot(), 1, 2))
        assertEquals(model.state.value.draft, restored.state.value.draft)
        assertFalse(restored.state.value.saving)
        assertFalse(restored.state.value.saved)
        assertFalse(restored.state.value.exitRequested)
        restored.undo()
        assertEquals(original().draft.copy(recordUuid = restored.state.value.draft.recordUuid), restored.state.value.draft)
        guard.transition(route, route.back()) { target = it }
        model.discardAndExit()
        assertEquals(route.back(), target)
        assertFalse(model.state.value.dirty)
    }
    @Test fun typedEditRoundTripBackReturnsToSameMeasurementAndParent() {
        val details = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(10).openMeasurement(10, 20)
        val edit = details.editMeasurement(10, 20)
        assertEquals(edit, AppNavigationState.restore(edit.snapshot()))
        assertEquals(details, edit.back())
        assertEquals(details, edit.measurementSaved(10, 20))
        assertEquals(10L, edit.back().back().projectDetails?.projectId)
        assertNull(AppNavigationState.restore(listOf("OBJECTS", "PROJECT_DETAILS:10", "MEASUREMENT_EDIT:11:20")).measurementEdit)
        assertNull(AppNavigationState.restore(listOf("OBJECTS", "PROJECT_DETAILS:10", "MEASUREMENT_EDIT:10:020")).measurementEdit)
    }
    @Test fun viewportHitTestingAndTemplateRelationsDoNotChangeWorldGeometry() {
        val points = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(100.0, 0.0), DrawingPoint(100.0, 100.0), DrawingPoint(0.0, 100.0))
        val viewport = MeasurementViewport().move(10f, 20f, 2f)
        val projected = points.map { viewport.transform(it, 100f, 100f) }
        assertEquals(0, measurementWallAt(projected, 60f, -29f, 4f))
        assertNull(measurementWallAt(projected, 60f, 70f, 4f))
        assertEquals(12f, viewport.move(0f, 0f, 100f).zoom)
        assertEquals("C", measurementWallParameter("l_shaped", 4))
        for (side in listOf(2, 3, 5)) assertNull(measurementWallParameter("l_shaped", side))
        assertEquals("length", measurementWallParameter("rectangle", 2))
    }
}
