package com.andreyceilings.shared.prices

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import com.andreyceilings.shared.theme.CeilingsTheme

/** Compatibility wrapper: shares the app palette and inherits Android's Inter. */
@Composable
fun PriceCatalogTheme(
    darkTheme: Boolean,
    typography: Typography? = null,
    content: @Composable () -> Unit
) {
    CeilingsTheme(darkTheme, typography ?: MaterialTheme.typography, content)
}
