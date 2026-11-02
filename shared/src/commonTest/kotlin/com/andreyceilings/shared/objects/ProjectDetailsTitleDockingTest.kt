package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
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
    fun snapDownwardFromStatusCardEvenWhenHeadingIsOffscreen() {
        // At-rest status top = 260px. With a 200px heading travel,
        // restored >=30% means current card top >=260-140=120px.
        // In this state LazyColumn may still report firstVisibleItemIndex=1.
        assertTrue(projectDetailsShouldSettleFromStatus(120, 260, 200, true, true, false))
        assertTrue(projectDetailsShouldSettleFromStatus(170, 260, 200, true, true, false))
        assertTrue(projectDetailsShouldSettleFromStatus(259, 260, 200, true, true, false))
        assertFalse(projectDetailsShouldSettleFromStatus(119, 260, 200, true, true, false))
        assertFalse(projectDetailsShouldSettleFromStatus(120, 260, 200, false, true, false))
        assertFalse(projectDetailsShouldSettleFromStatus(120, 260, 200, true, false, false))
        assertFalse(projectDetailsShouldSettleFromStatus(260, 260, 200, true, true, true))
        assertFalse(projectDetailsShouldSettleFromStatus(120, 260, 0, true, true, false))
    }

    @Test
    fun snapNeedsMeasuredStatusAnchorAndVisibleCard() {
        assertFalse(projectDetailsShouldSettleFromStatus(null, 260, 200, true, true, false))
        assertFalse(projectDetailsShouldSettleFromStatus(120, null, 200, true, true, false))
        assertFalse(projectDetailsShouldSettleFromStatus(120, 260, 200, true, true, true))
        assertFalse(projectDetailsShouldSettleFromStatus(261, 260, 200, true, true, false))
    }

    @Test
    fun centeredAddressSharesFabVerticalCenterAtDockAndStaysTopAlignedAtRest() {
        // FAB hit row is 56px: 24px single line needs 16px offset.
        assertEquals(0, projectAddressVerticalOffsetPx(0f, 56, 24))
        assertEquals(8, projectAddressVerticalOffsetPx(0.5f, 56, 24))
        assertEquals(16, projectAddressVerticalOffsetPx(1f, 56, 24))
        // Two lines of 52px: 2px offset. A 60px address extends 2px above
        // the 56px FAB hit row, but its center is still exactly aligned.
        assertEquals(2, projectAddressVerticalOffsetPx(1f, 56, 52))
        assertEquals(-2, projectAddressVerticalOffsetPx(1f, 56, 60))
    }

    @Test
    fun clampedAndSavedPositionsDoNotAccidentallyUndock() {
        assertTrue(projectTitleDocked(3, -1))
        assertFalse(projectTitleDocked(0, -1))
        assertFalse(projectTitleDocked(0, 0))
    }
}
