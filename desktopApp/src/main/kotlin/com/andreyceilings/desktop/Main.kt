package com.andreyceilings.desktop

import com.andreyceilings.shared.calendar.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import com.andreyceilings.shared.localization.AppLanguageResources
import com.andreyceilings.shared.localization.AppLanguageState
import com.andreyceilings.shared.localization.DesktopAppLanguageStorage
import java.util.Locale
import java.util.prefs.Preferences
import com.andreyceilings.shared.dashboard.CurrencyRatesStore
import com.andreyceilings.shared.dashboard.MonobankCurrencyGateway
import com.andreyceilings.shared.dashboard.dashboardClockMillis
import com.andreyceilings.shared.dashboard.dashboardTodayLabel
import com.andreyceilings.shared.dashboard.currencyLocalDateTimeLabel
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andreyceilings.shared.navigation.AppDestination
import com.andreyceilings.shared.navigation.AppRoute
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.navigation.AppFloatingNavigation
import com.andreyceilings.shared.navigation.AppHeader
import com.andreyceilings.shared.navigation.AppHeaderHeight
import com.andreyceilings.shared.navigation.FloatingBarHeight
import com.andreyceilings.shared.dashboard.DashboardScreen
import com.andreyceilings.shared.dashboard.DashboardPreferences
import kotlinx.coroutines.flow.Flow
import com.andreyceilings.shared.objects.CardSwipeState
import com.andreyceilings.shared.objects.dismissCardSwipeOnInteraction
import com.andreyceilings.shared.objects.ObjectsClientsScreen
import com.andreyceilings.shared.objects.ObjectsClientsRows
import com.andreyceilings.shared.objects.ContactAction
import com.andreyceilings.shared.prices.PriceCatalogScreen
import com.andreyceilings.shared.prices.PriceCatalogStore
import com.andreyceilings.shared.prices.PriceCatalogTheme
import com.andreyceilings.shared.prices.ceilingsTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    val appLanguage = remember {
        AppLanguageState(DesktopAppLanguageStorage(Preferences.userNodeForPackage(DesktopAppLanguageStorage::class.java)))
    }
    var navigation by remember { mutableStateOf(AppNavigationState()) }
    val availableRoutes = remember { setOf(AppRoute.DASHBOARD, AppRoute.PRICES, AppRoute.OBJECTS, AppRoute.MEASUREMENTS, AppRoute.ESTIMATES) }
    val measurementCreateNavigationGuard = remember { com.andreyceilings.shared.measurement.MeasurementCreateNavigationGuard() }
    SideEffect { measurementCreateNavigationGuard.quoteSession.navigate(navigation) }
    fun applyNavigation(next: AppNavigationState) { measurementCreateNavigationGuard.quoteSession.navigate(next); navigation = next }
    fun updateNavigation(proposed: AppNavigationState) {
        applyNavigation(measurementCreateNavigationGuard.transition(navigation, proposed, ::applyNavigation))
    }
    Window(
        onCloseRequest = { measurementCreateNavigationGuard.requestClose { exitApplication() } },
        title = "СтеляПро",
        // Bubble after editor/dialog handlers so Escape closes an active modal first.
        onKeyEvent = { event ->
            handleNavigationKeyEvent(event, navigation, availableRoutes, measurementCreateNavigationGuard, ::updateNavigation)
        }
    ) {
        AppLanguageResources(appLanguage.preference, Locale.getDefault().toLanguageTag()) {
            PriceCatalogTheme(
                darkTheme = isSystemInDarkTheme(),
                typography = remember { ceilingsTypography() }
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DesktopPriceHost(
                        navigation = navigation,
                        availableRoutes = availableRoutes,
                        onNavigate = ::updateNavigation,
                        measurementCreateNavigationGuard = measurementCreateNavigationGuard
                    )
                }
            }
        }
    }
}

/** Native key adapter; the shared guard owns whether the current editor may leave. */
internal fun handleNavigationKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    navigation: AppNavigationState,
    availableRoutes: Set<AppRoute>,
    guard: com.andreyceilings.shared.measurement.MeasurementCreateNavigationGuard,
    onNavigate: (AppNavigationState) -> Unit
): Boolean {
    if (event.key != Key.Escape || event.type != KeyEventType.KeyDown) return false
    if (guard.blocksExit(navigation)) return true
    val next = navigation.back(availableRoutes)
    if (next == navigation) return false
    onNavigate(next)
    return true
}

