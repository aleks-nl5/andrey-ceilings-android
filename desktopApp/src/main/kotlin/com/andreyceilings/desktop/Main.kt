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
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andreyceilings.shared.navigation.AppDestination
import com.andreyceilings.shared.navigation.AppFloatingNavigation
import com.andreyceilings.shared.navigation.AppHeader
import com.andreyceilings.shared.navigation.AppHeaderHeight
import com.andreyceilings.shared.navigation.FloatingBarHeight
import com.andreyceilings.shared.dashboard.DashboardScreen
import com.andreyceilings.shared.dashboard.DashboardSummary
import kotlinx.coroutines.flow.Flow
import com.andreyceilings.shared.prices.PriceCatalogScreen
import com.andreyceilings.shared.prices.PriceCatalogStore
import com.andreyceilings.shared.prices.PriceCatalogTheme
import com.andreyceilings.shared.prices.ceilingsTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class DesktopTab { HOME, PRICES }

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "СтеляПро") {
        PriceCatalogTheme(
            darkTheme = isSystemInDarkTheme(),
            typography = remember { ceilingsTypography() }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                DesktopPriceHost()
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
private fun DesktopPriceHost() {
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
        val summaryFlow: Flow<DashboardSummary?> = remember(database) { database.dashboard.summary }
        val summary by summaryFlow.collectAsState(initial = null)
        val currencyStore = remember(database) {
            CurrencyRatesStore(DesktopCurrencyRatesGateway(), System::currentTimeMillis)
        }
        val currencyState by currencyStore.state.collectAsState()
        val currencyDateFormatter = remember {
            SimpleDateFormat("dd.MM, HH:mm", Locale.forLanguageTag("uk-UA"))
        }
        LaunchedEffect(currencyStore) { currencyStore.start() }
        LaunchedEffect(currencyStore) {
            while (true) {
                delay(30_000L)
                currencyStore.tick()
            }
        }
        var selectedTab by remember(database) { mutableStateOf(DesktopTab.HOME) }
        // Same commonMain navigation chrome as Android. Windows exposes only
        // the destinations implemented by its present encrypted desktop host.
        val navItems = remember {
            listOf(
                AppDestination("HOME", "Панель", "Панель", Icons.Default.Dashboard),
                AppDestination("PRICES", "Прайс", "Прайс", Icons.Default.ReceiptLong)
            )
        }
        val topScrollBehavior = key(selectedTab) {
            TopAppBarDefaults.enterAlwaysScrollBehavior()
        }
        Box(Modifier.fillMaxSize().nestedScroll(topScrollBehavior.nestedScrollConnection)) {
            when (selectedTab) {
                DesktopTab.HOME -> {
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
                            onObjects = null,
                            onEstimates = null,
                            onCalendar = null,
                            todayLabel = LocalDate.now().format(
                                DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("uk"))
                            ),
                            nowMillis = System.currentTimeMillis(),
                            currencyRates = currencyState,
                            onCurrencyRefresh = { scope.launch { currencyStore.refresh() } },
                            onMonobank = {
                                runCatching {
                                    if (Desktop.isDesktopSupported() &&
                                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                                    ) {
                                        Desktop.getDesktop().browse(URI("https://monobank.ua/"))
                                    }
                                }
                            },
                            formatCurrencyDate = { millis -> currencyDateFormatter.format(Date(millis)) }
                        )
                    }
                }
                DesktopTab.PRICES -> PriceCatalogScreen(
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
                onSelect = { selectedTab = DesktopTab.valueOf(it) },
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
