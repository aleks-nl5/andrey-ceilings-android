package com.andreyceilings.shared.objects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectStatusRules
import com.andreyceilings.shared.domain.ProjectWorkStage
import com.andreyceilings.shared.domain.ProjectPaymentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private enum class InlineStatusKind { WORK, PAYMENT }

/**
 * Direction-independent docking rule. Once the original title has passed the
 * safe top edge, its floating copy stays visible until the *actual* row returns.
 * A reverse swipe anywhere else in the list does not undock the title.
 */
internal fun shouldDockProjectTitle(
    firstVisibleItemIndex: Int,
    titleOffsetPx: Int?,
    titleSizePx: Int?,
    topEdgePx: Int
): Boolean {
    if (firstVisibleItemIndex > 0) return true
    if (titleOffsetPx == null || titleSizePx == null) return false
    return titleOffsetPx + titleSizePx <= topEdgePx
}

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
    systemTopInset: androidx.compose.ui.unit.Dp = 0.dp,
    onDockedChange: (Boolean) -> Unit = {},
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
    val visibleProject = (state as? DetailsState.Loaded)?.data?.project
    val uriHandler = LocalUriHandler.current
    val latestOnDockedChange by rememberUpdatedState(onDockedChange)
    // A new object always starts at its own top, never another object’s scroll.
    val detailsListState = remember(projectId) { LazyListState() }
    val direction = LocalLayoutDirection.current
    val horizontalStart = contentPadding.calculateLeftPadding(direction)
    val horizontalEnd = contentPadding.calculateRightPadding(direction)
    val topEdgePx = with(LocalDensity.current) { (systemTopInset + 4.dp).roundToPx() }
    val isDocked by remember(detailsListState, projectId, topEdgePx) {
        derivedStateOf {
            val heading = detailsListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == "project-heading" }
            shouldDockProjectTitle(
                firstVisibleItemIndex = detailsListState.firstVisibleItemIndex,
                titleOffsetPx = heading?.offset,
                titleSizePx = heading?.size,
                topEdgePx = topEdgePx
            )
        }
    }
    LaunchedEffect(projectId, isDocked, visibleProject?.id) {
        latestOnDockedChange(visibleProject != null && isDocked)
    }
    DisposableEffect(projectId) {
        onDispose { latestOnDockedChange(false) }
    }
    var contactError by remember(projectId) { mutableStateOf<String?>(null) }
    var statusError by remember(projectId) { mutableStateOf<String?>(null) }
    var updatingStatus by remember(projectId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Update only the selected field; never rebuild an entire object draft from
    // possibly stale UI values. One operation at a time and no optimistic fake status.
    val updateStatus: (InlineStatusKind, String) -> Unit = { kind, value ->
        if (!updatingStatus) {
            updatingStatus = true
            statusError = null
            scope.launch {
                try {
                    val activeStore = checkNotNull(store) { "Захищена база недоступна." }
                    withContext(Dispatchers.Default) {
                        when (kind) {
                            InlineStatusKind.WORK ->
                                activeStore.updateProjectWorkStage(projectId, value)
                            InlineStatusKind.PAYMENT ->
                                activeStore.updateProjectPaymentStatus(projectId, value)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    statusError = error.message ?: "Не вдалося зберегти статус."
                } finally {
                    updatingStatus = false
                }
            }
        }
    }
    // Surface supplies LocalContentColor as well as the background. This matters
    // when the host does not provide a themed content color (notably Android).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = detailsListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "project-heading") {
                visibleProject?.let { project ->
                    Column {
                        ProjectDetailsTitleRow(
                            project = project,
                            onBack = onBack,
                            onEdit = { onEdit(project) }
                        )
                        if (project.title.isNotBlank() && project.address.isNotBlank() &&
                            project.title != project.address
                        ) {
                            Text(
                                project.title,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                        item(key = "status") {
                            DetailsCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                        WorkStagePicker(
                                            projectId = projectId,
                                            status = project.workStatus,
                                            enabled = !updatingStatus && store != null,
                                            onSelect = { updateStatus(InlineStatusKind.WORK, it) }
                                        )
                                    }
                                    PaymentStatusPicker(
                                        projectId = projectId,
                                        status = project.paymentStatus,
                                        enabled = !updatingStatus && store != null,
                                        onSelect = { updateStatus(InlineStatusKind.PAYMENT, it) }
                                    )
                                }
                                statusError?.let { error ->
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        if (project.notes.isNotBlank()) item(key = "notes") {
                            DetailsCard("Примітки") { Text(project.notes, style = MaterialTheme.typography.bodyMedium) }
                        }
                        item(key = "client") {
                            val client = clients.firstOrNull { it.id == project.clientId }
                            val phone = client?.phone?.takeIf { it.isNotBlank() }
                            val email = client?.email?.takeIf { it.isNotBlank() }
                            DetailsCard("Клієнт") {
                                Text(
                                    client?.name ?: project.clientName.ifBlank { "Не вказано" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (phone != null || email != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        phone?.let { number ->
                                            ContactLink(
                                                number, "Зателефонувати", Icons.Default.Phone,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                contactError = if (runCatching { onDial(number) }.isFailure)
                                                    "Не вдалося відкрити телефонний застосунок." else null
                                            }
                                        }
                                        email?.let { address ->
                                            ContactLink(
                                                address, "Написати листа", Icons.Default.Email,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                contactError = if (runCatching {
                                                    uriHandler.openUri("mailto:${address.trim()}")
                                                }.isFailure) "Не вдалося відкрити поштовий застосунок." else null
                                            }
                                        }
                                    }
                                }
                                client?.notes?.takeIf { it.isNotBlank() }?.let { clientNotes ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
                                            MaterialTheme.colorScheme.surfaceDim
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ) {
                                        Column(
                                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                "Примітки",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                clientNotes.trim(),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                                contactError?.let { error ->
                                    Text(
                                        error,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
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
        if (visibleProject != null && isDocked) {
            ProjectDetailsDockedHeader(
                project = visibleProject,
                topInset = systemTopInset,
                startPadding = horizontalStart,
                endPadding = horizontalEnd,
                onBack = onBack,
                onEdit = { onEdit(visibleProject) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
        }
    }
}


/** One row is used in both positions, so back/edit and the address never drift. */
@Composable
private fun ProjectDetailsTitleRow(
    project: ProjectListEntry,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().height(76.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ObjectsClientsCreateFab(
            visible = true,
            actionLabel = "До об'єктів",
            icon = Icons.Default.ArrowBack,
            onClick = onBack
        )
        Text(
            project.address.ifBlank {
                project.title.ifBlank { "Адреса не вказана" }
            },
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = if (compact) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        ObjectsClientsCreateFab(
            visible = true,
            actionLabel = "Редагувати об'єкт",
            icon = Icons.Default.Edit,
            onClick = onEdit
        )
    }
}

/** Gradient matches the existing AppHeader's fading background style. */
@Composable
private fun ProjectDetailsDockedHeader(
    project: ProjectListEntry,
    topInset: androidx.compose.ui.unit.Dp,
    startPadding: androidx.compose.ui.unit.Dp,
    endPadding: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier.fillMaxWidth()
            .height(topInset + 108.dp)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to background.copy(alpha = 0.98f),
                        0.64f to background.copy(alpha = 0.95f),
                        1f to background.copy(alpha = 0f)
                    )
                )
            }
    ) {
        ProjectDetailsTitleRow(
            project = project,
            onBack = onBack,
            onEdit = onEdit,
            compact = true,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInset, start = startPadding, end = endPadding)
        )
    }
}

@Composable
private fun detailsCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface
)

/** Quiet, consistent grouping for project metadata and contacts. */
@Composable
private fun DetailsCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = detailsCardColors(),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

/**
 * Both chips are genuine tappable badge surfaces. Their popup menus use the
 * application Material theme and its status colors, not platform default tints.
 */
@Composable
private fun WorkStagePicker(
    projectId: Long,
    status: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember(projectId) { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.height(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                Modifier.fillMaxHeight().padding(horizontal = 9.dp)
                    .semantics {
                        contentDescription = "Змінити етап робіт: ${ProjectStatusRules.workLabel(status)}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                WorkStatusMark(status)
                Text(
                    ProjectStatusRules.workLabel(status),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 204.dp)
        ) {
            ProjectWorkStage.entries.forEach { stage ->
                DropdownMenuItem(
                    text = { Text(stage.label, style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { WorkStatusMark(stage.wireId) },
                    trailingIcon = {
                        if (stage.wireId == status) {
                            Icon(
                                Icons.Default.Check, contentDescription = "Поточний етап",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (stage.wireId != status) onSelect(stage.wireId)
                    }
                )
            }
        }
    }
}

@Composable
private fun PaymentStatusPicker(
    projectId: Long,
    status: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember(projectId) { mutableStateOf(false) }
    val tint = paymentStateTint(status)
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.height(40.dp),
            shape = CircleShape,
            color = tint.copy(alpha = 0.11f),
            contentColor = tint,
            border = BorderStroke(1.dp, tint.copy(alpha = 0.36f))
        ) {
            Row(
                Modifier.fillMaxHeight().padding(horizontal = 11.dp)
                    .semantics {
                        contentDescription = "Змінити оплату: ${ProjectStatusRules.paymentLabel(status)}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    when (status) {
                        "unknown" -> "Невизначено"
                        "partial" -> "Часткова"
                        else -> ProjectStatusRules.paymentLabel(status)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 204.dp)
        ) {
            ProjectPaymentStatus.entries.forEach { payment ->
                DropdownMenuItem(
                    text = { Text(payment.label, style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Box(
                            Modifier.size(11.dp).background(
                                paymentStateTint(payment.wireId),
                                CircleShape
                            )
                        )
                    },
                    trailingIcon = {
                        if (payment.wireId == status) {
                            Icon(
                                Icons.Default.Check, contentDescription = "Поточна оплата",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (payment.wireId != status) onSelect(payment.wireId)
                    }
                )
            }
        }
    }
}

@Composable
private fun ContactLink(
    value: String,
    actionLabel: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
