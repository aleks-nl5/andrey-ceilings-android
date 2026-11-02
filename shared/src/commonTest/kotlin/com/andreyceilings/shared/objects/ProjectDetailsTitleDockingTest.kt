package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One title/actions layer must follow the real LazyColumn source slot.
 * No last-swipe-direction state and no second sticky copy are required.
 */
class ProjectDetailsTitleDockingTest {
    private val firstTop = 240
    private val safeTop = 24
    private val addressRow = 38
    private val totalTravel = firstTop + addressRow - safeTop // 254 pixels

    private fun at(index: Int, offset: Int?) =
        projectTitleMotion(index, offset, firstTop, safeTop, addressRow)

    @Test
    fun startsAtOriginalHeaderAndTransitionsContinuously() {
        assertEquals(ProjectTitleMotion(firstTop, 0f), at(0, null))
        assertEquals(ProjectTitleMotion(firstTop, 0f), at(0, firstTop))

        val quarter = at(0, firstTop - totalTravel / 4)
        val middle = at(0, firstTop - totalTravel / 2)
        val threeQuarters = at(0, firstTop - 3 * totalTravel / 4)
        assertTrue(quarter.progress in 0.24f..0.26f)
        assertTrue(middle.progress in 0.49f..0.51f)
        assertTrue(threeQuarters.progress in 0.74f..0.76f)
        assertTrue(quarter.progress < middle.progress && middle.progress < threeQuarters.progress)

        assertEquals(ProjectTitleMotion(safeTop - addressRow, 1f),
            at(0, safeTop - addressRow))
        assertEquals(1f, at(0, -500).progress)
    }

    @Test
    fun remainsDockedUntilOriginalAddressReturnsDespiteReverseSwipe() {
        // LazyColumn has already removed the source from its visible viewport.
        assertEquals(1f, at(17, null).progress)
        assertEquals(1f, at(1, null).progress)
        // The source item is back but is still above the actual docking edge.
        assertEquals(1f, at(0, -120).progress)
        assertEquals(1f, at(0, safeTop - addressRow).progress)

        val returning = at(0, 100)
        assertTrue(returning.progress > 0f && returning.progress < 1f)
        assertEquals(100, returning.sourceTopPx)
        assertEquals(0f, at(0, firstTop).progress)
    }

    @Test
    fun clampsOverscrollAndHandlesUnexpectedInsets() {
        assertEquals(0f, at(0, 500).progress)
        assertEquals(1f, at(0, -999).progress)
        assertEquals(0f, projectTitleMotion(0, 60, 60, 60, 38).progress)
        assertEquals(1f, projectTitleMotion(0, 22, 60, 60, 38).progress)
        assertEquals(1f, projectTitleMotion(8, null, 60, 60, 38).progress)
    }
}
