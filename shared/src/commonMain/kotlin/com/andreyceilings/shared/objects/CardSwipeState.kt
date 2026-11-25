package com.andreyceilings.shared.objects

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** One transient tray for the Objects tab, including project ceiling cards. */
class CardSwipeState {
    internal val opened = mutableStateOf<OpenSwipe?>(null, referentialEqualityPolicy())

    fun dismiss() { opened.value = null }

    /** A different swipe opened during this gesture must survive its release. */
    internal fun dismissPrevious(previous: OpenSwipe?) {
        if (previous != null && opened.value === previous) dismiss()
    }
}

/**
 * Observe the whole host, including its header and navigation overlays.
 * Never consume pointer changes. The revealed action remains hit-testable
 * through the child's Main pass. A new swipe uses a fresh token.
 */
fun Modifier.dismissCardSwipeOnInteraction(state: CardSwipeState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val previous = state.opened.value
            if (previous != null) {
                try {
                    var pressed: Boolean
                    do {
                        pressed = awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }
                    } while (pressed)
                } finally {
                    state.dismissPrevious(previous)
                }
            }
        }
    }
