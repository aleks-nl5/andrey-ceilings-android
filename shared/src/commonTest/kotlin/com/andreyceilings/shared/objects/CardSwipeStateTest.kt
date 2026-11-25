package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class CardSwipeStateTest {
    @Test fun interactionClosesOnlyTheTrayThatWasOpenAtItsStart() {
        val state = CardSwipeState()
        val first = OpenSwipe("ceiling-1", SwipeSide.RIGHT)
        state.opened.value = first
        state.dismissPrevious(first)
        assertNull(state.opened.value)

        state.opened.value = first
        val second = OpenSwipe("ceiling-2", SwipeSide.RIGHT)
        state.opened.value = second
        state.dismissPrevious(first)
        assertSame(second, state.opened.value)

        val reopened = second.copy()
        state.opened.value = reopened
        state.dismissPrevious(second)
        assertSame(reopened, state.opened.value)

        state.dismissPrevious(null)
        assertSame(reopened, state.opened.value)
        state.dismiss()
        assertNull(state.opened.value)
    }
}
