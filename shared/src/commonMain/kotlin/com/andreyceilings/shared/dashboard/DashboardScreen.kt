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
import com.andreyceilings.shared.calendar.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
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
    modifier: Modifier = Modifier,
    calendar: CalendarStore? = null,
    onCalendarEdit: ((CalendarEditorRoute) -> Unit)? = null,
) {
    val now = Instant.fromEpochMilliseconds(nowMillis)
    val optionsRead = calendarOptionsState(calendar)
    val calendarOptions = optionsRead.data()
    val zone = calendarOptions?.settings?.zone ?: TimeZone.currentSystemDefault()
    val today = now.toLocalDateTime(zone).date
    val workRead = if (calendar != null && calendarOptions == null && optionsRead is CalendarRead.Loading)
        calendar.cachedUpcoming(today, limit = 12)?.let { CalendarRead.Ready(it) } ?: CalendarRead.Loading
    else upcomingCalendarState(calendar, today, null, 12, now)
    val scheduled = workRead.data().orEmpty().filter { it.upcoming(now) }.mapNotNull { work ->
        val eventZone = work.draft.schedule.zone
        val next = work.days.firstOrNull { day ->
            day.interval?.let { it.endExclusive > now } ?: (day.date >= now.toLocalDateTime(eventZone).date)
        } ?: return@mapNotNull null
        val start = next.interval?.start ?: next.date.atStartOfDayIn(eventZone)
        val end = next.interval?.endExclusive ?: next.date.shift(1).atStartOfDayIn(eventZone)
        UpcomingWorkEvent("${work.key.rootUuid}/${work.key.originalDate}", start.toEpochMilliseconds(),
            dashboardWorkDate(next.date), if (work.draft.schedule.mode == WorkTimeMode.TIMED)
                start.toLocalDateTime(eventZone).time.shortTime() else stringResource(work.draft.schedule.mode.label()),
            work.draft.title, work.projectLabel ?: work.contact.address.orEmpty(), work.contact.name,
            endsAtMillis = end.toEpochMilliseconds(), key = work.key)
    }
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
                DashboardBlock.WORK -> UpcomingEventsCard(if (calendar == null) upcomingEvents else scheduled,
                    if (calendar == null) todayLabel else dashboardWorkDate(today), nowMillis,
                    store != null && (calendar == null || workRead !is CalendarRead.Unavailable), onCalendar, onOpenProject,
                    onOpenWork = onCalendarEdit?.let { open -> { open(CalendarEditorRoute.Open(it)) } },
                    onAddWork = onCalendarEdit?.takeIf { calendar != null }?.let { open -> { open(CalendarEditorRoute.Create()) } },
                    loading = workRead is CalendarRead.Loading)
                DashboardBlock.RATES -> if (currencyRates != null && formatCurrencyDate != null) {
                    CurrencyRatesCard(state = currencyRates, onRefresh = onCurrencyRefresh,
                        onMonobank = onMonobank, onMessageTimeout = onCurrencyMessageDismiss, formatDate = formatCurrencyDate)
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    blocks.forEach { block ->
                        val value = when (block) {
                            DashboardBlock.FINANCE -> (finance as? RegistryRead.Ready)?.data?.let { compactQuoteAmount(it) }
                                ?: if (store == null || finance is RegistryRead.Loading) "" else stringResource(Res.string.p6a_finance_unavailable)
                            DashboardBlock.AREA -> area
                            DashboardBlock.CEILINGS -> stats?.ceilingCount?.toString()
                            DashboardBlock.QUOTES -> countText
                            DashboardBlock.OBJECTS -> stats?.projectCount?.toString()
                            DashboardBlock.CLIENTS -> stats?.clientCount?.toString()
                            else -> null
                        } ?: when (if (block == DashboardBlock.QUOTES) count else summary) {
                            RegistryRead.Loading -> ""
                            else -> if (store == null) "" else stringResource(Res.string.p6c_unavailable)
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
            Text(stringResource(block.label()), style = if (isRegistry) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(value, minLines = 1, style = if (isRegistry) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
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

@Composable
private fun dashboardWorkDate(date: kotlinx.datetime.LocalDate): String =
    stringResource(date.dayOfWeek.label()) + " " + date.day.toString().padStart(2, '0') + "." + date.monthNumber.toString().padStart(2, '0')
