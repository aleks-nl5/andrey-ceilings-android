package com.andreyceilings.shared.objects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectStatusRules
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private sealed interface DetailsState {
    data object Loading : DetailsState
    data object Unavailable : DetailsState
    data class Loaded(val data: ProjectDetailsData) : DetailsState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectDetailsScreen(
    projectId: Long,
    store: ObjectsClientsStore?,
    clients: List<ClientListEntry>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: (ProjectListEntry) -> Unit,
    onDial: (String) -> Unit
) {
    val flow = remember(store, projectId) {
        store?.observeProjectDetails(projectId)?.map<ProjectDetailsData, DetailsState> { DetailsState.Loaded(it) }
            ?.catch { emit(DetailsState.Unavailable) }
    }
    val state = if (flow == null) DetailsState.Unavailable
        else flow.collectAsState(initial = DetailsState.Loading).value
    val uriHandler = LocalUriHandler.current
    var contactError by remember(projectId) { mutableStateOf(false) }
    // Surface supplies LocalContentColor as well as the background. This matters
    // when the host does not provide a themed content color (notably Android).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "back") {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("← До списку")
                }
            }
            when (state) {
                DetailsState.Loading -> item { Text("Завантаження…") }
                DetailsState.Unavailable -> item { Text("Захищена база недоступна або заблокована.") }
                is DetailsState.Loaded -> {
                    val project = state.data.project
                    if (project == null) item { Text("Об'єкт не знайдено або його видалено.") }
                    else {
                        item(key = "heading") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(project.address.ifBlank { project.title.ifBlank { "Адреса не вказана" } },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { onEdit(project) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Редагувати об'єкт",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        item(key = "information") {
                            DetailsCard("Інформація про об'єкт") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StatusBadge("Етап: ${ProjectStatusRules.workLabel(project.workStatus)}")
                                    StatusBadge("Оплата: ${ProjectStatusRules.paymentLabel(project.paymentStatus)}")
                                }
                            }
                        }
                        if (project.notes.isNotBlank()) item(key = "notes") {
                            DetailsCard("Примітки") { Text(project.notes, style = MaterialTheme.typography.bodyMedium) }
                        }
                        item(key = "client") {
                            val client = clients.firstOrNull { it.id == project.clientId }
                            DetailsCard("Клієнт") {
                                Text(client?.name ?: project.clientName.ifBlank { "Не вказано" },
                                    style = MaterialTheme.typography.bodyMedium)
                                client?.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                                    ContactLink(phone, "Зателефонувати") { onDial(phone) }
                                }
                                client?.email?.takeIf { it.isNotBlank() }?.let { email ->
                                    ContactLink(email, "Написати листа") {
                                        contactError = runCatching {
                                            uriHandler.openUri("mailto:${email.trim()}")
                                        }.isFailure
                                    }
                                }
                                if (contactError) Text("Не вдалося відкрити поштовий застосунок.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item(key = "measurements-heading") {
                            Text("Заміри (${state.data.measurements.size})",
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        if (state.data.measurements.isEmpty()) item { Text("Замірів ще немає.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(state.data.measurements, key = { it.id }) { measurement ->
                            Card(Modifier.fillMaxWidth(), colors = detailsCardColors()) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(measurement.name, style = MaterialTheme.typography.titleSmall)
                                    Text("Форма: ${CeilingShapeTemplates.template(measurement.shape)?.title ?: if (measurement.shape == "polygon") "Багатокутник" else measurement.shape}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Площа: ${measurement.area} м² · Периметр: ${measurement.perimeter} м",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun detailsCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor = MaterialTheme.colorScheme.onSurface
)

@Composable
private fun DetailsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = detailsCardColors()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun StatusBadge(label: String) {
    Surface(shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ContactLink(value: String, actionLabel: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().minimumInteractiveComponentSize()
        .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick),
        contentAlignment = Alignment.CenterStart) {
        Text(value, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = TextDecoration.Underline)
    }
}
