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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    val projectId: String? = null
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
        .filter { it.startsAtMillis >= nowMillis }
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
    modifier: Modifier = Modifier
) {
    val upcoming = upcomingWorkEvents(events, nowMillis)
    val theme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (upcoming.isEmpty()) theme.primaryContainer else theme.surface
        )
    ) {
        if (upcoming.isEmpty()) {
            // 58 -> 44dp (~24% shorter) while Compose's clickable touch
            // expansion still allows a >=48dp hit area for the calendar link.
            Row(
                Modifier.fillMaxWidth().height(44.dp)
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = if (!databaseAvailable)
                        "База даних недоступна: заплановані події не завантажені"
                    else "Запланованих майбутніх подій немає",
                    tint = theme.primary
                )
                Text(
                    if (databaseAvailable) todayLabel else "Події недоступні",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clickable(enabled = onCalendar != null) { onCalendar?.invoke() }
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val linkColor = if (onCalendar != null) theme.primary else theme.onSurfaceVariant
                    Text(
                        "Календар",
                        style = MaterialTheme.typography.labelLarge,
                        color = linkColor,
                        maxLines = 1
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = linkColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            val closest = upcoming.first()
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EventDateBadge(
                        dateLabel = closest.dateLabel,
                        timeLabel = closest.timeLabel,
                        urgency = workUrgency(nowMillis, closest.startsAtMillis)
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            closest.description,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            closest.projectName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        closest.clientName?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = theme.onSurfaceVariant, maxLines = 1)
                        }
                        if (closest.projectId != null && onOpenProject != null) {
                            TextButton(
                                onClick = { onOpenProject(closest.projectId) },
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("До об’єкта") }
                        }
                    }
                }

                upcoming.drop(1).forEach { event ->
                    val openProject = event.projectId?.let { id ->
                        onOpenProject?.let { open -> { open(id) } }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = openProject != null) { openProject?.invoke() }
                            .padding(vertical = 7.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            "${event.dateLabel} · ${event.timeLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = theme.onSurfaceVariant,
                            modifier = Modifier.width(106.dp),
                            maxLines = 2
                        )
                        Text(
                            event.description,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (openProject != null) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "До об’єкта")
                        }
                    }
                }
                if (onCalendar != null) {
                    TextButton(
                        onClick = onCalendar,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Усі події в календарі")
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDateBadge(dateLabel: String, timeLabel: String, urgency: WorkUrgency) {
    val theme = MaterialTheme.colorScheme
    val background = when (urgency) {
        WorkUrgency.DISTANT -> theme.surfaceVariant.copy(alpha = 0.55f)
        WorkUrgency.APPROACHING -> theme.primaryContainer.copy(alpha = 0.75f)
        WorkUrgency.SOON -> theme.tertiaryContainer
        WorkUrgency.IMMINENT -> Color(0xFFF6A55C)
    }
    val foreground = when (urgency) {
        WorkUrgency.IMMINENT -> Color(0xFF412004)
        WorkUrgency.DISTANT -> theme.onSurfaceVariant
        WorkUrgency.APPROACHING -> theme.onPrimaryContainer
        WorkUrgency.SOON -> theme.onTertiaryContainer
    }
    // Pulse only the small badge for events starting within the next three hours.
    // Slow, subtle alpha motion; no flashing entire cards or text.
    val pulseAlpha = if (urgency == WorkUrgency.IMMINENT) {
        val transition = rememberInfiniteTransition(label = "Наближення події")
        val alpha by transition.animateFloat(
            initialValue = 0.78f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "М’яке підсвічування часу"
        )
        alpha
    } else 1f

    Column(
        modifier = Modifier.width(98.dp)
            .background(background.copy(alpha = pulseAlpha), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            dateLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 2
        )
        Text(
            timeLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = foreground,
            maxLines = 1
        )
    }
}
