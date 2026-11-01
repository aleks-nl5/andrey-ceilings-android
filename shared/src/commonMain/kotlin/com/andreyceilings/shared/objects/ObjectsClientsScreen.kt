package com.andreyceilings.shared.objects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ObjectsClientsSearch
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectPaymentStatus
import com.andreyceilings.shared.domain.ProjectStatusRules
import com.andreyceilings.shared.domain.ProjectWorkStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class ObjectsClientsTab { PROJECTS, CLIENTS }
enum class ContactAction { GOOGLE_MAPS, WAZE, WHATSAPP, TELEGRAM, VIBER }

private sealed interface EditorTarget {
    data class Client(val item: ClientListEntry?) : EditorTarget
    data class Project(val item: ProjectListEntry?) : EditorTarget
}
private sealed interface DeletionTarget {
    data class Client(val item: ClientListEntry) : DeletionTarget
    data class Project(val item: ProjectListEntry) : DeletionTarget
}

/**
 * Shared Compose Multiplatform content. Host owns only decrypted SQLDelight session,
 * blocking IO dispatcher, insets and external URL/dialer platform adapters.
 * No fake data, Android APIs, implicit calls or destructive swipe-to-delete.
 */
@Composable
fun ObjectsClientsScreen(
    rows: ObjectsClientsRows,
    databaseAvailable: Boolean,
    onSaveClient: suspend (Long?, ClientDraft) -> Unit,
    onSaveProject: suspend (Long?, ProjectDraft) -> Unit,
    onDeleteClient: suspend (Long) -> Unit,
    onDeleteProject: suspend (Long) -> Unit,
    onDial: (String) -> Unit,
    onExternalAction: (ContactAction, String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    requestCreateProject: Boolean = false,
    onCreateProjectConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var selectedTab by remember { mutableStateOf(ObjectsClientsTab.PROJECTS) }
    var search by remember { mutableStateOf("") }
    var selectedWork by remember { mutableStateOf<String?>(null) }
    var selectedPayment by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleting by remember { mutableStateOf<DeletionTarget?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var fabVisible by remember { mutableStateOf(true) }

    val results = remember(rows, search) {
        ObjectsClientsSearch.suggestions(rows.projects, rows.clients, search)
    }
    val projects = remember(rows.projects, search, selectedWork, selectedPayment) {
        ObjectsClientsSearch.projects(rows.projects, search, selectedWork, selectedPayment)
    }
    val clients = remember(rows.clients, search) {
        ObjectsClientsSearch.clients(rows.clients, search)
    }

    // Gesture/scroll direction is shared, not driven by an Android callback.
    LaunchedEffect(listState) {
        var index = listState.firstVisibleItemIndex
        var offset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress)
        }.collect { (nextIndex, nextOffset, scrolling) ->
            fabVisible = when {
                !scrolling -> true
                nextIndex < index || (nextIndex == index && nextOffset < offset) -> true
                nextIndex > index || (nextIndex == index && nextOffset > offset) -> false
                else -> fabVisible
            }
            index = nextIndex
            offset = nextOffset
        }
    }
    LaunchedEffect(requestCreateProject, databaseAvailable) {
        if (requestCreateProject && databaseAvailable) {
            selectedTab = ObjectsClientsTab.PROJECTS
            editor = EditorTarget.Project(null)
            onCreateProjectConsumed()
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header") {
                Text("Об'єкти та клієнти",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            item(key = "tabs") {
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(selected = selectedTab == ObjectsClientsTab.PROJECTS,
                        onClick = { selectedTab = ObjectsClientsTab.PROJECTS },
                        text = { Text("Об'єкти") })
                    Tab(selected = selectedTab == ObjectsClientsTab.CLIENTS,
                        onClick = { selectedTab = ObjectsClientsTab.CLIENTS },
                        text = { Text("Клієнти") })
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Пошук об'єктів та клієнтів") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистити пошук")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!databaseAvailable) {
                item(key = "unavailable") {
                    Text("Захищена база ще відкривається або недоступна.",
                        color = MaterialTheme.colorScheme.error)
                }
            } else {
                if (search.isNotBlank() && !results.isEmpty) {
                    item(key = "suggestions") {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (results.projects.isNotEmpty()) {
                                    Text("Об'єкти", style = MaterialTheme.typography.labelMedium)
                                    results.projects.forEach { row ->
                                        TextButton(onClick = {
                                            selectedTab = ObjectsClientsTab.PROJECTS
                                            editor = EditorTarget.Project(row)
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Text(row.title + " · " + row.address,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                if (results.clients.isNotEmpty()) {
                                    Text("Клієнти", style = MaterialTheme.typography.labelMedium)
                                    results.clients.forEach { row ->
                                        TextButton(onClick = {
                                            selectedTab = ObjectsClientsTab.CLIENTS
                                            editor = EditorTarget.Client(row)
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Text(row.name, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (selectedTab == ObjectsClientsTab.PROJECTS) {
                    item(key = "filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                item {
                                    FilterChip(selected = selectedWork == null,
                                        onClick = { selectedWork = null },
                                        label = { Text("Усі") })
                                }
                                items(ProjectWorkStage.entries, key = { it.wireId }) { stage ->
                                    FilterChip(selected = selectedWork == stage.wireId,
                                        onClick = {
                                            selectedWork = stage.wireId.takeIf { selectedWork != it }
                                        },
                                        label = { Text(stage.label) })
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                items(ProjectPaymentStatus.entries, key = { it.wireId }) { payment ->
                                    FilterChip(selected = selectedPayment == payment.wireId,
                                        onClick = {
                                            selectedPayment = payment.wireId
                                                .takeIf { selectedPayment != it }
                                        }, label = { Text(payment.label) })
                                }
                            }
                        }
                    }
                    if (projects.isEmpty()) {
                        item(key = "empty-projects") {
                            Text(if (rows.projects.isEmpty()) "Об'єктів ще немає."
                                 else "За цими умовами об'єктів не знайдено.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else items(projects, key = { "p-${it.id}" }) { row ->
                        ProjectCard(row,
                            onEdit = { editor = EditorTarget.Project(row) },
                            onDelete = { deleting = DeletionTarget.Project(row) },
                            onExternalAction = onExternalAction)
                    }
                } else {
                    if (clients.isEmpty()) {
                        item(key = "empty-clients") {
                            Text(if (rows.clients.isEmpty()) "Клієнтів ще немає."
                                 else "За цими умовами клієнтів не знайдено.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else items(clients, key = { "c-${it.id}" }) { row ->
                        ClientCard(row,
                            onEdit = { editor = EditorTarget.Client(row) },
                            onDelete = { deleting = DeletionTarget.Client(row) },
                            onDial = onDial,
                            onExternalAction = onExternalAction)
                    }
                }
                if (message != null) item(key = "message") {
                    Text(message.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        AnimatedVisibility(
            visible = databaseAvailable && !busy && fabVisible && editor == null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 88.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    message = null
                    editor = if (selectedTab == ObjectsClientsTab.PROJECTS)
                        EditorTarget.Project(null) else EditorTarget.Client(null)
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (selectedTab == ObjectsClientsTab.PROJECTS) "Об'єкт" else "Клієнт") }
            )
        }
    }

    when (val target = editor) {
        is EditorTarget.Client -> ClientEditor(
            initial = target.item, busy = busy,
            onDismiss = { if (!busy) editor = null },
            onSave = { draft ->
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            onSaveClient(target.item?.id, draft)
                            editor = null
                            message = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            message = failure.message ?: "Не вдалося зберегти клієнта."
                        } finally { busy = false }
                    }
                }
            },
            error = message
        )
        is EditorTarget.Project -> ProjectEditor(
            initial = target.item, clients = rows.clients, busy = busy,
            onDismiss = { if (!busy) editor = null },
            onSave = { draft ->
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            onSaveProject(target.item?.id, draft)
                            editor = null
                            message = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            message = failure.message ?: "Не вдалося зберегти об'єкт."
                        } finally { busy = false }
                    }
                }
            },
            error = message
        )
        null -> Unit
    }
    deleting?.let { target ->
        val isClient = target is DeletionTarget.Client
        val name = when (target) {
            is DeletionTarget.Client -> target.item.name
            is DeletionTarget.Project -> target.item.title
        }
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("Видалити ${if (isClient) "клієнта" else "об'єкт"}?") },
            text = {
                Text(if (isClient)
                    "«$name». Клієнта з активними об'єктами видалити не можна."
                else "«$name». Пов'язані кошториси та історичні ревізії " +
                    "будуть приховані в списках. Замірів автоматично не видаляємо.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (busy) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            when (target) {
                                is DeletionTarget.Client -> onDeleteClient(target.item.id)
                                is DeletionTarget.Project -> onDeleteProject(target.item.id)
                            }
                            message = null
                            deleting = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            message = failure.message ?: "Не вдалося видалити запис."
                            deleting = null
                        } finally { busy = false }
                    }
                }) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) {
                Text("Скасувати")
            } }
        )
    }
}

@Composable
private fun ProjectCard(
    item: ProjectListEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExternalAction: (ContactAction, String) -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val statusColor = when (item.workStatus) {
                "completed" -> Color(0xFF36886B)
                "cancelled" -> MaterialTheme.colorScheme.error
                "in_progress" -> Color(0xFF3D82BE)
                "rework" -> Color(0xFFB07325)
                else -> MaterialTheme.colorScheme.primary
            }
            Icon(Icons.Default.Directions, null, tint = statusColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (item.address.isNotBlank()) Text(item.address,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (item.clientName.isNotBlank()) Text(item.clientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    ProjectStatusRules.workLabel(item.workStatus) + " · " +
                        ProjectStatusRules.paymentLabel(item.paymentStatus),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            OverflowActions(
                onDelete = onDelete,
                actions = if (item.address.isBlank()) emptyList() else listOf(
                    "Google Maps" to ContactAction.GOOGLE_MAPS,
                    "Waze" to ContactAction.WAZE
                ),
                onAction = { action -> onExternalAction(action, item.address) }
            )
        }
    }
}

@Composable
private fun ClientCard(
    item: ClientListEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDial: (String) -> Unit,
    onExternalAction: (ContactAction, String) -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("Об'єктів: ${item.activeProjectCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.phone.isNotBlank()) TextButton(onClick = { onDial(item.phone) }) {
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(item.phone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OverflowActions(onDelete = onDelete,
                actions = if (item.phone.isBlank()) emptyList() else listOf(
                    "WhatsApp" to ContactAction.WHATSAPP,
                    "Telegram" to ContactAction.TELEGRAM,
                    "Viber" to ContactAction.VIBER
                ),
                onAction = { action -> onExternalAction(action, item.phone) })
        }
    }
}

@Composable
private fun OverflowActions(
    onDelete: () -> Unit,
    actions: List<Pair<String, ContactAction>>,
    onAction: (ContactAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Дії із записом")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) },
                    onClick = { expanded = false; onAction(action) })
            }
            DropdownMenuItem(text = { Text("Видалити") }, leadingIcon = {
                Icon(Icons.Default.Delete, null)
            }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun ClientEditor(
    initial: ClientListEntry?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ClientDraft) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember(initial?.id) { mutableStateOf(initial?.phone.orEmpty()) }
    var email by remember(initial?.id) { mutableStateOf(initial?.email.orEmpty()) }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новий клієнт" else "Редагувати клієнта") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Ім'я *") }, singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it },
                    label = { Text("Телефон") }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it },
                    label = { Text("Email") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it },
                    label = { Text("Примітки") })
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(),
                onClick = { onSave(ClientDraft(name, phone, email, notes)) }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Скасувати") } }
    )
}

