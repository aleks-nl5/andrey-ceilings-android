package com.andreyceilings.shared.navigation

import kotlin.test.*

class MeasurementCreateNavigationTest {
    @Test fun createDoubleTapBackAndSuccessRetainTheParentAndListHistory() {
        val project = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7)
        val editor = project.createMeasurement(7)
        assertEquals(MeasurementCreate(7), editor.measurementCreate)
        assertSame(editor, editor.createMeasurement(7))
        assertEquals(project, editor.back())
        val saved = editor.measurementCreated(7, 12)
        assertNull(saved.measurementCreate)
        assertEquals(MeasurementDetails(7, 12), saved.measurementDetails)
        assertEquals(project, saved.back())
        assertEquals(project.back(), saved.back().back())
        assertSame(project, project.measurementCreated(7, 12), "Late completion must not reopen a cancelled editor")
        assertNull(editor.selectTab(AppRoute.PRICES).measurementCreate)
        assertNull(editor.openProject(8).measurementCreate)
        assertSame(project, project.createMeasurement(8))
        assertSame(project, project.createMeasurement(0))
        assertNull(AppNavigationState().createMeasurement(7).measurementCreate)
    }

    @Test fun idOnlySnapshotRestoresTheScreenAndNeverASaveCommand() {
        val editor = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7).createMeasurement(7)
        assertEquals(listOf("OBJECTS", "DASHBOARD", "PROJECT_DETAILS:7", "MEASUREMENT_CREATE:7"), editor.snapshot())
        assertEquals(editor, AppNavigationState.restore(editor.snapshot()))
        assertNull(AppNavigationState.restore(editor.snapshot()).pendingQuickCreate)
        for (suffix in listOf("0", "-1", "+7", "07", "8", "7:2", "9223372036854775808")) {
            val restored = AppNavigationState.restore(listOf("OBJECTS", "PROJECT_DETAILS:7", "MEASUREMENT_CREATE:$suffix"))
            assertEquals(ProjectDetails(7), restored.projectDetails)
            assertNull(restored.measurementCreate)
        }
        for (snapshot in listOf(listOf("OBJECTS", "MEASUREMENT_CREATE:7"),
            listOf("DASHBOARD", "PROJECT_DETAILS:7", "MEASUREMENT_CREATE:7"),
            listOf("OBJECTS", "MEASUREMENT_CREATE:7", "PROJECT_DETAILS:7", "MEASUREMENT_CREATE:7"),
            listOf("OBJECTS", "MEASUREMENT_DETAILS:7:12", "PROJECT_DETAILS:7", "MEASUREMENT_CREATE:7"))) {
            assertNull(AppNavigationState.restore(snapshot).measurementCreate)
        }
        assertNull(AppNavigationState.restore(editor.snapshot(), setOf(AppRoute.DASHBOARD)).measurementCreate)
    }
}
