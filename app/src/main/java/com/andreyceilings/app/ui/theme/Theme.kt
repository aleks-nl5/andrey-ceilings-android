package com.andreyceilings.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    MaterialTheme(colorScheme = if (useDark) DarkScheme else LightScheme, content = content)
}

/** Shared theme picker used in the app bar and Settings to keep both controls synchronized. */
@Composable
fun ThemeModeSelector(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val icon = when (mode) {
        ThemeMode.LIGHT -> Icons.Default.LightMode
        ThemeMode.DARK -> Icons.Default.DarkMode
        ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
    }
    BoxForThemeSelector(modifier = modifier) {
        if (compact) {
            IconButton(onClick = { expanded = true }) {
                Icon(icon, contentDescription = "Тема: ${mode.label}")
            }
        } else {
            OutlinedButton(onClick = { expanded = true }) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(mode.label)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.SettingsBrightness, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        Icon(
                            when (option) {
                                ThemeMode.LIGHT -> Icons.Default.LightMode
                                ThemeMode.DARK -> Icons.Default.DarkMode
                                ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
                            },
                            contentDescription = null
                        )
                    },
                    onClick = {
                        onModeChange(option)
                        expanded = false
                    }
                )
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
