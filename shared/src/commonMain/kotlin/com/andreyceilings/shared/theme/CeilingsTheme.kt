package com.andreyceilings.shared.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Stable preference names are also used by Android's existing saved setting. */
enum class ThemeMode(val label: String) {
    LIGHT("Світла"), DARK("Темна"), SYSTEM("Системна");

    fun usesDarkColors(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }
}

// Single palette/shape foundation for all hosts; keep established navigation,
// FAB, surface and accent colors while completing the Material 3 surface roles.
private val LightColors = lightColorScheme(
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
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F7F8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8E1E4),
    inverseSurface = Color(0xFF29383D),
    inverseOnSurface = Color(0xFFEAF1F2),
    inversePrimary = Color(0xFF69D3DD),
    surfaceTint = Color(0xFF087E8B),
    scrim = Color(0xFF000000),
    primaryFixed = Color(0xFFDDF4F3),
    primaryFixedDim = Color(0xFF69D3DD),
    onPrimaryFixed = Color(0xFF103638),
    onPrimaryFixedVariant = Color(0xFF244447),
    secondaryFixed = Color(0xFFD8ECEE),
    secondaryFixedDim = Color(0xFFB3D0D4),
    onSecondaryFixed = Color(0xFF103638),
    onSecondaryFixedVariant = Color(0xFF294449),
    tertiaryFixed = Color(0xFFDDF4F3),
    tertiaryFixedDim = Color(0xFFB3D0D4),
    onTertiaryFixed = Color(0xFF103638),
    onTertiaryFixedVariant = Color(0xFF244447),

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

private val DarkColors = darkColorScheme(
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
    surfaceContainerLowest = Color(0xFF0E1518),
    surfaceContainerLow = Color(0xFF182226),
    surfaceBright = Color(0xFF3B4A4F),
    surfaceDim = Color(0xFF121A1D),
    inverseSurface = Color(0xFFE8EFF1),
    inverseOnSurface = Color(0xFF182328),
    inversePrimary = Color(0xFF087E8B),
    surfaceTint = Color(0xFF69D3DD),
    scrim = Color(0xFF000000),
    primaryFixed = Color(0xFFDDF4F3),
    primaryFixedDim = Color(0xFF69D3DD),
    onPrimaryFixed = Color(0xFF103638),
    onPrimaryFixedVariant = Color(0xFF244447),
    secondaryFixed = Color(0xFFD8ECEE),
    secondaryFixedDim = Color(0xFFB3D0D4),
    onSecondaryFixed = Color(0xFF103638),
    onSecondaryFixedVariant = Color(0xFF294449),
    tertiaryFixed = Color(0xFFDDF4F3),
    tertiaryFixedDim = Color(0xFFB3D0D4),
    onTertiaryFixed = Color(0xFF103638),
    onTertiaryFixedVariant = Color(0xFF244447),

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

val CeilingsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)


fun ceilingsColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) DarkColors else LightColors

@Composable
fun CeilingsTheme(
    darkTheme: Boolean,
    typography: Typography = ceilingsTypography(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = ceilingsColorScheme(darkTheme), typography = typography,
        shapes = CeilingsShapes, content = content)
}

/** Semantic status inks, shared by the object list and details badges. */
data class StatusColors(val success: Color, val warning: Color)
private val LightStatusColors = StatusColors(Color(0xFF176B52), Color(0xFF805B22))
private val DarkStatusColors = StatusColors(Color(0xFF73D6B2), Color(0xFFE5B971))
fun ceilingsStatusColors(darkTheme: Boolean): StatusColors =
    if (darkTheme) DarkStatusColors else LightStatusColors
