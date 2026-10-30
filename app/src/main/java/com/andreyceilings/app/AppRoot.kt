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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import com.andreyceilings.app.ui.theme.CeilingsTheme
import com.andreyceilings.app.ui.theme.ThemeMode
import com.andreyceilings.app.ui.theme.ThemeModeSelector
import com.andreyceilings.app.core.CeilingEstimate
import com.andreyceilings.app.core.CeilingCalculator
import com.andreyceilings.app.core.CalculatorOptions
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.FrozenCeilingShape
import com.andreyceilings.shared.estimate.VerifiedCeilingGeometry
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.ui.screens.*
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicLong

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal enum class AppTab(val title: String) {
    DASHBOARD("Панель"), OBJECTS("Об’єкти"), MEASUREMENTS("Заміри"),
    CALCULATOR("Калькулятор"), ESTIMATES("Кошториси"), PRICES("Прайс"), SETTINGS("Налаштування"), CALENDAR("Календар")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CeilingsApp(databaseStatus: String, database: CeilingsDatabase?, onDatabaseReplaced: (CeilingsDatabase) -> Unit, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    var tab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var tabChangedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var lastBackPressTime by remember { mutableStateOf(0L) }
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDarkTheme
    }

    val topAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val isScrolling = remember { mutableStateOf(false) }
    // Keep app chrome transparent so the window background is visible behind the system bars.
    val appBarColor = androidx.compose.ui.graphics.Color.Transparent
    val navigationBarColor = androidx.compose.ui.graphics.Color.Transparent
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
        if (tab != target) {
            tabChangedAt = SystemClock.elapsedRealtime()
            tab = target
        }
    }

    LaunchedEffect(tab) {
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

    BackHandler {
        if (tab != AppTab.DASHBOARD) {
            navigateTo(AppTab.DASHBOARD)
            lastBackPressTime = 0L
        } else {
            val now = SystemClock.elapsedRealtime()
            if (lastBackPressTime != 0L && now - lastBackPressTime <= 2_000L) {
                context.findActivity()?.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(context, "Натисніть «Назад» ще раз, щоб вийти", Toast.LENGTH_SHORT).show()
            }
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
    var length by remember { mutableStateOf("5.4") }
    var width by remember { mutableStateOf("3.8") }
    var geometryShape by remember { mutableStateOf(FrozenCeilingShape.RECTANGLE.wireId) }
    val lShapedSides = remember {
        mutableStateListOf("5", "4", "2", "2", "3", "2")
    }
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
    val calculation = remember(length, width, geometryShape, lShapedSides.toList(), pricePerM2, profilePrice, priceItems, calculatorOptions, material, profileType, measurementPrecision) {
        runCatching {
            val verifiedGeometry = when (geometryShape) {
                FrozenCeilingShape.RECTANGLE.wireId ->
                    VerifiedCeilingGeometry.capturedRectangle(
                        length.toSafeDouble(), width.toSafeDouble(),
                        measurementPrecision.stepMeters
                    )
                FrozenCeilingShape.L_SHAPED.wireId -> VerifiedCeilingGeometry.capturedLShaped(
                    lShapedSides.map {
                        it.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN
                    },
                    measurementPrecision.stepMeters
                )
                else -> error("Невідомий тип геометрії")
            }
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

    val topContentPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 16.dp
    val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp + 16.dp

    // 50% transparent at the screen edges, fading smoothly to fully transparent
    // over the app content. Android's own system bars remain truly transparent.
    val systemPanelTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.50f)
    val transparentSystemPanel = systemPanelTint.copy(alpha = 0f)
    val systemPanelGradientHeight = 36.dp
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Overlay the app bars on a full-screen content layer. The screen can scroll
    // underneath the transparent bars, while its initial content respects their insets.
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
                        AppTab.OBJECTS -> ObjectsScreen(database)
                        AppTab.MEASUREMENTS -> MeasurementsScreen(database, measurementPrecision, onOpenEstimates = { navigateTo(AppTab.ESTIMATES) })
                        AppTab.ESTIMATES -> EstimatesScreen(database)
                        AppTab.PRICES -> PriceListScreen(database)
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
                            onNewMeasurement = { navigateTo(AppTab.MEASUREMENTS) },
                            onCalculator = { navigateTo(AppTab.CALCULATOR) },
                            onObjects = { navigateTo(AppTab.OBJECTS) },
                            onSettings = { navigateTo(AppTab.SETTINGS) }
                        )
                        AppTab.CALCULATOR -> CalculatorScreen(
                            database = database,
                            length = length, onLength = { length = it },
                            width = width, onWidth = { width = it },
                            geometryShape = geometryShape,
                            onGeometryShape = { geometryShape = it },
                            lShapedSides = lShapedSides.toList(),
                            onLShapedSide = { index, value -> lShapedSides[index] = value },
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
                            onOpenPriceList = { navigateTo(AppTab.PRICES) },
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

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(statusBarInset + systemPanelGradientHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(systemPanelTint, transparentSystemPanel)
                    )
                )
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

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            windowInsets = WindowInsets.statusBars,
            scrollBehavior = topAppBarScrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = appBarColor,
                scrolledContainerColor = appBarColor,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground
            ),
            title = {
                Column {
                    Text("СтеляПро", fontWeight = FontWeight.Bold)
                    Text("Майстер натяжних стель", style = MaterialTheme.typography.labelSmall)
                }
            },
            actions = {
                ThemeModeSelector(mode = themeMode, onModeChange = onThemeModeChange, compact = true)
                IconButton(onClick = { navigateTo(AppTab.SETTINGS) }) { Icon(Icons.Default.Settings, "Налаштування") }
            }
        )
        NavigationBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .height(68.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface),
            windowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = navigationBarColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp
        ) {
            val navigationItemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val items = listOf(
                AppTab.DASHBOARD to Icons.Default.Dashboard,
                AppTab.OBJECTS to Icons.Default.Business,
                AppTab.MEASUREMENTS to Icons.Default.Straighten,
                AppTab.CALCULATOR to Icons.Default.Calculate,
                AppTab.CALENDAR to Icons.Default.CalendarMonth
            )
            items.forEach { (item, icon) ->
                NavigationBarItem(
                    selected = tab == item,
                    onClick = { navigateTo(item) },
                    icon = { Icon(icon, contentDescription = item.title) },
                    label = {
                        Text(
                            item.title,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = (-0.25).sp),
                            modifier = Modifier.offset(y = (-2).dp),
                            textAlign = TextAlign.Center
                        )
                    },
                    colors = navigationItemColors
                )
            }
        }
    }
}