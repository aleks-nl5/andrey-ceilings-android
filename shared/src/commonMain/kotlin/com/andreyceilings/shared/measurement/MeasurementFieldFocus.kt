package com.andreyceilings.shared.measurement

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

internal data class MeasurementInputFocus(val imeOverride: WindowInsets?)

internal val LocalMeasurementInputFocus = compositionLocalOf<MeasurementInputFocus?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun measurementFieldFocusModifier(): Modifier {
    val focus = LocalMeasurementInputFocus.current ?: return Modifier
    // A modal reads the insets of its own window. The override makes this policy
    // exercisable in headless scenes using the same padding as the editor.
    val keyboard = focus.imeOverride ?: WindowInsets.ime
    val keyboardBottom = keyboard.getBottom(LocalDensity.current)
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, keyboardBottom) {
        if (focused) {
            // Let the scroll viewport use the new keyboard bounds before relocating.
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    return Modifier.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}
