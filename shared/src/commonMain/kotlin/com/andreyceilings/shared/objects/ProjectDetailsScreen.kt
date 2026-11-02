package com.andreyceilings.shared.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectStatusRules
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private sealed interface DetailsState {
    data object Loading : DetailsState
    data object Unavailable : DetailsState
    data class Loaded(val data: ProjectDetailsData) : DetailsState
}

@Composable
internal fun ProjectDetailsScreen(
    projectId: Long,
    store: ObjectsClientsStore?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: (ProjectListEntry) -> Unit
) {
    val flow = remember(store, projectId) {
        store?.observeProjectDetails(projectId)?.map<ProjectDetailsData, DetailsState> { DetailsState.Loaded(it) }
            ?.catch { emit(DetailsState.Unavailable) }
    }
    val state = if (flow == null) DetailsState.Unavailable
        else flow.collectAsState(initial = DetailsState.Loading).value
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("← До списку") } }
        when (state) {
            DetailsState.Loading -> item { Text("Завантаження…") }
            DetailsState.Unavailable -> item { Text("Захищена база недоступна або заблокована.") }
            is DetailsState.Loaded -> {
                val project = state.data.project
                if (project == null) item { Text("Об'єкт не знайдено або його видалено.") }
                else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(project.address.ifBlank { project.title.ifBlank { "Адреса не вказана" } },
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text("Клієнт: ${project.clientName.ifBlank { "Не вказано" }}")
                            Text("Етап: ${ProjectStatusRules.workLabel(project.workStatus)}")
                            Text("Оплата: ${ProjectStatusRules.paymentLabel(project.paymentStatus)}")
                            if (project.notes.isNotBlank()) Text("Примітки: ${project.notes}")
                            TextButton(onClick = { onEdit(project) }) { Text("Редагувати об'єкт") }
                            Text("Заміри (${state.data.measurements.size})", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (state.data.measurements.isEmpty()) item { Text("Замірів ще немає.") }
                    items(state.data.measurements, key = { it.id }) { measurement ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(measurement.name, fontWeight = FontWeight.SemiBold)
                                Text("Форма: ${CeilingShapeTemplates.template(measurement.shape)?.title ?: if (measurement.shape == "polygon") "Багатокутник" else measurement.shape}")
                                Text("Площа: ${measurement.area} м² · Периметр: ${measurement.perimeter} м")
                            }
                        }
                    }
                }
            }
        }
    }
}
