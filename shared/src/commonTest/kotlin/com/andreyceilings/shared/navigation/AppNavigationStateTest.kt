package com.andreyceilings.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppNavigationStateTest {
    @Test fun primaryTabsGoBackToDashboard() {
        val state = AppNavigationState().selectTab(AppRoute.OBJECTS).selectTab(AppRoute.MEASUREMENTS)
        assertEquals(listOf(AppRoute.DASHBOARD), state.backStack)
        assertEquals(AppNavigationState(), state.back())
        assertEquals(AppNavigationState(), state.back().back())
    }

    @Test fun duplicateSelectionIsIgnored() {
        val state = AppNavigationState().selectTab(AppRoute.OBJECTS)
        assertSame(state, state.selectTab(AppRoute.OBJECTS))
    }

    @Test fun nestedRoutesAndBackStack() {
        val state = AppNavigationState().selectTab(AppRoute.SETTINGS).navigate(AppRoute.PRICES)
        assertEquals(AppRoute.PRICES, state.current)
        assertEquals(AppRoute.SETTINGS, state.back().current)
        assertEquals(AppRoute.DASHBOARD, state.back().back().current)
    }

    @Test fun replaceDoesNotPushHistory() {
        val state = AppNavigationState().selectTab(AppRoute.OBJECTS).replace(AppRoute.ESTIMATES)
        assertEquals(listOf(AppRoute.DASHBOARD), state.backStack)
        assertEquals(AppRoute.DASHBOARD, state.back().current)
    }

    @Test fun unavailableDesktopRoutesAreRejected() {
        val available = setOf(AppRoute.DASHBOARD, AppRoute.PRICES)
        val state = AppNavigationState()
        assertSame(state, state.selectTab(AppRoute.OBJECTS, available))
        assertSame(state, state.navigate(AppRoute.CALENDAR, available))
        assertSame(state, state.requestQuickCreate(AppQuickCreate.OBJECT, available))
        assertEquals(AppRoute.PRICES, state.selectTab(AppRoute.PRICES, available).current)
    }

    @Test fun quickCreateOnActiveTabIsDeliveredAndConsumed() {
        val state = AppNavigationState().selectTab(AppRoute.OBJECTS)
            .requestQuickCreate(AppQuickCreate.OBJECT)
        assertEquals(AppQuickCreate.OBJECT, state.pendingQuickCreate)
        assertEquals(AppRoute.OBJECTS, state.current)
        assertSame(state, state.consumeQuickCreate(AppQuickCreate.MEASUREMENT))
        assertNull(state.consumeQuickCreate(AppQuickCreate.OBJECT).pendingQuickCreate)
    }

    @Test fun stateRestorationNeverReplaysQuickCreate() {
        val state = AppNavigationState().selectTab(AppRoute.OBJECTS)
            .requestQuickCreate(AppQuickCreate.OBJECT)
        assertEquals(listOf("OBJECTS", "DASHBOARD"), state.snapshot())
        val restored = AppNavigationState.restore(state.snapshot())
        assertEquals(AppRoute.OBJECTS, restored.current)
        assertEquals(listOf(AppRoute.DASHBOARD), restored.backStack)
        assertNull(restored.pendingQuickCreate)
    }

    @Test fun corruptedAndUnsupportedRoutesAreFiltered() {
        val available = setOf(AppRoute.DASHBOARD, AppRoute.PRICES)
        assertEquals(AppNavigationState(), AppNavigationState.restore(
            listOf("SETTINGS", "INVALID", "OBJECTS", "DASHBOARD"), available
        ))
        assertEquals(AppNavigationState(), AppNavigationState.restore(emptyList()))
        assertEquals(AppNavigationState(), AppNavigationState.restore(listOf("UNKNOWN")))
    }

    @Test fun routeIdsAreCaseSensitiveAndDoNotUseDesktopAliases() {
        assertEquals(AppRoute.DASHBOARD, AppRoute.fromId("DASHBOARD"))
        assertNull(AppRoute.fromId("dashboard"))
        assertNull(AppRoute.fromId("HOME"))
    }

    @Test fun historyCannotGrowWithoutBound() {
        var state = AppNavigationState()
        val routes = listOf(AppRoute.OBJECTS, AppRoute.MEASUREMENTS, AppRoute.PRICES)
        repeat(30) { state = state.navigate(routes[it % routes.size]) }
        assertTrue(state.backStack.size <= 16)
        repeat(20) { state = state.back() }
        assertTrue(state.backStack.size <= 16)
        assertTrue(state.current in AppRoute.entries)
    }
}
