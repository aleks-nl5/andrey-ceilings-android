package com.andreyceilings.desktop

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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.andreyceilings.shared.dashboard.DashboardSummary
import kotlinx.coroutines.flow.Flow
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
    var navigation by remember { mutableStateOf(AppNavigationState()) }
    val availableRoutes = remember { setOf(AppRoute.DASHBOARD, AppRoute.PRICES, AppRoute.OBJECTS) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "СтеляПро",
        // Bubble after editor/dialog handlers so Escape closes an active modal first.
        onKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                val next = navigation.back(availableRoutes)
                if (next != navigation) {
                    navigation = next
                    true
                } else false
            } else false
        }
    ) {
        PriceCatalogTheme(
            darkTheme = isSystemInDarkTheme(),
            typography = remember { ceilingsTypography() }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                DesktopPriceHost(
                    navigation = navigation,
                    availableRoutes = availableRoutes,
                    onNavigate = { navigation = it }
                )
            }
        }
    }
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
    onNavigate: (AppNavigationState) -> Unit
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DesktopPriceDatabase?>(null) }
    var password by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf(false) }

    // Close the exact previous session, never a newly assigned replacement.
    val database = session
    DisposableEffect(database) {
        onDispose { database?.close() }
    }
    if (database != null) {
        val store = remember(database) {
            PriceCatalogStore(database.prices, nowMillis = System::currentTimeMillis)
        }
        val items by store.items.collectAsState(initial = emptyList())
        val objectsStore = remember(database) { database.objects }
        val objectRows by objectsStore.rows.collectAsState(initial =
            ObjectsClientsRows(emptyList(), emptyList()))
        var externalError by remember { mutableStateOf<String?>(null) }
        fun openExternal(url: String) {
            runCatching {
                check(Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    "Немає застосунку для зовнішнього посилання."
                }
                Desktop.getDesktop().browse(URI(url))
            }.onFailure { externalError = it.message ?: "Не вдалося відкрити посилання." }
        }
        val summaryFlow: Flow<DashboardSummary?> = remember(database) { database.dashboard.summary }
        val summary by summaryFlow.collectAsState(initial = null)
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
                AppDestination(AppRoute.OBJECTS.name, "Об'єкти", "Об'єкти", Icons.Default.Business)
            )
        }
        val topScrollBehavior = key(selectedTab) {
            TopAppBarDefaults.enterAlwaysScrollBehavior()
        }
        Box(Modifier.fillMaxSize().nestedScroll(topScrollBehavior.nestedScrollConnection)) {
            when (selectedTab) {
                AppRoute.DASHBOARD -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(
                                start = 24.dp, end = 24.dp,
                                top = AppHeaderHeight + 16.dp,
                                bottom = FloatingBarHeight + 16.dp
                            )
                    ) {
                        // Only real host actions are available, never mock buttons.
                        DashboardScreen(
                            summary = summary,
                            databaseAvailable = true,
                            onNewMeasurement = null,
                            onCalculator = null,
                            onObjects = { onNavigate(navigation.selectTab(AppRoute.OBJECTS, availableRoutes)) },
                            onEstimates = null,
                            onCalendar = null,
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
                    rows = objectRows,
                    headerVisibleFraction = 1f - topScrollBehavior.state.collapsedFraction,
                    databaseAvailable = true,
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp,
                        top = AppHeaderHeight + 16.dp,
                        bottom = FloatingBarHeight + 16.dp
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
                AppRoute.PRICES -> PriceCatalogScreen(
                    items = items,
                    databaseAvailable = true,
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp,
                        top = AppHeaderHeight + 16.dp,
                        bottom = FloatingBarHeight + 16.dp
                    ),
                    onSave = { original, draft ->
                        withContext(Dispatchers.IO) { store.save(original, draft) }
                    },
                    onDelete = { item ->
                        withContext(Dispatchers.IO) { store.delete(item) }
                    }
                )
                else -> Unit // Only actually implemented desktop routes are exposed.
            }
            if (externalError != null) {
                TextButton(onClick = { externalError = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = AppHeaderHeight)) {
                    Text(externalError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
            AppHeader(
                collapsedFraction = topScrollBehavior.state.collapsedFraction,
                shadeOpacity = 0.50f + 0.43f * topScrollBehavior.state.collapsedFraction,
                statusBarHeight = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = topScrollBehavior,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            AppFloatingNavigation(
                selectedId = selectedTab.name,
                destinations = navItems,
                darkTheme = isSystemInDarkTheme(),
                onSelect = { id ->
                    AppRoute.fromId(id)?.let { route ->
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
