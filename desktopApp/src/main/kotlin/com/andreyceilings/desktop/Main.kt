package com.andreyceilings.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andreyceilings.shared.SharedApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Andrey Ceilings",
    ) {
        SharedApp()
    }
}
