package com.andreyceilings.shared.navigation

import kotlin.test.*

class MeasurementNavigationTest {
    @Test fun projectMeasurementBackAndDoubleTapUseOneNestedRoute() {
        val list = AppNavigationState().selectTab(AppRoute.OBJECTS)
        val project = list.openProject(7)
        val measurement = project.openMeasurement(7, 12)
        assertEquals(MeasurementDetails(7, 12), measurement.measurementDetails)
        assertSame(measurement, measurement.openMeasurement(7, 12))
        assertEquals(project, measurement.back())
        assertEquals(list, measurement.back().back())
        assertNull(measurement.openProject(8).measurementDetails)
        assertNull(measurement.selectTab(AppRoute.MEASUREMENTS).measurementDetails)
    }

    @Test fun snapshotRestoresOnlyCanonicalIdsAndNeverReplaysCommands() {
        val state = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7).openMeasurement(7, 12)
        assertEquals(listOf("OBJECTS", "DASHBOARD", "PROJECT_DETAILS:7", "MEASUREMENT_DETAILS:7:12"), state.snapshot())
        assertEquals(state, AppNavigationState.restore(state.snapshot()))
        assertNull(AppNavigationState.restore(state.copy(pendingQuickCreate = AppQuickCreate.MEASUREMENT).snapshot()).pendingQuickCreate)
        for (suffix in listOf("7:0", "7:-1", "7:012", "7:+12", "07:12", "8:12", "7:12:3", "7:9223372036854775808")) {
            val restored = AppNavigationState.restore(listOf("OBJECTS", "PROJECT_DETAILS:7", "MEASUREMENT_DETAILS:$suffix"))
            assertEquals(ProjectDetails(7), restored.projectDetails)
            assertNull(restored.measurementDetails)
        }
        assertNull(AppNavigationState.restore(listOf("OBJECTS", "MEASUREMENT_DETAILS:7:12")).measurementDetails)
        assertNull(AppNavigationState.restore(listOf("DASHBOARD", "PROJECT_DETAILS:7", "MEASUREMENT_DETAILS:7:12")).measurementDetails)
    }

    @Test fun wrongParentInvalidIdsAndUnavailableHostCannotOpenMeasurement() {
        val project = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7)
        assertSame(project, project.openMeasurement(8, 12))
        assertSame(project, project.openMeasurement(7, 0))
        assertNull(AppNavigationState().openMeasurement(7, 12).measurementDetails)
        assertNull(AppNavigationState.restore(project.openMeasurement(7, 12).snapshot(),
            setOf(AppRoute.DASHBOARD, AppRoute.PRICES)).measurementDetails)
    }
}
