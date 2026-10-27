package com.andreyceilings.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalTextApi::class)
private val InterFontFamily = FontFamily(
    Font(
        com.andreyceilings.app.R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        com.andreyceilings.app.R.font.inter_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        com.andreyceilings.app.R.font.inter_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        com.andreyceilings.app.R.font.inter_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val BaseTypography = Typography()
private val AppTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = InterFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = InterFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = InterFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = InterFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = InterFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = InterFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = InterFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = InterFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = InterFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = InterFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = InterFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = InterFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = InterFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = InterFontFamily)
)

enum class ThemeMode(val label: String) {
    LIGHT("Світла"),
    DARK("Темна"),
    SYSTEM("Системна")
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF087E8B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF4F3),
    onPrimaryContainer = Color(0xFF103638),
    secondary = Color(0xFF456A73),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F7F8),
    onBackground = Color(0xFF182328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF182328),
    surfaceVariant = Color(0xFFE8EFF1),
    onSurfaceVariant = Color(0xFF52666C),
    outline = Color(0xFF788B90),
    outlineVariant = Color(0xFFD8E1E4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF69D3DD),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF244447),
    onPrimaryContainer = Color(0xFFB9F2F4),
    secondary = Color(0xFFB3D0D4),
    onSecondary = Color(0xFF1C3438),
    background = Color(0xFF121A1D),
    onBackground = Color(0xFFEAF1F2),
    surface = Color(0xFF1D282C),
    onSurface = Color(0xFFEAF1F2),
    surfaceVariant = Color(0xFF29383D),
    onSurfaceVariant = Color(0xFFB3C2C5),
    outline = Color(0xFF84979C),
    outlineVariant = Color(0xFF3B4A4F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun CeilingsTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

/** Shared theme picker: a one-tap cycling icon in the app bar and three choices in Settings. */
@Composable
fun ThemeModeSelector(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val icon = when (mode) {
        ThemeMode.LIGHT -> Icons.Default.LightMode
        ThemeMode.DARK -> Icons.Default.DarkMode
        ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
    }

    if (compact) {
        IconButton(
            onClick = {
                val nextMode = when (mode) {
                    ThemeMode.LIGHT -> ThemeMode.DARK
                    ThemeMode.DARK -> ThemeMode.SYSTEM
                    ThemeMode.SYSTEM -> ThemeMode.LIGHT
                }
                onModeChange(nextMode)
            },
            modifier = modifier
        ) {
            Icon(icon, contentDescription = "Тема: ${mode.label}. Натисніть, щоб змінити")
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemeMode.values().forEach { option ->
                val optionIcon = when (option) {
                    ThemeMode.LIGHT -> Icons.Default.LightMode
                    ThemeMode.DARK -> Icons.Default.DarkMode
                    ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
                }
                val selected = mode == option
                if (selected) {
                    Button(
                        onClick = { onModeChange(option) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Icon(optionIcon, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(option.label, maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onModeChange(option) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Icon(optionIcon, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(option.label, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxForThemeSelector(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    androidx.compose.foundation.layout.Box(modifier = modifier, content = content)
}


/**
 * Shared dialog styling. Explicit colors are important because dialogs render in a
 * separate window and must follow the app's selected light/dark palette.
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp
    )
}

/** Shared themed popup surface for every exposed dropdown in the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.AppExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        content = content
    )
}
