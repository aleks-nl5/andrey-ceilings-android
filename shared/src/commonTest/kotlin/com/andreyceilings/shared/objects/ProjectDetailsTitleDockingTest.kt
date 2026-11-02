package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Docking is triggered by real list position, not by how far the title can
 * travel. A finished tween remains finished while the user stops scrolling.
 */
class ProjectDetailsTitleDockingTest {
    @Test
    fun initialPageOpensWithRegularAppHeader() {
        assertFalse(projectTitleDocked(0, 0))
    }

    @Test
    fun anyRealScrollCanCompleteDockingOnShortPages() {
        assertTrue(projectTitleDocked(0, 1))
        assertTrue(projectTitleDocked(0, 3))
        assertTrue(projectTitleDocked(0, 8))
        assertTrue(projectTitleDocked(0, 22))
        assertTrue(projectTitleDocked(0, 500))
    }

    @Test
    fun reverseScrollKeepsHeaderDockedUntilContentIsAtOriginalTop() {
        assertTrue(projectTitleDocked(12, 0))
        assertTrue(projectTitleDocked(1, 0))
        assertTrue(projectTitleDocked(0, 80))
        assertTrue(projectTitleDocked(0, 1))
        assertFalse(projectTitleDocked(0, 0))
    }

    @Test
    fun clampedAndSavedPositionsDoNotAccidentallyUndock() {
        assertTrue(projectTitleDocked(3, -1))
        assertFalse(projectTitleDocked(0, -1))
        assertFalse(projectTitleDocked(0, 0))
    }
}
