package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.app.ui.theme.AppExposedDropdownMenu
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import android.content.Context
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument

import com.andreyceilings.app.*

@Composable
internal fun ObjectsScreen(database: CeilingsDatabase?) {
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
 LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
internal fun MeasurementsScreen(database: CeilingsDatabase?, onOpenEstimates: () -> Unit) {
 val measurementsFlow = remember(database) { database?.measurements()?.observeAll() ?: flowOf<List<RoomMeasurementEntity>>(emptyList()) }
 val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
 val measurements by measurementsFlow.collectAsState(initial = emptyList())
 val projects by projectsFlow.collectAsState(initial = emptyList())
 val projectsById = remember(projects) { projects.associateBy { it.id } }
 val scope = rememberCoroutineScope()
 var showDialog by remember { mutableStateOf(false) }
 var error by remember { mutableStateOf<String?>(null) }
 LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  item {
   Text("Заміри", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
   Text("Заміри зберігаються в зашифрованій локальній БД", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
   OutlinedButton(onClick = onOpenEstimates, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Кошториси") }
  }
  if (database == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Захищена база ще відкривається або недоступна.") }
  else {
   item { Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = projects.isNotEmpty()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Створити замір") }; if (projects.isEmpty()) EmptyDataCard("Спочатку створіть об’єкт у вкладці «Об’єкти»."); if (measurements.isEmpty() && projects.isNotEmpty()) EmptyDataCard("Заміри ще не додані.") }
   items(measurements, key = { it.id }) { measurement ->
    val project = projectsById[measurement.projectId]
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
     Icon(Icons.Default.Straighten, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
     Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(measurement.name, fontWeight = FontWeight.SemiBold); Text(project?.title ?: "Об’єкт #${measurement.projectId}", style = MaterialTheme.typography.bodySmall); Text("${measurement.lengthMeters.format2()} × ${measurement.widthMeters.format2()} м · ${measurement.areaSquareMeters.format2()} м² · периметр ${measurement.perimeterMeters.format2()} м", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
     IconButton(onClick = { scope.launch { runCatching { database.measurements().deleteById(measurement.id) }.onFailure { error = it.message ?: "Не вдалося видалити замір" } } }) { Icon(Icons.Default.Delete, contentDescription = "Видалити замір") }
    } }
   }
  }
  item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
 }
 if (showDialog && database != null && projects.isNotEmpty()) AddMeasurementDialog(projects = projects, onDismiss = { showDialog = false }, onSave = { projectId, name, length, width ->
  scope.launch { val area = length * width; val perimeter = 2.0 * (length + width); runCatching { database.measurements().insert(RoomMeasurementEntity(projectId = projectId, name = name.trim(), lengthMeters = length, widthMeters = width, areaSquareMeters = area, perimeterMeters = perimeter)) }.onFailure { error = it.message ?: "Не вдалося зберегти замір" }; showDialog = false }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMeasurementDialog(projects: List<ProjectEntity>, onDismiss: () -> Unit, onSave: (Long, String, Double, Double) -> Unit) {
    var selectedProjectId by remember(projects) { mutableLongStateOf(projects.first().id) }
    var name by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("5.0") }
    var width by remember { mutableStateOf("4.0") }
    var expanded by remember { mutableStateOf(false) }
    val parsedLength = length.replace(',', '.').toDoubleOrNull()
    val parsedWidth = width.replace(',', '.').toDoubleOrNull()
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("Новий замір") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value = projects.firstOrNull { it.id == selectedProjectId }?.title ?: "Оберіть об’єкт", onValueChange = {}, readOnly = true, label = { Text("Об’єкт") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth())
                AppExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    projects.forEach { project -> DropdownMenuItem(text = { Text(project.title) }, onClick = { selectedProjectId = project.id; expanded = false }) }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Назва кімнати *") }, singleLine = true)
            OutlinedTextField(length, { length = it }, label = { Text("Довжина, м") }, singleLine = true)
            OutlinedTextField(width, { width = it }, label = { Text("Ширина, м") }, singleLine = true)
            if (parsedLength != null && parsedWidth != null && parsedLength > 0 && parsedWidth > 0) Text("Площа: ${(parsedLength * parsedWidth).format2()} м² · периметр: ${(2 * (parsedLength + parsedWidth)).format2()} м")
        }
    }, confirmButton = { Button(onClick = { if (name.isNotBlank() && parsedLength != null && parsedWidth != null && parsedLength > 0 && parsedWidth > 0) onSave(selectedProjectId, name, parsedLength, parsedWidth) }, enabled = name.isNotBlank() && parsedLength != null && parsedWidth != null && parsedLength > 0 && parsedWidth > 0) { Text("Зберегти") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } })
}

