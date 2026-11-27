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
    val measurementEdit: MeasurementEdit? = null,
    val measurementProjectPicker: Boolean = false,
    val measurementQuote: Boolean = false,
    val quickMeasurement: Boolean = false,
    val registrySourceProject: ProjectDetails? = null,
    val calculatorOrigin: List<String> = emptyList()
) {
    fun selectTab(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = if (target == AppRoute.DASHBOARD) emptyList() else listOf(AppRoute.DASHBOARD),
            pendingQuickCreate = null, projectDetails = null, measurementDetails = null, measurementQuote = false, measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, quickMeasurement = false, registrySourceProject = null, calculatorOrigin = emptyList()
        )
    }

    fun navigate(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(
            current = target,
            backStack = (backStack.filterNot { it == target } + current).takeLast(maxHistory),
            pendingQuickCreate = null, projectDetails = null, measurementDetails = null, measurementQuote = false, measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, quickMeasurement = false, registrySourceProject = when {
                target == AppRoute.OBJECTS -> null
                current == AppRoute.OBJECTS -> projectDetails
                else -> registrySourceProject
            }, calculatorOrigin = if (target == AppRoute.CALCULATOR && measurementEdit != null)
                copy(calculatorOrigin = emptyList()).snapshot() else if (target == AppRoute.CALCULATOR)
                emptyList() else calculatorOrigin
        )
    }

    fun replace(target: AppRoute, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (target !in available || current == target) return this
        return copy(current = target, pendingQuickCreate = null, projectDetails = null, measurementDetails = null, measurementQuote = false, measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, quickMeasurement = false, registrySourceProject = null, calculatorOrigin = emptyList())
    }

    fun openProject(projectId: Long): AppNavigationState =
        if (current != AppRoute.OBJECTS || projectId <= 0) this
        else if (projectDetails?.projectId == projectId && measurementDetails == null && measurementCreate == null && measurementEdit == null) this
        else copy(projectDetails = ProjectDetails(projectId), measurementDetails = null, measurementQuote = false, measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, pendingQuickCreate = null)

    private fun hasMeasurementParent(projectId: Long): Boolean = projectId > 0 &&
        (quickMeasurement || current == AppRoute.MEASUREMENTS ||
            (current == AppRoute.OBJECTS && projectDetails?.projectId == projectId))

    /** A persistent screen, with explicit selection even when there is just one project. */
    fun chooseMeasurementProject(): AppNavigationState =
        if (current != AppRoute.MEASUREMENTS) this
        else copy(measurementProjectPicker = true, measurementDetails = null, measurementQuote = false,
            measurementCreate = null, measurementEdit = null, pendingQuickCreate = null)

    fun openMeasurement(projectId: Long, measurementId: Long): AppNavigationState {
        if (!hasMeasurementParent(projectId) || measurementId <= 0) return this
        val target = MeasurementDetails(projectId, measurementId)
        return if (measurementDetails == target) this
            else copy(measurementDetails = target, measurementQuote = false, measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, pendingQuickCreate = null)
    }

    /** Older saved identity-only routes enter the current workspace. */
    fun restoreCeilingWorkspace(): AppNavigationState {
        val legacy = measurementDetails ?: return this
        return openCeilingWorkspace(legacy.projectId, legacy.measurementId)
    }

    /** Ordinary ceiling navigation skips the retired intermediate details screen. */
    fun openLinkedCeiling(projectId: Long, measurementId: Long, available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (projectId <= 0 || measurementId <= 0) return this
        // Calendar/project/global links must not detach a ceiling from its already-open parent.
        if (current == AppRoute.OBJECTS && projectDetails?.projectId == projectId)
            return openCeilingWorkspace(projectId, measurementId)
        if (AppRoute.MEASUREMENTS !in available) return this
        return navigate(AppRoute.MEASUREMENTS, available).openCeilingWorkspace(projectId, measurementId)
    }

    fun openCeilingWorkspace(projectId: Long, measurementId: Long): AppNavigationState {
        if (!hasMeasurementParent(projectId) || measurementId <= 0) return this
        val target = MeasurementEdit(projectId, measurementId)
        return if (measurementEdit == target) this else copy(
            measurementDetails = null, measurementQuote = false, measurementCreate = null,
            measurementEdit = target, measurementProjectPicker = false, pendingQuickCreate = null)
    }

    fun createMeasurement(projectId: Long): AppNavigationState {
        if (!hasMeasurementParent(projectId) || measurementDetails != null || measurementEdit != null) return this
        val target = MeasurementCreate(projectId)
        return if (measurementCreate == target) this
            else copy(measurementCreate = target, measurementProjectPicker = false, pendingQuickCreate = null)
    }

    fun editMeasurement(projectId: Long, measurementId: Long): AppNavigationState {
        if (projectId <= 0 || measurementId <= 0 || measurementDetails != MeasurementDetails(projectId, measurementId)) return this
        return copy(measurementDetails = null, measurementQuote = false, measurementEdit = MeasurementEdit(projectId, measurementId), pendingQuickCreate = null)
    }

    /** Quote is a child of Details; only route identity is restorable, never its financial state. */
    fun openMeasurementQuote(): AppNavigationState =
        if (measurementDetails == null || measurementEdit != null) this else copy(measurementQuote = true)

    fun measurementSaved(projectId: Long, measurementId: Long): AppNavigationState =
        if (projectId > 0 && measurementId > 0 && measurementEdit == MeasurementEdit(projectId, measurementId)) back() else this

    /** Replace the editor, so Back from the new readonly record returns to its parent. */
    fun measurementCreated(projectId: Long, measurementId: Long): AppNavigationState =
        if (measurementCreate?.projectId == projectId && measurementId > 0)
            openMeasurement(projectId, measurementId) else this

    /** First workspace Save promotes the draft to an ordinary persistent editor.
     * Back then follows the same path as any other measurement editor. */
    fun measurementCreatedInEditor(projectId: Long, measurementId: Long): AppNavigationState =
        measurementCreated(projectId, measurementId).let { created ->
            if (created == this) this else created.editMeasurement(projectId, measurementId)
        }

    fun back(available: Set<AppRoute> = allRoutes): AppNavigationState {
        if (current !in available) return restore(snapshot(), available)
        if (measurementQuote) return copy(measurementQuote = false)
        if (quickMeasurement) return copy(quickMeasurement = false, measurementDetails = null,
            measurementEdit = null, measurementCreate = null, measurementProjectPicker = false,
            pendingQuickCreate = null)
        if (measurementEdit != null) return copy(measurementDetails = null, measurementQuote = false,
            measurementEdit = null, measurementProjectPicker = false, pendingQuickCreate = null)
        if (measurementCreate != null) return copy(measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, pendingQuickCreate = null)
        if (measurementDetails != null) return copy(measurementDetails = null, measurementQuote = false, pendingQuickCreate = null)
        if (projectDetails != null) return copy(projectDetails = null, measurementDetails = null, measurementQuote = false, measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, pendingQuickCreate = null)
        if (measurementProjectPicker) return copy(measurementProjectPicker = false, pendingQuickCreate = null)
        if (current == AppRoute.CALCULATOR && calculatorOrigin.isNotEmpty()) {
            val origin = restore(calculatorOrigin, available)
            if (origin.measurementEdit != null && origin.current == backStack.lastOrNull()) return origin
        }
        val index = backStack.indexOfLast { it in available && it != current }
        if (index >= 0) {
            return copy(
                current = backStack[index],
                backStack = backStack.take(index),
                pendingQuickCreate = null,
                projectDetails = registrySourceProject.takeIf { backStack[index] == AppRoute.OBJECTS },
                registrySourceProject = registrySourceProject.takeUnless { backStack[index] == AppRoute.OBJECTS },
                calculatorOrigin = calculatorOrigin.takeUnless { current == AppRoute.CALCULATOR }.orEmpty()
            )
        }
        return if (current != AppRoute.DASHBOARD && AppRoute.DASHBOARD in available)
            AppNavigationState() else copy(pendingQuickCreate = null)
    }

    /** The host verifies the active project before deciding between the editor and picker. */
    fun requestQuickCreate(
        action: AppQuickCreate,
        available: Set<AppRoute> = allRoutes,
        activeProjectId: Long? = null
    ): AppNavigationState {
        if (action.destination !in available) return this
        if (action == AppQuickCreate.MEASUREMENT) {
            if (quickMeasurement || measurementCreate != null || measurementEdit != null ||
                measurementDetails != null || measurementProjectPicker) return this
            val project = activeProjectId?.takeIf { current == AppRoute.OBJECTS && projectDetails?.projectId == it }
            return copy(quickMeasurement = true, pendingQuickCreate = null,
                measurementCreate = project?.let(::MeasurementCreate), measurementProjectPicker = project == null)
        }
        return selectTab(action.destination, available).copy(pendingQuickCreate = action,
            projectDetails = null, measurementDetails = null, measurementQuote = false,
            measurementCreate = null, measurementEdit = null, measurementProjectPicker = false, quickMeasurement = false)
    }

    fun consumeQuickCreate(action: AppQuickCreate): AppNavigationState =
        if (pendingQuickCreate == action) copy(pendingQuickCreate = null) else this

    /** Save only route IDs. One-shot commands and confidential data are excluded. */
    fun snapshot(): List<String> =
        listOf(current.name) + backStack.takeLast(maxHistory).map { it.name } +
            (registrySourceProject?.let { listOf("REGISTRY_SOURCE_PROJECT:${it.projectId}") } ?: emptyList()) +
            (calculatorOrigin.takeIf { it.isNotEmpty() }?.let { listOf("CALCULATOR_ORIGIN:" + it.joinToString("|")) } ?: emptyList()) +
            (if (quickMeasurement) listOf("QUICK_MEASUREMENT") else emptyList()) +
            (projectDetails?.let { listOf("PROJECT_DETAILS:${it.projectId}") } ?: emptyList()) +
            (measurementDetails?.let { listOf("${if (measurementQuote) "MEASUREMENT_QUOTE" else "MEASUREMENT_DETAILS"}:${it.projectId}:${it.measurementId}") } ?: emptyList()) +
            (measurementCreate?.let { listOf("MEASUREMENT_CREATE:${it.projectId}") } ?: emptyList()) +
            (measurementEdit?.let { listOf("MEASUREMENT_EDIT:${it.projectId}:${it.measurementId}") } ?: emptyList()) +
            (if (measurementProjectPicker) listOf("MEASUREMENT_PROJECT_PICKER") else emptyList())

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
            val hasQuoteTail = snapshot.lastOrNull()?.startsWith("MEASUREMENT_QUOTE:") == true
            val hasMeasurementTail = hasQuoteTail || snapshot.lastOrNull()?.startsWith("MEASUREMENT_DETAILS:") == true
            val hasCreateTail = snapshot.lastOrNull()?.startsWith("MEASUREMENT_CREATE:") == true
            val hasEditTail = snapshot.lastOrNull()?.startsWith("MEASUREMENT_EDIT:") == true
            val childCount = snapshot.count { it.startsWith("MEASUREMENT_DETAILS:") || it.startsWith("MEASUREMENT_QUOTE:") || it.startsWith("MEASUREMENT_CREATE:") || it.startsWith("MEASUREMENT_EDIT:") }
            val picker = snapshot.lastOrNull() == "MEASUREMENT_PROJECT_PICKER" &&
                snapshot.count { it == "MEASUREMENT_PROJECT_PICKER" } == 1
            val quick = snapshot.count { it == "QUICK_MEASUREMENT" } == 1 &&
                AppRoute.MEASUREMENTS in available && (hasCreateTail || hasEditTail || picker)
            val projectToken = snapshot.getOrNull(snapshot.lastIndex - if (hasMeasurementTail || hasCreateTail || hasEditTail || picker) 1 else 0)
            val source = snapshot.singleOrNull { it.startsWith("REGISTRY_SOURCE_PROJECT:") }
                ?.substringAfter(':')?.let(::canonicalId)?.takeIf {
                    AppRoute.OBJECTS in history && route != AppRoute.OBJECTS
                }?.let(::ProjectDetails)
            val projectId = projectToken?.takeIf { it.startsWith("PROJECT_DETAILS:") }
                ?.substringAfter(":")?.let(::canonicalId)
                ?.takeIf { route == AppRoute.OBJECTS && snapshot.count { it.startsWith("PROJECT_DETAILS:") } == 1 }
            val details = projectId?.let(::ProjectDetails)
            // Global measurement routes have no project-details parent. The active
            // tab is their origin; never fabricate an OBJECTS route on restoration.
            val globalParent = route == AppRoute.MEASUREMENTS &&
                snapshot.none { it.startsWith("PROJECT_DETAILS:") } && childCount == 1 &&
                "MEASUREMENT_PROJECT_PICKER" !in snapshot
            val args = if (hasMeasurementTail) snapshot.last().split(":") else emptyList()
            val measurement = if (args.size == 3 && childCount == 1 &&
                ((details != null && canonicalId(args[1]) == projectId) || globalParent)
            ) canonicalId(args[1])?.let { parent -> canonicalId(args[2])?.let { MeasurementDetails(parent, it) } } else null
            val createArgs = if (hasCreateTail) snapshot.last().split(":") else emptyList()
            val create = if (createArgs.size == 2 && childCount == 1 &&
                ((details != null && canonicalId(createArgs[1]) == projectId) || globalParent || quick))
                canonicalId(createArgs[1])?.let(::MeasurementCreate) else null
            val editArgs = if (hasEditTail) snapshot.last().split(":") else emptyList()
            val edit = if (editArgs.size == 3 && childCount == 1 &&
                ((details != null && canonicalId(editArgs[1]) == projectId) || globalParent || quick))
                canonicalId(editArgs[1])?.let { parent -> canonicalId(editArgs[2])?.let { MeasurementEdit(parent, it) } } else null
            val calculatorSource = snapshot.singleOrNull { it.startsWith("CALCULATOR_ORIGIN:") }
                ?.substringAfter(':')?.split('|')?.takeIf { tokens ->
                    tokens.size <= maxHistory + 5 && tokens.none { it.startsWith("CALCULATOR_ORIGIN:") } &&
                        tokens.firstOrNull()?.let(AppRoute::fromId)?.let { it in available && it != AppRoute.CALCULATOR } == true &&
                        (route == AppRoute.CALCULATOR || AppRoute.CALCULATOR in history)
                }?.takeIf { tokens ->
                    val origin = restore(tokens, available)
                    val calculatorIndex = if (route == AppRoute.CALCULATOR) history.size else history.indexOf(AppRoute.CALCULATOR)
                    origin.measurementEdit != null && origin.current == history.getOrNull(calculatorIndex - 1)
                }.orEmpty()
            return AppNavigationState(route, history, projectDetails = details,
                measurementDetails = measurement, measurementCreate = create, measurementEdit = edit,
                measurementQuote = hasQuoteTail && measurement != null,
                measurementProjectPicker = picker && childCount == 0 &&
                    (quick || route == AppRoute.MEASUREMENTS && details == null),
                quickMeasurement = quick && (picker || create != null || edit != null),
                registrySourceProject = source, calculatorOrigin = calculatorSource)
        }
    }
}
