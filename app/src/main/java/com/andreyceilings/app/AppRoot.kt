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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
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
    CALCULATOR("Калькулятор"), ESTIMATES("Кошториси"), PRICES("Прайс"), SETTINGS("Налаштування")
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

    SideEffect {
        context.findActivity()?.window?.let { window ->
            window.statusBarColor = MaterialTheme.colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !useDarkTheme
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
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                lastScrollAtNs.set(SystemClock.elapsedRealtimeNanos())
                return Offset.Zero
            }
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
    var length by remember { mutableStateOf("5.4") }
    var width by remember { mutableStateOf("3.8") }
    var pricePerM2 by remember { mutableStateOf("290") }
    var profilePrice by remember { mutableStateOf("130") }
    var profileType by remember { mutableStateOf("Алюмінієвий гарпунний") }
    var material by remember { mutableStateOf("ПВХ матовий") }
    var lightLineMeters by remember { mutableStateOf("0") }
    var shadowProfileMeters by remember { mutableStateOf("0") }
    var floatingCeilingMeters by remember { mutableStateOf("0") }
    var chandeliersCount by remember { mutableStateOf("0") }
    var spotsCount by remember { mutableStateOf("0") }
    var pipePassCount by remember { mutableStateOf("0") }
    var curtainNicheMeters by remember { mutableStateOf("0") }
    var extraCorners by remember { mutableStateOf("0") }
    var hardWallMeters by remember { mutableStateOf("0") }
    var complexity by remember { mutableStateOf("straight") }

    val priceItemsFlow = remember(database) {
        database?.priceItems()?.observeAll() ?: flowOf<List<PriceItemEntity>>(emptyList())
    }
    val priceItems by priceItemsFlow.collectAsState(initial = emptyList())
    fun materialCode(label: String): String = when (label) {
        "ПВХ матовий" -> "canvas.pvc_matte"
        "ПВХ глянцевий" -> "canvas.pvc_glossy"
        "ПВХ сатиновий" -> "canvas.pvc_satin"
        "Тканинний Descor" -> "canvas.fabric"
        "Негорючий KM2" -> "canvas.fireproof_km2"
        "Акустичний" -> "canvas.acoustic"
        else -> "canvas.pvc_matte"
    }
    fun profileCode(label: String): String = when (label) {
        "Алюмінієвий гарпунний" -> "profile.standard_alu"
        "ПВХ стіновий" -> "profile.pvc"
        "Тіньовий EuroKRAAB" -> "profile.shadow_kraab"
        "Парящий з LED" -> "profile.floating_led"
        "Слот / трековий" -> "profile.slott_tracks"
        else -> "profile.standard_alu"
    }

    // Keep calculator defaults synchronized with the current encrypted price list.
    LaunchedEffect(priceItems, material, profileType) {
        priceItems.firstOrNull { it.code == materialCode(material) }?.let { pricePerM2 = it.price.toPriceInput() }
        priceItems.firstOrNull { it.code == profileCode(profileType) }?.let { profilePrice = it.price.toPriceInput() }
    }

    val calculatorOptions = CalculatorOptions(
        lightLinesMeters = lightLineMeters.toSafeDouble(),
        shadowProfileMeters = shadowProfileMeters.toSafeDouble(),
        floatingCeilingMeters = floatingCeilingMeters.toSafeDouble(),
        chandeliersCount = chandeliersCount.toSafeDouble(),
        spotsGX53Count = spotsCount.toSafeDouble(),
        pipePassCount = pipePassCount.toSafeDouble(),
        curtainNicheMeters = curtainNicheMeters.toSafeDouble(),
        extraCorners = extraCorners.toSafeDouble(),
        hardWallMeters = hardWallMeters.toSafeDouble(),
        complexity = complexity
    )
    // Go is the only calculation engine. Failure is surfaced to the user; no Kotlin fallback exists.
    val calculation = remember(length, width, pricePerM2, profilePrice, priceItems, calculatorOptions, material, profileType) {
        runCatching {
            CeilingCalculator.estimate(
                length.toSafeDouble(), width.toSafeDouble(),
                pricePerM2.toSafeDouble(), profilePrice.toSafeDouble(),
                priceItems = priceItems, options = calculatorOptions,
                materialType = materialCode(material).removePrefix("canvas."),
                profileType = profileCode(profileType).removePrefix("profile."),
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
        error.message?.takeIf { it.isNotBlank() } ?: "Невідома помилка Go-рушія"
    }

    Scaffold(
        topBar = {
            TopAppBar(
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
        },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    AppTab.DASHBOARD to Icons.Default.Dashboard,
                    AppTab.OBJECTS to Icons.Default.Business,
                    AppTab.MEASUREMENTS to Icons.Default.Straighten,
                    AppTab.CALCULATOR to Icons.Default.Calculate,
                    AppTab.ESTIMATES to Icons.Default.ReceiptLong,
                    AppTab.PRICES to Icons.Default.PriceChange
                )
                items.forEach { (item, icon) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { navigateTo(item) },
                        icon = { Icon(icon, contentDescription = item.title) },
                        label = { Text(item.title, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        key(tab) {
            if (tab == AppTab.OBJECTS || tab == AppTab.MEASUREMENTS || tab == AppTab.ESTIMATES || tab == AppTab.PRICES) {
                Box(
                    Modifier.fillMaxSize().padding(padding)
                        .nestedScroll(screenScrollConnection).padding(16.dp)
                ) {
                    when (tab) {
                        AppTab.OBJECTS -> ObjectsScreen(database)
                        AppTab.MEASUREMENTS -> MeasurementsScreen(database)
                        AppTab.ESTIMATES -> EstimatesScreen(database)
                        AppTab.PRICES -> PriceListScreen(database)
                        else -> Unit
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding)
                        .nestedScroll(screenScrollConnection)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
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
                            price = pricePerM2, onPrice = { pricePerM2 = it },
                            profilePrice = profilePrice, onProfilePrice = { profilePrice = it },
                            material = material, onMaterial = { material = it },
                            profileType = profileType, onProfileType = { profileType = it },
                            estimate = estimate,
                            calculationError = calculationError,
                            priceItems = priceItems,
                            priceItemsAvailable = priceItems.isNotEmpty(),
                            lightLineMeters = lightLineMeters, onLightLineMeters = { lightLineMeters = it },
                            shadowProfileMeters = shadowProfileMeters, onShadowProfileMeters = { shadowProfileMeters = it },
                            floatingCeilingMeters = floatingCeilingMeters, onFloatingCeilingMeters = { floatingCeilingMeters = it },
                            chandeliersCount = chandeliersCount, onChandeliersCount = { chandeliersCount = it },
                            spotsCount = spotsCount, onSpotsCount = { spotsCount = it },
                            pipePassCount = pipePassCount, onPipePassCount = { pipePassCount = it },
                            curtainNicheMeters = curtainNicheMeters, onCurtainNicheMeters = { curtainNicheMeters = it },
                            extraCorners = extraCorners, onExtraCorners = { extraCorners = it },
                            hardWallMeters = hardWallMeters, onHardWallMeters = { hardWallMeters = it },
                            complexity = complexity, onComplexity = { complexity = it }
                        )
                        AppTab.SETTINGS -> SettingsScreen(databaseStatus, database, onDatabaseReplaced, themeMode, onThemeModeChange)
                        else -> Unit
                    }
                }
            }
        }
    }
}
