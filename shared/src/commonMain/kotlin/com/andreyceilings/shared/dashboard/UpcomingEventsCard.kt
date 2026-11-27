package com.andreyceilings.shared.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calendar.WorkOccurrenceKey
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * UI-only CMP contract. Only scheduled occurrences (NOT record createdAt) belong here.
 * Hosts will provide locally formatted date/time strings and a SQLDelight-backed list
 * when the future scheduling feature is implemented. No hardcoded events or mock rows.
 */
data class UpcomingWorkEvent(
    val id: String,
    val startsAtMillis: Long,
    val dateLabel: String,
    val timeLabel: String,
    val description: String,
    val projectName: String,
    val clientName: String? = null,
    val projectId: String? = null,
    val endsAtMillis: Long? = null,
    val key: WorkOccurrenceKey? = null
)

enum class WorkUrgency { DISTANT, APPROACHING, SOON, IMMINENT }

fun workUrgency(nowMillis: Long, startsAtMillis: Long): WorkUrgency {
    val remaining = startsAtMillis - nowMillis
    return when {
        remaining in 0L..THREE_HOURS_MS -> WorkUrgency.IMMINENT
        remaining <= ONE_DAY_MS -> WorkUrgency.SOON
        remaining <= SEVEN_DAYS_MS -> WorkUrgency.APPROACHING
        else -> WorkUrgency.DISTANT
    }
}

fun upcomingWorkEvents(events: List<UpcomingWorkEvent>, nowMillis: Long): List<UpcomingWorkEvent> =
    events.asSequence()
        .filter { it.endsAtMillis?.let { end -> end > nowMillis } ?: (it.startsAtMillis >= nowMillis) }
        .sortedWith(compareBy<UpcomingWorkEvent> { it.startsAtMillis }.thenBy { it.id })
        .take(4)
        .toList()

private const val THREE_HOURS_MS = 3L * 60L * 60L * 1000L
private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
private const val SEVEN_DAYS_MS = 7L * ONE_DAY_MS

/**
 * Compact single-row empty state at the very top of the dashboard.
 * For upcoming events, the closest occurrence is detailed, the next three are rows.
 */
@Composable
fun UpcomingEventsCard(
    events: List<UpcomingWorkEvent>,
    todayLabel: String,
    nowMillis: Long,
    databaseAvailable: Boolean,
    onCalendar: (() -> Unit)?,
    onOpenProject: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onOpenWork: ((WorkOccurrenceKey) -> Unit)? = null,
    onAddWork: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    val upcoming = upcomingWorkEvents(events, nowMillis)
    val theme = MaterialTheme.colorScheme
    val dark = theme.background.luminance() < 0.4f

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surfaceContainerLow),
        border = BorderStroke(1.dp, theme.outlineVariant.copy(alpha = .6f))) {
    Column(Modifier.fillMaxWidth().padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (upcoming.isEmpty()) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = theme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(todayLabel, Modifier.weight(1f)
                        .clickable(enabled = databaseAvailable && onCalendar != null) { onCalendar?.invoke() }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium, color = theme.onPrimaryContainer)
                    if (onAddWork != null) IconButton(onClick = onAddWork) {
                        Icon(Icons.Default.Add, stringResource(Res.string.work_add))
                    }
                }
            }
        } else upcoming.forEachIndexed { index, event ->
            val closest = index == 0
            val urgency = workUrgency(nowMillis, event.startsAtMillis)
            val background = if (!closest) (if (dark) theme.surface else theme.surfaceContainerHigh) else when (urgency) {
                WorkUrgency.DISTANT -> theme.surfaceVariant
                WorkUrgency.APPROACHING -> theme.primaryContainer
                WorkUrgency.SOON -> theme.tertiaryContainer
                WorkUrgency.IMMINENT -> Color(0xFFF6A55C)
            }
            val foreground = if (!closest) theme.onSurface else when (urgency) {
                WorkUrgency.DISTANT -> theme.onSurfaceVariant
                WorkUrgency.APPROACHING -> theme.onPrimaryContainer
                WorkUrgency.SOON -> theme.onTertiaryContainer
                WorkUrgency.IMMINENT -> Color(0xFF412004)
            }
            val open = event.key?.let { key -> onOpenWork?.let { action -> { action(key) } } }
                ?: event.projectId?.let { id -> onOpenProject?.let { action -> { action(id) } } }
            Card(shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
                border = if (!closest && !dark) BorderStroke(1.dp, theme.outlineVariant) else null) {
                Row(Modifier.fillMaxWidth().clickable(enabled = open != null) { open?.invoke() }
                    .padding(start = 12.dp, end = 5.dp, top = if (closest) 7.dp else 3.dp, bottom = if (closest) 7.dp else 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(if (closest) 2.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(event.dateLabel, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(event.timeLabel, Modifier.fillMaxWidth(), style = if (closest) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(event.description, style = if (closest) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (closest) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (closest) {
                            if (event.projectName.isNotBlank()) Text(event.projectName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            event.clientName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (closest && onAddWork != null) IconButton(onClick = onAddWork) {
                        Icon(Icons.Default.Add, stringResource(Res.string.work_add), tint = foreground)
                    }
                }
            }
        }
    }
}
}
