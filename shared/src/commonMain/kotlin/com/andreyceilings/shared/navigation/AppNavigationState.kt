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

data class ProjectDetails(val projectId: Long) {
    init { require(projectId > 0) }
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
    val pendingQuickCreate: AppQuickCreate? = null,
    val projectDetails: ProjectDetails? = null
) {
    fun selectTab(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = if (target == AppRoute.DASHBOARD) emptyList() else listOf(AppRoute.DASHBOARD),
            pendingQuickCreate = null, projectDetails = null
        )
    }

    fun navigate(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = (backStack.filterNot { it == target } + current).takeLast(maxHistory),
            pendingQuickCreate = null, projectDetails = null
        )
    }

    fun replace(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(current = target, pendingQuickCreate = null, projectDetails = null)
    }

    fun openProject(projectId: Long): AppNavigationState =
        if (current != AppRoute.OBJECTS || projectId <= 0) this
        else copy(projectDetails = ProjectDetails(projectId), pendingQuickCreate = null)

    fun back(available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (current !in available) return restore(snapshot(), available)
        if (projectDetails != null) return copy(projectDetails = null, pendingQuickCreate = null)
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
        return selectTab(action.destination, available).copy(pendingQuickCreate = action, projectDetails = null)
    }

    fun consumeQuickCreate(action: AppQuickCreate): AppNavigationState =
        if (pendingQuickCreate == action) copy(pendingQuickCreate = null) else this

    /** Save only route IDs. One-shot commands and confidential data are excluded. */
    fun snapshot(): List<String> =
        listOf(current.name) + backStack.takeLast(maxHistory).map { it.name } +
            (projectDetails?.let { listOf("PROJECT_DETAILS:${it.projectId}") } ?: emptyList())

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
            val history = snapshot.drop(1).mapNotNull(AppRoute::fromId).takeLast(maxHistory)
                .filter { it in available && it != route }
            val details = snapshot.lastOrNull()?.removePrefix("PROJECT_DETAILS:")
                ?.takeIf { snapshot.last().startsWith("PROJECT_DETAILS:") }
                ?.toLongOrNull()?.takeIf { it > 0 && it.toString() == snapshot.last().substringAfter(":") }
                ?.takeIf { route == AppRoute.OBJECTS }?.let(::ProjectDetails)
            return AppNavigationState(route, history, projectDetails = details)
        }
    }
}
