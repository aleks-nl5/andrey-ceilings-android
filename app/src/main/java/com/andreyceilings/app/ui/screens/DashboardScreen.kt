package com.andreyceilings.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.andreyceilings.shared.dashboard.CurrencyRatesStore
import com.andreyceilings.shared.dashboard.MonobankCurrencyGateway
import com.andreyceilings.shared.dashboard.dashboardClockMillis
import com.andreyceilings.shared.dashboard.dashboardTodayLabel
import com.andreyceilings.shared.dashboard.currencyLocalDateTimeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.shared.dashboard.DashboardScreen as SharedDashboardScreen
import com.andreyceilings.shared.dashboard.DashboardStore
import com.andreyceilings.shared.dashboard.DashboardSummary
import kotlinx.coroutines.flow.flowOf

/** Android-only compatibility helper still used by Calculator/Settings. */
@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Thin Android host: the actual dashboard is shared CMP UI and SQLDelight state. */
@Composable
internal fun DashboardScreen(
    database: CeilingsDatabase?,
    onNewMeasurement: () -> Unit,
    onCalculator: () -> Unit,
    onObjects: () -> Unit,
    onEstimates: () -> Unit,
    onCalendar: () -> Unit
) {
    val store = remember(database) { database?.let { DashboardStore(it.database) } }
    val summaryFlow = remember(store) { store?.summary ?: flowOf<DashboardSummary?>(null) }
    val summary by summaryFlow.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gateway = remember(context) { MonobankCurrencyGateway(AndroidCurrencyRatesCache(context)) }
    DisposableEffect(gateway) { onDispose { gateway.close() } }
    val currencyStore = remember(gateway) {
        CurrencyRatesStore(gateway, ::dashboardClockMillis)
    }
    val currencyState by currencyStore.state.collectAsState()
    LaunchedEffect(currencyStore) { currencyStore.start() }
    LaunchedEffect(currencyStore) {
        while (true) {
            delay(30_000L)
            currencyStore.tick()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SharedDashboardScreen(
            summary = summary,
            databaseAvailable = database != null,
            onNewMeasurement = onNewMeasurement,
            onCalculator = onCalculator,
            onObjects = onObjects,
            onEstimates = onEstimates,
            onCalendar = onCalendar,
            todayLabel = dashboardTodayLabel(currencyState.nowMillis),
            nowMillis = currencyState.nowMillis,
            currencyRates = currencyState,
            onCurrencyRefresh = { scope.launch { currencyStore.refresh() } },
            onCurrencyMessageDismiss = currencyStore::dismissMessage,
            onMonobank = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://monobank.ua/"))
                    )
                }
            },
            formatCurrencyDate = ::currencyLocalDateTimeLabel
            // Scheduling fields do not exist in SQLDelight yet. The empty
            // state is real; creation dates must never become fake appointments.
        )
    }
}
