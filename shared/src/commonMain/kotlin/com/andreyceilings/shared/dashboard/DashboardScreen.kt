package com.andreyceilings.shared.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.objects.compactQuoteAmount
import com.andreyceilings.shared.registry.RegistryRead
import com.andreyceilings.shared.registry.registryRead
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** The host owns scrolling, system insets and public rates transport. */
@Composable
fun DashboardScreen(
    store: DashboardStore?,
    preferences: DashboardPreferences,
    onCeilings: (() -> Unit)?,
    onObjects: (() -> Unit)?,
    onClients: (() -> Unit)?,
    onEstimates: (() -> Unit)?,
    onCalendar: (() -> Unit)?,
    todayLabel: String,
    nowMillis: Long,
    upcomingEvents: List<UpcomingWorkEvent> = emptyList(),
    onOpenProject: ((String) -> Unit)? = null,
    currencyRates: CurrencyRatesUiState? = null,
    onCurrencyRefresh: (() -> Unit)? = null,
    onCurrencyMessageDismiss: ((Long) -> Unit)? = null,
    onMonobank: (() -> Unit)? = null,
    formatCurrencyDate: ((Long) -> String)? = null,
    modifier: Modifier = Modifier
) {
    val summary = registryRead(store?.rows)
    val stats = (summary as? RegistryRead.Ready)?.data
    val count = registryRead(store?.quoteCount)
    val financeVisible = preferences.isVisible(DashboardBlock.FINANCE)
    val financeFlow = remember(store, financeVisible) { if (financeVisible) store?.approvedFinance() else null }
    val finance = registryRead(financeFlow)
    val countText = (count as? RegistryRead.Ready)?.data?.toString()
    val area = stats?.let { dashboardAreaLabel(it.area) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        preferences.rows().forEach { blocks ->
            when (blocks.singleOrNull()) {
                DashboardBlock.WORK -> UpcomingEventsCard(upcomingEvents, todayLabel, nowMillis,
                    store != null, onCalendar, onOpenProject)
                DashboardBlock.RATES -> if (currencyRates != null && formatCurrencyDate != null) {
                    CurrencyRatesCard(state = currencyRates, onRefresh = onCurrencyRefresh,
                        onMonobank = onMonobank, onMessageTimeout = onCurrencyMessageDismiss, formatDate = formatCurrencyDate)
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    blocks.forEach { block ->
                        val value = when (block) {
                            DashboardBlock.FINANCE -> (finance as? RegistryRead.Ready)?.data?.let { compactQuoteAmount(it) }
                                ?: stringResource(if (finance is RegistryRead.Loading) Res.string.p6a_finance_loading else Res.string.p6a_finance_unavailable)
                            DashboardBlock.AREA -> area
                            DashboardBlock.CEILINGS -> stats?.ceilingCount?.toString()
                            DashboardBlock.QUOTES -> countText
                            DashboardBlock.OBJECTS -> stats?.projectCount?.toString()
                            DashboardBlock.CLIENTS -> stats?.clientCount?.toString()
                            else -> null
                        } ?: when (if (block == DashboardBlock.QUOTES) count else summary) {
                            RegistryRead.Loading -> stringResource(Res.string.p6c_loading)
                            else -> stringResource(Res.string.p6c_unavailable)
                        }
                        val action = when (block) {
                            DashboardBlock.CEILINGS -> onCeilings
                            DashboardBlock.QUOTES -> onEstimates
                            DashboardBlock.OBJECTS -> onObjects
                            DashboardBlock.CLIENTS -> onClients
                            else -> null
                        }
                        DashboardTile(block, value, Modifier.weight(1f), action)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTile(block: DashboardBlock, value: String, modifier: Modifier, action: (() -> Unit)?) {
    val isRegistry = block.columns == 2
    val shape = RoundedCornerShape(16.dp)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val content: @Composable ColumnScope.() -> Unit = {
        Column(
            Modifier.fillMaxWidth().heightIn(min = if (isRegistry) 100.dp else 78.dp).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
        ) {
            Icon(block.icon(), null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (isRegistry) 32.dp else 20.dp))
            Text(stringResource(block.label()), style = if (isRegistry) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.labelMedium)
            Text(value, style = if (isRegistry) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (action == null) Card(modifier, shape = shape, colors = colors, content = content)
    else Card(action, modifier, shape = shape, colors = colors, content = content)
}

private fun DashboardBlock.icon(): ImageVector = when (this) {
    DashboardBlock.WORK -> Icons.Default.CalendarMonth
    DashboardBlock.FINANCE -> Icons.Default.Payments
    DashboardBlock.AREA -> Icons.Default.SquareFoot
    DashboardBlock.CEILINGS -> Icons.Default.CropSquare
    DashboardBlock.QUOTES -> Icons.Default.ReceiptLong
    DashboardBlock.OBJECTS -> Icons.Default.Business
    DashboardBlock.CLIENTS -> Icons.Default.People
    DashboardBlock.RATES -> Icons.Default.CurrencyExchange
}

internal fun DashboardBlock.label() = when (this) {
    DashboardBlock.WORK -> Res.string.p6c_upcoming
    DashboardBlock.FINANCE -> Res.string.p6c_approved_value
    DashboardBlock.AREA -> Res.string.p6c_total_area
    DashboardBlock.CEILINGS -> Res.string.p6c_ceilings
    DashboardBlock.QUOTES -> Res.string.p6c_quotes
    DashboardBlock.OBJECTS -> Res.string.p6c_objects
    DashboardBlock.CLIENTS -> Res.string.p6c_clients
    DashboardBlock.RATES -> Res.string.p6c_rates
}
