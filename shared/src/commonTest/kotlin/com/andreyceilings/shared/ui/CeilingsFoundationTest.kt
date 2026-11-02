package com.andreyceilings.shared.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CeilingsFoundationTest {
    @Test
    fun measuredForegroundAndSafeAreaAreAddedOnceToListItems() {
        val padding = ceilingsContentPadding(PaddingValues(16.dp),
            PaddingValues(start = 3.dp, top = 24.dp, end = 7.dp, bottom = 30.dp),
            LayoutDirection.Ltr, 56.dp, 97.dp, 64.dp)
        assertEquals(193.dp, padding.calculateTopPadding())
        assertEquals(110.dp, padding.calculateBottomPadding())
        assertEquals(19.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(23.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
    }

    @Test
    fun nestedScreenHasNoSecondSafeAreaOrNavigationReservation() {
        val padding = ceilingsContentPadding(PaddingValues(top = 88.dp, bottom = 110.dp),
            PaddingValues(0.dp), LayoutDirection.Ltr, 0.dp, 97.dp, 0.dp)
        assertEquals(185.dp, padding.calculateTopPadding())
        assertEquals(110.dp, padding.calculateBottomPadding())
    }

    @Test
    fun resizeAndWrappedPinnedContentUseTheNewMeasuredSize() {
        for (height in listOf(56.dp, 72.dp, 112.dp)) {
            val padding = ceilingsContentPadding(PaddingValues(8.dp), PaddingValues(0.dp),
                LayoutDirection.Rtl, 0.dp, height, 0.dp)
            assertEquals(height + 8.dp, padding.calculateTopPadding())
            assertEquals(8.dp, padding.calculateBottomPadding())
        }
    }

    @Test
    fun fabProfilesPreserveAcceptedGeometryAndContainTheirShadows() {
        assertEquals(76.dp, CeilingsFabProfile.CREATE.drawSize)
        assertEquals(56.dp, CeilingsFabProfile.CREATE.circleSize)
        assertEquals(42.dp, CeilingsFabProfile.COMPACT.circleSize)
        for (profile in CeilingsFabProfile.entries) {
            assertTrue(profile.touchSize >= 48.dp)
            assertTrue(profile.circleSize <= profile.touchSize)
            assertTrue(profile.ambientRadius + 1.dp <= profile.drawSize / 2)
            assertTrue(profile.directionalRadius + profile.directionalOffset <= profile.drawSize / 2)
        }
        assertEquals(0.27f, ceilingsFloatingOutlineAlpha(false))
        assertEquals(0.46f, ceilingsFloatingOutlineAlpha(true))
    }
}
