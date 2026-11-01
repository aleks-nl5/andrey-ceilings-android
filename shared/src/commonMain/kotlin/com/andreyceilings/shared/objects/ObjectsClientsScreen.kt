package com.andreyceilings.shared.objects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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

private enum class SwipeSide { LEFT, RIGHT }
private data class OpenSwipe(val id: String, val side: SwipeSide)

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
    headerVisibleFraction: Float = 1f,
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
    var openSwipe by remember { mutableStateOf<OpenSwipe?>(null) }

    val results = remember(rows, search) {
        ObjectsClientsSearch.suggestions(rows.projects, rows.clients, search)
    }
    val projects = remember(rows.projects, search, selectedWork, selectedPayment) {
        ObjectsClientsSearch.projects(rows.projects, search, selectedWork, selectedPayment)
    }
    val clients = remember(rows.clients, search) {
        ObjectsClientsSearch.clients(rows.clients, search)
    }

    // Hide on *either* direction of vertical movement, including before
    // the app header finishes collapsing. Show only after scrolling stops.
    val fabGestureConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput &&
                    kotlin.math.abs(available.y) > 0.5f) {
                    fabVisible = false
                }
                return Offset.Zero // Do not consume list / app bar gestures.
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput &&
                    kotlin.math.abs(consumed.y + available.y) > 0.5f) {
                    fabVisible = false
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                fabVisible = true
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress
        }.collect { scrolling ->
            if (scrolling) {
                openSwipe = null
            } else {
                // Restore the full-size FAB after a drag/fling is over.
                fabVisible = true
            }
        }
    }
    LaunchedEffect(requestCreateProject, databaseAvailable) {
        if (requestCreateProject && databaseAvailable) {
            selectedTab = ObjectsClientsTab.PROJECTS
            editor = EditorTarget.Project(null)
            onCreateProjectConsumed()
        }
    }


    // Tabs and search stay mounted and sticky below the status bar at all scroll
    // positions. Only the app's main header slides out; the pinned controls
    // shift up to meet the system bar as it collapses.
    val headerSpace = (contentPadding.calculateTopPadding() - 56.dp).coerceAtLeast(0.dp) +
        56.dp * headerVisibleFraction.coerceIn(0f, 1f)
    val layoutDirection = LocalLayoutDirection.current
    val listPadding = PaddingValues(
        start = contentPadding.calculateLeftPadding(layoutDirection),
        end = contentPadding.calculateRightPadding(layoutDirection),
        bottom = contentPadding.calculateBottomPadding()
    )

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(headerSpace))
        // This is intentionally NOT inside LazyColumn and never scrolls away.
        Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    ObjectsTabButton("Об'єкти", selectedTab == ObjectsClientsTab.PROJECTS,
                        Modifier.weight(1f)) {
                        selectedTab = ObjectsClientsTab.PROJECTS
                        openSwipe = null
                    }
                    ObjectsTabButton("Клієнти", selectedTab == ObjectsClientsTab.CLIENTS,
                        Modifier.weight(1f)) {
                        selectedTab = ObjectsClientsTab.CLIENTS
                        openSwipe = null
                    }
                }
                CompactObjectsSearchField(
                    value = search,
                    onChange = { search = it; openSwipe = null },
                    onClear = { search = ""; openSwipe = null }
                )
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.nestedScroll(fabGestureConnection),
                state = listState,
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
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
                                            openSwipe = null
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
                                            openSwipe = null
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
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    CompactObjectsChip("Усі", selectedWork == null) {
                                        selectedWork = null
                                        openSwipe = null
                                    }
                                }
                                items(ProjectWorkStage.entries, key = { it.wireId }) { stage ->
                                    CompactObjectsChip(stage.label, selectedWork == stage.wireId) {
                                        selectedWork = stage.wireId.takeIf { selectedWork != it }
                                        openSwipe = null
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ProjectPaymentStatus.entries, key = { it.wireId }) { payment ->
                                    CompactObjectsChip(payment.label, selectedPayment == payment.wireId) {
                                        selectedPayment = payment.wireId.takeIf { selectedPayment != it }
                                        openSwipe = null
                                    }
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
                            onEdit = { openSwipe = null; editor = EditorTarget.Project(row) },
                            onDelete = { openSwipe = null; deleting = DeletionTarget.Project(row) },
                            onExternalAction = onExternalAction,
                            openSwipe = openSwipe,
                            onSwipeOpen = { openSwipe = it },
                            onSwipeClose = { openSwipe = null })
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
                            onEdit = { openSwipe = null; editor = EditorTarget.Client(row) },
                            onDelete = { openSwipe = null; deleting = DeletionTarget.Client(row) },
                            onDial = onDial,
                            onExternalAction = onExternalAction,
                            openSwipe = openSwipe,
                            onSwipeOpen = { openSwipe = it },
                            onSwipeClose = { openSwipe = null })
                    }
                }
                if (message != null) item(key = "message") {
                    Text(message.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        val showFab = databaseAvailable && !busy && fabVisible && editor == null
        // Always keep the FAB's layout space. Animating AnimatedVisibility's
        // size clipped the floating button against a straight parent boundary.
        // graphicsLayer changes only paint alpha / scale / vertical translation.
        val fabFraction by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (showFab) 1f else 0f,
            animationSpec = tween(durationMillis = 190),
            label = "FAB opacity"
        )
        Box(
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 152.dp)
                .graphicsLayer {
                    alpha = fabFraction
                    scaleX = 0.86f + 0.14f * fabFraction
                    scaleY = 0.86f + 0.14f * fabFraction
                    translationY = (1f - fabFraction) * 14.dp.toPx()
                }
        ) {
            if (fabFraction > 0.001f && databaseAvailable && editor == null) {
                FloatingActionButton(
                    shape = CircleShape,
                    modifier = Modifier.border(
                        1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape
                    ),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    onClick = {
                        if (showFab) {
                            message = null
                            openSwipe = null
                            editor = if (selectedTab == ObjectsClientsTab.PROJECTS)
                                EditorTarget.Project(null) else EditorTarget.Client(null)
                        }
                    }
                ) {
                    Icon(Icons.Default.Add,
                        contentDescription = if (selectedTab == ObjectsClientsTab.PROJECTS)
                            "Створити об'єкт" else "Створити клієнта")
                }
            }
        }
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
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ObjectsTabButton(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(38.dp)
            .background(if (selected) colors.primary else colors.surfaceVariant, shape)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onPrimary else colors.onSurfaceVariant)
    }
}

