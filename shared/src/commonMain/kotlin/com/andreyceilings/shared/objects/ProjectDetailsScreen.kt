package com.andreyceilings.shared.objects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
 * Scroll starts docking; the tween completes even on a short scrollable page.
 * Only returning all the way to the original top reverses the transition.
 */
internal fun projectTitleDocked(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int
): Boolean = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffsetPx > 0

internal data class ProjectTitleMotion(val sourceTopPx: Int, val progress: Float)

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
    // The animation deliberately decouples from how far the content can scroll.
    // A non-zero scroll triggers docking, and 0 returns to the source position.
    val docked by remember(detailsListState, projectId) {
        derivedStateOf {
            projectTitleDocked(
                detailsListState.firstVisibleItemIndex,
                detailsListState.firstVisibleItemScrollOffset
            )
        }
    }
    val titleProgress by animateFloatAsState(
        targetValue = if (docked) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "Project address docking"
    )
    val motion = ProjectTitleMotion(initialTopPx, titleProgress)
    val progressCallback by rememberUpdatedState(onHeaderProgress)
    LaunchedEffect(projectId, visibleProject?.id) {
        if (visibleProject == null) {
            progressCallback(0f)
        } else {
            snapshotFlow { titleProgress }.collect { progressCallback(it) }
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
                            Spacer(Modifier.size(56.dp))
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
 * Bounded radial shadow copied from the CMP Objects/Clients create FAB approach.
 * Radii are 27/24dp instead of the larger "+" FAB's 34/33dp. Both gradients
 * fade to transparent inside the fixed 56dp touch target; unlike native
 * shadow/elevation this does not animate a polygonal outline.
 */
private fun Modifier.projectDetailsFabShadow(progress: Float): Modifier = drawBehind {
    if (progress > 0f) {
        val middle = Offset(size.width / 2f, size.height / 2f)
        val ambientCenter = Offset(middle.x, middle.y + 1.dp.toPx())
        val ambientRadius = 27.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.07f * progress),
                0.72f to Color.Black.copy(alpha = 0.07f * progress),
                0.84f to Color.Black.copy(alpha = 0.035f * progress),
                1f to Color.Transparent,
                center = ambientCenter,
                radius = ambientRadius
            ),
            radius = ambientRadius,
            center = ambientCenter
        )
        val directionalCenter = Offset(middle.x + 1.dp.toPx(), middle.y + 3.dp.toPx())
        val directionalRadius = 24.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.14f * progress),
                0.68f to Color.Black.copy(alpha = 0.14f * progress),
                0.80f to Color.Black.copy(alpha = 0.09f * progress),
                1f to Color.Transparent,
                center = directionalCenter,
                radius = directionalRadius
            ),
            radius = directionalRadius,
            center = directionalCenter
        )
    }
}

/**
 * One CMP foreground layer moves the real address and actions between the
 * original separate rows and the pinned top row, without a sticky duplicate.
 * FAB shadows share the stable two-gradient CMP technique of the list create FAB.
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
    val addressRowPx = with(density) { 42.dp.roundToPx() }
    val dockY = pinnedTopPx + with(density) { 8.dp.roundToPx() }
    val darkTheme = scheme.background.luminance() < 0.4f
    val fabBackground = if (darkTheme) scheme.surfaceContainerHigh else Color.White
    // Same outline role and theme-specific opacity as AppFloatingNavigation.
    val fabOutline = scheme.outline.copy(alpha = (if (darkTheme) 0.46f else 0.27f) * p)
    val actionTint = lerp(scheme.primary, scheme.onSurface, p)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val endPx = with(density) { endPadding.roundToPx() }
        val backY = interpolatePx(motion.sourceTopPx, dockY, p)
        val contentY = interpolatePx(motion.sourceTopPx + addressRowPx, dockY, p)
        val editX = with(density) {
            widthPx - endPx - ((52f + 4f * p).dp).roundToPx()
        }
        val addressX = startPx + with(density) { (56f * p).dp.roundToPx() }
        val addressWidth = (maxWidth - startPadding - endPadding -
            (56f + 56f * p).dp).coerceAtLeast(64.dp)
        val addressWidthPx = with(density) { addressWidth.roundToPx() }
        var measuredTitleWidth by remember(project.id) { mutableIntStateOf(0) }
        val backInteraction = remember(project.id) { MutableInteractionSource() }
        val editInteraction = remember(project.id) { MutableInteractionSource() }

        // Visual fade and pointer shield share exactly the docked header bounds:
        // status-bar safe area + 8dp offset + 72dp (two-line title). No 36dp
        // extra hit area over the cards below. The shield only activates once
        // the pinned title has practically reached its final position.
        if (p > 0.95f) {
            Box(
                Modifier.fillMaxWidth()
                    .height(with(density) { dockY.toDp() } + 72.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                scheme.surface.copy(alpha = 0.50f * p),
                                scheme.surface.copy(alpha = 0f)
                            )
                        )
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            )
        }

        // 56dp hit target, 42dp painted circle when docked. At rest the
        // button is only an arrow; edit is aligned with it on the same row.
        Box(
            Modifier.offset { IntOffset(startPx, backY) }
                .size(56.dp)
                .projectDetailsFabShadow(p)
                .clip(CircleShape)
                .clickable(
                    interactionSource = backInteraction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "До списку",
                    onClick = onBack
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(42.dp)
                    .background(fabBackground.copy(alpha = p), CircleShape)
                    .border(1.dp, fabOutline, CircleShape)
                    .clip(CircleShape)
                    .indication(editInteraction, ripple(bounded = true)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = actionTint,
                    modifier = Modifier.size(23.dp)
                )
            }
        }

        Box(
            Modifier.offset { IntOffset(addressX, contentY) }
                .width(addressWidth).height(72.dp),
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
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            Modifier.offset { IntOffset(editX, backY) }
                .size(56.dp)
                .projectDetailsFabShadow(p)
                .clip(CircleShape)
                .clickable(
                    interactionSource = editInteraction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Редагувати об'єкт",
                    onClick = onEdit
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(42.dp)
                    .background(fabBackground.copy(alpha = p), CircleShape)
                    .border(1.dp, fabOutline, CircleShape),
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
