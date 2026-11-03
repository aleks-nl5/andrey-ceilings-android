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
        val saved = create.measurementCreated(7, 12)
        assertEquals(MeasurementDetails(7, 12), saved.measurementDetails)
        assertEquals(list, saved.back())
        assertSame(list, list.measurementCreated(7, 12), "Late create cannot reopen a cancelled editor")
    }

    @Test fun detailsEditCancelAndSaveNeverSwitchToObjects() {
        for (list in listOf(AppNavigationState().selectTab(AppRoute.MEASUREMENTS),
            AppNavigationState().selectTab(AppRoute.SETTINGS).navigate(AppRoute.MEASUREMENTS))) {
            val details = list.openMeasurement(7, 12)
            val edit = details.editMeasurement(7, 12)
            assertEquals(edit, AppNavigationState.restore(edit.snapshot()))
            assertEquals(details, edit.back())
            assertEquals(details, edit.measurementSaved(7, 12))
            assertSame(edit, edit.measurementSaved(8, 12))
            assertSame(edit, edit.measurementSaved(7, 13))
            assertEquals(list, edit.measurementSaved(7, 12).back())
            assertEquals(list, details.back())
            assertNull(edit.selectTab(AppRoute.PRICES).measurementEdit)
        }
    }

    @Test fun quickCreateIsARestorablePickerWithoutAReplayableCommand() {
        val list = AppNavigationState().selectTab(AppRoute.MEASUREMENTS)
        for (start in listOf(list, AppNavigationState(), AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7))) {
            val picker = start.requestQuickCreate(AppQuickCreate.MEASUREMENT)
            assertEquals(list.chooseMeasurementProject(), picker)
            assertEquals(picker, AppNavigationState.restore(picker.snapshot()))
            assertNull(picker.pendingQuickCreate)
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
