package com.andreyceilings.shared.objects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

private enum class InlineStatusKind { WORK, PAYMENT }

/**
 * The title itself is rendered once, in the foreground. Its position follows
 * the original LazyColumn slot until it reaches the safe top edge; afterwards
 * it remains there regardless of scroll direction. The 0..1 interpolation
 * drives its background, typography and two small floating buttons.
 */
internal data class ProjectHeaderMotion(val topPx: Int, val progress: Float)

internal fun projectHeaderMotion(
    firstVisibleItemIndex: Int,
    headingOffsetPx: Int?,
    initialTopPx: Int,
    dockedTopPx: Int
): ProjectHeaderMotion {
    val travel = (initialTopPx - dockedTopPx).coerceAtLeast(1)
    if (firstVisibleItemIndex > 0) return ProjectHeaderMotion(dockedTopPx, 1f)
    val source = headingOffsetPx ?: initialTopPx
    // Keep the title inside its defined travel range even when the list
    // bounces past the initial position or is restored with an unusual offset.
    val top = source.coerceIn(dockedTopPx, initialTopPx.coerceAtLeast(dockedTopPx))
    val fraction = ((initialTopPx - top).toFloat() / travel).coerceIn(0f, 1f)
    return ProjectHeaderMotion(top, fraction)
}

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
    val visibleProject = (state as? DetailsState.Loaded)?.data?.project
    val uriHandler = LocalUriHandler.current
    val latestOnHeaderProgress by rememberUpdatedState(onHeaderProgress)
    // Separate scroll state per project, restored only for this project's ID.
    val detailsListState = rememberSaveable(projectId, saver = LazyListState.Saver) {
        LazyListState()
    }
    val direction = LocalLayoutDirection.current
    val horizontalStart = contentPadding.calculateLeftPadding(direction)
    val horizontalEnd = contentPadding.calculateRightPadding(direction)
    val density = LocalDensity.current
    val initialTopPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
    val dockedTopPx = with(density) { systemTopInset.roundToPx() }
    val motion by remember(detailsListState, projectId, initialTopPx, dockedTopPx) {
        derivedStateOf {
            val heading = detailsListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == "project-heading" }
            projectHeaderMotion(
                firstVisibleItemIndex = detailsListState.firstVisibleItemIndex,
                headingOffsetPx = heading?.offset,
                initialTopPx = initialTopPx,
                dockedTopPx = dockedTopPx
            )
        }
    }
    // The host fades out its ordinary AppHeader as this shared heading moves
    // towards the status bar. On reversing, both morph back together.
    LaunchedEffect(projectId, visibleProject?.id) {
        if (visibleProject == null) {
            latestOnHeaderProgress(0f)
        } else {
            snapshotFlow { motion.progress }.collect { latestOnHeaderProgress(it) }
        }
    }
    DisposableEffect(projectId) {
        onDispose { latestOnHeaderProgress(0f) }
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
            // The original slot stays inside the list as the physical scroll
            // anchor. Only the foreground header draws text/buttons: no snap,
            // duplicate toolbar, or two interactive copies during a fling.
            item(key = "project-heading") {
                Spacer(Modifier.fillMaxWidth().height(76.dp))
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
            ProjectDetailsMorphingHeader(
                project = visibleProject,
                progress = motion.progress,
                topPx = motion.topPx,
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


/**
 * A single morphing title replaces both the former regular row and the
 * separate sticky duplicate. No layout animation fights the LazyColumn scroll.
 */
@Composable
private fun ProjectDetailsMorphingHeader(
    project: ProjectListEntry,
    progress: Float,
    topPx: Int,
    topInset: androidx.compose.ui.unit.Dp,
    startPadding: androidx.compose.ui.unit.Dp,
    endPadding: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val pinned = progress.coerceIn(0f, 1f)
    val background = scheme.background
    Box(modifier.fillMaxSize()) {
        // The same edge-to-content gradient direction as AppHeader. The
        // gradient grows as the native top bar fades away, not as a hard cut.
        if (pinned > 0f) {
            Box(
                Modifier.fillMaxWidth()
                    .height(topInset + 98.dp)
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to background.copy(alpha = pinned * 0.98f),
                                0.68f to background.copy(alpha = pinned * 0.94f),
                                1f to background.copy(alpha = 0f)
                            )
                        )
                    }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .offset { IntOffset(0, topPx) }
                .padding(start = startPadding, end = endPadding)
                .height(76.dp)
                .background(
                    scheme.surface.copy(alpha = 1f - pinned),
                    MaterialTheme.shapes.large
                )
                .border(
                    BorderStroke(
                        1.dp,
                        scheme.outlineVariant.copy(alpha = (1f - pinned) * 0.65f)
                    ),
                    MaterialTheme.shapes.large
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ProjectMorphAction(
                icon = Icons.Default.ArrowBack,
                label = "До об'єктів",
                progress = pinned,
                onClick = onBack
            )
            BoxWithConstraints(Modifier.weight(1f)) {
                val maxWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                var textWidthPx by remember(project.id) { mutableIntStateOf(0) }
                val centerShiftPx = (
                    (maxWidthPx - textWidthPx).coerceAtLeast(0) * pinned / 2f
                ).roundToInt()
                Text(
                    project.address.ifBlank {
                        project.title.ifBlank { "Адреса не вказана" }
                    },
                    modifier = Modifier
                        .offset { IntOffset(centerShiftPx, 0) }
                        .onSizeChanged { textWidthPx = it.width },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = (22f - 3f * pinned).sp
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = lerp(scheme.onSurface, scheme.onBackground, pinned),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ProjectMorphAction(
                icon = Icons.Default.Edit,
                label = "Редагувати об'єкт",
                progress = pinned,
                onClick = onEdit
            )
        }
    }
}

/**
 * The familiar '+' FAB shadow, scaled from 56dp to 42dp (25% smaller).
 * 56dp stationary hit area stays accessible. White FAB background appears
 * during docking, while the original icon-only controls remain at the card.
 */
@Composable
private fun ProjectMorphAction(
    icon: ImageVector,
    label: String,
    progress: Float,
    onClick: () -> Unit
) {
    val shadow = Color.Black
    val shade = progress.coerceIn(0f, 1f)
    val tint = lerp(MaterialTheme.colorScheme.primary, Color(0xFF182328), shade)
    Box(
        Modifier.size(56.dp)
            .drawBehind {
                if (shade > 0f) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val ambient = Offset(center.x, center.y + 1.dp.toPx())
                    val radius = 27.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to shadow.copy(alpha = shade * 0.07f),
                            0.72f to shadow.copy(alpha = shade * 0.07f),
                            0.84f to shadow.copy(alpha = shade * 0.035f),
                            1f to Color.Transparent,
                            center = ambient,
                            radius = radius
                        ),
                        center = ambient, radius = radius
                    )
                    val directional = Offset(center.x + 1.dp.toPx(), center.y + 3.dp.toPx())
                    val directionalRadius = 26.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to shadow.copy(alpha = shade * 0.14f),
                            0.68f to shadow.copy(alpha = shade * 0.14f),
                            0.80f to shadow.copy(alpha = shade * 0.09f),
                            1f to Color.Transparent,
                            center = directional,
                            radius = directionalRadius
                        ),
                        center = directional, radius = directionalRadius
                    )
                }
            }
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(42.dp)
                .background(Color.White.copy(alpha = shade), CircleShape)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f * shade),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(23.dp))
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
