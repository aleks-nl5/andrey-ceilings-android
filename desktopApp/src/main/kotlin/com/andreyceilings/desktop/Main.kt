package com.andreyceilings.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andreyceilings.shared.SharedFoundationStatus

/**
 * Minimal desktop host consuming the shared Compose component.
 * This is scaffolding, not the production UI.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Andrey Ceilings"
    ) {
        DesktopFoundationScreen()
    }
}

@Composable
private fun DesktopFoundationScreen() {
    SharedFoundationStatus()
}
