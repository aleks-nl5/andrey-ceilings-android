package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A new details page with zero scroll always shows the original toolbar,
 * regardless of how LazyColumn places its first item inside content padding.
 */
class ProjectDetailsTitleDockingTest {
    private val initialTop = 240
    private val safeTop = 24
    private val addressRowOffset = 38
    private val travel = initialTop + addressRowOffset - safeTop // 254 px

    // 'scrollPx' is LazyListState.firstVisibleItemScrollOffset, NOT itemInfo.offset.
    private fun at(index: Int, scrollPx: Int) =
        projectTitleMotion(index, scrollPx, initialTop, safeTop, addressRowOffset)

    @Test
    fun newlyOpenedPageAlwaysShowsOrdinaryHeaderBeforeScroll() {
        assertEquals(ProjectTitleMotion(initialTop, 0f), at(0, 0))
        assertEquals(ProjectTitleMotion(60, 0f), projectTitleMotion(0, 0, 60, 24, 38))
        assertEquals(ProjectTitleMotion(100, 0f), projectTitleMotion(0, 0, 100, 0, 38))
        assertEquals(ProjectTitleMotion(0, 0f), projectTitleMotion(0, 0, 0, 0, 38))
    }

    @Test
    fun motionTracksScrollPxContinuouslyAndDockingClamps() {
        val quarter = at(0, travel / 4)
        val middle = at(0, travel / 2)
        val threeQuarters = at(0, 3 * travel / 4)
        assertTrue(quarter.progress in 0.24f..0.26f)
        assertTrue(middle.progress in 0.49f..0.51f)
        assertTrue(threeQuarters.progress in 0.74f..0.76f)
        assertTrue(quarter.progress < middle.progress && middle.progress < threeQuarters.progress)
        assertEquals(ProjectTitleMotion(safeTop - addressRowOffset, 1f), at(0, travel))
        assertEquals(1f, at(0, travel + 300).progress)
    }

    @Test
    fun reversingDeepScrollDoesNotRestoreHeaderUntilNearInitialPosition() {
        assertEquals(1f, at(20, 0).progress)
        assertEquals(1f, at(1, 0).progress)
        assertEquals(1f, at(0, 400).progress)
        assertEquals(1f, at(0, travel).progress)
        val returning = at(0, 100)
        assertTrue(returning.progress > 0f && returning.progress < 1f)
        assertEquals(initialTop - 100, returning.sourceTopPx)
        assertEquals(ProjectTitleMotion(initialTop, 0f), at(0, 0))
    }

    @Test
    fun clampsInvalidAndEqualInsets() {
        assertEquals(ProjectTitleMotion(initialTop, 0f), at(0, -9))
        assertEquals(0f, projectTitleMotion(0, 0, 60, 60, 38).progress)
        assertEquals(1f, projectTitleMotion(0, 38, 60, 60, 38).progress)
        assertEquals(1f, projectTitleMotion(8, 0, 60, 60, 38).progress)
    }
}