@Composable
private fun CompactObjectsSearchField(
    value: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier.fillMaxWidth().height(47.dp)
            .background(colors.surface, shape)
            .border(1.dp, colors.outline.copy(alpha = 0.7f), shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null,
            tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text("Пошук об'єктів та клієнтів",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Очистити пошук",
                    tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Real 32dp chips, independent of Material3's 48dp minimum touch layout. */
@Composable
private fun CompactObjectsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.height(32.dp)
            .background(
                if (selected) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = 0.22f),
                CircleShape
            )
            .border(
                1.dp,
                if (selected) scheme.primary.copy(alpha = 0.48f)
                else scheme.outlineVariant,
                CircleShape
            )
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
    }
}

private val ProjectCardShape = RoundedCornerShape(21.dp)

@Composable
private fun WorkStatusMark(status: String) {
    val symbol = when (status) {
        "measurement" -> Icons.Default.Straighten
        "calculation" -> Icons.Default.Calculate
        "completed" -> Icons.Default.TaskAlt
        else -> Icons.Default.HelpOutline
    }
    val color = when (status) {
        "completed" -> Color(0xFF29876B)
        "cancelled" -> MaterialTheme.colorScheme.error
        "rework" -> Color(0xFFBD8A43)
        "in_progress" -> MaterialTheme.colorScheme.primary
        "paused" -> Color(0xFFAB7C30)
        "approved" -> Color(0xFF36866A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (status in setOf("in_progress", "paused", "approved", "cancelled", "rework")) {
        RequestedWorkArtwork(status, color)
    } else {
        Icon(symbol,
            contentDescription = ProjectStatusRules.workLabel(status),
            modifier = Modifier.size(24.dp),
            tint = color)
    }
}

@Composable
private fun PaymentStateTag(status: String) {
    val colors = MaterialTheme.colorScheme
    val fullLabel = ProjectStatusRules.paymentLabel(status)
    val shortLabel = when (status) {
        "unknown" -> "Невизначено"
        "partial" -> "Часткова"
        else -> fullLabel
    }
    val tint = when (status) {
        "paid" -> Color(0xFF238369)
        "partial" -> Color(0xFFAC7D35)
        "unpaid" -> colors.error
        else -> colors.onSurfaceVariant
    }
    val shape = CircleShape
    Box(
        Modifier.background(tint.copy(alpha = 0.11f), shape)
            .border(1.dp, tint.copy(alpha = 0.36f), shape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(shortLabel, color = tint, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun projectCountText(count: Int): String {
    val last100 = count % 100
    val suffix = when {
        last100 in 11..14 -> "об'єктів"
        count % 10 == 1 -> "об'єкт"
        count % 10 in 2..4 -> "об'єкти"
        else -> "об'єктів"
    }
    return "$count $suffix"
}

@Composable
private fun ClientExperienceMark(count: Int) {
    val symbol = when {
        count <= 1 -> Icons.Default.Person
        count <= 5 -> Icons.Default.Group
        else -> Icons.Default.Groups
    }
    val label = when {
        count <= 1 -> "Клієнт"
        count <= 5 -> "Постійний клієнт"
        else -> "Клієнт з багатьма об'єктами"
    }
    Icon(symbol, contentDescription = label, modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ProjectCard(
    item: ProjectListEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExternalAction: (ContactAction, String) -> Unit,
    openSwipe: OpenSwipe?,
    onSwipeOpen: (OpenSwipe) -> Unit,
    onSwipeClose: () -> Unit
) {
    val id = "p-" + item.id
    val actions = if (item.address.isBlank()) emptyList() else listOf(
        "Maps" to ContactAction.GOOGLE_MAPS, "Waze" to ContactAction.WAZE
    )
    SwipeActionsSurface(id, actions, openSwipe, onSwipeOpen, onSwipeClose,
        onDelete = onDelete,
        onAction = { action -> onSwipeClose(); onExternalAction(action, item.address) }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
                .clip(ProjectCardShape)
                .clickable {
                    if (openSwipe?.id == id) onSwipeClose() else onEdit()
                },
            shape = ProjectCardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WorkStatusMark(item.workStatus)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(item.title, modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        PaymentStateTag(item.paymentStatus)
                    }
                    if (item.address.isNotBlank()) Text(item.address,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (item.clientName.isNotBlank()) Text(item.clientName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ProjectStatusRules.workLabel(item.workStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OverflowActions(
                    onDelete = { onSwipeClose(); onDelete() },
                    actions = actions,
                    onAction = { action ->
                        onSwipeClose()
                        onExternalAction(action, item.address)
                    }
                )
            }
        }
    }
}

@Composable
private fun ClientCard(
    item: ClientListEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDial: (String) -> Unit,
    onExternalAction: (ContactAction, String) -> Unit,
    openSwipe: OpenSwipe?,
    onSwipeOpen: (OpenSwipe) -> Unit,
    onSwipeClose: () -> Unit
) {
    val id = "c-" + item.id
    val actions = if (item.phone.isBlank()) emptyList() else listOf(
        "WhatsApp" to ContactAction.WHATSAPP,
        "Telegram" to ContactAction.TELEGRAM,
        "Viber" to ContactAction.VIBER
    )
    SwipeActionsSurface(id, actions, openSwipe, onSwipeOpen, onSwipeClose,
        onDelete = onDelete,
        onAction = { action -> onSwipeClose(); onExternalAction(action, item.phone) }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
                .clip(ProjectCardShape)
                .clickable {
                    if (openSwipe?.id == id) onSwipeClose() else onEdit()
                },
            shape = ProjectCardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClientExperienceMark(item.activeProjectCount)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(item.name, modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Box(
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer,
                                CircleShape)
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(projectCountText(item.activeProjectCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1)
                        }
                    }
                    if (item.phone.isNotBlank()) {
                        TextButton(
                            onClick = { onSwipeClose(); onDial(item.phone) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(item.phone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                OverflowActions(
                    onDelete = { onSwipeClose(); onDelete() },
                    actions = actions,
                    onAction = { action ->
                        onSwipeClose()
                        onExternalAction(action, item.phone)
                    }
                )
            }
        }
    }
}

/**
 * Hold a row at a fixed, partially revealed offset after release.
 * Swipe left exposes external actions, swipe right exposes ONLY a delete button;
 * delete still requires the separate confirmation dialog.
 * Another row's swipe, list scrolling or any other action clears the opened row.
 */
@Composable
private fun SwipeActionsSurface(
    id: String,
    actions: List<Pair<String, ContactAction>>,
    openSwipe: OpenSwipe?,
    onSwipeOpen: (OpenSwipe) -> Unit,
    onSwipeClose: () -> Unit,
    onDelete: () -> Unit,
    onAction: (ContactAction) -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val leftWidth = (actions.size * 72 + 8).dp
    val leftPx = if (actions.isEmpty()) 0f else with(density) { leftWidth.toPx() }
    val rightPx = with(density) { 76.dp.toPx() }
    val reversalTolerancePx = with(density) { 14.dp.toPx() }
    val target = when {
        openSwipe?.id != id -> 0f
        openSwipe?.side == SwipeSide.LEFT -> -leftPx
        else -> rightPx
    }
    var dragging by remember(id) { mutableStateOf(false) }
    var dragged by remember(id) { mutableFloatStateOf(0f) }
    var startOfDrag by remember(id) { mutableFloatStateOf(0f) }
    var farthestPoint by remember(id) { mutableFloatStateOf(0f) }

    // The previous animateFloatAsState animated every finger delta, creating
    // visible lag and a bounce on release. While dragging, draw the exact
    // gesture position. Animate ONLY the final snap to the chosen anchor.
    val settleAnimation = remember(id) { Animatable(0f) }
    val displayed = if (dragging) dragged else settleAnimation.value
    val settleSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    LaunchedEffect(target) {
        if (!dragging) settleAnimation.animateTo(target, animationSpec = settleSpec)
    }

    val dragState = rememberDraggableState { delta ->
        dragged = (dragged + delta).coerceIn(-leftPx, rightPx)
        if (kotlin.math.abs(dragged) > kotlin.math.abs(farthestPoint)) {
            farthestPoint = dragged
        }
    }

    Box(Modifier.fillMaxWidth()) {
        // Keep action buttons mounted behind the card while the tray is opening.
        // Slight spacing and compact themed pictograms distinguish each destination.
        if (actions.isNotEmpty() && (displayed < 0f || target < 0f)) {
            Row(
                Modifier.align(Alignment.CenterEnd)
                    .width(leftWidth)
                    .height(86.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(18.dp))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { (label, action) ->
                    SwipeExternalButton(label, action) { onAction(action) }
                }
            }
        }
        if (displayed > 0f || target > 0f) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .width(76.dp).height(80.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { onSwipeClose(); onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Видалити з підтвердженням",
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(displayed.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        settleAnimation.stop()
                        dragged = settleAnimation.value
                        startOfDrag = dragged
                        farthestPoint = dragged
                        dragging = true
                    },
                    onDragStopped = {
                        // Start exactly at the finger's release position.
                        // The anchored settle uses a critically-damped spring,
                        // without a frame snapping back to the previous anchor.
                        settleAnimation.snapTo(dragged)
                        dragging = false
                        val pulledBack =
                            kotlin.math.abs(farthestPoint) - kotlin.math.abs(dragged) >=
                                reversalTolerancePx
                        val closingExisting = when {
                            startOfDrag < 0f ->
                                dragged > startOfDrag + leftPx * 0.30f
                            startOfDrag > 0f ->
                                dragged < startOfDrag - rightPx * 0.30f
                            else -> false
                        }
                        val settleTo = when {
                            pulledBack || closingExisting -> {
                                onSwipeClose()
                                0f
                            }
                            leftPx > 0f && dragged <= -leftPx * 0.56f -> {
                                onSwipeOpen(OpenSwipe(id, SwipeSide.LEFT))
                                -leftPx
                            }
                            dragged >= rightPx * 0.56f -> {
                                onSwipeOpen(OpenSwipe(id, SwipeSide.RIGHT))
                                rightPx
                            }
                            else -> {
                                onSwipeClose()
                                0f
                            }
                        }
                        settleAnimation.animateTo(settleTo, animationSpec = settleSpec)
                    }
                )
        ) { content() }
    }
}

@Composable
private fun SwipeExternalButton(
    label: String,
    action: ContactAction,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shortLabel = when (action) {
        ContactAction.GOOGLE_MAPS -> "Maps"
        ContactAction.WAZE -> "Waze"
        ContactAction.WHATSAPP -> "WA"
        ContactAction.TELEGRAM -> "TG"
        ContactAction.VIBER -> "Viber"
    }
    Column(
        Modifier.width(66.dp)
            .height(70.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(29.dp)
                .background(colors.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ExternalBrandArtwork(action, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(shortLabel, style = MaterialTheme.typography.labelSmall,
            color = colors.onSurface, maxLines = 1)
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface) {
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
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        onDismissRequest = { showClientMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface) {
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
                        onDismissRequest = { showWorkMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface) {
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
                        onDismissRequest = { showPaymentMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface) {
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
