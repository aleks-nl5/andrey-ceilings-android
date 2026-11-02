package com.andreyceilings.shared.objects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
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
    var contactError by remember(projectId) { mutableStateOf<String?>(null) }
    // Surface supplies LocalContentColor as well as the background. This matters
    // when the host does not provide a themed content color (notably Android).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "header") {
                Column {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("← До списку")
                    }
                    val project = (state as? DetailsState.Loaded)?.data?.project
                    if (project != null) {
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    WorkStatusMark(project.workStatus)
                                    Text("Етап: ${ProjectStatusRules.workLabel(project.workStatus)}",
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            PaymentStateTag(project.paymentStatus, prefix = "Оплата: ")
                        }
                    }
                }
            }
            when (state) {
                DetailsState.Loading -> item { Text("Завантаження…") }
                DetailsState.Unavailable -> item { Text("Захищена база недоступна або заблокована.") }
                is DetailsState.Loaded -> {
                    val project = state.data.project
                    if (project == null) item { Text("Об'єкт не знайдено або його видалено.") }
                    else {
                        if (project.notes.isNotBlank()) item(key = "notes") {
                            DetailsCard("Примітки") { Text(project.notes, style = MaterialTheme.typography.bodyMedium) }
                        }
                        item(key = "client") {
                            val client = clients.firstOrNull { it.id == project.clientId }
                            DetailsCard("Клієнт", spacing = 0) {
                                Text(client?.name ?: project.clientName.ifBlank { "Не вказано" },
                                    style = MaterialTheme.typography.bodyMedium)
                                client?.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                                    ContactLink(phone, "Зателефонувати", Icons.Default.Phone) {
                                        contactError = if (runCatching { onDial(phone) }.isFailure)
                                            "Не вдалося відкрити телефонний застосунок." else null
                                    }
                                }
                                client?.email?.takeIf { it.isNotBlank() }?.let { email ->
                                    ContactLink(email, "Написати листа", Icons.Default.Email) {
                                        contactError = if (runCatching {
                                            uriHandler.openUri("mailto:${email.trim()}")
                                        }.isFailure) "Не вдалося відкрити поштовий застосунок." else null
                                    }
                                }
                                contactError?.let { Text(it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall) }
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
private fun DetailsCard(title: String, spacing: Int = 4, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = detailsCardColors()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ContactLink(value: String, actionLabel: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = actionLabel, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text(value, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium)
    }
}
