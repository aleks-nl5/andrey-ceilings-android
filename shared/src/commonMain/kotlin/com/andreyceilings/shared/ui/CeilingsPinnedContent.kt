package com.andreyceilings.shared.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A measured foreground region. The shield is a sibling BEHIND its actions, so
 * buttons keep their gestures, and empty space cannot click/drag the list below.
 * No fillMaxSize, fade height or guessed screen-wide hit region is added here.
 */
@Composable
fun CeilingsPinnedContent(
    modifier: Modifier = Modifier,
    interceptInput: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier) {
        if (interceptInput) Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })
        content()
    }
}
