package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.EstimateEntity
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class CalendarRecord(val date: LocalDate, val title: String, val subtitle: String)

@Composable
internal fun CalendarScreen(database: CeilingsDatabase?) {
    val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf(emptyList<ProjectEntity>()) }
    val estimatesFlow = remember(database) { database?.estimates()?.observeAll() ?: flowOf(emptyList<EstimateEntity>()) }
    val projects by projectsFlow.collectAsState(initial = emptyList())
    val estimates by estimatesFlow.collectAsState(initial = emptyList())
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val today = LocalDate.now()
    val monthTitle = remember(displayedMonth) {
        displayedMonth.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale.forLanguageTag("uk"))
            .replaceFirstChar { it.titlecase(Locale.forLanguageTag("uk")) } + " " + displayedMonth.year
    }
    val records = remember(projects, estimates) {
        buildList {
            projects.forEach { project ->
                add(CalendarRecord(
                    Instant.ofEpochMilli(project.createdAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                    project.title,
                    "Створено об’єкт · " + project.address.ifBlank { "Адресу не вказано" }
                ))
            }
            estimates.forEach { estimate ->
                val project = projects.firstOrNull { it.id == estimate.projectId }
                add(CalendarRecord(
                    Instant.ofEpochMilli(estimate.createdAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                    estimate.name,
                    "Збережено кошторис · " + (project?.title ?: "Об’єкт #" + estimate.projectId)
                ))
            }
        }.sortedWith(compareBy<CalendarRecord> { it.date }.thenBy { it.title.lowercase(Locale.forLanguageTag("uk")) })
    }
    val recordsByDate = remember(records) { records.groupBy { it.date } }
    val firstDayOffset = displayedMonth.atDay(1).dayOfWeek.value - 1
    val dayCells: List<LocalDate?> = List(firstDayOffset) { null } + (1..displayedMonth.lengthOfMonth()).map { displayedMonth.atDay(it) }
    val selectedRecords = recordsByDate[selectedDate].orEmpty()
    val upcomingRecords = emptyList<CalendarRecord>() // Scheduled work dates are not stored in the current data model yet.

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp + 16.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp + 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Календар", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { displayedMonth = displayedMonth.minusMonths(1) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Попередній місяць")
                    }
                    Text(monthTitle, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { displayedMonth = displayedMonth.plusMonths(1) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Наступний місяць")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд").forEach {
                        Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                dayCells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            Box(Modifier.weight(1f).height(40.dp).padding(1.dp), contentAlignment = Alignment.Center) {
                                if (date != null) {
                                    val isSelected = date == selectedDate
                                    val isToday = date == today
                                    val hasRecords = recordsByDate[date].orEmpty().isNotEmpty()
                                    Box(
                                        Modifier.size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    isSelected -> MaterialTheme.colorScheme.primary
                                                    isToday -> MaterialTheme.colorScheme.secondaryContainer
                                                    else -> MaterialTheme.colorScheme.surface
                                                }
                                            )
                                            .clickable { selectedDate = date; displayedMonth = YearMonth.from(date) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Text(date.dayOfMonth.toString(), color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                            if (hasRecords) Box(Modifier.size(4.dp).background(if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary, CircleShape))
                                            else Spacer(Modifier.size(4.dp))
                                        }
                                    }
                                }
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text("Є записи в застосунку", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(selectedDate.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("uk"))), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (selectedRecords.isEmpty()) Text("На цю дату записів немає.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else selectedRecords.forEach { CalendarRecordCard(it) }

        Text("Наступні роботи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (upcomingRecords.isEmpty()) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    "Поки немає майбутніх робіт із датою. Зараз календар показує дати створення об’єктів і кошторисів. Планування робіт за датою потрібно додати до даних заміру/об’єкта.",
                    Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else upcomingRecords.take(30).forEach { CalendarRecordCard(it) }
    }
}

@Composable
private fun CalendarRecordCard(record: CalendarRecord) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(record.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(record.date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.forLanguageTag("uk")), style = MaterialTheme.typography.labelSmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(record.title, fontWeight = FontWeight.SemiBold)
                Text(record.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