/**
 * Windows preview of the *same* CMP price catalog used by Android.
 * Each user unlocks a local encrypted SQLCipher catalog with their own password.
 * Full OS-keystore integration, backup and desktop packaging remain in issue #42.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopPriceHost(
    navigation: AppNavigationState,
    availableRoutes: Set<AppRoute>,
    onNavigate: (AppNavigationState) -> Unit,
    measurementCreateNavigationGuard: com.andreyceilings.shared.measurement.MeasurementCreateNavigationGuard
) {
    val measurementFullscreen by measurementCreateNavigationGuard.fullscreen.collectAsState()
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DesktopPriceDatabase?>(null) }
    var password by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf(false) }

    // Close the exact previous session, never a newly assigned replacement.
    val database = session
    val cardSwipes = remember(database) { CardSwipeState() }
    LaunchedEffect(navigation, cardSwipes) { cardSwipes.dismiss() }
    DisposableEffect(database) {
        onDispose { database?.close() }
    }
    if (database != null) {
        val routeState = key(database) { androidx.compose.runtime.saveable.rememberSaveableStateHolder() }
        val store = remember(database) {
            PriceCatalogStore(database.prices, nowMillis = System::currentTimeMillis)
        }
        val items by store.items.collectAsState(initial = null as List<com.andreyceilings.shared.prices.PriceCatalogItem>?)
        val priceGroups by store.groups.collectAsState(initial = null as List<com.andreyceilings.shared.prices.PriceCatalogGroup>?)
        val objectsStore = remember(database) { database.objects }
        SideEffect { measurementCreateNavigationGuard.bindQuotes(objectsStore.measurementDetails.elements) }
        val objectRows by objectsStore.rows.collectAsState(initial = objectsStore.cachedRows)
        var externalError by remember { mutableStateOf<String?>(null) }
        val calendar = remember(database) { objectsStore.calendar }
        var calendarOpen by remember(database) { mutableStateOf(false) }
        var calendarProject by remember(database) { mutableStateOf<String?>(null) }
        val calendarPreferences = remember { java.util.prefs.Preferences.userNodeForPackage(DesktopPriceDatabase::class.java) }
        var calendarMode by remember(calendarPreferences) {
            mutableStateOf(runCatching { CalendarDisplayMode.valueOf(calendarPreferences.get("calendarDisplayMode", "WEEK")) }
                .getOrDefault(CalendarDisplayMode.WEEK))
        }
        var calendarEditor by remember(database) { mutableStateOf<CalendarEditorRoute?>(null) }
        var completedProject by remember(database) { mutableStateOf<Pair<String, WorkOccurrenceKey>?>(null) }
        var detachedWork by remember(database) { mutableStateOf(0) }
        fun openExternal(url: String) {
            runCatching {
                check(Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    "Немає застосунку для зовнішнього посилання."
                }
                Desktop.getDesktop().browse(URI(url))
            }.onFailure { externalError = it.message ?: "Не вдалося відкрити посилання." }
        }
        val currencyGateway = remember(database) {
            MonobankCurrencyGateway(DesktopCurrencyRatesCache())
        }
        DisposableEffect(currencyGateway) { onDispose { currencyGateway.close() } }
        val currencyStore = remember(currencyGateway) {
            CurrencyRatesStore(currencyGateway, ::dashboardClockMillis)
        }
        val currencyState by currencyStore.state.collectAsState()
        LaunchedEffect(currencyStore) { currencyStore.start() }
        LaunchedEffect(currencyStore) {
            while (true) {
                delay(30_000L)
                currencyStore.tick()
            }
        }
        val selectedTab = navigation.current
        // Same commonMain navigation chrome as Android. Windows exposes only
        // the destinations implemented by its present encrypted desktop host.
        val navItems = remember {
            listOf(
                AppDestination(AppRoute.DASHBOARD.name, "Панель", "Панель", Icons.Default.Dashboard),
                AppDestination(AppRoute.PRICES.name, "Прайс", "Прайс", Icons.Default.ReceiptLong),
                AppDestination(AppRoute.OBJECTS.name, "Об'єкти", "Об'єкти", Icons.Default.Business),
                AppDestination(AppRoute.ESTIMATES.name, "Кошториси", "Кошториси", Icons.Default.ReceiptLong)
            )
        }
        val topScrollBehavior = key(selectedTab) {
            TopAppBarDefaults.enterAlwaysScrollBehavior()
        }
        val isProjectDetailsOpen = selectedTab == AppRoute.OBJECTS &&
            navigation.projectDetails != null
        val isMeasurementDetailsOpen = (isProjectDetailsOpen || selectedTab == AppRoute.MEASUREMENTS) &&
            (navigation.measurementDetails != null || navigation.measurementCreate != null || navigation.measurementEdit != null)
        var projectTitleProgress by remember { mutableFloatStateOf(0f) }
        val passiveDetailsScroll = remember { object : NestedScrollConnection {} }
        LaunchedEffect(navigation.projectDetails?.projectId) {
            projectTitleProgress = 0f
            if (navigation.projectDetails != null) topScrollBehavior.state.heightOffset = 0f
        }
        Box(Modifier.fillMaxSize()
            .then(if (selectedTab == AppRoute.OBJECTS) Modifier.dismissCardSwipeOnInteraction(cardSwipes) else Modifier)
            .nestedScroll(
            if (isProjectDetailsOpen) passiveDetailsScroll
            else topScrollBehavior.nestedScrollConnection
        )) {
            val routeSlot = if (selectedTab == AppRoute.MEASUREMENTS && isMeasurementDetailsOpen)
                "ceiling-workspace" else selectedTab.name
            routeState.SaveableStateProvider(routeSlot) {
            when (selectedTab) {
                AppRoute.DASHBOARD -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(
                                start = 24.dp, end = 24.dp,
                                top = AppHeaderHeight + 16.dp,
                                bottom = if (measurementFullscreen) 8.dp else FloatingBarHeight + 16.dp
                            )
                    ) {
                        // Only real host actions are available, never mock buttons.
                        DashboardScreen(
                            store = database.dashboard,
                            preferences = DashboardPreferences.default,
                            onCeilings = {
                                routeState.removeState(AppRoute.MEASUREMENTS.name)
                                onNavigate(navigation.navigate(AppRoute.MEASUREMENTS, availableRoutes))
                            },
                            onObjects = { onNavigate(navigation.selectTab(AppRoute.OBJECTS, availableRoutes)) },
                            onClients = null,
                            onEstimates = {
                                routeState.removeState(AppRoute.ESTIMATES.name)
                                onNavigate(navigation.navigate(AppRoute.ESTIMATES, availableRoutes))
                            },
                            onCalendar = { calendarProject = null; calendarOpen = true },
                            calendar = calendar,
                            onCalendarEdit = { calendarEditor = it },
                            todayLabel = dashboardTodayLabel(currencyState.nowMillis),
                            nowMillis = currencyState.nowMillis,
                            currencyRates = currencyState,
                            onCurrencyRefresh = { scope.launch { currencyStore.refresh() } },
                            onCurrencyMessageDismiss = currencyStore::dismissMessage,
                            onMonobank = {
                                runCatching {
                                    if (Desktop.isDesktopSupported() &&
                                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                                    ) {
                                        Desktop.getDesktop().browse(URI("https://monobank.ua/"))
                                    }
                                }
                            },
                            formatCurrencyDate = { millis -> currencyLocalDateTimeLabel(millis) }
                        )
                    }
                }
                AppRoute.OBJECTS -> ObjectsClientsScreen(
                    rows = objectRows ?: ObjectsClientsRows(emptyList(), emptyList()),
                    rowsReady = objectRows != null,
                    swipeState = cardSwipes,
                    detailsProjectId = navigation.projectDetails?.projectId,
                    measurementEdit = navigation.measurementEdit,
                    measurementCreate = navigation.measurementCreate,
                    measurementCreateNavigationGuard = measurementCreateNavigationGuard,
                    onCreateMeasurement = { onNavigate(navigation.createMeasurement(it)) },
                    onMeasurementCreated = { projectId, measurementId -> onNavigate(navigation.measurementCreatedInEditor(projectId, measurementId)) },
                    onOpenMeasurement = { projectId, measurementId -> onNavigate(navigation.openCeilingWorkspace(projectId, measurementId)) },
                    detailsStore = objectsStore,
                    onCalendarEdit = { calendarEditor = it },
                    onProjectCalendar = { calendarProject = it; calendarOpen = true },
                    onOpenProject = { onNavigate(navigation.openProject(it)) },
                    onOpenCeilingRegistry = {
                        routeState.removeState(AppRoute.MEASUREMENTS.name)
                        onNavigate(navigation.navigate(AppRoute.MEASUREMENTS, availableRoutes))
                    },
                    onCloseProject = { onNavigate(navigation.back(availableRoutes)) },
                    onProjectHeaderProgress = { projectTitleProgress = it.coerceIn(0f, 1f) },
                    onListTabChanged = {
                        // Keep the desktop app bar in sync with each fresh CMP list.
                        topScrollBehavior.state.heightOffset = 0f
                        topScrollBehavior.state.contentOffset = 0f
                    },
                    headerVisibleFraction = if (isProjectDetailsOpen) 1f
                        else 1f - topScrollBehavior.state.collapsedFraction,
                    databaseAvailable = true,
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp,
                        top = AppHeaderHeight + 16.dp,
                        bottom = if (measurementFullscreen) 8.dp else FloatingBarHeight + 16.dp
                    ),
                    onSaveClient = { id, draft ->
                        withContext(Dispatchers.IO) { objectsStore.saveClient(id, draft) }
                    },
                    onSaveProject = { id, draft ->
                        withContext(Dispatchers.IO) { objectsStore.saveProject(id, draft) }
                    },
                    onDeleteClient = { id ->
                        withContext(Dispatchers.IO) { objectsStore.deleteClient(id) }
                    },
                    onDeleteProject = { id ->
                        withContext(Dispatchers.IO) { objectsStore.deleteProject(id) }
                    },
                    onDial = { phone ->
                        openExternal("tel:" + phone.filter { it.isDigit() || it == '+' })
                    },
                    onProjectDial = { phone ->
                        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                        Desktop.getDesktop().browse(URI("tel:" + phone.filter { it.isDigit() || it == '+' }))
                    },
                    onExternalAction = { action, raw ->
                        val encoded = URLEncoder.encode(raw, "UTF-8")
                        val digits = raw.filter(Char::isDigit)
                        val international = raw.trim().startsWith("+") && digits.length in 8..15
                        val url = when (action) {
                            ContactAction.GOOGLE_MAPS ->
                                "https://www.google.com/maps/search/?api=1&query=$encoded"
                            ContactAction.WAZE ->
                                "https://waze.com/ul?q=$encoded&navigate=yes"
                            ContactAction.WHATSAPP -> if (international)
                                "https://wa.me/$digits" else null
                            ContactAction.TELEGRAM -> if (international)
                                "tg://resolve?phone=$digits" else null
                            ContactAction.VIBER -> if (international)
                                "viber://chat?number=" + URLEncoder.encode(raw.trim(), "UTF-8") else null
                        }
                        if (url != null) openExternal(url)
                        else externalError = "Вкажіть телефон із міжнародним кодом країни."
                    }
                )
                AppRoute.MEASUREMENTS -> com.andreyceilings.shared.measurement.MeasurementsScreen(
                    store = objectsStore, navigation = navigation,
                    precision = com.andreyceilings.shared.calculation.MeasurementPrecision.ONE_CENTIMETER,
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp,
                        top = if (isMeasurementDetailsOpen) 8.dp else AppHeaderHeight + 16.dp,
                        bottom = if (isMeasurementDetailsOpen) 8.dp else FloatingBarHeight + 16.dp),
                    systemTopInset = 0.dp, navigationGuard = measurementCreateNavigationGuard,
                    onNavigate = onNavigate
                )
                AppRoute.ESTIMATES -> com.andreyceilings.shared.registry.QuoteRegistryScreen(objectsStore,
                    PaddingValues(start = 24.dp, end = 24.dp, top = AppHeaderHeight + 16.dp, bottom = FloatingBarHeight + 16.dp))
                AppRoute.PRICES -> PriceCatalogScreen(
                    items = items.orEmpty(),
                    groups = priceGroups.orEmpty(),
                    rowsReady = items != null && priceGroups != null,
                    databaseAvailable = true,
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp,
                        top = AppHeaderHeight + 16.dp,
                        bottom = if (measurementFullscreen) 8.dp else FloatingBarHeight + 16.dp
                    ),
                    onSave = { original, draft ->
                        withContext(Dispatchers.IO) { store.saveConfigured(original, draft) }
                    },
                    onQuickPrice = { original, price ->
                        withContext(Dispatchers.IO) { store.quickPrice(original, price) }
                    },
                    onSaveGroup = { original, draft ->
                        withContext(Dispatchers.IO) { store.saveGroup(original, draft) }
                    },
                    onDeleteGroup = { group ->
                        withContext(Dispatchers.IO) { store.deleteGroup(group) }
                    },
                    onDelete = { item ->
                        withContext(Dispatchers.IO) { store.delete(item) }
                    }
                )
                else -> Unit // Only actually implemented desktop routes are exposed.
            }
            if (calendarOpen) androidx.compose.ui.window.Dialog(
                onDismissRequest = { calendarOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                WorkCalendarScreen(calendar, if (calendarProject == null) calendarMode else CalendarDisplayMode.LIST,
                    onMode = { mode -> calendarMode = mode; calendarPreferences.put("calendarDisplayMode", mode.name) },
                    onEdit = { calendarEditor = it }, contentPadding = PaddingValues(16.dp), projectFilter = calendarProject,
                    onClose = { calendarOpen = false })
            }
            calendarEditor?.let { route ->
                WorkEditor(calendar, route, onClose = { calendarEditor = null }, onSaved = { result, draft ->
                    calendarEditor = null
                    detachedWork = result.detached
                    if (result.installationCompleted) completedProject = draft.projectUuid?.let { it to result.key }
                })
            }
            CalendarSaveFeedback(detachedWork, completedProject != null,
                onDismiss = { completedProject = null; detachedWork = 0 },
                onComplete = {
                    completedProject?.let { project ->
                        scope.launch { runCatching { objectsStore.completeCalendarProject(project.first, project.second) } }
                    }
                    completedProject = null
                    detachedWork = 0
                })
            if (externalError != null) {
                TextButton(onClick = { externalError = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = AppHeaderHeight)) {
                    Text(externalError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
            }
            if (!isMeasurementDetailsOpen && (!isProjectDetailsOpen || projectTitleProgress < 0.95f)) AppHeader(
                collapsedFraction = if (isProjectDetailsOpen) 0f
                    else topScrollBehavior.state.collapsedFraction,
                shadeOpacity = 0.50f + 0.43f * topScrollBehavior.state.collapsedFraction,
                drawGradient = selectedTab != AppRoute.OBJECTS || isProjectDetailsOpen,
                statusBarHeight = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = if (isProjectDetailsOpen) null else topScrollBehavior,
                modifier = Modifier.align(Alignment.TopCenter)
                    .alpha(if (isProjectDetailsOpen)
                        (1f - projectTitleProgress) * (1f - projectTitleProgress)
                    else 1f)
            )
            if (!measurementFullscreen && !isMeasurementDetailsOpen) AppFloatingNavigation(
                selectedId = selectedTab.name,
                destinations = navItems,
                darkTheme = isSystemInDarkTheme(),
                onSelect = { id ->
                    AppRoute.fromId(id)?.let { route ->
                        if (route != selectedTab) routeState.removeState(route.name)
                        onNavigate(navigation.selectTab(route, availableRoutes))
                    }
                },
                onQuickCreate = {},
                modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp)
            )
        }
    } else {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth(0.66f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Прайс-лист", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Введіть пароль для відкриття захищеного локального прайсу Windows. " +
                        "Якщо його ще немає, буде створено новий зашифрований каталог. " +
                        "Збережіть пароль: без нього відновити цей каталог неможливо."
                )
                if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    Text("Шифрований Windows-драйвер доступний лише у збірці Windows.",
                        color = MaterialTheme.colorScheme.error)
                } else {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Пароль локальної БД (від 10 символів)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !unlocking,
                        modifier = Modifier.fillMaxWidth()
                    )
                    unlockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = password.length >= 10 && !unlocking,
                        onClick = {
                            if (!unlocking) {
                                unlocking = true
                                unlockError = null
                                val secret = password.toCharArray()
                                password = ""
                                scope.launch {
                                    try {
                                        session = withContext(Dispatchers.IO) {
                                            DesktopPriceDatabase.open(secret)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        unlockError = "Не вдалося відкрити зашифрований прайс. " +
                                            "Перевірте пароль, доступ до файлів і стан БД. " +
                                            "Нова порожня БД замість існуючої не створюється."
                                    } finally {
                                        secret.fill('\u0000')
                                        unlocking = false
                                    }
                                }
                            }
                        }
                    ) { Text(if (unlocking) "Відкриття…" else "Відкрити прайс") }
                }
            }
        }
    }
}
