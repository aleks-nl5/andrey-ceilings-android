package com.andreyceilings.shared.objects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
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
import kotlinx.coroutines.flow.collect

private enum class InlineStatusKind { WORK, PAYMENT }

/**
 * The source header remains in the LazyColumn as a layout anchor; it never
 * draws a second clickable copy. Motion comes from the actual item offset,
 * so reverse gestures do not release the pinned address prematurely.
 */
internal data class ProjectTitleMotion(val sourceTopPx: Int, val progress: Float)

internal fun projectTitleMotion(
    firstVisibleItemIndex: Int,
    sourceOffsetPx: Int?,
    initialTopPx: Int,
    dockedTopPx: Int,
    addressRowOffsetPx: Int
): ProjectTitleMotion {
    val sourceTop = sourceOffsetPx ?: initialTopPx
    val travel = (initialTopPx + addressRowOffsetPx - dockedTopPx).coerceAtLeast(1)
    val fraction = if (firstVisibleItemIndex > 0) 1f else
        ((initialTopPx - sourceTop).toFloat() / travel).coerceIn(0f, 1f)
    return ProjectTitleMotion(sourceTop, fraction)
}

private fun interpolatePx(from: Int, to: Int, progress: Float): Int =
    (from + (to - from) * progress.coerceIn(0f, 1f)).roundToInt()

private sealed interface DetailsState {
    data object Loading : DetailsState
    data object Unavailable : DetailsState
    data class Loaded(val data: ProjectDetailsData) : DetailsState
}

@Composable
internal fun ProjectDetailsScreen(
    projectId: Long,
    store: ObjectsClientsStore?,
    clients: List<ClientListEntry>,
    contentPadding: PaddingValues,
    systemTopInset: androidx.compose.ui.unit.Dp = 0.dp,
    onHeaderProgress: (Float) -> Unit = {},
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
    val visibleProject = (state as? DetailsState.Loaded)?.data?.project
    val detailsListState = rememberSaveable(projectId, saver = LazyListState.Saver) {
        LazyListState()
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = contentPadding.calculateLeftPadding(layoutDirection)
    val endPadding = contentPadding.calculateRightPadding(layoutDirection)
    val initialTopPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
    val pinnedTopPx = with(density) { systemTopInset.roundToPx() }
    val rowOffsetPx = with(density) { 38.dp.roundToPx() }
    val motion by remember(detailsListState, projectId, initialTopPx, pinnedTopPx, rowOffsetPx) {
        derivedStateOf {
            val item = detailsListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == "project-heading" }
            projectTitleMotion(
                detailsListState.firstVisibleItemIndex,
                item?.offset,
                initialTopPx,
                pinnedTopPx,
                rowOffsetPx
            )
        }
    }
    val progressCallback by rememberUpdatedState(onHeaderProgress)
    LaunchedEffect(projectId, visibleProject?.id) {
        if (visibleProject == null) {
            progressCallback(0f)
        } else {
            snapshotFlow { motion.progress }.collect { progressCallback(it) }
        }
    }
    DisposableEffect(projectId) {
        onDispose { progressCallback(0f) }
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
            // Retain the original header's geometry for natural scrolling.
            // The foreground address and actions are the only rendered/interactive copies.
            item(key = "project-heading") {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Spacer(Modifier.fillMaxWidth().height(40.dp))
                    val project = (state as? DetailsState.Loaded)?.data?.project
                    if (project != null) {
                        Row(
                            Modifier.fillMaxWidth().clearAndSetSemantics { },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                project.address.ifBlank {
                                    project.title.ifBlank { "Адреса не вказана" }
                                },
                                modifier = Modifier.weight(1f).alpha(0f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2
                            )
                            Spacer(Modifier.size(48.dp))
                        }
                        if (project.title.isNotBlank() &&
                            project.title != project.address &&
                            project.address.isNotBlank()
                        ) {
                            Text(
                                project.title,
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
        if (visibleProject != null) {
            ProjectDetailsMovingTitle(
                project = visibleProject,
                motion = motion,
                pinnedTopPx = pinnedTopPx,
                startPadding = startPadding,
                endPadding = endPadding,
                onBack = onBack,
                onEdit = { onEdit(visibleProject) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
        }
    }
}

/**
 * One CMP foreground layer moves the real address and actions between the
 * original separate rows and the pinned top row, without a sticky duplicate.
 * FABs are plain white circles: intentionally NO shadow or elevation.
 */
@Composable
private fun ProjectDetailsMovingTitle(
    project: ProjectListEntry,
    motion: ProjectTitleMotion,
    pinnedTopPx: Int,
    startPadding: androidx.compose.ui.unit.Dp,
    endPadding: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = motion.progress
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val startPx = with(density) { startPadding.roundToPx() }
    val addressRowPx = with(density) { 38.dp.roundToPx() }
    val backRowPx = with(density) { 8.dp.roundToPx() }
    val actionTint = lerp(scheme.primary, Color(0xFF1B252C), p)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val endPx = with(density) { endPadding.roundToPx() }
        val backWidth = (112f - 56f * p).dp
        val backY = interpolatePx(motion.sourceTopPx - backRowPx, pinnedTopPx, p)
        val contentY = interpolatePx(motion.sourceTopPx + addressRowPx, pinnedTopPx, p)
        val editX = with(density) {
            widthPx - endPx - ((52f + 4f * p).dp).roundToPx()
        }
        val addressX = startPx + with(density) { (56f * p).dp.roundToPx() }
        val addressWidth = (maxWidth - startPadding - endPadding -
            (56f + 56f * p).dp).coerceAtLeast(64.dp)
        val addressWidthPx = with(density) { addressWidth.roundToPx() }
        var measuredTitleWidth by remember(project.id) { mutableIntStateOf(0) }

        Box(
            Modifier.offset { IntOffset(startPx, backY) }
                .width(backWidth).height(56.dp)
                .clickable(role = Role.Button, onClickLabel = "До списку", onClick = onBack),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                "← До списку",
                modifier = Modifier.alpha(1f - p),
                color = scheme.primary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
            Box(
                Modifier.align(Alignment.Center)
                    .size(42.dp)
                    .background(Color.White.copy(alpha = p), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = actionTint,
                    modifier = Modifier.size(23.dp).alpha(p)
                )
            }
        }

        Box(
            Modifier.offset { IntOffset(addressX, contentY) }
                .width(addressWidth).height(56.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                project.address.ifBlank {
                    project.title.ifBlank { "Адреса не вказана" }
                },
                modifier = Modifier
                    .offset {
                        IntOffset(
                            ((addressWidthPx - measuredTitleWidth).coerceAtLeast(0) * p / 2f)
                                .roundToInt(), 0
                        )
                    }
                    .onSizeChanged { measuredTitleWidth = it.width },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = (MaterialTheme.typography.titleLarge.fontSize.value - 3f * p).sp
                ),
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            Modifier.offset { IntOffset(editX, contentY) }
                .size(56.dp)
                .clickable(role = Role.Button, onClickLabel = "Редагувати об'єкт", onClick = onEdit),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(42.dp)
                    .background(Color.White.copy(alpha = p), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = actionTint,
                    modifier = Modifier.size(23.dp)
                )
            }
        }
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
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
