package com.andreyceilings.shared.prices

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * CMP theme uses the same palette and shapes as the existing Android CeilingsTheme.
 * Explicitly propagated by platform hosts so manual light/dark preference is respected.
 * Eventually share the whole app theme when the remaining screens move to CMP.
 */
private val PriceLightColors = lightColorScheme(
    primary = Color(0xFF087E8B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF4F3),
    onPrimaryContainer = Color(0xFF103638),
    secondary = Color(0xFF456A73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8ECEE),
    onSecondaryContainer = Color(0xFF103638),
    tertiary = Color(0xFF456A73),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF4F3),
    onTertiaryContainer = Color(0xFF103638),
    background = Color(0xFFF4F7F8),
    onBackground = Color(0xFF182328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF182328),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF4F7F8),
    surfaceContainerHighest = Color(0xFFE8EFF1),
    surfaceVariant = Color(0xFFE8EFF1),
    onSurfaceVariant = Color(0xFF52666C),
    outline = Color(0xFF788B90),
    outlineVariant = Color(0xFFD8E1E4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val PriceDarkColors = darkColorScheme(
    primary = Color(0xFF69D3DD),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF244447),
    onPrimaryContainer = Color(0xFFB9F2F4),
    secondary = Color(0xFFB3D0D4),
    onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF294449),
    onSecondaryContainer = Color(0xFFEAF1F2),
    tertiary = Color(0xFFB3D0D4),
    onTertiary = Color(0xFF1C3438),
    tertiaryContainer = Color(0xFF244447),
    onTertiaryContainer = Color(0xFFB9F2F4),
    background = Color(0xFF121A1D),
    onBackground = Color(0xFFEAF1F2),
    surface = Color(0xFF1D282C),
    onSurface = Color(0xFFEAF1F2),
    surfaceContainer = Color(0xFF1D282C),
    surfaceContainerHigh = Color(0xFF29383D),
    surfaceContainerHighest = Color(0xFF3B4A4F),
    surfaceVariant = Color(0xFF29383D),
    onSurfaceVariant = Color(0xFFB3C2C5),
    outline = Color(0xFF84979C),
    outlineVariant = Color(0xFF3B4A4F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val PriceShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PriceCatalogTheme(
    darkTheme: Boolean,
    typography: Typography? = null,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PriceDarkColors else PriceLightColors,
        // Android inherits the actual Inter family; desktop has the shared text scale.
        typography = typography ?: MaterialTheme.typography,
        shapes = PriceShapes,
        content = content
    )
}
