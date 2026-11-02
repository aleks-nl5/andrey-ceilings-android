package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A floating title must follow its real source row, never swipe direction. */
class ProjectDetailsTitleDockingTest {
    @Test
    fun onlyDocksAfterOriginalTitlePassesTheSafeTopEdge() {
        assertFalse(shouldDockProjectTitle(0, null, null, 30))
        assertFalse(shouldDockProjectTitle(0, 110, 76, 30))
        assertFalse(shouldDockProjectTitle(0, -40, 76, 30))
        assertTrue(shouldDockProjectTitle(0, -46, 76, 30))
        assertTrue(shouldDockProjectTitle(1, null, null, 30))
        assertTrue(shouldDockProjectTitle(4, null, null, 30))
    }

    @Test
    fun reverseSwipeDoesNotRevealOriginalUntilItActuallyReachesItsPosition() {
        // A swipe upward may move lower rows but must not unpin the address.
        assertTrue(shouldDockProjectTitle(12, null, null, 30))
        assertTrue(shouldDockProjectTitle(3, null, null, 30))
        assertTrue(shouldDockProjectTitle(0, -47, 76, 30))
        // The original title becomes visible again only when its bottom clears
        // the dock threshold, independently of the direction of the last gesture.
        assertFalse(shouldDockProjectTitle(0, -45, 76, 30))
        assertFalse(shouldDockProjectTitle(0, 140, 76, 30))
    }
}
