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

data class MeasurementDetails(val projectId: Long, val measurementId: Long) {
    init { require(projectId > 0 && measurementId > 0) }
}

data class MeasurementCreate(val projectId: Long) {
    init { require(projectId > 0) }
}

data class MeasurementEdit(val projectId: Long, val measurementId: Long) {
    init { require(projectId > 0 && measurementId > 0) }
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
    val projectDetails: ProjectDetails? = null,
    val measurementDetails: MeasurementDetails? = null,
    val measurementCreate: MeasurementCreate? = null,
    val measurementEdit: MeasurementEdit? = null
) {
    fun selectTab(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = if (target == AppRoute.DASHBOARD) emptyList() else listOf(AppRoute.DASHBOARD),
            pendingQuickCreate = null, projectDetails = null, measurementDetails = null, measurementCreate = null, measurementEdit = null
        )
    }

    fun navigate(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = (backStack.filterNot { it == target } + current).takeLast(maxHistory),
            pendingQuickCreate = null, projectDetails = null, measurementDetails = null, measurementCreate = null, measurementEdit = null
        )
    }

    fun replace(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(current = target, pendingQuickCreate = null, projectDetails = null, measurementDetails = null, measurementCreate = null, measurementEdit = null)
    }

    fun openProject(projectId: Long): AppNavigationState =
        if (current != AppRoute.OBJECTS || projectId <= 0) this
        else if (projectDetails?.projectId == projectId && measurementDetails == null && measurementCreate == null && measurementEdit == null) this
        else copy(projectDetails = ProjectDetails(projectId), measurementDetails = null, measurementCreate = null, measurementEdit = null, pendingQuickCreate = null)

    fun openMeasurement(projectId: Long, measurementId: Long): AppNavigationState {
        if (current != AppRoute.OBJECTS || projectDetails?.projectId != projectId ||
            projectId <= 0 || measurementId <= 0) return this
        val target = MeasurementDetails(projectId, measurementId)
        return if (measurementDetails == target) this
            else copy(measurementDetails = target, measurementCreate = null, measurementEdit = null, pendingQuickCreate = null)
    }

    fun createMeasurement(projectId: Long): AppNavigationState {
        if (current != AppRoute.OBJECTS || projectDetails?.projectId != projectId ||
            projectId <= 0 || measurementDetails != null || measurementEdit != null) return this
        val target = MeasurementCreate(projectId)
        return if (measurementCreate == target) this
            else copy(measurementCreate = target, pendingQuickCreate = null)
    }

    fun editMeasurement(projectId: Long, measurementId: Long): AppNavigationState {
        if (projectId <= 0 || measurementId <= 0 || measurementDetails != MeasurementDetails(projectId, measurementId)) return this
        return copy(measurementDetails = null, measurementEdit = MeasurementEdit(projectId, measurementId), pendingQuickCreate = null)
    }

    fun measurementSaved(projectId: Long, measurementId: Long): AppNavigationState =
        if (projectId > 0 && measurementId > 0 && measurementEdit == MeasurementEdit(projectId, measurementId)) openMeasurement(projectId, measurementId) else this

    /** Replace the editor, so Back from the new readonly record returns to its parent. */
    fun measurementCreated(projectId: Long, measurementId: Long): AppNavigationState =
        if (measurementCreate?.projectId == projectId && measurementId > 0)
            openMeasurement(projectId, measurementId) else this

    fun back(available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (current !in available) return restore(snapshot(), available)
        if (measurementEdit != null) return copy(measurementDetails = MeasurementDetails(measurementEdit.projectId, measurementEdit.measurementId),
            measurementEdit = null, pendingQuickCreate = null)
        if (measurementCreate != null) return copy(measurementCreate = null, measurementEdit = null, pendingQuickCreate = null)
        if (measurementDetails != null) return copy(measurementDetails = null, pendingQuickCreate = null)
        if (projectDetails != null) return copy(projectDetails = null, measurementDetails = null, measurementCreate = null, measurementEdit = null, pendingQuickCreate = null)
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
        return selectTab(action.destination, available).copy(pendingQuickCreate = action, projectDetails = null, measurementDetails = null, measurementCreate = null, measurementEdit = null)
    }

    fun consumeQuickCreate(action: AppQuickCreate): AppNavigationState =
        if (pendingQuickCreate == action) copy(pendingQuickCreate = null) else this

    /** Save only route IDs. One-shot commands and confidential data are excluded. */
    fun snapshot(): List<String> =
        listOf(current.name) + backStack.takeLast(maxHistory).map { it.name } +
            (projectDetails?.let { listOf("PROJECT_DETAILS:${it.projectId}") } ?: emptyList()) +
            (measurementDetails?.let { listOf("MEASUREMENT_DETAILS:${it.projectId}:${it.measurementId}") } ?: emptyList()) +
            (measurementCreate?.let { listOf("MEASUREMENT_CREATE:${it.projectId}") } ?: emptyList()) +
            (measurementEdit?.let { listOf("MEASUREMENT_EDIT:${it.projectId}:${it.measurementId}") } ?: emptyList())

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
            fun canonicalId(value: String?): Long? = value?.toLongOrNull()
                ?.takeIf { it > 0 && it.toString() == value }
            val hasMeasurementTail = snapshot.lastOrNull()?.startsWith("MEASUREMENT_DETAILS:") == true
            val hasCreateTail = snapshot.lastOrNull()?.startsWith("MEASUREMENT_CREATE:") == true
            val hasEditTail = snapshot.lastOrNull()?.startsWith("MEASUREMENT_EDIT:") == true
            val childCount = snapshot.count { it.startsWith("MEASUREMENT_DETAILS:") || it.startsWith("MEASUREMENT_CREATE:") || it.startsWith("MEASUREMENT_EDIT:") }
            val projectToken = snapshot.getOrNull(snapshot.lastIndex - if (hasMeasurementTail || hasCreateTail || hasEditTail) 1 else 0)
            val projectId = projectToken?.takeIf { it.startsWith("PROJECT_DETAILS:") }
                ?.substringAfter(":")?.let(::canonicalId)
                ?.takeIf { route == AppRoute.OBJECTS && snapshot.count { it.startsWith("PROJECT_DETAILS:") } == 1 }
            val details = projectId?.let(::ProjectDetails)
            val args = if (hasMeasurementTail) snapshot.last().split(":") else emptyList()
            val measurement = if (details != null && args.size == 3 &&
                canonicalId(args[1]) == projectId && childCount == 1
            ) canonicalId(args[2])?.let { MeasurementDetails(details.projectId, it) } else null
            val createArgs = if (hasCreateTail) snapshot.last().split(":") else emptyList()
            val create = if (details != null && createArgs.size == 2 &&
                canonicalId(createArgs[1]) == projectId && childCount == 1) MeasurementCreate(details.projectId) else null
            val editArgs = if (hasEditTail) snapshot.last().split(":") else emptyList()
            val edit = if (details != null && editArgs.size == 3 && canonicalId(editArgs[1]) == projectId && childCount == 1)
                canonicalId(editArgs[2])?.let { MeasurementEdit(details.projectId, it) } else null
            return AppNavigationState(route, history, projectDetails = details,
                measurementDetails = measurement, measurementCreate = create, measurementEdit = edit)
        }
    }
}
