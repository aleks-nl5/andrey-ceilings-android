package com.andreyceilings.shared.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Common CMP home content. Scrolling/insets are owned by each platform host. */
@Composable
fun DashboardScreen(
    summary: DashboardSummary?,
    databaseAvailable: Boolean,
    onNewMeasurement: (() -> Unit)?,
    onCalculator: (() -> Unit)?,
    onObjects: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    onCalendar: (() -> Unit)?,
    todayLabel: String,
    nowMillis: Long,
    upcomingEvents: List<UpcomingWorkEvent> = emptyList(),
    onOpenProject: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UpcomingEventsCard(
            events = upcomingEvents,
            todayLabel = todayLabel,
            nowMillis = nowMillis,
            databaseAvailable = databaseAvailable,
            onCalendar = onCalendar,
            onOpenProject = onOpenProject
        )

        Text(
            "ШВИДКІ ІНСТРУМЕНТИ МАЙСТРА",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 900.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashboardActionTile("Швидкий замір", Icons.Default.Straighten, onNewMeasurement, Modifier.weight(1f), "+ новий")
                    DashboardActionTile("Калькулятор", Icons.Default.Calculate, onCalculator, Modifier.weight(1f))
                    DashboardActionTile("Об’єкти та клієнти", Icons.Default.Business, onObjects, Modifier.weight(1f))
                    DashboardActionTile("Налаштування", Icons.Default.Settings, onSettings, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DashboardActionTile("Швидкий замір", Icons.Default.Straighten, onNewMeasurement, Modifier.weight(1f), "+ новий")
                        DashboardActionTile("Калькулятор", Icons.Default.Calculate, onCalculator, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DashboardActionTile("Об’єкти та клієнти", Icons.Default.Business, onObjects, Modifier.weight(1f))
                        DashboardActionTile("Налаштування", Icons.Default.Settings, onSettings, Modifier.weight(1f))
                    }
                }
            }
        }

        Text(
            "КОРОТКО ПРО РОБОТУ", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactMetric("Об’єкти", summary?.projectCount?.toString() ?: "—", Icons.Default.Business, Modifier.weight(1f))
            CompactMetric("Заміри", summary?.measurementCount?.toString() ?: "—", Icons.Default.Straighten, Modifier.weight(1f))
            CompactMetric("Площа", summary?.areaLabel() ?: "—", Icons.Default.SquareFoot, Modifier.weight(1f))
        }
        if (!databaseAvailable || summary == null) {
            Text(
                if (databaseAvailable) "Завантаження статистики…" else "Захищена база даних недоступна. Статистику не завантажено.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "За активними об’єктами та їхніми замірами в локальній базі.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DashboardActionTile(
    title: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier.heightIn(min = 132.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp).size(24.dp))
                }
                val label = if (onClick == null) "Пізніше" else badge
                if (label != null) Text(
                    label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
