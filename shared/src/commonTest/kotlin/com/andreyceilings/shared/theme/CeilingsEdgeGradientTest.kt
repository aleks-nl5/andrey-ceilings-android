package com.andreyceilings.shared.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class CeilingsEdgeGradientTest {
    @Test
    fun bothEdgesFadeToZeroInLightDarkAndSystem() {
        for (mode in ThemeMode.entries) for (systemDark in listOf(false, true)) {
            val surface = ceilingsColorScheme(mode.usesDarkColors(systemDark)).surface
            val top = ceilingsEdgeColors(surface, CeilingsEdge.TOP)
            val bottom = ceilingsEdgeColors(surface, CeilingsEdge.BOTTOM)
            assertEquals(listOf(surface.copy(alpha = 0.5f), surface.copy(alpha = 0f)), top)
            assertEquals(top.reversed(), bottom)
        }
    }

    @Test
    fun intensityIsBoundedAndNeverIntroducesAnOpaqueSurface() {
        val surface = ceilingsColorScheme(false).surface
        for ((intensity, expected) in listOf(-1f to 0f, 0f to 0f, 0.5f to 0.25f,
            1f to 0.5f, 2f to 0.5f)) {
            assertEquals(surface.copy(alpha = expected),
                ceilingsEdgeColors(surface, CeilingsEdge.TOP, intensity).first())
            assertEquals(surface.copy(alpha = 0f),
                ceilingsEdgeColors(surface, CeilingsEdge.TOP, intensity).last())
        }
    }
}
