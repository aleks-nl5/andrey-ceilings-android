package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.*

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.backup.BackupPasswordStore
import com.andreyceilings.app.data.backup.AutomaticBackupScheduler
import com.andreyceilings.app.data.backup.AutomaticBackupSettings
import com.andreyceilings.app.data.backup.BackupSchedule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.ui.theme.CeilingsTheme
import com.andreyceilings.app.core.CeilingEstimate
import com.andreyceilings.app.core.CeilingCalculator
import com.andreyceilings.app.core.CalculatorOptions
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.EstimateEntity
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(shape = RoundedCornerShape(18.dp), modifier = modifier.widthIn(min = 130.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun DashboardScreen(onNewMeasurement: () -> Unit, onCalculator: () -> Unit) {
    SectionTitle("Панель майстра", "Ваші об’єкти та розрахунки в одному місці")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Об’єкти", "3", Icons.Default.Business, Modifier.weight(1f))
        MetricCard("Заміри", "5", Icons.Default.Straighten, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Площа", "86,4 м²", Icons.Default.SquareFoot, Modifier.weight(1f))
        MetricCard("До монтажу", "2", Icons.Default.Event, Modifier.weight(1f))
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Швидкі дії", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Button(onClick = onNewMeasurement, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Новий замір")
            }
            OutlinedButton(onClick = onCalculator, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Calculate, null); Spacer(Modifier.width(8.dp)); Text("Розрахувати вартість")
            }
        }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Найближча подія", fontWeight = FontWeight.Bold)
            Text("Монтаж натяжної стелі")
            Text("Квартира · Київ · 22 жовтня", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text("Демо-дані показані лише для макета. Зберігання та повна міграція даних — наступний етап.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}