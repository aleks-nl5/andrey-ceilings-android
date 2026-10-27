package com.andreyceilings.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Minimal desktop host proving that the shared KMP module can be consumed by
 * a Compose Desktop application. This is scaffolding, not the production UI.
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
    MaterialTheme {
        Surface {
            Text("Andrey Ceilings · desktop foundation")
        }
    }
}
