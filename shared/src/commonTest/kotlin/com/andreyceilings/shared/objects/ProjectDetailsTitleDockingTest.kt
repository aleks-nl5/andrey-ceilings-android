package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The title follows the actual list position, never the last swipe direction. */
class ProjectDetailsTitleDockingTest {
    private val start = 240
    private val pinned = 48

    @Test
    fun titleStartsInsideCardThenMovesContinuouslyToTop() {
        assertEquals(ProjectHeaderMotion(start, 0f), projectHeaderMotion(0, null, start, pinned))
        assertEquals(ProjectHeaderMotion(start, 0f), projectHeaderMotion(0, start, start, pinned))
        assertEquals(ProjectHeaderMotion(192, 0.25f), projectHeaderMotion(0, 192, start, pinned))
        assertEquals(ProjectHeaderMotion(144, 0.5f), projectHeaderMotion(0, 144, start, pinned))
        assertEquals(ProjectHeaderMotion(96, 0.75f), projectHeaderMotion(0, 96, start, pinned))
        assertEquals(ProjectHeaderMotion(pinned, 1f), projectHeaderMotion(0, pinned, start, pinned))
        assertEquals(ProjectHeaderMotion(pinned, 1f), projectHeaderMotion(0, -120, start, pinned))
    }

    @Test
    fun reverseScrollMovesHeaderOnlyWhenItsOriginalSlotComesBack() {
        assertEquals(ProjectHeaderMotion(pinned, 1f), projectHeaderMotion(16, null, start, pinned))
        assertEquals(ProjectHeaderMotion(pinned, 1f), projectHeaderMotion(1, null, start, pinned))
        assertEquals(ProjectHeaderMotion(pinned, 1f), projectHeaderMotion(0, 0, start, pinned))
        val returning = projectHeaderMotion(0, 120, start, pinned)
        assertTrue(returning.progress > 0f && returning.progress < 1f)
        assertEquals(120, returning.topPx)
        assertEquals(ProjectHeaderMotion(start, 0f), projectHeaderMotion(0, 300, start, pinned))
    }

    @Test
    fun clampsEvenOnShortOrUnexpectedInitialInset() {
        assertEquals(ProjectHeaderMotion(60, 0f), projectHeaderMotion(0, 60, 60, 60))
        assertEquals(ProjectHeaderMotion(60, 1f), projectHeaderMotion(0, 0, 60, 60))
        assertEquals(ProjectHeaderMotion(60, 1f), projectHeaderMotion(4, null, 60, 60))
    }
}
