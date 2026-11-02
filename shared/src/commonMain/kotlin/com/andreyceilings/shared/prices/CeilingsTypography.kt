package com.andreyceilings.shared.prices

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/** Compatibility entry point; the app-wide typography scale lives in theme. */
fun ceilingsTypography(fontFamily: FontFamily = FontFamily.Default): Typography =
    com.andreyceilings.shared.theme.ceilingsTypography(fontFamily)