@Composable
private fun ProjectEditor(
    initial: ProjectListEntry?,
    clients: List<ClientListEntry>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ProjectDraft) -> Unit
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var address by remember(initial?.id) { mutableStateOf(initial?.address.orEmpty()) }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes.orEmpty()) }
    // Legacy 'paid' was only a billing signal. Require a real work stage on edit.
    var work by remember(initial?.id) {
        mutableStateOf(initial?.workStatus?.takeIf(ProjectStatusRules::isKnownWorkStage)
            ?: if (initial == null) ProjectWorkStage.MEASUREMENT.wireId else "")
    }
    var payment by remember(initial?.id) {
        mutableStateOf(initial?.paymentStatus ?: ProjectPaymentStatus.UNPAID.wireId)
    }
    var clientId by remember(initial?.id) { mutableStateOf(initial?.clientId) }
    var showClientMenu by remember { mutableStateOf(false) }
    var showWorkMenu by remember { mutableStateOf(false) }
    var showPaymentMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новий об'єкт" else "Редагувати об'єкт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("Назва об'єкта *") }, singleLine = true)
                OutlinedTextField(value = address, onValueChange = { address = it },
                    label = { Text("Адреса") })
                Box {
                    TextButton(onClick = { showClientMenu = true }) {
                        Text("Клієнт: " + (clients.firstOrNull { it.id == clientId }?.name ?: "Не вказаний"))
                    }
                    DropdownMenu(expanded = showClientMenu,
                        onDismissRequest = { showClientMenu = false }) {
                        DropdownMenuItem(text = { Text("Без клієнта") },
                            onClick = { clientId = null; showClientMenu = false })
                        clients.forEach { client ->
                            DropdownMenuItem(text = { Text(client.name) },
                                onClick = { clientId = client.id; showClientMenu = false })
                        }
                    }
                }
                Box {
                    TextButton(onClick = { showWorkMenu = true }) {
                        Text("Робота: " + if (work.isBlank()) "Оберіть етап" else
                            ProjectStatusRules.workLabel(work))
                    }
                    DropdownMenu(expanded = showWorkMenu,
                        onDismissRequest = { showWorkMenu = false }) {
                        ProjectWorkStage.entries.forEach { stage ->
                            DropdownMenuItem(text = { Text(stage.label) },
                                onClick = { work = stage.wireId; showWorkMenu = false })
                        }
                    }
                }
                Box {
                    TextButton(onClick = { showPaymentMenu = true }) {
                        Text("Оплата: " + ProjectStatusRules.paymentLabel(payment))
                    }
                    DropdownMenu(expanded = showPaymentMenu,
                        onDismissRequest = { showPaymentMenu = false }) {
                        ProjectPaymentStatus.entries.forEach { status ->
                            DropdownMenuItem(text = { Text(status.label) },
                                onClick = { payment = status.wireId; showPaymentMenu = false })
                        }
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it },
                    label = { Text("Примітки") })
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank() && ProjectStatusRules.isKnownWorkStage(work),
                onClick = {
                    onSave(ProjectDraft(title, address, clientId, work, payment, notes))
                }
            ) { Text("Зберегти") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Скасувати") } }
    )
}
