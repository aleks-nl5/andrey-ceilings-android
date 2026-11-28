package com.andreyceilings.app

import com.andreyceilings.shared.calendar.*

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.net.Uri
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.andreyceilings.app.data.PerformanceDiagnosticsLog
import com.andreyceilings.app.data.PerformanceDiagnosticsSettings
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.backup.BackupPasswordStore
import com.andreyceilings.app.data.backup.AutomaticBackupScheduler
import com.andreyceilings.app.data.backup.AutomaticBackupSettings
import com.andreyceilings.app.data.backup.BackupSchedule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import com.andreyceilings.shared.objects.CardSwipeState
import com.andreyceilings.shared.objects.dismissCardSwipeOnInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.localization.AndroidAppLanguageResources
import com.andreyceilings.shared.localization.AndroidAppLanguageStorage
import com.andreyceilings.shared.localization.AppLanguageState
import com.andreyceilings.shared.theme.CeilingsEdge
import com.andreyceilings.shared.theme.CeilingsEdgeDefaults
import com.andreyceilings.shared.theme.CeilingsEdgeGradient
import com.andreyceilings.shared.navigation.AppHeader
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.navigation.AppPredictiveBackSurface
import com.andreyceilings.shared.navigation.AppQuickCreate
import com.andreyceilings.shared.navigation.AppRoute
import com.andreyceilings.shared.navigation.appRouteDisplayTitle
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.andreyceilings.shared.dashboard.DashboardPreferences
import com.andreyceilings.shared.registry.QuoteRegistryScreen
import com.andreyceilings.shared.dashboard.RegistrySearchDialog
import com.andreyceilings.shared.registry.GlobalRegistryTarget
import com.andreyceilings.shared.dashboard.RegistrySearchAction
import com.andreyceilings.app.ui.theme.CeilingsTheme
import com.andreyceilings.app.ui.theme.ThemeMode
import com.andreyceilings.app.ui.theme.ThemeModeSelector
import com.andreyceilings.app.core.CeilingEstimate
import com.andreyceilings.app.core.CeilingCalculator
import com.andreyceilings.app.core.CalculatorOptions
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.FrozenCeilingShape
import com.andreyceilings.shared.estimate.VerifiedCeilingGeometry
import com.andreyceilings.app.ui.screens.sidesFromText
import com.andreyceilings.app.ui.screens.defaultPolygonSides
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.ui.screens.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicLong

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Android UI remains a thin adapter over the single commonMain route enum.
internal typealias AppTab = AppRoute

internal val navigationStateSaver = Saver<AppNavigationState, ArrayList<String>>(
    save = { ArrayList(it.snapshot()) },
    restore = { AppNavigationState.restore(it).restoreCeilingWorkspace() }
)

