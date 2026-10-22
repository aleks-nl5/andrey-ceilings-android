package com.andreyceilings.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF087E8B)
private val LightScheme = lightColorScheme(primary = Blue, secondary = Color(0xFF456A73), background = Color(0xFFF7F9FA))
private val DarkScheme = darkColorScheme(primary = Color(0xFF69D3DD), secondary = Color(0xFFB2CDD1))

@Composable
fun CeilingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}
