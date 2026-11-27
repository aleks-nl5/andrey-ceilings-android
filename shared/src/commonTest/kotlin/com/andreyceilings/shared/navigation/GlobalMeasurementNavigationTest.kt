package com.andreyceilings.shared.navigation

import kotlin.test.*

class GlobalMeasurementNavigationTest {
    @Test fun explicitPickerAndCreateKeepTheMeasurementsOriginAcrossRestore() {
        val list = AppNavigationState().selectTab(AppRoute.MEASUREMENTS)
        val picker = list.chooseMeasurementProject()
        assertTrue(picker.measurementProjectPicker)
        assertNull(picker.measurementCreate, "No implicit first/only project selection")
        assertEquals(picker, AppNavigationState.restore(picker.snapshot()))
        assertEquals(list, picker.back())
        assertSame(picker, picker.createMeasurement(0))
        val create = picker.createMeasurement(7)
        assertEquals(AppRoute.MEASUREMENTS, create.current)
        assertNull(create.projectDetails)
        assertFalse(create.measurementProjectPicker)
        assertEquals(create, AppNavigationState.restore(create.snapshot()))
        assertSame(create, create.createMeasurement(7))
        assertEquals(list, create.back())
        val saved = create.measurementCreatedInEditor(7, 12)
        assertEquals(MeasurementEdit(7, 12), saved.measurementEdit)
        assertNull(saved.measurementDetails)
        assertEquals(saved, AppNavigationState.restore(saved.snapshot()).restoreCeilingWorkspace())
        assertEquals(list, saved.back())
        assertSame(list, list.measurementCreatedInEditor(7, 12), "Late create cannot reopen a cancelled editor")
    }

    @Test fun workspaceCancelAndSaveNeverSwitchToObjects() {
        for (list in listOf(AppNavigationState().selectTab(AppRoute.MEASUREMENTS),
            AppNavigationState().selectTab(AppRoute.SETTINGS).navigate(AppRoute.MEASUREMENTS))) {
            val edit = list.openCeilingWorkspace(7, 12)
            assertEquals(edit, AppNavigationState.restore(edit.snapshot()))
            assertEquals(list, edit.back())
            assertEquals(list, edit.measurementSaved(7, 12))
            assertSame(edit, edit.measurementSaved(8, 12))
            assertSame(edit, edit.measurementSaved(7, 13))
            assertEquals(list.back(), edit.measurementSaved(7, 12).back())
            assertEquals(edit, list.openMeasurement(7, 12).restoreCeilingWorkspace())
            assertNull(edit.selectTab(AppRoute.PRICES).measurementEdit)
        }
    }

    @Test fun quickCreateIsARestorablePickerWithoutAReplayableCommand() {
        val list = AppNavigationState().selectTab(AppRoute.MEASUREMENTS)
        for (start in listOf(list, AppNavigationState(), AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7))) {
            val picker = start.requestQuickCreate(AppQuickCreate.MEASUREMENT)
            assertEquals(start.current, picker.current)
            assertTrue(picker.measurementProjectPicker)
            assertEquals(start, picker.back())
            assertSame(picker, picker.requestQuickCreate(AppQuickCreate.MEASUREMENT))
            val created = picker.createMeasurement(9).measurementCreatedInEditor(9, 10)
            assertEquals(MeasurementEdit(9, 10), created.measurementEdit)
            assertEquals(start, created.back())
            assertEquals(picker, AppNavigationState.restore(picker.snapshot()))
            assertNull(picker.pendingQuickCreate)
        }
    }

    @Test fun quickCreateUsesAnExplicitlyVerifiedProjectAndReturnsToIt() {
        val project = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7)
        val create = project.requestQuickCreate(AppQuickCreate.MEASUREMENT, activeProjectId = 7)
        assertEquals(MeasurementCreate(7), create.measurementCreate)
        assertEquals(create, AppNavigationState.restore(create.snapshot()))
        assertEquals(project, create.back())
        assertTrue(project.requestQuickCreate(AppQuickCreate.MEASUREMENT, activeProjectId = 8).measurementProjectPicker)
        val registry = project.navigate(AppRoute.MEASUREMENTS)
        assertEquals(project, AppNavigationState.restore(registry.snapshot()).back())
    }

    @Test fun calculatorReturnsToExactCeilingAcrossRestore() {
        val root = AppNavigationState()
        val origins = listOf(
            root.selectTab(AppRoute.MEASUREMENTS).openCeilingWorkspace(7, 12),
            root.selectTab(AppRoute.OBJECTS).openProject(7).openCeilingWorkspace(7, 12),
            root.requestQuickCreate(AppQuickCreate.MEASUREMENT).createMeasurement(7).measurementCreatedInEditor(7, 12)
        )
        for (origin in origins) {
            val calculator = origin.navigate(AppRoute.CALCULATOR)
            assertNull(calculator.measurementEdit)
            assertEquals(origin, calculator.back())
            assertEquals(calculator, AppNavigationState.restore(calculator.snapshot()))
            assertEquals(origin, AppNavigationState.restore(calculator.snapshot()).back())
            assertNull(calculator.selectTab(AppRoute.PRICES).back().measurementEdit)
        }
    }

    @Test fun malformedOrUnavailableGlobalChildrenAreRejected() {
        for (token in listOf("MEASUREMENT_DETAILS:07:12", "MEASUREMENT_DETAILS:7:0", "MEASUREMENT_DETAILS:7:12:3",
            "MEASUREMENT_CREATE:+7", "MEASUREMENT_CREATE:0", "MEASUREMENT_EDIT:7:012")) {
            val state = AppNavigationState.restore(listOf("MEASUREMENTS", token))
            assertNull(state.measurementDetails); assertNull(state.measurementCreate); assertNull(state.measurementEdit)
        }
        for (tail in listOf(listOf("MEASUREMENT_CREATE:7", "MEASUREMENT_DETAILS:7:12"),
            listOf("PROJECT_DETAILS:7", "MEASUREMENT_CREATE:7"),
            listOf("MEASUREMENT_PROJECT_PICKER", "MEASUREMENT_EDIT:7:12"))) {
            val state = AppNavigationState.restore(listOf("MEASUREMENTS") + tail)
            assertNull(state.measurementDetails); assertNull(state.measurementCreate); assertNull(state.measurementEdit)
        }
        val editor = AppNavigationState().selectTab(AppRoute.MEASUREMENTS).createMeasurement(7)
        assertNull(AppNavigationState.restore(editor.snapshot(), setOf(AppRoute.DASHBOARD)).measurementCreate)
    }
}
