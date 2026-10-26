package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardActionTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp).size(24.dp)
                    )
                }
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CompactMetric(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun DashboardScreen(
    onNewMeasurement: () -> Unit,
    onCalculator: () -> Unit,
    onObjects: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Keep the most urgent item at the top, before tools and summary statistics.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "НАЙБЛИЖЧА ПОДІЯ",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    "Монтаж натяжної стелі",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Квартира · Київ · 22 жовтня",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                )
                OutlinedButton(
                    onClick = onObjects,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                ) {
                    Text("Перейти до об’єктів")
                }
            }
        }

        CurrencyRatesCard()

        Text(
            "ШВИДКІ ІНСТРУМЕНТИ МАЙСТРА",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardActionTile(
                    "Швидкий замір", Icons.Default.Straighten, onNewMeasurement,
                    modifier = Modifier.weight(1f), badge = "+ новий"
                )
                DashboardActionTile("Калькулятор", Icons.Default.Calculate, onCalculator, Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardActionTile("Об’єкти та клієнти", Icons.Default.Business, onObjects, Modifier.weight(1f))
                DashboardActionTile("Налаштування", Icons.Default.Settings, onSettings, Modifier.weight(1f))
            }
        }

        Text(
            "КОРОТКО ПРО РОБОТУ",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactMetric("Об’єкти", "3", Icons.Default.Business, Modifier.weight(1f))
            CompactMetric("Заміри", "5", Icons.Default.Straighten, Modifier.weight(1f))
            CompactMetric("Площа", "86,4 м²", Icons.Default.SquareFoot, Modifier.weight(1f))
        }

        Text(
            "Показані демонстраційні дані. Підключення реальної статистики буде окремим етапом.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
