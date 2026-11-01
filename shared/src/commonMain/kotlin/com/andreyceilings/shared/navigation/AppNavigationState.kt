package com.andreyceilings.shared.navigation

/** Stable public IDs: never include entity data, keys or passwords in routes. */
enum class AppRoute(val title: String) {
    DASHBOARD("Панель"), OBJECTS("Об’єкти"), MEASUREMENTS("Заміри"),
    CALCULATOR("Калькулятор"), ESTIMATES("Кошториси"), PRICES("Прайс"),
    SETTINGS("Налаштування"), CALENDAR("Календар");

    companion object {
        fun fromId(id: String): AppRoute? = entries.firstOrNull { it.name == id }
    }
}

enum class AppQuickCreate(val destination: AppRoute) {
    OBJECT(AppRoute.OBJECTS), MEASUREMENT(AppRoute.MEASUREMENTS)
}

/**
 * Immutable KMP navigation state. Hosts expose only genuinely implemented routes.
 * selectTab retains Android's Back-to-Dashboard convention. navigate opens a
 * nested route while preserving its real history.
 */
data class AppNavigationState(
    val current: AppRoute = AppRoute.DASHBOARD,
    val backStack: List<AppRoute> = emptyList(),
    val pendingQuickCreate: AppQuickCreate? = null
) {
    fun selectTab(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = if (target == AppRoute.DASHBOARD) emptyList() else listOf(AppRoute.DASHBOARD),
            pendingQuickCreate = null
        )
    }

    fun navigate(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = (backStack.filterNot { it == target } + current).takeLast(maxHistory),
            pendingQuickCreate = null
        )
    }

    fun replace(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(current = target, pendingQuickCreate = null)
    }

    fun back(available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (current !in available) return restore(snapshot(), available)
        val index = backStack.indexOfLast { it in available && it != current }
        if (index >= 0) {
            return copy(
                current = backStack[index],
                backStack = backStack.take(index),
                pendingQuickCreate = null
            )
        }
        return if (current != AppRoute.DASHBOARD && AppRoute.DASHBOARD in available)
            AppNavigationState() else copy(pendingQuickCreate = null)
    }

    /** One-shot host command; a long-press on the active tab is still actionable. */
    fun requestQuickCreate(
        action: AppQuickCreate,
        available: Set<AppRoute> = allRoutes
    ): AppNavigationState {
        if (action.destination !in available) return this
        return selectTab(action.destination, available).copy(pendingQuickCreate = action)
    }

    fun consumeQuickCreate(action: AppQuickCreate): AppNavigationState =
        if (pendingQuickCreate == action) copy(pendingQuickCreate = null) else this

    /** Save only route IDs. One-shot commands and confidential data are excluded. */
    fun snapshot(): List<String> =
        listOf(current.name) + backStack.takeLast(maxHistory).map { it.name }

    companion object {
        private const val maxHistory = 16
        private val allRoutes = AppRoute.entries.toSet()

        fun restore(
            snapshot: List<String>,
            available: Set<AppRoute> = allRoutes
        ): AppNavigationState {
            val fallback = if (AppRoute.DASHBOARD in available) AppRoute.DASHBOARD
                else available.firstOrNull() ?: return AppNavigationState()
            val route = snapshot.firstOrNull()?.let(AppRoute::fromId)
                ?.takeIf { it in available } ?: fallback
            val history = snapshot.drop(1).takeLast(maxHistory).mapNotNull(AppRoute::fromId)
                .filter { it in available && it != route }
            return AppNavigationState(route, history)
        }
    }
}
