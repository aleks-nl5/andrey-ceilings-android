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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.prices.ceilingsTypography

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

// One KMP typography scale; Android continues supplying its packaged Inter family.
private val AppTypography = ceilingsTypography(InterFontFamily)

typealias ThemeMode = com.andreyceilings.shared.theme.ThemeMode

@Composable
fun CeilingsTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    com.andreyceilings.shared.theme.CeilingsTheme(
        darkTheme = mode.usesDarkColors(isSystemInDarkTheme()),
        typography = AppTypography,
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
