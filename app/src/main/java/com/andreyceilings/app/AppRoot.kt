package com.andreyceilings.app

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
import com.andreyceilings.app.data.db.FrozenPolygonGeometryJson
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.navigation.AppHeader
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.navigation.AppPredictiveBackSurface
import com.andreyceilings.shared.navigation.AppQuickCreate
import com.andreyceilings.shared.navigation.AppRoute
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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

private val navigationStateSaver = Saver<AppNavigationState, ArrayList<String>>(
    save = { ArrayList(it.snapshot()) },
    restore = { AppNavigationState.restore(it) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CeilingsApp(databaseStatus: String, database: CeilingsDatabase?, onDatabaseReplaced: (CeilingsDatabase) -> Unit, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    var navigation by rememberSaveable(stateSaver = navigationStateSaver) {
        mutableStateOf(AppNavigationState())
    }
    val tab = navigation.current
    var tabChangedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var lastBackPressTime by remember { mutableStateOf(0L) }
    val predictiveBackProgress = remember { Animatable(0f) }
    val predictiveBackFromLeft = remember { mutableStateOf(true) }
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDarkTheme
    }

    // Each tab opens with the app header visible. Previously the same TopAppBar
    // scroll state survived tab changes, so opening Price List after scrolling
    // another tab could leave the СтеляПро header collapsed until the next swipe.
    // Keying only the scroll behavior (not the app state) preserves per-screen
    // scrolling while resetting the header on navigation.
    val topAppBarScrollBehavior = key(tab) {
        TopAppBarDefaults.enterAlwaysScrollBehavior()
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

    fun navigateTo(target: AppTab) {
        val next = navigation.selectTab(target)
        if (next.current != tab) tabChangedAt = SystemClock.elapsedRealtime()
        navigation = next
    }

    fun openNested(target: AppTab) {
        val next = navigation.navigate(target)
        if (next.current != tab) tabChangedAt = SystemClock.elapsedRealtime()
        navigation = next
    }

    fun startQuickCreate(action: AppQuickCreate) {
        val next = navigation.requestQuickCreate(action)
        if (next.current != tab) tabChangedAt = SystemClock.elapsedRealtime()
        navigation = next
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
                contentScrolledPx.floatValue =
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
    PredictiveBackHandler(enabled = tab != AppTab.DASHBOARD) { events ->
        var completed = false
        try {
            events.collect { event ->
                predictiveBackFromLeft.value = event.swipeEdge == BackEventCompat.EDGE_LEFT
                predictiveBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            // Commit only after a fully completed gesture (never during a swipe).
            completed = true
            predictiveBackProgress.snapTo(0f)
            val next = navigation.back()
            if (next.current != navigation.current) tabChangedAt = SystemClock.elapsedRealtime()
            navigation = next
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
    BackHandler(enabled = tab == AppTab.DASHBOARD) {
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
    val calculation = remember(geometryShape, templateMeasurements, polygonSides.toList(), legacyPolygonSource, pricePerM2, profilePrice, priceItems, calculatorOptions, material, profileType, measurementPrecision) {
        runCatching {
            val verifiedGeometry = if (geometryShape == "polygon") {
                val old = legacyPolygonSource
                if (old != null) {
                    require(old.precisionStepMeters == measurementPrecision.stepMeters) {
                        "Точність збереженого заміру змінилась"
                    }
                    com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract.requireValid(old)
                    com.andreyceilings.shared.calculation.GeometryInput(
                        shape = "polygon", verticesMm = old.vertices,
                        precisionStepMeters = old.precisionStepMeters
                    )
                } else {
                    val sides = sidesFromText(polygonSides.toList(), measurementPrecision.stepMeters)
                    val contour = com.andreyceilings.shared.calculation.OrthogonalSideContour.build(
                        sides, measurementPrecision.stepMeters
                    )
                    com.andreyceilings.shared.calculation.GeometryInput(
                        shape = "polygon", verticesMm = contour.vertices,
                        precisionStepMeters = measurementPrecision.stepMeters
                    )
                }
            } else VerifiedCeilingGeometry.capturedTemplate(
                geometryShape,
                templateMeasurements.mapValues { (_, text) ->
                    text.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN
                },
                measurementPrecision.stepMeters
            )
            CeilingCalculator.estimate(
                length.toSafeDouble(), width.toSafeDouble(),
                pricePerM2.toSafeDouble(), profilePrice.toSafeDouble(),
                priceItems = priceItems, options = calculatorOptions,
                materialType = material,
                profileType = profileType,
                precisionStepMeters = measurementPrecision.stepMeters,
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
    val systemPanelTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.50f)
    val transparentSystemPanel = systemPanelTint.copy(alpha = 0f)
    val systemPanelGradientHeight = 36.dp
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
        destinationTitle = navigation.back().current.title,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
        ) {
        key(tab) {
            if (tab == AppTab.OBJECTS || tab == AppTab.MEASUREMENTS || tab == AppTab.ESTIMATES || tab == AppTab.PRICES || tab == AppTab.CALENDAR) {
                Box(
                    Modifier.fillMaxSize().nestedScroll(screenScrollConnection).padding(horizontal = 16.dp)
                ) {
                    when (tab) {
                        AppTab.OBJECTS -> ObjectsClientsHost(
                            database = database,
                            openCreateProject = navigation.pendingQuickCreate == AppQuickCreate.OBJECT,
                            onCreateProjectConsumed = { navigation = navigation.consumeQuickCreate(AppQuickCreate.OBJECT) }
                        )
                        AppTab.MEASUREMENTS -> MeasurementsScreen(
                            database = database,
                            precision = measurementPrecision,
                            onOpenEstimates = { navigateTo(AppTab.ESTIMATES) },
                            onUseMeasurement = { measured ->
                                // A saved measurement is copied into calculator STATE.
                                // Never mutate its SQLDelight row or any old estimate revision.
                                val legacy = measured.shape == "rectangle" &&
                                    measured.drawingJson.trim() == "{}"
                                if (legacy) {
                                    check(measured.lengthMeters > 0.0 &&
                                        measured.widthMeters > 0.0) {
                                        "Історичний замір не має коректних габаритів"
                                    }
                                    geometryShape = "rectangle"
                                    geometryMeasurements["rectangle.length"] =
                                        measured.lengthMeters.toString()
                                    geometryMeasurements["rectangle.width"] =
                                        measured.widthMeters.toString()
                                } else {
                                    val source = FrozenPolygonGeometryJson.readMeasurement(
                                        measured.drawingJson
                                    )
                                    val selected = checkNotNull(
                                        MeasurementPrecision.fromStep(source.precisionStepMeters)
                                    )
                                    measurementPrecision = selected
                                    precisionPreferences.edit()
                                        .putString("measurement_precision", selected.name).apply()
                                    geometryShape = source.shapeType
                                    if (source.shapeType == "polygon") {
                                        if (source.sides.isEmpty()) {
                                            legacyPolygonSource = source
                                        } else {
                                            legacyPolygonSource = null
                                            polygonSides.clear()
                                            polygonSides.addAll(source.sides.map { wall ->
                                                (wall.lengthMm / 1000.0).toString() to wall.turnAfter
                                            })
                                        }
                                    } else {
                                        source.measurementsMm.forEach { (key, mm) ->
                                            geometryMeasurements["${source.shapeType}.$key"] =
                                                (mm / 1000.0).toString()
                                        }
                                    }
                                }
                                navigateTo(AppTab.CALCULATOR)
                            },
                            openCreateMeasurement = navigation.pendingQuickCreate == AppQuickCreate.MEASUREMENT,
                            onCreateMeasurementConsumed = { navigation = navigation.consumeQuickCreate(AppQuickCreate.MEASUREMENT) },
                            onMissingProject = {
                                startQuickCreate(AppQuickCreate.OBJECT)
                                Toast.makeText(context, "Спочатку створіть об’єкт для заміру", Toast.LENGTH_LONG).show()
                            }
                        )
                        AppTab.ESTIMATES -> EstimatesScreen(database)
                        AppTab.PRICES -> PriceListScreen(database, darkTheme = useDarkTheme)
                        AppTab.CALENDAR -> CalendarScreen(database)
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
                            onNewMeasurement = { navigateTo(AppTab.MEASUREMENTS) },
                            onCalculator = { navigateTo(AppTab.CALCULATOR) },
                            onObjects = { navigateTo(AppTab.OBJECTS) },
                            onEstimates = { navigateTo(AppTab.ESTIMATES) },
                            onCalendar = { navigateTo(AppTab.CALENDAR) }
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
                            measurementPrecision = measurementPrecision,
                            onMeasurementPrecision = { selected ->
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
                            measurementPrecision = measurementPrecision,
                            onMeasurementPrecision = { selected ->
                                measurementPrecision = selected
                                precisionPreferences.edit().putString("measurement_precision", selected.name).apply()
                            }
                        )
                        else -> Unit
                    }
                }
            }
        }

        // The shared CMP header retains the Android enter-always scroll behavior.
        // Only window insets and platform theme actions remain in this host.
        AppHeader(
            collapsedFraction = topAppBarScrollBehavior.state.collapsedFraction,
            shadeOpacity = headerShadeOpacity,
            statusBarHeight = statusBarInset,
            windowInsets = WindowInsets.statusBars,
            scrollBehavior = topAppBarScrollBehavior,
            onSettings = { openNested(AppTab.SETTINGS) },
            extraActions = {
                ThemeModeSelector(mode = themeMode, onModeChange = onThemeModeChange, compact = true)
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navigationBarInset + systemPanelGradientHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(transparentSystemPanel, systemPanelTint)
                    )
                )
        )

        FloatingNavigationBar(
            selectedTab = tab,
            darkTheme = useDarkTheme,
            onSelect = ::navigateTo,
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
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }
}