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
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.calculation.KmpCeilingCalculator
import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import com.andreyceilings.shared.estimate.VerifiedCeilingGeometry
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
 LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(
  top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 16.dp,
  bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp + 16.dp
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
internal fun MeasurementsScreen(
 database: CeilingsDatabase?,
 precision: MeasurementPrecision,
 onOpenEstimates: () -> Unit,
 onUseMeasurement: (RoomMeasurementEntity) -> Unit
) {
 val measurementsFlow = remember(database) { database?.measurements()?.observeAll() ?: flowOf<List<RoomMeasurementEntity>>(emptyList()) }
 val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
 val measurements by measurementsFlow.collectAsState(initial = emptyList())
 val projects by projectsFlow.collectAsState(initial = emptyList())
 val projectsById = remember(projects) { projects.associateBy { it.id } }
 val scope = rememberCoroutineScope()
 var showDialog by remember { mutableStateOf(false) }
 var editingMeasurement by remember { mutableStateOf<RoomMeasurementEntity?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(
  top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 16.dp,
  bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp + 16.dp
 )) {
  item {
   Text("Заміри", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
   Text("Заміри зберігаються в зашифрованій локальній БД", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
   OutlinedButton(onClick = onOpenEstimates, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Кошториси") }
  }
  if (database == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Захищена база ще відкривається або недоступна.") }
  else {
   item { Button(onClick = { editingMeasurement = null; showDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = projects.isNotEmpty()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Створити замір") }; if (projects.isEmpty()) EmptyDataCard("Спочатку створіть об’єкт у вкладці «Об’єкти»."); if (measurements.isEmpty() && projects.isNotEmpty()) EmptyDataCard("Заміри ще не додані.") }
   items(measurements, key = { it.id }) { measurement ->
    val project = projectsById[measurement.projectId]
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
     Icon(Icons.Default.Straighten, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
     Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(measurement.name, fontWeight = FontWeight.SemiBold); Text(project?.title ?: "Об’єкт #${measurement.projectId}", style = MaterialTheme.typography.bodySmall); Text(
         "${when (measurement.shape) {
             "polygon" -> "Багатокутник"
             "l_shaped" -> "L-подібна"
             "rectangle" -> "Прямокутник"
             else -> measurement.shape
         }} · ${formatStoredSquareMeters(measurement.areaSquareMeters)} м² · периметр ${formatStoredMeters(measurement.perimeterMeters)} м",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant
     ) }
     TextButton(onClick = {
      runCatching { onUseMeasurement(measurement) }
       .onFailure { error = it.message ?: "Не вдалося відкрити замір у калькуляторі" }
     }) { Text("Розрахувати") }
     IconButton(onClick = {
      editingMeasurement = measurement
      showDialog = true
     }) { Icon(Icons.Default.Edit, contentDescription = "Редагувати замір") }
     IconButton(onClick = { scope.launch { runCatching { database.measurements().deleteById(measurement.id) }.onFailure { error = it.message ?: "Не вдалося видалити замір" } } }) { Icon(Icons.Default.Delete, contentDescription = "Видалити замір") }
    } }
   }
  }
  item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
 }
 if (showDialog && database != null && projects.isNotEmpty()) {
  AddMeasurementDialog(
   projects = projects, precision = precision,
   existing = editingMeasurement,
   onDismiss = { showDialog = false; editingMeasurement = null },
   onSave = { projectId, name, geometry ->
    scope.launch {
     runCatching {
      val xs = geometry.vertices.map { it.xMm }
      val ys = geometry.vertices.map { it.yMm }
      val length = (xs.maxOrNull()!! - xs.minOrNull()!!) / 1000.0
      val width = (ys.maxOrNull()!! - ys.minOrNull()!!) / 1000.0
      val value = RoomMeasurementEntity(
       id = editingMeasurement?.id ?: 0L,
       projectId = editingMeasurement?.projectId ?: projectId,
       name = name.trim(),
       shape = geometry.shapeType,
       lengthMeters = length, widthMeters = width,
       areaSquareMeters = geometry.areaSquareMeters,
       perimeterMeters = geometry.perimeterMeters,
       drawingJson = FrozenPolygonGeometryJson.writeMeasurement(geometry),
       notes = editingMeasurement?.notes.orEmpty(),
       createdAt = editingMeasurement?.createdAt ?: System.currentTimeMillis()
      )
      if (editingMeasurement == null) database.measurements().insert(value)
      else database.measurements().update(value)
     }.onSuccess {
      showDialog = false; editingMeasurement = null; error = null
     }.onFailure {
      error = it.message ?: "Не вдалося зберегти замір"
     }
    }
   }
  )
 }
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
private fun AddMeasurementDialog(
    projects: List<ProjectEntity>,
    precision: MeasurementPrecision,
    existing: RoomMeasurementEntity?,
    onDismiss: () -> Unit,
    onSave: (Long, String, FrozenPolygonGeometry) -> Unit
) {
    val previous = remember(existing?.id) {
        existing?.let { record ->
            runCatching {
                FrozenPolygonGeometryJson.readMeasurement(record.drawingJson)
            }.getOrNull()
        }
    }
    var selectedProjectId by remember(existing?.id, projects) {
        mutableLongStateOf(existing?.projectId ?: projects.first().id)
    }
    var name by remember(existing?.id) {
        mutableStateOf(existing?.name.orEmpty())
    }
    var shape by remember(existing?.id) {
        mutableStateOf(previous?.shapeType ?: existing?.shape ?: "rectangle")
    }
    var selectedPrecision by remember(existing?.id, precision) {
        mutableStateOf(
            previous?.precisionStepMeters?.let { MeasurementPrecision.fromStep(it) }
                ?: precision
        )
    }
    val fields = remember(existing?.id) {
        mutableStateMapOf<String, String>().apply {
            put("rectangle.length", existing?.lengthMeters?.toString() ?: "5.0")
            put("rectangle.width", existing?.widthMeters?.toString() ?: "4.0")
            put("l_shaped.A", "5")
            put("l_shaped.B", "4")
            put("l_shaped.C", "2")
            put("l_shaped.D", "2")
            previous?.let { saved ->
                saved.measurementsMm.forEach { (key, mm) ->
                    put("${saved.shapeType}.$key", (mm / 1000.0).toString())
                }
            }
        }
    }
    val vertices = remember(existing?.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(
                previous?.vertices?.map { (x, y) ->
                    (x / 1000.0).toString() to (y / 1000.0).toString()
                } ?: defaultPolygonVertices()
            )
        }
    }
    var projectExpanded by remember { mutableStateOf(false) }
    var shapeExpanded by remember { mutableStateOf(false) }
    var precisionExpanded by remember { mutableStateOf(false) }
    val activeTemplate = CeilingShapeTemplates.template(shape)
    val validation = runCatching {
        val input = if (shape == "polygon") {
            capturedPolygonFromText(vertices.toList(), selectedPrecision.stepMeters)
        } else {
            val template = requireNotNull(activeTemplate) { "Невідома форма стелі" }
            VerifiedCeilingGeometry.capturedTemplate(
                shape, template.parameterKeys.associateWith { key ->
                    fields["$shape.$key"]?.trim()?.replace(',', '.')?.toDoubleOrNull()
                        ?: Double.NaN
                }, selectedPrecision.stepMeters
            )
        }
        val measurementsMm = if (shape == "polygon") emptyMap() else
            checkNotNull(activeTemplate).parameterKeys.associateWith { key ->
                val value = checkNotNull(input.let {
                    fields["$shape.$key"]?.replace(',', '.')?.toDoubleOrNull()
                })
                kotlin.math.round(selectedPrecision.length(value) * 1000.0).toLong()
            }
        FrozenPolygonGeometryContract.capture(
            shape, input.verticesMm, measurementsMm, selectedPrecision.stepMeters
        )
    }
    val geometry = validation.getOrNull()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Новий замір" else "Редагування заміру") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = projectExpanded,
                    onExpandedChange = { if (existing == null) projectExpanded = it }
                ) {
                    OutlinedTextField(
                        value = projects.firstOrNull { it.id == selectedProjectId }?.title
                            ?: "Оберіть об’єкт",
                        onValueChange = {},
                        readOnly = true,
                        enabled = existing == null,
                        label = { Text("Об’єкт") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(projectExpanded)
                        },
                        modifier = Modifier.menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            enabled = existing == null
                        ).fillMaxWidth()
                    )
                    AppExposedDropdownMenu(
                        expanded = projectExpanded,
                        onDismissRequest = { projectExpanded = false }
                    ) {
                        projects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.title) },
                                onClick = {
                                    selectedProjectId = project.id
                                    projectExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    name, { name = it }, label = { Text("Назва кімнати *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                ExposedDropdownMenuBox(
                    expanded = shapeExpanded,
                    onExpandedChange = { shapeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = activeTemplate?.title ?:
                            if (shape == "polygon") "Довільний багатокутник" else shape,
                        onValueChange = {}, readOnly = true,
                        label = { Text("Форма стелі") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(shapeExpanded)
                        },
                        modifier = Modifier.menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryNotEditable, true
                        ).fillMaxWidth()
                    )
                    AppExposedDropdownMenu(
                        expanded = shapeExpanded,
                        onDismissRequest = { shapeExpanded = false }
                    ) {
                        (CeilingShapeTemplates.templates.map {
                            it.shapeType to it.title
                        } + ("polygon" to "Довільний багатокутник")).forEach { (id, title) ->
                            DropdownMenuItem(text = { Text(title) }, onClick = {
                                shape = id; shapeExpanded = false
                            })
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = precisionExpanded,
                    onExpandedChange = { precisionExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedPrecision.label,
                        onValueChange = {}, readOnly = true,
                        label = { Text("Точність вимірювань") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(precisionExpanded)
                        },
                        modifier = Modifier.menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryNotEditable, true
                        ).fillMaxWidth()
                    )
                    AppExposedDropdownMenu(
                        expanded = precisionExpanded,
                        onDismissRequest = { precisionExpanded = false }
                    ) {
                        MeasurementPrecision.entries.forEach { entry ->
                            DropdownMenuItem(text = { Text(entry.label) }, onClick = {
                                selectedPrecision = entry
                                precisionExpanded = false
                            })
                        }
                    }
                }
                if (shape == "polygon") {
                    PolygonVerticesEditor(
                        vertices = vertices.toList(),
                        onEdit = { i, point -> vertices[i] = point },
                        onAdd = {
                            vertices.add("" to "")
                        },
                        onRemove = { i ->
                            if (vertices.size > 3) vertices.removeAt(i)
                        }
                    )
                } else {
                    if (shape == "l_shaped") {
                        Text(
                            "Прямі кути: E = A − C, F = B − D.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    activeTemplate?.parameterKeys?.forEach { key ->
                        OutlinedTextField(
                            value = fields["$shape.$key"].orEmpty(),
                            onValueChange = { fields["$shape.$key"] = it },
                            label = { Text(activeTemplate.parameterLabels.getValue(key)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                }
                if (geometry != null) {
                    Text(
                        "Площа: ${formatMeasuredArea(geometry.areaSquareMeters, selectedPrecision)} м²" +
                            " · Периметр: ${formatMeasuredLength(geometry.perimeterMeters, selectedPrecision)} м",
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        validation.exceptionOrNull()?.message
                            ?: "Перевірте форму та координати вершин.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { geometry?.let { onSave(selectedProjectId, name, it) } },
                enabled = name.isNotBlank() && geometry != null
            ) { Text("Зберегти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )
}
