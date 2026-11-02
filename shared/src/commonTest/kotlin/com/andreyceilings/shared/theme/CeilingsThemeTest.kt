package com.andreyceilings.shared.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CeilingsThemeTest {
    @Test fun systemFollowsDeviceAndManualPreferenceOverridesIt() {
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, ThemeMode.SYSTEM.usesDarkColors(systemDark))
            assertEquals(false, ThemeMode.LIGHT.usesDarkColors(systemDark))
            assertEquals(true, ThemeMode.DARK.usesDarkColors(systemDark))
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.valueOf("SYSTEM"))
    }

    @Test fun allSurfaceRolesStayInGraphitePaletteWithReadableText() {
        for (dark in listOf(false, true)) {
            val scheme = ceilingsColorScheme(dark)
            val surfaces = listOf(scheme.surface, scheme.surfaceVariant, scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest, scheme.surfaceBright, scheme.surfaceDim)
            for (surface in surfaces) {
                // Neutral graphite/cool grey roles must never inherit Material's purple fallback.
                assertTrue(surface.red <= surface.green + 0.02f)
                assertTrue(abs(surface.blue - surface.green) < 0.04f)
                assertTrue(contrast(surface, scheme.onSurface) >= 4.5f)
                assertTrue(contrast(surface, scheme.onSurfaceVariant) >= 4.5f)
            }
            val pairs = listOf(
                scheme.background to scheme.onBackground,
                scheme.primary to scheme.onPrimary,
                scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.errorContainer to scheme.onErrorContainer,
                scheme.inverseSurface to scheme.inverseOnSurface,
                scheme.primaryFixed to scheme.onPrimaryFixed,
                scheme.secondaryFixed to scheme.onSecondaryFixed,
                scheme.tertiaryFixed to scheme.onTertiaryFixed
            )
            for ((background, text) in pairs) assertTrue(contrast(background, text) >= 4.5f)
            assertEquals(scheme.primary, scheme.surfaceTint)
        }
    }

    @Test fun paymentBadgeTextRemainsReadableOnTintedSurfaces() {
        for (dark in listOf(false, true)) {
            val scheme = ceilingsColorScheme(dark)
            val statuses = ceilingsStatusColors(dark)
            for (ink in listOf(statuses.success, statuses.warning, scheme.error, scheme.onSurfaceVariant)) {
                val background = ink.copy(alpha = 0.11f).compositeOver(scheme.surfaceContainerLow)
                assertTrue(contrast(ink, background) >= 4.5f)
            }
        }
    }

    @Test fun projectCardSurfacesRemainVisibleAgainstScreenBackground() {
        for (dark in listOf(false, true)) {
            val scheme = ceilingsColorScheme(dark)
            // Project details and measurement cards use these neutral surface roles.
            // A matching canvas color makes the entire card hierarchy disappear.
            assertTrue(
                abs(scheme.surfaceContainerLow.luminance() - scheme.background.luminance()) > 0.018f,
                "Card surface must remain distinct from the screen in ${if (dark) "dark" else "light"} mode"
            )
            assertTrue(
                abs(scheme.surface.luminance() - scheme.background.luminance()) > 0.009f
            )
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)
}
