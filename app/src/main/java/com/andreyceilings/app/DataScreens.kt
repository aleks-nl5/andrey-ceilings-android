package com.andreyceilings.app

import androidx.compose.foundation.layout.*
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

@Composable
internal fun ObjectsScreen(database: CeilingsDatabase?) {
    val clientsFlow = remember(database) { database?.clients()?.observeAll() ?: flowOf<List<ClientEntity>>(emptyList()) }
    val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
    val clients by clientsFlow.collectAsState(initial = emptyList())
    val projects by projectsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showClientDialog by remember { mutableStateOf(false) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var dbError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Об’єкти та клієнти", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Дані зберігаються локально в зашифрованій базі", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (database == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Захищена база ще відкривається або недоступна.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showClientDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Клієнт")
                }
                Button(onClick = { showProjectDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Об’єкт")
                }
            }
            Text("Об’єкти (${projects.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (projects.isEmpty()) EmptyDataCard("Поки немає об’єктів. Додайте перший об’єкт.")
            projects.forEach { project ->
                val clientName = clients.firstOrNull { it.id == project.clientId }?.name ?: "Клієнт не вказаний"
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Business, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.title, fontWeight = FontWeight.SemiBold)
                                if (project.address.isNotBlank()) Text(project.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scope.launch { runCatching { database.projects().deleteById(project.id) }.onFailure { dbError = it.message ?: "Не вдалося видалити об’єкт" } } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Видалити об’єкт")
                            }
                        }
                        Text(clientName, style = MaterialTheme.typography.bodyMedium)
                        val statuses = listOf("measurement", "calculation", "approved", "in_progress", "completed", "paid", "cancelled")
                        val labels = mapOf("measurement" to "Замір", "calculation" to "Розрахунок", "approved" to "Погоджено", "in_progress" to "В монтажі", "completed" to "Завершено", "paid" to "Оплачено", "cancelled" to "Скасовано")
                        AssistChip(onClick = {
                            val next = statuses[(statuses.indexOf(project.status).coerceAtLeast(0) + 1) % statuses.size]
                            scope.launch { runCatching { database.projects().updateStatus(project.id, next) }.onFailure { dbError = it.message ?: "Не вдалося змінити статус" } }
                        }, label = { Text((labels[project.status] ?: project.status) + " · змінити") })
                    }
                }
            }
            HorizontalDivider()
            Text("Клієнти (${clients.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (clients.isEmpty()) EmptyDataCard("Список клієнтів порожній.")
            clients.forEach { client ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(client.name, fontWeight = FontWeight.SemiBold)
                            if (client.phone.isNotBlank()) Text(client.phone, style = MaterialTheme.typography.bodyMedium)
                            if (client.email.isNotBlank()) Text(client.email, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { scope.launch { runCatching { database.clients().deleteById(client.id) }.onFailure { dbError = it.message ?: "Не вдалося видалити клієнта" } } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Видалити клієнта")
                        }
                    }
                }
            }
        }
        dbError?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
    }

    if (showClientDialog && database != null) AddClientDialog(
        onDismiss = { showClientDialog = false },
        onSave = { name, phone, email ->
            scope.launch {
                runCatching { database.clients().insert(ClientEntity(name = name.trim(), phone = phone.trim(), email = email.trim())) }
                    .onFailure { dbError = it.message ?: "Не вдалося зберегти клієнта" }
                showClientDialog = false
            }
        }
    )
    if (showProjectDialog && database != null) AddProjectDialog(
        onDismiss = { showProjectDialog = false },
        onSave = { title, address, clientName ->
            scope.launch {
                runCatching {
                    val cleanedClient = clientName.trim()
                    val clientId = if (cleanedClient.isBlank()) null else {
                        database.clients().findByName(cleanedClient)?.id
                            ?: database.clients().insert(ClientEntity(name = cleanedClient))
                    }
                    database.projects().insert(ProjectEntity(clientId = clientId, title = title.trim(), address = address.trim()))
                }.onFailure { dbError = it.message ?: "Не вдалося зберегти об’єкт" }
                showProjectDialog = false
            }
        }
    )
}