@Composable
internal fun CeilingsApp(
    databaseStatus: String, database: CeilingsDatabase?, onDatabaseReplaced: (CeilingsDatabase) -> Unit,
    themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit,
    initialNavigation: AppNavigationState = AppNavigationState(),
    notificationWork: WorkOccurrenceKey? = null,
    onNotificationOpened: () -> Unit = {},
    notificationCalendar: Boolean = false,
    onNotificationCalendarOpened: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val appLanguage = remember(context) { AppLanguageState(AndroidAppLanguageStorage(context)) }
    AndroidAppLanguageResources(appLanguage) {
        com.andreyceilings.app.calendar.AndroidCalendarIntegrationHost(database?.objectsClientsStore?.calendar) {
            CeilingsAppContent(
                databaseStatus, database, onDatabaseReplaced, themeMode, onThemeModeChange,
                initialNavigation, appLanguage, notificationWork, onNotificationOpened,
                notificationCalendar, onNotificationCalendarOpened,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CeilingsAppContent(
    databaseStatus: String,
    database: CeilingsDatabase?,
    onDatabaseReplaced: (CeilingsDatabase) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    initialNavigation: AppNavigationState,
    appLanguage: AppLanguageState,
    notificationWork: WorkOccurrenceKey?,
    onNotificationOpened: () -> Unit,
    notificationCalendar: Boolean,
    onNotificationCalendarOpened: () -> Unit,
) {
    var navigation by rememberSaveable(stateSaver = navigationStateSaver) {
        mutableStateOf(initialNavigation.restoreCeilingWorkspace())
    }
    val cardSwipes = remember(database) { CardSwipeState() }
    LaunchedEffect(navigation, cardSwipes) { cardSwipes.dismiss() }
    val editorSession: MeasurementEditorSession = androidx.lifecycle.viewmodel.compose.viewModel()
    val measurementCreateNavigationGuard = editorSession.navigationGuard
    SideEffect {
        measurementCreateNavigationGuard.quoteSession.navigate(navigation)
        measurementCreateNavigationGuard.bindQuotes(database?.objectsClientsStore?.measurementDetails?.elements)
    }
    val measurementFullscreen by measurementCreateNavigationGuard.fullscreen.collectAsState()
    val tab = navigation.current
    val registryState = rememberSaveableStateHolder()
    var sessionGeneration by remember { mutableIntStateOf(0) }
    var lastDatabase by remember { mutableStateOf(database) }
    if (lastDatabase !== database) {
        val hadSession = lastDatabase != null
        SideEffect {
            if (hadSession) {
                AppRoute.entries.forEach { registryState.removeState(it.name) }
                registryState.removeState("quick-measurement")
                registryState.removeState("ceiling-workspace")
                sessionGeneration++
            }
            lastDatabase = database
        }
    }
    var objectsStartInClients by rememberSaveable { mutableStateOf(false) }
    var clientsBackToObjects by rememberSaveable { mutableStateOf(false) }
    var returnToProjectsRequest by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var tabChangedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var lastBackPressTime by remember { mutableStateOf(0L) }
    val predictiveBackProgress = remember { Animatable(0f) }
    val predictiveBackFromLeft = remember { mutableStateOf(true) }
    val context = LocalContext.current
    val dashboardStorage = remember(context) {
        context.applicationContext.getSharedPreferences("dashboard_preferences", Context.MODE_PRIVATE)
    }
    var dashboardPreferences by remember(dashboardStorage) {
        mutableStateOf(DashboardPreferences.decode(dashboardStorage.getString("layout", null)))
    }
    val calendarStore = remember(database) { database?.objectsClientsStore?.calendar }
    val calendarStorage = remember(context) { context.getSharedPreferences("calendar_display", 0) }
    var calendarMode by remember(calendarStorage) {
        mutableStateOf(runCatching { CalendarDisplayMode.valueOf(calendarStorage.getString("mode", null).orEmpty()) }
            .getOrDefault(CalendarDisplayMode.WEEK))
    }
    var showWeekNumbers by remember(calendarStorage) { mutableStateOf(calendarStorage.getBoolean("showWeekNumbers", true)) }
    fun setShowWeekNumbers(value: Boolean) {
        showWeekNumbers = value
        calendarStorage.edit().putBoolean("showWeekNumbers", value).apply()
    }
    fun setCalendarMode(mode: CalendarDisplayMode) {
        calendarMode = mode
        calendarStorage.edit().putString("mode", mode.name).apply()
    }
    val firstWeekday = kotlinx.datetime.DayOfWeek.entries[(java.util.Calendar.getInstance().firstDayOfWeek + 5) % 7]
    LaunchedEffect(calendarStore, calendarMode, firstWeekday) { calendarStore?.warmCurrentPeriod(calendarMode, firstWeekday) }
    var calendarEditor by remember(database) { mutableStateOf<CalendarEditorRoute?>(null) }
    LaunchedEffect(notificationWork, calendarStore, calendarEditor == null) {
        if (notificationWork != null && calendarStore != null) {
            if (calendarEditor == null) {
                calendarEditor = CalendarEditorRoute.Open(notificationWork)
                onNotificationOpened()
            }
        }
    }
    var projectCalendar by remember(database) { mutableStateOf<String?>(null) }
    val projectCalendarStates = key(database) { rememberSaveableStateHolder() }
    var calendarReturnOverlay by remember(database) { mutableStateOf<Triple<List<String>, CalendarEditorRoute, String?>?>(null) }
    var completionProject by remember(database) { mutableStateOf<Pair<String, WorkOccurrenceKey>?>(null) }
    var detachedCalendarWorks by remember(database) { mutableIntStateOf(0) }
    val calendarWindow = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false,
        decorFitsSystemWindows = false)
    val systemDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = themeMode.usesDarkColors(systemDarkTheme)

    // Each tab opens with the app header visible. Previously the same TopAppBar
    // scroll state survived tab changes, so opening Price List after scrolling
    // another tab could leave the СтеляПро header collapsed until the next swipe.
    // Keying only the scroll behavior (not the app state) preserves per-screen
    // scrolling while resetting the header on navigation.
    val topAppBarScrollBehavior = key(tab) {
        TopAppBarDefaults.enterAlwaysScrollBehavior()
    }
    val isProjectDetailsOpen = !navigation.quickMeasurement && tab == AppTab.OBJECTS && navigation.projectDetails != null
    val isMeasurementDetailsOpen = (navigation.quickMeasurement || isProjectDetailsOpen || tab == AppTab.MEASUREMENTS) &&
        (navigation.measurementDetails != null || navigation.measurementCreate != null || navigation.measurementEdit != null)
    val isMeasurementEditOpen = isMeasurementDetailsOpen &&
        (navigation.measurementEdit != null || navigation.measurementCreate != null)
    var projectTitleProgress by remember { mutableFloatStateOf(0f) }
    // Let the shared title own details scrolling rather than enterAlways showing
    // the original toolbar during a reverse swipe.
    val passiveDetailsScroll = remember { object : NestedScrollConnection {} }
    // Collapse only after a child actually scrolls. A short list cannot hide the header.
    val contentOnlyHeaderScroll = remember(topAppBarScrollBehavior) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val state = topAppBarScrollBehavior.state
                val delta = if (consumed.y != 0f) consumed.y else available.y.coerceAtLeast(0f)
                if (delta != 0f) state.heightOffset = (state.heightOffset + delta).coerceIn(state.heightOffsetLimit, 0f)
                state.contentOffset += consumed.y
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Snap an actually moved toolbar; never let an unconsumed short-page fling collapse it.
                val state = topAppBarScrollBehavior.state
                return if (state.collapsedFraction > 0f && state.collapsedFraction < 1f)
                    topAppBarScrollBehavior.nestedScrollConnection.onPostFling(consumed, Velocity.Zero)
                else Velocity.Zero
            }
        }
    }
    LaunchedEffect(navigation.projectDetails?.projectId) {
        projectTitleProgress = 0f
        if (navigation.projectDetails != null) {
            topAppBarScrollBehavior.state.heightOffset = 0f
        }
    }
    val isScrolling = remember { mutableStateOf(false) }
    // Track actual content displacement, not just toolbar collapse, so the shade
    // remains legible after the finger stops and goes away near the top again.
    val contentScrolledPx = remember { mutableFloatStateOf(0f) }
    // Keep app chrome transparent so the window background is visible behind the system bars.
    val appBarColor = androidx.compose.ui.graphics.Color.Transparent
    SideEffect {
        context.findActivity()?.window?.let { window ->
            // Keep content edge-to-edge explicitly; transparent system bars must not
            // cause the window to fit the app content between the bars.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
                window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
            }
            // Keep system icons readable when the app's selected light/dark theme changes.
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !useDarkTheme
                isAppearanceLightNavigationBars = !useDarkTheme
            }
        }
    }

    fun applyNavigation(next: AppNavigationState) {
        measurementCreateNavigationGuard.quoteSession.navigate(next)
        if (next.current != navigation.current) tabChangedAt = SystemClock.elapsedRealtime()
        val pending = calendarReturnOverlay
        if (pending != null && next != navigation && navigation.calendarLinkReturn != null && next.calendarLinkReturn == null &&
            next.snapshot() == pending.first && next == navigation.back()) {
            calendarEditor = pending.second
            projectCalendar = pending.third
            calendarReturnOverlay = null
        } else if (pending != null && next != navigation && next.calendarLinkReturn == null) {
            calendarReturnOverlay = null
        } else if (pending != null && next != navigation && next.calendarLinkReturn != null && calendarEditor == pending.second) {
            calendarEditor = null
            projectCalendar = null
        }
        navigation = next
    }
    fun updateNavigation(proposed: AppNavigationState) {
        applyNavigation(measurementCreateNavigationGuard.transition(navigation, proposed, ::applyNavigation))
    }

    var initialSearch by remember { mutableStateOf("") }
    // Only the screens without their own search field need the global action.
    val showGlobalSearch = !isMeasurementDetailsOpen && when {
        navigation.measurementProjectPicker -> true
        tab == AppTab.OBJECTS -> isProjectDetailsOpen
        tab == AppTab.MEASUREMENTS || tab == AppTab.ESTIMATES || tab == AppTab.PRICES -> false
        else -> true
    }
    var openSearch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showGlobalSearch) { if (!showGlobalSearch) openSearch = false }
    var requestedClientId by remember(database) { mutableStateOf<Long?>(null) }
    var requestedQuote by remember(database) { mutableStateOf<GlobalRegistryTarget.Quote?>(null) }
    var searchGeneration by rememberSaveable { mutableIntStateOf(0) }
    var repeatedSearchRoute by remember { mutableStateOf<AppTab?>(null) }
    val searchRequest = if (tab == repeatedSearchRoute) searchGeneration else 0
    fun navigateTo(target: AppTab, clients: Boolean = false, query: String = "") {
        if (target == tab) return
        calendarReturnOverlay = null
        if (target == AppTab.OBJECTS) {
            objectsStartInClients = clients
            clientsBackToObjects = false
        }
        initialSearch = query
        AppRoute.entries.filter { it != tab }.forEach { registryState.removeState(it.name) }
        updateNavigation(navigation.selectTab(target))
    }

    fun openNested(target: AppTab, query: String = "", clients: Boolean = false) {
        if (target == tab) return
        initialSearch = query
        if (target == AppTab.OBJECTS) {
            objectsStartInClients = clients
            clientsBackToObjects = false
        }
        registryState.removeState(target.name)
        updateNavigation(navigation.navigate(target))
    }

    LaunchedEffect(notificationCalendar, calendarStore, calendarEditor == null, projectCalendar == null) {
        if (notificationCalendar && calendarStore != null && calendarEditor == null && projectCalendar == null) {
            openSearch = false
            openNested(AppTab.CALENDAR)
            onNotificationCalendarOpened()
        }
    }

    var checkingProject by remember { mutableStateOf(false) }
    fun startQuickCreate(action: AppQuickCreate) {
        if (action == AppQuickCreate.OBJECT) {
            updateNavigation(navigation.requestQuickCreate(action))
            return
        }
        if (checkingProject || navigation.quickMeasurement) return
        val origin = navigation
        val projectId = origin.projectDetails?.projectId
        if (projectId == null) {
            updateNavigation(origin.requestQuickCreate(action))
            return
        }
        checkingProject = true
        scope.launch {
            try {
                val active = database?.objectsClientsStore?.isActiveProject(projectId) == true
                if (navigation == origin) updateNavigation(origin.requestQuickCreate(action,
                    activeProjectId = projectId.takeIf { active }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (navigation == origin) updateNavigation(origin.requestQuickCreate(action))
            } finally {
                checkingProject = false
            }
        }
    }

    LaunchedEffect(tab) {
        contentScrolledPx.floatValue = 0f
        val requestedAt = tabChangedAt
        withFrameNanos { }
        PerformanceDiagnosticsLog.record(
            context,
            "screen_first_frame tab=${tab.name} elapsed_ms=${SystemClock.elapsedRealtime() - requestedAt}"
        )
    }

    // Track nested scroll activity separately from screen-opening work on every tab.
    val lastScrollAtNs = remember { AtomicLong(0L) }
    val measurementRouteVisible = rememberUpdatedState(isMeasurementDetailsOpen)
    val screenScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                lastScrollAtNs.set(SystemClock.elapsedRealtimeNanos())
                isScrolling.value = true
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                lastScrollAtNs.set(SystemClock.elapsedRealtimeNanos())
                isScrolling.value = true
                if (!measurementRouteVisible.value) contentScrolledPx.floatValue =
                    (contentScrolledPx.floatValue - consumed.y).coerceAtLeast(0f)
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val lastScroll = lastScrollAtNs.get()
            isScrolling.value = lastScroll > 0L &&
                SystemClock.elapsedRealtimeNanos() - lastScroll < 180_000_000L
            kotlinx.coroutines.delay(50L)
        }
    }

    // Collect Android frame timings for the visible tab when diagnostics are enabled.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(tab, activity) {
        if (activity == null || !BuildConfig.DEBUG ||
            !PerformanceDiagnosticsSettings.isEnabled(context.applicationContext)
        ) {
            onDispose { }
        } else {
            lastScrollAtNs.set(0L)
            val frameThread = HandlerThread("screen-frame-metrics").apply { start() }
            val frameCount = AtomicLong(0L)
            val slowFrameCount = AtomicLong(0L)
            val totalFrameDurationNs = AtomicLong(0L)
            val maxFrameDurationNs = AtomicLong(0L)
            val droppedFrames = AtomicLong(0L)
            val scrollFrameCount = AtomicLong(0L)
            val scrollSlowFrameCount = AtomicLong(0L)
            val scrollTotalFrameDurationNs = AtomicLong(0L)
            val scrollMaxFrameDurationNs = AtomicLong(0L)
            val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration > 0L) {
                    frameCount.incrementAndGet()
                    totalFrameDurationNs.addAndGet(duration)
                    maxFrameDurationNs.updateAndGet { previous -> maxOf(previous, duration) }
                    if (duration >= 16_666_667L) slowFrameCount.incrementAndGet()
                    if (dropped > 0) droppedFrames.addAndGet(dropped.toLong())

                    // Include frames during active scroll and the short settling/fling tail.
                    val sinceScrollNs = SystemClock.elapsedRealtimeNanos() - lastScrollAtNs.get()
                    if (sinceScrollNs in 0L..500_000_000L) {
                        scrollFrameCount.incrementAndGet()
                        scrollTotalFrameDurationNs.addAndGet(duration)
                        scrollMaxFrameDurationNs.updateAndGet { previous -> maxOf(previous, duration) }
                        if (duration >= 16_666_667L) scrollSlowFrameCount.incrementAndGet()
                    }
                }
            }
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
            onDispose {
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                frameThread.quitSafely()
                val scrollFrames = scrollFrameCount.get()
                if (scrollFrames > 0L) {
                    PerformanceDiagnosticsLog.record(
                        context,
                        "scroll_frame_summary tab=${tab.name} frames=$scrollFrames " +
                            "slow_frames_ge_16_67ms=${scrollSlowFrameCount.get()} " +
                            "avg_frame_us=${scrollTotalFrameDurationNs.get() / scrollFrames / 1_000L} " +
                            "max_frame_us=${scrollMaxFrameDurationNs.get() / 1_000L}"
                    )
                }
                val frames = frameCount.get()
                if (frames > 0L) {
                    PerformanceDiagnosticsLog.record(
                        context,
                        "screen_frame_summary tab=${tab.name} frames=$frames " +
                            "slow_frames_ge_16_67ms=${slowFrameCount.get()} " +
                            "avg_frame_us=${totalFrameDurationNs.get() / frames / 1_000L} " +
                            "max_frame_us=${maxFrameDurationNs.get() / 1_000L} " +
                            "dropped_frames_reported=${droppedFrames.get()}"
                    )
                }
            }
        }
    }

    // Android only provides gesture progress. KMP owns the destination and CMP the preview.
    // Keep the callback composed unconditionally; dialogs may take Back precedence.
    PredictiveBackHandler(enabled = tab != AppTab.DASHBOARD || navigation.quickMeasurement) { events ->
        if (measurementCreateNavigationGuard.blocksExit(navigation)) {
            // Consume the gesture without preview or navigation while Save owns the screen.
            events.collect { }
            return@PredictiveBackHandler
        }
        var completed = false
        try {
            events.collect { event ->
                predictiveBackFromLeft.value = event.swipeEdge == BackEventCompat.EDGE_LEFT
                predictiveBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            // Commit only after a fully completed gesture (never during a swipe).
            completed = true
            predictiveBackProgress.snapTo(0f)
            if (tab == AppTab.OBJECTS && navigation.projectDetails == null && !navigation.quickMeasurement && clientsBackToObjects) {
                clientsBackToObjects = false
                objectsStartInClients = false
                returnToProjectsRequest++
            } else updateNavigation(navigation.back())
            lastBackPressTime = 0L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!completed) {
                // Cancelled swipes never mutate navigation. Smoothly return to full size.
                withContext(NonCancellable) {
                    predictiveBackProgress.animateTo(0f, tween(durationMillis = 160))
                }
            }
        }
    }
    // Dashboard retains its familiar two-step Back-to-exit behavior.
    BackHandler(enabled = tab == AppTab.DASHBOARD && !navigation.quickMeasurement) {
        val now = SystemClock.elapsedRealtime()
        if (lastBackPressTime != 0L && now - lastBackPressTime <= 2_000L) {
            context.findActivity()?.finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(context, "Натисніть «Назад» ще раз, щоб вийти", Toast.LENGTH_SHORT).show()
        }
    }
    val precisionPreferences = remember(context) {
        context.applicationContext.getSharedPreferences("calculator_preferences", Context.MODE_PRIVATE)
    }
    var measurementPrecision by remember {
        mutableStateOf(
            MeasurementPrecision.entries.firstOrNull {
                it.name == precisionPreferences.getString("measurement_precision", "")
            } ?: MeasurementPrecision.ONE_CENTIMETER
        )
    }
    // Using a saved measurement captures precision for this calculator input only.
    // The global preference remains the default for the next new measurement.
    var capturedCalculatorPrecision by remember { mutableStateOf<MeasurementPrecision?>(null) }
    val calculatorPrecision = capturedCalculatorPrecision ?: measurementPrecision
    var geometryShape by remember { mutableStateOf(FrozenCeilingShape.RECTANGLE.wireId) }
    // A single keyed measurement store serves every registered shape template.
    // New templates require no new Compose fields or per-shape state variables.
    val geometryMeasurements = remember {
        mutableStateMapOf(
            "rectangle.length" to "5.4", "rectangle.width" to "3.8",
            "l_shaped.A" to "5", "l_shaped.B" to "4",
            "l_shaped.C" to "2", "l_shaped.D" to "2"
        )
    }
    val polygonSides = remember {
        mutableStateListOf<Pair<String, com.andreyceilings.shared.calculation.OrthogonalTurn>>()
            .apply { addAll(defaultPolygonSides()) }
    }
    // Existing historical v2 polygon sources stay readable. New side records
    // never inherit this legacy-only geometry unless the user explicitly edits.
    var legacyPolygonSource by remember {
        mutableStateOf<com.andreyceilings.shared.estimate.FrozenPolygonGeometry?>(null)
    }
    val length = geometryMeasurements["rectangle.length"].orEmpty()
    val width = geometryMeasurements["rectangle.width"].orEmpty()
    val templateMeasurements = CeilingShapeTemplates.template(geometryShape)
        ?.parameterKeys?.associateWith { key ->
            geometryMeasurements["$geometryShape.$key"].orEmpty()
        }.orEmpty()
    var pricePerM2 by remember { mutableStateOf("290") }
    var profilePrice by remember { mutableStateOf("130") }
    var profileType by remember { mutableStateOf("standard_alu") }
    var material by remember { mutableStateOf("pvc_matte") }
    var lightLineMeters by remember { mutableStateOf("0") }
    var shadowProfileMeters by remember { mutableStateOf("0") }
    var floatingCeilingMeters by remember { mutableStateOf("0") }
    var chandeliersCount by remember { mutableStateOf("0") }
    var spotCount by remember { mutableStateOf("0") }
    var pipePassCount by remember { mutableStateOf("0") }
    var curtainNicheMeters by remember { mutableStateOf("0") }
    var extraCorners by remember { mutableStateOf("0") }
    var hardWallMeters by remember { mutableStateOf("0") }
    var complexity by remember { mutableStateOf("straight") }

    val priceItemsFlow = remember(database) {
        database?.priceItems()?.observeAll() ?: flowOf<List<PriceItemEntity>>(emptyList())
    }
    val priceItems by priceItemsFlow.collectAsState(initial = emptyList())
    // Keep calculator defaults synchronized with the current encrypted price list.
    LaunchedEffect(priceItems, material, profileType) {
        priceItems.firstOrNull { it.code == "canvas.$material" }?.let { pricePerM2 = it.price.toPriceInput() }
        priceItems.firstOrNull { it.code == "profile.$profileType" }?.let { profilePrice = it.price.toPriceInput() }
    }

    val calculatorOptions = CalculatorOptions(
        lightLineMeters = lightLineMeters.toSafeDouble(),
        shadowProfileMeters = shadowProfileMeters.toSafeDouble(),
        floatingCeilingMeters = floatingCeilingMeters.toSafeDouble(),
        chandeliersCount = chandeliersCount.toSafeDouble(),
        spotCount = spotCount.toSafeDouble(),
        pipePassCount = pipePassCount.toSafeDouble(),
        curtainNicheMeters = curtainNicheMeters.toSafeDouble(),
        extraCorners = extraCorners.toSafeDouble(),
        hardWallMeters = hardWallMeters.toSafeDouble(),
        complexity = complexity
    )
    // The Android calculator now uses the shared Kotlin Multiplatform engine.
    val optionTexts = listOf(lightLineMeters, shadowProfileMeters, floatingCeilingMeters, curtainNicheMeters,
        hardWallMeters, chandeliersCount, spotCount, pipePassCount, extraCorners)
    val calculation = remember(geometryShape, templateMeasurements, polygonSides.toList(), legacyPolygonSource, pricePerM2, profilePrice, priceItems, calculatorOptions, optionTexts, material, profileType, calculatorPrecision) {
        runCatching {
            // A partial/invalid editor must never be calculated or saved as zero.
            require(listOf(lightLineMeters, shadowProfileMeters, floatingCeilingMeters,
                curtainNicheMeters, hardWallMeters).all { it.toSafeDouble().isFinite() } &&
                listOf(chandeliersCount, spotCount, pipePassCount, extraCorners).all {
                    it.toSafeDouble().let { number -> number.isFinite() && number % 1.0 == 0.0 }
                }) { "Перевірте кількості: до 3 десяткових знаків для метрів, лише цілі штуки." }
            val canvasPrice = requireNotNull(com.andreyceilings.shared.prices.PriceCatalogRules.parseSalePrice(pricePerM2)) {
                "Ціна полотна: до 2 знаків після коми/крапки."
            }
            val primaryProfilePrice = requireNotNull(com.andreyceilings.shared.prices.PriceCatalogRules.parseSalePrice(profilePrice)) {
                "Ціна профілю: до 2 знаків після коми/крапки."
            }
            val verifiedGeometry = if (geometryShape == "polygon") {
                val old = legacyPolygonSource
                if (old != null) {
                    require(old.precisionStepMeters == calculatorPrecision.stepMeters) {
                        "Точність збереженого заміру змінилась"
                    }
                    com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract.requireValid(old)
                    com.andreyceilings.shared.calculation.GeometryInput(
                        shape = "polygon", verticesMm = old.vertices,
                        precisionStepMeters = old.precisionStepMeters
                    )
                } else {
                    val sides = sidesFromText(polygonSides.toList(), calculatorPrecision.stepMeters)
                    val contour = com.andreyceilings.shared.calculation.OrthogonalSideContour.build(
                        sides, calculatorPrecision.stepMeters
                    )
                    com.andreyceilings.shared.calculation.GeometryInput(
                        shape = "polygon", verticesMm = contour.vertices,
                        precisionStepMeters = calculatorPrecision.stepMeters
                    )
                }
            } else VerifiedCeilingGeometry.capturedTemplate(
                geometryShape,
                templateMeasurements.mapValues { (_, text) ->
                    text.toSafeDouble()
                },
                calculatorPrecision.stepMeters
            )
            CeilingCalculator.estimate(
                length.toSafeDouble(), width.toSafeDouble(),
                canvasPrice, primaryProfilePrice,
                priceItems = priceItems, options = calculatorOptions,
                materialType = material,
                profileType = profileType,
                precisionStepMeters = calculatorPrecision.stepMeters,
                geometryInput = verifiedGeometry,
                onTimings = if (BuildConfig.DEBUG &&
                    PerformanceDiagnosticsSettings.isEnabled(context.applicationContext)
                ) {
                    { timings ->
                        val timingFields = timings.joinToString(separator = " ") { (phase, elapsedUs) ->
                            "${phase}_us=${elapsedUs}"
                        }
                        PerformanceDiagnosticsLog.record(
                            context,
                            "calculator_estimate price_items_count=${priceItems.size} $timingFields"
                        )
                    }
                } else null
            )
        }
    }
    val estimate = calculation.getOrNull()
    val calculationError = calculation.exceptionOrNull()?.let { error ->
        error.message?.takeIf { it.isNotBlank() } ?: "Невідома помилка KMP-рушія"
    }

    val topContentPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp + 16.dp
    val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp + 16.dp

    // 50% transparent at the screen edges, fading smoothly to fully transparent
    // over the app content. Android's own system bars remain truly transparent.
    val systemPanelGradientHeight = CeilingsEdgeDefaults.FadeHeight
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The header keeps sliding in/out as before. Only its background becomes denser
    // as it collapses; the shade starts at the physical screen edge and fades to zero.
    val headerShadeOpacity by animateFloatAsState(
        targetValue = 0.50f + 0.43f * maxOf(
            topAppBarScrollBehavior.state.collapsedFraction.coerceIn(0f, 1f),
            (contentScrolledPx.floatValue / 160f).coerceIn(0f, 1f)
        ),
        animationSpec = tween(durationMillis = 180),
        label = "Top app bar gradient opacity"
    )

    // Overlay the app bars on a full-screen content layer. The screen can scroll
    // underneath the transparent bars, while its initial content respects their insets.
    AppPredictiveBackSurface(
        progress = predictiveBackProgress.asState(),
        fromLeftEdge = predictiveBackFromLeft,
        destinationTitle = appRouteDisplayTitle(navigation.back().current),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(if (tab == AppTab.OBJECTS) Modifier.dismissCardSwipeOnInteraction(cardSwipes) else Modifier)
                .nestedScroll(if (isProjectDetailsOpen || isMeasurementDetailsOpen) passiveDetailsScroll
                    else contentOnlyHeaderScroll)
        ) {
        val useCeilingInCalculator: (com.andreyceilings.shared.estimate.FrozenPolygonGeometry) -> Unit = { source ->
            capturedCalculatorPrecision = checkNotNull(MeasurementPrecision.fromStep(source.precisionStepMeters))
            geometryShape = source.shapeType
            legacyPolygonSource = null
            if (source.shapeType == "polygon") {
                if (source.sides.isEmpty()) legacyPolygonSource = source
                else {
                    polygonSides.clear()
                    polygonSides.addAll(source.sides.map { wall ->
                        (wall.lengthMm / 1000.0).toString() to wall.turnAfter
                    })
                }
            } else source.measurementsMm.forEach { (key, mm) ->
                geometryMeasurements["${source.shapeType}.$key"] = (mm / 1000.0).toString()
            }
            openNested(AppTab.CALCULATOR)
        }
        val routeSlot = when {
            navigation.quickMeasurement -> "quick-measurement"
            tab == AppTab.MEASUREMENTS && (isMeasurementDetailsOpen || navigation.measurementProjectPicker) -> "ceiling-workspace"
            else -> tab.name
        }
        key(sessionGeneration) {
        registryState.SaveableStateProvider(routeSlot) {
            if (navigation.quickMeasurement) {
                MeasurementsHost(database, navigation, measurementPrecision, measurementCreateNavigationGuard, ::updateNavigation,
                    onUseMeasurement = useCeilingInCalculator, headerVisibleFraction = 1f - topAppBarScrollBehavior.state.collapsedFraction)
            } else if (tab == AppTab.OBJECTS || tab == AppTab.MEASUREMENTS || tab == AppTab.ESTIMATES || tab == AppTab.PRICES || tab == AppTab.CALENDAR) {
                Box(
                    Modifier.fillMaxSize().nestedScroll(screenScrollConnection)
                        .padding(horizontal = if (tab == AppTab.OBJECTS || tab == AppTab.MEASUREMENTS) 0.dp else 16.dp)
                ) {
                    when (tab) {
                        AppTab.OBJECTS -> ObjectsClientsHost(
                            database = database,
                            swipeState = cardSwipes,
                            detailsProjectId = navigation.projectDetails?.projectId,
                            measurementEdit = navigation.measurementEdit,
                            measurementCreate = navigation.measurementCreate,
                            measurementCreateNavigationGuard = measurementCreateNavigationGuard,
                            measurementPrecision = measurementPrecision,
                            onCreateMeasurement = { updateNavigation(navigation.createMeasurement(it)) },
                            onMeasurementCreated = { projectId, measurementId ->
                                updateNavigation(navigation.measurementCreatedInEditor(projectId, measurementId))
                            },
                            onOpenMeasurement = { projectId, measurementId ->
                                updateNavigation(navigation.openCeilingWorkspace(projectId, measurementId))
                            },
                            onOpenProject = { updateNavigation(navigation.openProject(it)) },
                            onOpenCeilingRegistry = { openNested(AppTab.MEASUREMENTS) },
                            initialClients = objectsStartInClients,
                            initialSearch = initialSearch,
                            searchRequest = searchRequest,
                            onCloseProject = { updateNavigation(navigation.back()) },
                            onProjectHeaderProgress = { projectTitleProgress = it.coerceIn(0f, 1f) },
                            returnToProjectsRequest = returnToProjectsRequest,
                            requestedClientId = requestedClientId,
                            onClientRequestConsumed = { requestedClientId = null },
                            onCalendarEdit = { calendarEditor = it },
                            onProjectCalendar = { projectCalendarStates.removeState(it); projectCalendar = it },
                             onListTabChanged = { selected ->
                                 clientsBackToObjects = selected == com.andreyceilings.shared.objects.ObjectsClientsTab.CLIENTS
                                 objectsStartInClients = clientsBackToObjects
                                // The CMP list resets on tab change; reset the parent
                                // enterAlways header and scroll-based shading too.
                                topAppBarScrollBehavior.state.heightOffset = 0f
                                topAppBarScrollBehavior.state.contentOffset = 0f
                                contentScrolledPx.floatValue = 0f
                            },
                            headerVisibleFraction = if (isProjectDetailsOpen) 1f
                                else 1f - topAppBarScrollBehavior.state.collapsedFraction,
                            openCreateProject = navigation.pendingQuickCreate == AppQuickCreate.OBJECT,
                            onCreateProjectConsumed = { updateNavigation(navigation.consumeQuickCreate(AppQuickCreate.OBJECT)) }
                        )
                        AppTab.MEASUREMENTS -> MeasurementsHost(
                            database, navigation, measurementPrecision, measurementCreateNavigationGuard,
                            ::updateNavigation, initialSearch = initialSearch, searchRequest = searchRequest,
                            onUseMeasurement = useCeilingInCalculator, headerVisibleFraction = 1f - topAppBarScrollBehavior.state.collapsedFraction
                        )
                        AppTab.ESTIMATES -> QuoteRegistryScreen(database?.objectsClientsStore,
                            PaddingValues(top = topContentPadding, bottom = bottomContentPadding), initialSearch, searchRequest,
                            headerVisibleFraction = 1f - topAppBarScrollBehavior.state.collapsedFraction,
                            requestedQuote = requestedQuote, onQuoteRequestConsumed = { requestedQuote = null })
                        AppTab.PRICES -> PriceListScreen(database, darkTheme = useDarkTheme)
                        AppTab.CALENDAR -> WorkCalendarScreen(calendarStore, calendarMode, ::setCalendarMode,
                            onEdit = { calendarEditor = it }, contentPadding = PaddingValues(top = topContentPadding, bottom = bottomContentPadding),
                            systemFirstDay = firstWeekday, showWeekNumbers = showWeekNumbers)
                        else -> Unit
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .nestedScroll(screenScrollConnection)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = topContentPadding,
                            bottom = bottomContentPadding
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (tab) {
                        AppTab.DASHBOARD -> DashboardScreen(
                            database = database,
                            preferences = dashboardPreferences,
                            onCeilings = { openNested(AppTab.MEASUREMENTS) },
                            onObjects = { navigateTo(AppTab.OBJECTS) },
                            onClients = { navigateTo(AppTab.OBJECTS, clients = true) },
                            onEstimates = { openNested(AppTab.ESTIMATES) },
                            onCalendar = { openNested(AppTab.CALENDAR) },
                            onCalendarEdit = { calendarEditor = it }
                        )
                        AppTab.CALCULATOR -> CalculatorScreen(
                            database = database,
                            length = length,
                            width = width,
                            geometryShape = geometryShape,
                            onGeometryShape = {
                                legacyPolygonSource = null
                                geometryShape = it
                            },
                            shapeMeasurements = templateMeasurements,
                            onShapeMeasurement = { key, value ->
                                geometryMeasurements["$geometryShape.$key"] = value
                            },
                            polygonSides = polygonSides.toList(),
                            legacyPolygonSource = legacyPolygonSource,
                            onPolygonSide = { index, side ->
                                legacyPolygonSource = null
                                polygonSides[index] = side
                            },
                            onAddPolygonSide = {
                                legacyPolygonSource = null
                                polygonSides.add("" to
                                    com.andreyceilings.shared.calculation.OrthogonalTurn.RIGHT)
                            },
                            onRemovePolygonSide = { index ->
                                legacyPolygonSource = null
                                if (polygonSides.size > 4) polygonSides.removeAt(index)
                            },
                            price = pricePerM2, onPrice = { pricePerM2 = it },
                            profilePrice = profilePrice, onProfilePrice = { profilePrice = it },
                            material = material, onMaterial = { material = it },
                            profileType = profileType, onProfileType = { profileType = it },
                            measurementPrecision = calculatorPrecision,
                            onMeasurementPrecision = { selected ->
                                capturedCalculatorPrecision = null
                                measurementPrecision = selected
                                precisionPreferences.edit().putString("measurement_precision", selected.name).apply()
                            },
                            estimate = estimate,
                            calculationError = calculationError,
                            priceItems = priceItems,
                            priceItemsAvailable = priceItems.isNotEmpty(),
                            lightLineMeters = lightLineMeters, onLightLineMeters = { lightLineMeters = it },
                            shadowProfileMeters = shadowProfileMeters, onShadowProfileMeters = { shadowProfileMeters = it },
                            floatingCeilingMeters = floatingCeilingMeters, onFloatingCeilingMeters = { floatingCeilingMeters = it },
                            chandeliersCount = chandeliersCount, onChandeliersCount = { chandeliersCount = it },
                            spotCount = spotCount, onSpotCount = { spotCount = it },
                            pipePassCount = pipePassCount, onPipePassCount = { pipePassCount = it },
                            curtainNicheMeters = curtainNicheMeters, onCurtainNicheMeters = { curtainNicheMeters = it },
                            extraCorners = extraCorners, onExtraCorners = { extraCorners = it },
                            hardWallMeters = hardWallMeters, onHardWallMeters = { hardWallMeters = it },
                            complexity = complexity, onComplexity = { complexity = it }
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            databaseStatus, database, onDatabaseReplaced, themeMode, onThemeModeChange,
                            onOpenPriceList = { openNested(AppTab.PRICES) },
                            dashboardPreferences = dashboardPreferences,
                            onDashboardPreferences = { next ->
                                val saved = withContext(Dispatchers.IO) {
                                    dashboardStorage.edit().putString("layout", next.encode()).commit()
                                }
                                if (saved) dashboardPreferences = next
                                saved
                            },
                            appLanguage = appLanguage,
                            showWeekNumbers = showWeekNumbers, onShowWeekNumbersChange = ::setShowWeekNumbers,
                            measurementPrecision = measurementPrecision,
                            onMeasurementPrecision = { selected ->
                                capturedCalculatorPrecision = null
                                measurementPrecision = selected
                                precisionPreferences.edit().putString("measurement_precision", selected.name).apply()
                            }
                        )
                        else -> Unit
                    }
                }
            }
        }
        }

        // As the original address moves into the top safe area, it takes the
        // place of СтеляПро. On a true return to the top, the chrome reappears.
        if (!isMeasurementDetailsOpen && (!isProjectDetailsOpen || projectTitleProgress < 0.95f)) AppHeader(
            collapsedFraction = if (isProjectDetailsOpen) 0f
                else topAppBarScrollBehavior.state.collapsedFraction,
            // The objects/clients sticky glass reaches the screen's top edge.
            // Keep the Android system-bar fade at the same 50% translucency,
            // rather than covering it with the 93% collapsed-header shade.
            shadeOpacity = if (tab == AppTab.OBJECTS && !isProjectDetailsOpen)
                0.50f else headerShadeOpacity,
            drawGradient = (tab != AppTab.OBJECTS || isProjectDetailsOpen) &&
                !(tab == AppTab.ESTIMATES || tab == AppTab.MEASUREMENTS && !navigation.measurementProjectPicker),
            statusBarHeight = statusBarInset,
            windowInsets = WindowInsets.statusBars,
            scrollBehavior = if (isProjectDetailsOpen) null else topAppBarScrollBehavior,
            onSettings = { openNested(AppTab.SETTINGS) },
            extraActions = {
                if (showGlobalSearch) RegistrySearchAction { openSearch = true }
                ThemeModeSelector(mode = themeMode, onModeChange = onThemeModeChange, compact = true)
            },
            modifier = Modifier.align(Alignment.TopCenter)
                .alpha(if (isProjectDetailsOpen)
                    (1f - projectTitleProgress) * (1f - projectTitleProgress)
                else 1f)
        )
        if (!isMeasurementEditOpen) CeilingsEdgeGradient(
            edge = CeilingsEdge.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(navigationBarInset + systemPanelGradientHeight)
        )

        fun openCalendarRegistryTarget(hit: GlobalRegistryTarget) {
            if (hit is GlobalRegistryTarget.Ceiling && tab == AppTab.OBJECTS && navigation.projectDetails?.projectId == hit.projectId) {
                updateNavigation(navigation.openLinkedCeiling(hit.projectId, hit.id))
                return
            }
            val target = when (hit) {
                is GlobalRegistryTarget.Project, is GlobalRegistryTarget.Client -> AppTab.OBJECTS
                is GlobalRegistryTarget.Ceiling -> AppTab.MEASUREMENTS
                is GlobalRegistryTarget.Quote -> AppTab.ESTIMATES
            }
            if (target != tab) {
                initialSearch = ""
                repeatedSearchRoute = null
                registryState.removeState(target.name)
                if (target == AppTab.OBJECTS) {
                    objectsStartInClients = hit is GlobalRegistryTarget.Client
                    clientsBackToObjects = false
                }
            }
            val destination = navigation.navigate(target)
            when (hit) {
                is GlobalRegistryTarget.Project -> updateNavigation(destination.openProject(hit.id))
                is GlobalRegistryTarget.Ceiling -> updateNavigation(navigation.openLinkedCeiling(hit.projectId, hit.id))
                is GlobalRegistryTarget.Client -> {
                    requestedClientId = hit.id
                    updateNavigation(destination)
                }
                is GlobalRegistryTarget.Quote -> {
                    requestedQuote = hit
                    updateNavigation(destination)
                }
            }
        }

        if (showGlobalSearch && openSearch) RegistrySearchDialog(
            database?.objectsClientsStore, onClose = { openSearch = false },
            // Android Dialog has its own window; opt into edge-to-edge so its
            // content receives the IME insets instead of being panned behind it.
            properties = DialogProperties(usePlatformDefaultWidth = false,
                dismissOnClickOutside = false, decorFitsSystemWindows = false),
            calendar = calendarStore,
            onWork = { openSearch = false; calendarEditor = CalendarEditorRoute.Open(it) },
        ) { hit ->
            openSearch = false
            openCalendarRegistryTarget(hit)
        }

        if (calendarStore != null) {
            projectCalendar?.let { projectUuid ->
                fun closeProjectCalendar() { projectCalendarStates.removeState(projectUuid); projectCalendar = null }
                projectCalendarStates.SaveableStateProvider(projectUuid) {
                androidx.compose.ui.window.Dialog(onDismissRequest = ::closeProjectCalendar, properties = calendarWindow) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Surface(Modifier.fillMaxWidth().fillMaxHeight(.66f), shape = MaterialTheme.shapes.extraLarge) {
                            WorkCalendarScreen(calendarStore, CalendarDisplayMode.LIST, {}, onEdit = { calendarEditor = it },
                                contentPadding = PaddingValues(8.dp), systemFirstDay = firstWeekday, projectFilter = projectUuid,
                                onClose = ::closeProjectCalendar)
                        }
                    }
                }
                }
            }
            calendarEditor?.let { route ->
                WorkEditor(calendarStore, route, onClose = { calendarEditor = null }, properties = calendarWindow,
                    incomingWork = notificationWork,
                    onIncomingWorkHandled = { accepted ->
                        if (accepted) notificationWork?.let { calendarEditor = CalendarEditorRoute.Open(it) }
                        onNotificationOpened()
                    },
                    registry = database?.objectsClientsStore,
                    openExternalUri = { url ->
                        val action = if (url.startsWith("tel:")) android.content.Intent.ACTION_DIAL else android.content.Intent.ACTION_VIEW
                        context.startActivity(android.content.Intent(action, Uri.parse(url)))
                    },
                    onRegistryTarget = { hit ->
                        val destination = when (hit) {
                            is GlobalRegistryTarget.Project -> navigation.openCalendarProject(hit.id)
                            is GlobalRegistryTarget.Ceiling -> navigation.openCalendarCeiling(hit.projectId, hit.id)
                            else -> navigation
                        }
                        if (destination != navigation && destination.calendarLinkReturn != null) {
                            calendarReturnOverlay = Triple(checkNotNull(destination.calendarLinkReturn).origin, route, projectCalendar)
                            openSearch = false
                            if (destination.current != tab) {
                                initialSearch = ""
                                repeatedSearchRoute = null
                                if (destination.current == AppTab.OBJECTS) {
                                    objectsStartInClients = false
                                    clientsBackToObjects = false
                                }
                            }
                            updateNavigation(destination)
                        } else if (hit is GlobalRegistryTarget.Project && navigation.projectDetails?.projectId == hit.id) {
                            calendarEditor = null
                            projectCalendar = null
                        } else if (hit !is GlobalRegistryTarget.Project && hit !is GlobalRegistryTarget.Ceiling) {
                            calendarEditor = null; projectCalendar = null; openSearch = false; openCalendarRegistryTarget(hit)
                        }
                    },
                    onSaved = { result, draft ->
                        calendarEditor = null
                        detachedCalendarWorks = result.detached
                        if (result.installationCompleted) completionProject = draft.projectUuid?.let { it to result.key }
                    })
            }
            CalendarSaveFeedback(detachedCalendarWorks, completionProject != null,
                onDismiss = { completionProject = null; detachedCalendarWorks = 0 },
                onComplete = {
                    val project = completionProject
                    completionProject = null
                    detachedCalendarWorks = 0
                    if (project != null) scope.launch {
                        runCatching { database?.objectsClientsStore?.completeCalendarProject(project.first, project.second) }
                    }
                })
        }

        if (!isMeasurementEditOpen && !measurementFullscreen && !navigation.measurementProjectPicker) FloatingNavigationBar(
            selectedTab = tab,
            darkTheme = useDarkTheme,
            onSelect = { navigateTo(it) },
            onQuickCreate = { target ->
                if (database == null) {
                    Toast.makeText(context, "Захищена база зараз недоступна", Toast.LENGTH_SHORT).show()
                } else {
                    when (target) {
                        AppTab.OBJECTS -> startQuickCreate(AppQuickCreate.OBJECT)
                        AppTab.MEASUREMENTS -> startQuickCreate(AppQuickCreate.MEASUREMENT)
                        else -> navigateTo(target)
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).testTag("global-bottom-navigation")
        )
    }
    }
}

/** Keep the shared navigation guard attached to an in-flight save across Activity recreation. */
internal class MeasurementEditorSession : androidx.lifecycle.ViewModel() {
    val navigationGuard = com.andreyceilings.shared.measurement.MeasurementCreateNavigationGuard()
    override fun onCleared() { navigationGuard.quoteSession.clear() }
}
