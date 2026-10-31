package com.andreyceilings.app.ui.screens

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
    onSettings: () -> Unit,
    onCalendar: () -> Unit
) {
    val store = remember(database) { database?.let { DashboardStore(it.database) } }
    val summaryFlow = remember(store) { store?.summary ?: flowOf<DashboardSummary?>(null) }
    val summary by summaryFlow.collectAsState(initial = null)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SharedDashboardScreen(
            summary = summary,
            databaseAvailable = database != null,
            onNewMeasurement = onNewMeasurement,
            onCalculator = onCalculator,
            onObjects = onObjects,
            onSettings = onSettings,
            onCalendar = onCalendar
        )
        // Monobank's Android HTTP/SharedPreferences/Intent integration is retained intact.
        // Shared UI + KMP cache/network adaptation is independent follow-up work.
        CurrencyRatesCard()
    }
}