@Composable
internal fun MeasurementsScreen(database: CeilingsDatabase?) {
    val measurementsFlow = remember(database) { database?.measurements()?.observeAll() ?: flowOf<List<RoomMeasurementEntity>>(emptyList()) }
    val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
    val measurements by measurementsFlow.collectAsState(initial = emptyList())
    val projects by projectsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Заміри", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Заміри зберігаються в зашифрованій локальній БД", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (database == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Захищена база ще відкривається або недоступна.")
        } else {
            Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = projects.isNotEmpty()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Створити замір")
            }
            if (projects.isEmpty()) EmptyDataCard("Спочатку створіть об’єкт у вкладці «Об’єкти».")
            if (measurements.isEmpty() && projects.isNotEmpty()) EmptyDataCard("Заміри ще не додані.")
            measurements.forEach { measurement ->
                val project = projects.firstOrNull { it.id == measurement.projectId }
                Card {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Straighten, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(measurement.name, fontWeight = FontWeight.SemiBold)
                            Text(project?.title ?: "Об’єкт #${measurement.projectId}", style = MaterialTheme.typography.bodySmall)
                            Text("${measurement.lengthMeters.format2()} × ${measurement.widthMeters.format2()} м · ${measurement.areaSquareMeters.format2()} м² · периметр ${measurement.perimeterMeters.format2()} м", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { scope.launch { runCatching { database.measurements().deleteById(measurement.id) }.onFailure { error = it.message ?: "Не вдалося видалити замір" } } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Видалити замір")
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (showDialog && database != null && projects.isNotEmpty()) AddMeasurementDialog(
        projects = projects,
        onDismiss = { showDialog = false },
        onSave = { projectId, name, length, width ->
            scope.launch {
                val area = length * width
                val perimeter = 2.0 * (length + width)
                runCatching {
                    database.measurements().insert(RoomMeasurementEntity(projectId = projectId, name = name.trim(), lengthMeters = length, widthMeters = width, areaSquareMeters = area, perimeterMeters = perimeter))
                }.onFailure { error = it.message ?: "Не вдалося зберегти замір" }
                showDialog = false
            }
        }
    )
}

@Composable
private fun EmptyDataCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddClientDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новий клієнт") }, text = {
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новий об’єкт") }, text = {
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новий замір") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value = projects.firstOrNull { it.id == selectedProjectId }?.title ?: "Оберіть об’єкт", onValueChange = {}, readOnly = true, label = { Text("Об’єкт") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveEstimateDialog(
    projects: List<ProjectEntity>,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (Long, String) -> Unit
) {
    var selectedProjectId by remember(projects) { mutableLongStateOf(projects.first().id) }
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Зберегти кошторис") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = projects.firstOrNull { it.id == selectedProjectId }?.title ?: "Оберіть об’єкт",
                        onValueChange = {}, readOnly = true, label = { Text("Об’єкт") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        projects.forEach { project -> DropdownMenuItem(text = { Text(project.title) }, onClick = { selectedProjectId = project.id; expanded = false }) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Назва кімнати / кошторису") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(selectedProjectId, name) }, enabled = name.isNotBlank()) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EstimatesScreen(database: CeilingsDatabase?) {
    val estimatesFlow = remember(database) { database?.estimates()?.observeAll() ?: flowOf<List<EstimateEntity>>(emptyList()) }
    val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
    val estimates by estimatesFlow.collectAsState(initial = emptyList())
    val projects by projectsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var editingEstimate by remember { mutableStateOf<EstimateEntity?>(null) }
    var pendingPdfEstimate by remember { mutableStateOf<EstimateEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    val projectTotals = estimates.groupBy { it.projectId }.mapValues { (_, rows) -> rows.sumOf { it.totalCost } }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val selected = pendingPdfEstimate
        pendingPdfEstimate = null
        if (uri != null && selected != null) {
            val projectTitle = projects.firstOrNull { it.id == selected.projectId }?.title ?: "Об’єкт #${selected.projectId}"
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { writeEstimatePdf(context, uri, selected, projectTitle) } }
                    .onSuccess { status = "PDF-кошторис збережено." }
                    .onFailure { error = "Не вдалося створити PDF: ${it.message ?: "помилка запису"}" }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Кошториси", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Збережені розрахунки та суми по кожному об’єкту", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (database == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Захищена база ще відкривається або недоступна.")
        } else if (estimates.isEmpty()) {
            EmptyDataCard("Кошторисів поки немає. Відкрийте калькулятор, зробіть розрахунок і натисніть «Зберегти кошторис до об’єкта».")
        } else {
            projects.forEach { project ->
                val projectEstimates = estimates.filter { it.projectId == project.id }
                if (projectEstimates.isNotEmpty()) {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Разом за збереженими кошторисами: ${money(projectTotals[project.id] ?: 0.0)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            estimates.forEach { estimate ->
                val project = projects.firstOrNull { it.id == estimate.projectId }
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(estimate.name, fontWeight = FontWeight.SemiBold)
                                Text(project?.title ?: "Об’єкт #${estimate.projectId}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { renameText = estimate.name; editingEstimate = estimate }) {
                                Icon(Icons.Default.Edit, contentDescription = "Редагувати назву кошторису")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { database.estimates().insert(estimate.copy(id = 0, name = "${estimate.name} (копія)", createdAt = System.currentTimeMillis())) }
                                        .onSuccess { status = "Кошторис продубльовано." }
                                        .onFailure { error = it.message ?: "Не вдалося дублювати кошторис" }
                                }
                            }) { Icon(Icons.Default.ContentCopy, contentDescription = "Дублювати кошторис") }
                            IconButton(onClick = {
                                pendingPdfEstimate = estimate
                                val safeName = estimate.name.replace(Regex("[^A-Za-zА-Яа-яІіЇїЄє0-9_-]"), "_").take(40).ifBlank { "koshtorys" }
                                pdfLauncher.launch("$safeName.pdf")
                            }) { Icon(Icons.Default.PictureAsPdf, contentDescription = "Експортувати PDF") }
                            IconButton(onClick = { scope.launch { runCatching { database.estimates().deleteById(estimate.id) }.onFailure { error = it.message ?: "Не вдалося видалити кошторис" } } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Видалити кошторис")
                            }
                        }
                        Text("${estimate.lengthMeters.format2()} × ${estimate.widthMeters.format2()} м · ${estimate.areaSquareMeters.format2()} м² · ${estimate.material} · ${estimate.profileType}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Полотно ${money(estimate.canvasCost)} · профіль ${money(estimate.profileCost)} · роботи ${money(estimate.complexityCost + estimate.optionsCost)}")
                        val itemizedRows = EstimateLineItems.fromJson(estimate.detailsJson)
                        if (itemizedRows.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Розбивка за позиціями", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            itemizedRows.forEach { line ->
                                Text("${line.title}: ${line.quantity.format2()} ${line.unit} × ${money(line.unitPrice)} = ${money(line.total)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(money(estimate.totalCost), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    editingEstimate?.let { selected ->
        AlertDialog(
            onDismissRequest = { editingEstimate = null },
            title = { Text("Редагувати кошторис") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("Назва") }, singleLine = true) },
            confirmButton = {
                Button(enabled = renameText.isNotBlank(), onClick = {
                    scope.launch {
                        runCatching { database?.estimates()?.update(selected.copy(name = renameText.trim())) }
                            .onSuccess { status = "Назву кошторису оновлено." }
                            .onFailure { error = it.message ?: "Не вдалося оновити кошторис" }
                        editingEstimate = null
                    }
                }) { Text("Зберегти") }
            },
            dismissButton = { TextButton(onClick = { editingEstimate = null }) { Text("Скасувати") } }
        )
    }
}

private fun writeEstimatePdf(context: Context, uri: Uri, estimate: EstimateEntity, projectTitle: String) {
    val document = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 18f }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 12f }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 10f }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 9f; color = android.graphics.Color.DKGRAY }
    data class PdfLine(val text: String, val kind: Int = 0)
    val lines = mutableListOf<PdfLine>()
    lines += PdfLine("КОШТОРИС НАТЯЖНОЇ СТЕЛІ", 2)
    lines += PdfLine("Об’єкт: $projectTitle")
    lines += PdfLine("Кошторис: ${estimate.name}")
    lines += PdfLine("Дата: ${java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date())}")
    lines += PdfLine("")
    lines += PdfLine("ПАРАМЕТРИ ПРИМІЩЕННЯ", 1)
    lines += PdfLine("Розмір: ${estimate.lengthMeters.format2()} × ${estimate.widthMeters.format2()} м")
    lines += PdfLine("Площа: ${estimate.areaSquareMeters.format2()} м²    Периметр: ${estimate.perimeterMeters.format2()} м")
    lines += PdfLine("Матеріал: ${estimate.material}")
    lines += PdfLine("Профіль: ${estimate.profileType}")
    lines += PdfLine("Складність: ${when (estimate.complexity) { "multilevel" -> "Багаторівнева"; "curved" -> "Криволінійна"; else -> "Пряма" }}")
    lines += PdfLine("")
    lines += PdfLine("ДЕТАЛЬНИЙ РОЗРАХУНОК", 1)
    val lineItems = EstimateLineItems.fromJson(estimate.detailsJson)
    if (lineItems.isNotEmpty()) {
        lines += PdfLine("Позиція | Кількість | Ціна за одиницю | Сума", 1)
        lineItems.forEach { item ->
            lines += PdfLine("${item.title} | ${item.quantity.format2()} ${item.unit} | ${money(item.unitPrice)} | ${money(item.total)}")
        }
    } else {
        lines += PdfLine("Полотно: ${money(estimate.canvasCost)}")
        lines += PdfLine("Профіль: ${money(estimate.profileCost)}")
        lines += PdfLine("Складність: ${money(estimate.complexityCost)}")
        lines += PdfLine("Додаткові роботи: ${money(estimate.optionsCost)}")
    }
    lines += PdfLine("")
    lines += PdfLine("РАЗОМ: ${money(estimate.totalCost)}", 2)
    lines += PdfLine("Ціни зафіксовано на момент створення кошторису.", 3)
    lines += PdfLine("Документ сформовано застосунком Andrey Ceilings.", 3)

    val linesPerPage = 35
    val pages = lines.chunked(linesPerPage)
    pages.forEachIndexed { pageIndex, pageLines ->
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
        val canvas = page.canvas
        var y = 44f
        pageLines.forEach { line ->
            val paint = when (line.kind) { 1 -> headingPaint; 2 -> titlePaint; 3 -> mutedPaint; else -> bodyPaint }
            val maxWidth = 515f
            val text = line.text
            if (text.isNotEmpty() && paint.measureText(text) > maxWidth) {
                var remaining = text
                while (remaining.isNotEmpty()) {
                    var endIndex = remaining.length
                    while (endIndex > 1 && paint.measureText(remaining.substring(0, endIndex)) > maxWidth) endIndex--
                    val breakAt = remaining.lastIndexOf(' ', endIndex).takeIf { it > 0 } ?: endIndex
                    canvas.drawText(remaining.substring(0, breakAt), 40f, y, paint)
                    remaining = remaining.substring(breakAt).trimStart()
                    y += if (line.kind == 2) 26f else 17f
                }
            } else {
                canvas.drawText(text, 40f, y, paint)
                y += when (line.kind) { 2 -> 28f; 1 -> 21f; 3 -> 16f; else -> 17f }
            }
        }
        canvas.drawText("Сторінка ${pageIndex + 1} з ${pages.size}", 455f, 820f, mutedPaint)
        document.finishPage(page)
    }
    try {
        context.contentResolver.openOutputStream(uri)?.use { output -> document.writeTo(output) }
            ?: error("Не вдалося відкрити файл для запису")
    } finally { document.close() }
}

private fun money(value: Double): String = String.format(Locale.getDefault(), "%,.0f ₴", value)

private fun Double.format2(): String = String.format(Locale.getDefault(), "%.2f", this)

@Composable
internal fun PriceListScreen(database: CeilingsDatabase?) {
    val itemsFlow = remember(database) { database?.priceItems()?.observeAll() ?: flowOf<List<PriceItemEntity>>(emptyList()) }
    val priceItems by itemsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editingItem by remember { mutableStateOf<PriceItemEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Прайс-лист", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Демонстраційні ціни в гривнях. Перед використанням перевірте й змініть їх під свої умови.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (database == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Захищена база ще відкривається або недоступна.")
        } else {
            Button(onClick = { editingItem = null; showEditor = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Додати позицію")
            }
            if (priceItems.isEmpty()) EmptyDataCard("Прайс-лист порожній.")
            priceItems.groupBy { it.category }.forEach { (category, entries) ->
                Text(category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                entries.forEach { item ->
                    Card {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.title, fontWeight = FontWeight.Medium)
                                Text("${formatPrice(item.price)} ₴ / ${item.unit}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { editingItem = item; showEditor = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Редагувати ціну")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { database.priceItems().deleteById(item.id) }
                                        .onFailure { error = it.message ?: "Не вдалося видалити позицію" }
                                }
                            }) { Icon(Icons.Default.Delete, contentDescription = "Видалити позицію") }
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showEditor && database != null) PriceItemEditorDialog(
        initial = editingItem,
        onDismiss = { showEditor = false },
        onSave = { title, category, unit, price ->
            scope.launch {
                runCatching {
                    val current = editingItem
                    database.priceItems().upsert(
                        PriceItemEntity(
                            id = current?.id ?: 0,
                            code = current?.code ?: "custom.${System.currentTimeMillis()}",
                            title = title.trim(), category = category.trim(), unit = unit.trim(), price = price
                        )
                    )
                }.onFailure { error = it.message ?: "Не вдалося зберегти позицію" }
                showEditor = false
            }
        }
    )
}

@Composable
private fun PriceItemEditorDialog(
    initial: PriceItemEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Double) -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category ?: "Додаткові роботи") }
    var unit by remember(initial) { mutableStateOf(initial?.unit ?: "шт.") }
    var price by remember(initial) { mutableStateOf(initial?.price?.let { formatPrice(it) } ?: "0") }
    val parsedPrice = price.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Нова позиція прайсу" else "Редагувати позицію") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Назва *") }, singleLine = true)
                OutlinedTextField(category, { category = it }, label = { Text("Категорія *") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Одиниця виміру *") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Ціна, ₴") }, singleLine = true)
                Text("Приклади одиниць: м², м.п., шт. Ціни є демонстраційними.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank() && category.isNotBlank() && unit.isNotBlank() && parsedPrice != null && parsedPrice >= 0) onSave(title, category, unit, parsedPrice) }, enabled = title.isNotBlank() && category.isNotBlank() && unit.isNotBlank() && parsedPrice != null && parsedPrice >= 0) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

private fun formatPrice(value: Double): String = String.format(Locale("uk", "UA"), "%.2f", value).trimEnd('0').trimEnd(',').trimEnd('.')
