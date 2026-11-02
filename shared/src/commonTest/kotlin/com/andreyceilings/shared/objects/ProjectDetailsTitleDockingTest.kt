package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real title row, not the swipe direction, controls the floating header. */
class ProjectDetailsTitleDockingTest {
    @Test
    fun docksExactlyWhenOriginalTitleReachesTheSafeTopEdge() {
        assertFalse(shouldDockProjectTitle(0, null, 30))
        assertFalse(shouldDockProjectTitle(0, 110, 30))
        assertFalse(shouldDockProjectTitle(0, 31, 30))
        assertTrue(shouldDockProjectTitle(0, 30, 30))
        assertTrue(shouldDockProjectTitle(0, -40, 30))
        assertTrue(shouldDockProjectTitle(1, null, 30))
        assertTrue(shouldDockProjectTitle(4, null, 30))
    }

    @Test
    fun reverseScrollKeepsTitleDockedUntilOriginalRowReturns() {
        // Reversing direction in the middle of a long list does nothing.
        assertTrue(shouldDockProjectTitle(12, null, 30))
        assertTrue(shouldDockProjectTitle(3, null, 30))
        // The original title may be partly visible, but it has not reached
        // the floating title's real location; keep the overlay.
        assertTrue(shouldDockProjectTitle(0, -46, 30))
        assertTrue(shouldDockProjectTitle(0, 29, 30))
        // Once the two rows would coincide, the original takes over naturally.
        assertFalse(shouldDockProjectTitle(0, 31, 30))
        assertFalse(shouldDockProjectTitle(0, 140, 30))
    }
}
