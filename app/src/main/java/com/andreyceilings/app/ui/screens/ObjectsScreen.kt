package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.ui.theme.AppAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.*
import com.andreyceilings.app.core.EstimateLineItems
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale
import android.content.Context
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument

import com.andreyceilings.app.*

@Composable
internal fun ObjectsScreen(
 database: CeilingsDatabase?,
 openCreateProject: Boolean = false,
 onCreateProjectConsumed: () -> Unit = {}
) {
 val clientsFlow = remember(database) { database?.clients()?.observeAll() ?: flowOf<List<ClientEntity>>(emptyList()) }
 val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
 val clients by clientsFlow.collectAsState(initial = emptyList())
 val projects by projectsFlow.collectAsState(initial = emptyList())
 val clientsById = remember(clients) { clients.associateBy { it.id } }
 val projectsById = remember(projects) { projects.associateBy { it.id } }
 val projectStatuses = remember { listOf("measurement", "calculation", "approved", "in_progress", "completed", "paid", "cancelled") }
 val projectStatusLabels = remember {
  mapOf("measurement" to "Замір", "calculation" to "Розрахунок", "approved" to "Погоджено", "in_progress" to "В монтажі", "completed" to "Завершено", "paid" to "Оплачено", "cancelled" to "Скасовано")
 }
 val scope = rememberCoroutineScope()
 var showClientDialog by remember { mutableStateOf(false) }
 var showProjectDialog by remember { mutableStateOf(false) }
 var dbError by remember { mutableStateOf<String?>(null) }
 // Navigation quick action reuses the same real create dialog as the screen button.
 LaunchedEffect(openCreateProject, database) {
  if (openCreateProject && database != null) {
   showProjectDialog = true
   onCreateProjectConsumed()
  }
 }
 LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(
  top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp + 16.dp,
  bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp + 16.dp
 )) {
  item {
   Text("Об’єкти та клієнти", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
   Text("Дані зберігаються локально в зашифрованій базі", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (database == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Захищена база ще відкривається або недоступна.") }
  else {
   item {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
     OutlinedButton(onClick = { showClientDialog = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Клієнт") }
     Button(onClick = { showProjectDialog = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Об’єкт") }
    }
    Text("Об’єкти (${projects.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (projects.isEmpty()) EmptyDataCard("Поки немає об’єктів. Додайте перший об’єкт.")
   }
   items(projects, key = { "project-${it.id}" }) { project ->
    val clientName = project.clientId?.let { clientsById[it]?.name } ?: "Клієнт не вказаний"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
     Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Default.Business, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
      Column(Modifier.weight(1f)) { Text(project.title, fontWeight = FontWeight.SemiBold); if (project.address.isNotBlank()) Text(project.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      IconButton(onClick = { scope.launch { runCatching { database.projects().deleteById(project.id) }.onFailure { dbError = it.message ?: "Не вдалося видалити об’єкт" } } }) { Icon(Icons.Default.Delete, contentDescription = "Видалити об’єкт") }
     }
     Text(clientName, style = MaterialTheme.typography.bodyMedium)
     AssistChip(onClick = { val next = projectStatuses[(projectStatuses.indexOf(project.status).coerceAtLeast(0) + 1) % projectStatuses.size]; scope.launch { runCatching { database.projects().updateStatus(project.id, next) }.onFailure { dbError = it.message ?: "Не вдалося змінити статус" } } }, label = { Text((projectStatusLabels[project.status] ?: project.status) + " · змінити") })
    } }
   }
   item { HorizontalDivider(); Text("Клієнти (${clients.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); if (clients.isEmpty()) EmptyDataCard("Список клієнтів порожній.") }
   items(clients, key = { "client-${it.id}" }) { client ->
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
     Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(client.name, fontWeight = FontWeight.SemiBold); if (client.phone.isNotBlank()) Text(client.phone, style = MaterialTheme.typography.bodyMedium); if (client.email.isNotBlank()) Text(client.email, style = MaterialTheme.typography.bodySmall) }
     IconButton(onClick = { scope.launch { runCatching { database.clients().deleteById(client.id) }.onFailure { dbError = it.message ?: "Не вдалося видалити клієнта" } } }) { Icon(Icons.Default.Delete, contentDescription = "Видалити клієнта") }
    } }
   }
  }
  item { dbError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
 }
 if (showClientDialog && database != null) AddClientDialog(onDismiss = { showClientDialog = false }, onSave = { name, phone, email ->
  scope.launch { runCatching { database.clients().insert(ClientEntity(name = name.trim(), phone = phone.trim(), email = email.trim())) }.onFailure { dbError = it.message ?: "Не вдалося зберегти клієнта" }; showClientDialog = false }
 })
 if (showProjectDialog && database != null) AddProjectDialog(onDismiss = { showProjectDialog = false }, onSave = { title, address, clientName ->
  scope.launch { runCatching { val cleanedClient = clientName.trim(); val clientId = if (cleanedClient.isBlank()) null else database.clients().findByName(cleanedClient)?.id ?: database.clients().insert(ClientEntity(name = cleanedClient)); database.projects().insert(ProjectEntity(clientId = clientId, title = title.trim(), address = address.trim())) }.onFailure { dbError = it.message ?: "Не вдалося зберегти об’єкт" }; showProjectDialog = false }
 })
}

@Composable
internal fun EmptyDataCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddClientDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("Новий клієнт") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Ім’я клієнта *") }, singleLine = true)
            OutlinedTextField(phone, { phone = it }, label = { Text("Телефон") }, singleLine = true)
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
        }
    }, confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, phone, email) }, enabled = name.isNotBlank()) { Text("Зберегти") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } })
}

@Composable
private fun AddProjectDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var clientName by remember { mutableStateOf("") }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("Новий об’єкт") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Назва об’єкта *") }, singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text("Адреса") })
            OutlinedTextField(clientName, { clientName = it }, label = { Text("Ім’я клієнта") }, supportingText = { Text("Якщо клієнта ще немає, його буде створено автоматично") })
        }
    }, confirmButton = { Button(onClick = { if (title.isNotBlank()) onSave(title, address, clientName) }, enabled = title.isNotBlank()) { Text("Зберегти") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } })
}
