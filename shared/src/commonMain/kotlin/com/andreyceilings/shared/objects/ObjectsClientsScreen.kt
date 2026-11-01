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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.andreyceilings.shared.domain.ClientAlphabetIndex
import com.andreyceilings.shared.domain.ClientLetterJump
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.GlobalSearchResults
import com.andreyceilings.shared.domain.ObjectsClientsSearch
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectPaymentStatus
import com.andreyceilings.shared.domain.ProjectStatusRules
import com.andreyceilings.shared.domain.ProjectWorkStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }
    fun dismissSearchFocus() {
        if (searchFocused) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            searchFocused = false
        }
    }
    var selectedTab by remember { mutableStateOf(ObjectsClientsTab.PROJECTS) }
    // Each tab mounts a fresh scroll state when selected. The top position is
    // visible immediately (before side effects), and neither tab inherits the
    // other tab's list index, offset or ongoing fling on re-entry.
    val listState = remember(selectedTab) { LazyListState() }
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
    val clientLetters = remember(clients) { ClientAlphabetIndex.markers(clients) }
    // Do not overlap the global search suggestions with a redundant letter rail.
    val showClientLetters = databaseAvailable && selectedTab == ObjectsClientsTab.CLIENTS &&
        search.isBlank() && clientLetters.isNotEmpty() && !searchFocused

    // Keep the on-scroll focus action fresh inside the remembered connection.
    val dismissSearchOnScroll = rememberUpdatedState(newValue = { dismissSearchFocus() })
    // Conflated events do not invalidate Compose on every pointer delta.
    // A single collector owns the reveal; fling and list-idle callbacks must
    // never compete to flip fabVisible back and forth.
    val fabScrollEvents = remember { Channel<Unit>(Channel.CONFLATED) }
    val fabGestureConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput &&
                    kotlin.math.abs(available.y) > 0.5f) {
                    fabVisible = false
                    fabScrollEvents.trySend(Unit)
                    dismissSearchOnScroll.value()
                }
                return Offset.Zero // Never consume list / app bar gestures.
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (kotlin.math.abs(consumed.y + available.y) > 0.5f) {
                    // Include fling/parent-header movement, not only touch input.
                    fabVisible = false
                    fabScrollEvents.trySend(Unit)
                    if (source == NestedScrollSource.UserInput) dismissSearchOnScroll.value()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                // Queue a reveal, never show during a still-running list fling.
                fabScrollEvents.trySend(Unit)
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(fabScrollEvents, listState) {
        fabScrollEvents.receiveAsFlow().collectLatest {
            delay(120L) // Wait for a quiet moment after the final scroll delta.
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            fabVisible = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                fabVisible = false
                fabScrollEvents.trySend(Unit)
                openSwipe = null
                dismissSearchOnScroll.value()
            }
        }
    }
    LaunchedEffect(requestCreateProject, databaseAvailable) {
        if (requestCreateProject && databaseAvailable) {
            dismissSearchFocus()
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
                        dismissSearchFocus()
                        selectedTab = ObjectsClientsTab.PROJECTS
                        openSwipe = null
                    }
                    ObjectsTabButton("Клієнти", selectedTab == ObjectsClientsTab.CLIENTS,
                        Modifier.weight(1f)) {
                        dismissSearchFocus()
                        selectedTab = ObjectsClientsTab.CLIENTS
                        openSwipe = null
                    }
                }
                CompactObjectsSearchField(
                    value = search,
                    onChange = { search = it; openSwipe = null },
                    onClear = { search = ""; openSwipe = null },
                    onFocusChange = { searchFocused = it }
                )
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val suggestionsMaxHeight = maxHeight.coerceAtMost(320.dp)
            LazyColumn(
                modifier = Modifier
                    .padding(end = if (showClientLetters) 16.dp else 0.dp)
                    .nestedScroll(fabGestureConnection)
                    .pointerInput(Unit) {
                        // A tap on unused list space dismisses the keyboard;
                        // children with their own clickable actions do likewise.
                        detectTapGestures(onTap = { dismissSearchFocus() })
                    },
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
                if (selectedTab == ObjectsClientsTab.PROJECTS) {
                    item(key = "filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    CompactObjectsChip("Усі", selectedWork == null) {
                                        dismissSearchFocus()
                                        selectedWork = null
                                        openSwipe = null
                                    }
                                }
                                items(ProjectWorkStage.entries, key = { it.wireId }) { stage ->
                                    CompactObjectsChip(stage.label, selectedWork == stage.wireId) {
                                        dismissSearchFocus()
                                        selectedWork = stage.wireId.takeIf { selectedWork != it }
                                        openSwipe = null
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ProjectPaymentStatus.entries, key = { it.wireId }) { payment ->
                                    CompactObjectsChip(payment.label, selectedPayment == payment.wireId) {
                                        dismissSearchFocus()
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
                            onEdit = { dismissSearchFocus(); openSwipe = null; editor = EditorTarget.Project(row) },
                            onDelete = { dismissSearchFocus(); openSwipe = null; deleting = DeletionTarget.Project(row) },
                            onExternalAction = { action, value ->
                                dismissSearchFocus()
                                onExternalAction(action, value)
                            },
                            openSwipe = openSwipe,
                            onSwipeOpen = { dismissSearchFocus(); openSwipe = it },
                            onSwipeClose = { openSwipe = null },
                            onAnyInteraction = { dismissSearchFocus() })
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
                            onEdit = { dismissSearchFocus(); openSwipe = null; editor = EditorTarget.Client(row) },
                            onDelete = { dismissSearchFocus(); openSwipe = null; deleting = DeletionTarget.Client(row) },
                            onDial = { phone -> dismissSearchFocus(); onDial(phone) },
                            onExternalAction = { action, value ->
                                dismissSearchFocus()
                                onExternalAction(action, value)
                            },
                            openSwipe = openSwipe,
                            onSwipeOpen = { dismissSearchFocus(); openSwipe = it },
                            onSwipeClose = { openSwipe = null },
                            onAnyInteraction = { dismissSearchFocus() })
                    }
                }
                if (message != null) item(key = "message") {
                    Text(message.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        // A phonebook rail exists only for the clients tab. Labels correspond
        // to real first-letter groups in the *rendered* alphabetically sorted
        // list. Leave the lower corner clear for the animated create FAB.
        if (showClientLetters) {
            BoxWithConstraints(
                modifier = Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 8.dp, bottom = 226.dp)
                    .width(24.dp)
                    .zIndex(1f)
            ) {
                val letterHeight = (maxHeight / clientLetters.size).coerceAtMost(18.dp)
                ClientsAlphabetRail(
                    markers = clientLetters,
                    letterHeight = letterHeight,
                    modifier = Modifier.align(Alignment.Center),
                    onSelect = { marker ->
                        dismissSearchFocus()
                        openSwipe = null
                        scope.launch { listState.scrollToItem(marker.listIndex) }
                    }
                )
            }
        }
        // Results overlay is attached to the *pinned* search bar, not to the
        // LazyColumn. It remains immediately below the field no matter how far
        // the projects/clients list was scrolled. Keyboard resize limits height.
        if (databaseAvailable && searchFocused && search.isNotBlank() &&
            editor == null && deleting == null) {
            ObjectsSearchSuggestions(
                results = results,
                activeTab = selectedTab,
                maxPanelHeight = suggestionsMaxHeight,
                onProject = { project ->
                    dismissSearchFocus()
                    selectedTab = ObjectsClientsTab.PROJECTS
                    openSwipe = null
                    editor = EditorTarget.Project(project)
                },
                onClient = { client ->
                    dismissSearchFocus()
                    selectedTab = ObjectsClientsTab.CLIENTS
                    openSwipe = null
                    editor = EditorTarget.Client(client)
                },
                onDismiss = { dismissSearchFocus() },
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f)
            )
        }
        val showFab = databaseAvailable && !busy && fabVisible && editor == null && !searchFocused
        ObjectsClientsCreateFab(
            visible = showFab,
            actionLabel = if (selectedTab == ObjectsClientsTab.PROJECTS)
                "Створити об'єкт" else "Створити клієнта",
            // The 76dp drawing area reserves 10dp around the 56dp circle,
            // including its painted shadow. Preserve the original FAB center.
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 142.dp),
            onClick = {
                dismissSearchFocus()
                message = null
                openSwipe = null
                editor = if (selectedTab == ObjectsClientsTab.PROJECTS)
                    EditorTarget.Project(null) else EditorTarget.Client(null)
            }
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
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("Видалити ${if (isClient) "клієнта" else "об'єкт"}?") },
            text = {
                Text(if (isClient)
                    "«$name». Клієнта з активними об'єктами видалити не можна."
                else "«$name». Об'єкт із замірами видалити не можна: спочатку " +
                    "перенесіть або видаліть заміри. Якщо їх немає, пов'язані " +
                    "кошториси та історичні ревізії будуть приховані в списках.")
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

/**
 * Stable CMP FAB. Its 76dp draw area contains a 56dp tappable circle plus
 * 10dp transparent space for a soft, fully-contained shadow. Native
 * Modifier.shadow/elevation is deliberately avoided: animating its outline
 * revealed polygonal tessellation artifacts on some Android light themes.
 *
 * Only colors fade, with no graphicsLayer, offscreen buffer, changing shadow
 * elevation, parent clipping, resizing, or translation.
 */
@Composable
private fun ObjectsClientsCreateFab(
    visible: Boolean,
    actionLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 190),
        label = "Create FAB fade"
    )
    val colors = MaterialTheme.colorScheme
    // Explicit, cross-platform shadow tint: this Material3 ColorScheme has no shadow slot.
    val shadowColor = Color.Black
    // The navigation bar's stationary elevation casts most of its shadow
    // below the surface. Reproduce that perspective with two bounded CMP
    // gradients instead of animating platform shadow/elevation (which caused
    // polygon artifacts on the light-theme Android renderer).
    //
    // Both gradients fade to transparent *inside* the fixed 76dp drawing area.
    // The 56dp FAB, its hit target and its position are unchanged.
    Box(
        modifier = modifier
            .size(76.dp)
            .drawBehind {
                if (progress > 0f) {
                    val middle = Offset(size.width / 2f, size.height / 2f)
                    val ambientCenter = Offset(middle.x, middle.y + 1.dp.toPx())
                    val ambientRadius = 34.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to shadowColor.copy(alpha = 0.07f * progress),
                            0.72f to shadowColor.copy(alpha = 0.07f * progress),
                            0.84f to shadowColor.copy(alpha = 0.035f * progress),
                            1f to shadowColor.copy(alpha = 0f),
                            center = ambientCenter,
                            radius = ambientRadius
                        ),
                        radius = ambientRadius,
                        center = ambientCenter
                    )

                    // Slightly lower and rightward, as on the floating bottom
                    // navigation bar. Keep the entire penumbra within bounds.
                    val directionalCenter = Offset(
                        middle.x + 1.dp.toPx(),
                        middle.y + 4.dp.toPx()
                    )
                    val directionalRadius = 33.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to shadowColor.copy(alpha = 0.14f * progress),
                            0.68f to shadowColor.copy(alpha = 0.14f * progress),
                            0.80f to shadowColor.copy(alpha = 0.09f * progress),
                            1f to shadowColor.copy(alpha = 0f),
                            center = directionalCenter,
                            radius = directionalRadius
                        ),
                        radius = directionalRadius,
                        center = directionalCenter
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 56dp hit target, a stable clipped circle. The accessibility and
        // click target disappear immediately when scrolling starts.
        Box(
            modifier = Modifier.size(56.dp)
                .background(colors.primaryContainer.copy(alpha = progress), CircleShape)
                .border(1.dp, colors.outline.copy(alpha = 0.45f * progress), CircleShape)
                .clip(CircleShape)
                .then(
                    if (visible) Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = actionLabel,
                        onClick = onClick
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = actionLabel,
                tint = colors.onPrimaryContainer.copy(alpha = progress)
            )
        }
    }
}

/**
 * Tappable and draggable alphabet strip, similar to a contacts app.
 * Touch and drag only in the narrow strip; client cards retain their existing
 * horizontal swipe actions, and the list itself retains vertical scroll.
 */
@Composable
private fun ClientsAlphabetRail(
    markers: List<ClientLetterJump>,
    letterHeight: Dp,
    modifier: Modifier = Modifier,
    onSelect: (ClientLetterJump) -> Unit
) {
    if (markers.isEmpty() || letterHeight <= 0.dp) return
    val currentSelect by rememberUpdatedState(onSelect)
    val slotHeightPx = with(LocalDensity.current) { letterHeight.toPx() }
    Column(
        modifier = modifier
            .width(24.dp)
            .pointerInput(markers, slotHeightPx) {
                var previousIndex = -1
                fun selectAt(y: Float) {
                    val index = (y / slotHeightPx).toInt().coerceIn(0, markers.lastIndex)
                    if (index != previousIndex) {
                        previousIndex = index
                        currentSelect(markers[index])
                    }
                }
                detectDragGestures(
                    onDragStart = { selectAt(it.y) },
                    onDrag = { change, _ ->
                        selectAt(change.position.y)
                        change.consume()
                    },
                    onDragEnd = { previousIndex = -1 },
                    onDragCancel = { previousIndex = -1 }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        markers.forEach { marker ->
            Box(
                modifier = Modifier.fillMaxWidth().height(letterHeight)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Перейти до літери ${marker.letter}",
                        onClick = { currentSelect(marker) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = marker.letter.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (letterHeight < 13.dp) 9.sp else 11.sp
                    ),
                    maxLines = 1
                )
            }
        }
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
    onClear: () -> Unit,
    onFocusChange: (Boolean) -> Unit
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
            modifier = Modifier.weight(1f)
                .onFocusChanged { onFocusChange(it.isFocused) },
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

/**
 * Compact, scrollable cross-entity suggestions. The host places this in the
 * content overlay below the pinned search field. It is not a list item and
 * must not push the project/status list or drift when that list is scrolled.
 */
@Composable
private fun ObjectsSearchSuggestions(
    results: GlobalSearchResults,
    activeTab: ObjectsClientsTab,
    maxPanelHeight: Dp,
    onProject: (ProjectListEntry) -> Unit,
    onClient: (ClientListEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth()
            .padding(top = 3.dp)
            .heightIn(max = maxPanelHeight),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, colors.outlineVariant
        )
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (results.isEmpty) {
                Text(
                    "Нічого не знайдено",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                if (activeTab == ObjectsClientsTab.CLIENTS) {
                    ClientSuggestionsGroup(results.clients, onClient)
                    if (results.clients.isNotEmpty() && results.projects.isNotEmpty()) {
                        SuggestionsDivider()
                    }
                    ProjectSuggestionsGroup(results.projects, onProject)
                } else {
                    ProjectSuggestionsGroup(results.projects, onProject)
                    if (results.projects.isNotEmpty() && results.clients.isNotEmpty()) {
                        SuggestionsDivider()
                    }
                    ClientSuggestionsGroup(results.clients, onClient)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Переглянути список", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ProjectSuggestionsGroup(
    projects: List<ProjectListEntry>,
    onProject: (ProjectListEntry) -> Unit
) {
    if (projects.isEmpty()) return
    Text(
        "Об'єкти",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 9.dp, top = 4.dp, bottom = 2.dp)
    )
    projects.forEach { project ->
        SearchResultRow(
            icon = Icons.Default.Business,
            title = displayProjectAddress(project),
            detail = project.clientName.takeIf { it.isNotBlank() },
            onClick = { onProject(project) }
        )
    }
}

@Composable
private fun ClientSuggestionsGroup(
    clients: List<ClientListEntry>,
    onClient: (ClientListEntry) -> Unit
) {
    if (clients.isEmpty()) return
    Text(
        "Клієнти",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 9.dp, top = 4.dp, bottom = 2.dp)
    )
    clients.forEach { client ->
        SearchResultRow(
            icon = Icons.Default.Person,
            title = client.name,
            detail = client.phone.takeIf { it.isNotBlank() },
            onClick = { onClient(client) }
        )
    }
}

@Composable
private fun SuggestionsDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(vertical = 3.dp, horizontal = 8.dp)
    )
}

@Composable
private fun SearchResultRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String?,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            if (detail != null) {
                Text(
                    detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight, contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        "approved" -> if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
            Color(0xFF82B862) else Color(0xFFB3D78C)
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

/** Display only the address. Historical objects without an address fall back
 * to their old title until their address is provided in the editor.
 */
private fun displayProjectAddress(item: ProjectListEntry): String =
    item.address.ifBlank { item.title.ifBlank { "Адреса не вказана" } }

@Composable
private fun ProjectCard(
    item: ProjectListEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExternalAction: (ContactAction, String) -> Unit,
    openSwipe: OpenSwipe?,
    onSwipeOpen: (OpenSwipe) -> Unit,
    onSwipeClose: () -> Unit,
    onAnyInteraction: () -> Unit
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
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 3.dp, top = 9.dp, bottom = 11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // The workflow icon and address share the header; address
                // may wrap to two lines. The action menu stays at the top right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    WorkStatusMark(item.workStatus)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        displayProjectAddress(item),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    OverflowActions(
                        onMenuOpened = onAnyInteraction,
                        onDelete = { onSwipeClose(); onDelete() },
                        actions = actions,
                        onAction = { action ->
                            onSwipeClose()
                            onExternalAction(action, item.address)
                        }
                    )
                }
                // Keep the client's name at the left edge, and the separate
                // payment indicator on the lower right regardless of name.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 9.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (item.clientName.isNotBlank()) {
                        Text(
                            item.clientName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    PaymentStateTag(item.paymentStatus)
                }
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
    onSwipeClose: () -> Unit,
    onAnyInteraction: () -> Unit
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
                    onMenuOpened = onAnyInteraction,
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
    val leftWidth = (actions.size * 58 + (actions.size - 1).coerceAtLeast(0) * 6 + 8).dp
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
    // Recognizable brand artwork is the label visually; the full app name
    // remains available to accessibility services and click semantics.
    Box(
        modifier = Modifier.size(58.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(15.dp))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(39.dp).background(colors.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ExternalBrandArtwork(action, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun OverflowActions(
    onDelete: () -> Unit,
    actions: List<Pair<String, ContactAction>>,
    onAction: (ContactAction) -> Unit,
    onMenuOpened: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onMenuOpened(); expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Дії із записом")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        ExternalBrandArtwork(
                            action = action,
                            modifier = Modifier.size(22.dp),
                            monochromeTint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = { expanded = false; onAction(action) }
                )
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
                OutlinedTextField(value = address, onValueChange = { address = it },
                    label = { Text("Адреса об'єкта *") }, singleLine = true)
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
                enabled = !busy && address.isNotBlank() && ProjectStatusRules.isKnownWorkStage(work),
                onClick = {
                    onSave(ProjectDraft(address, clientId, work, payment, notes))
                }
            ) { Text("Зберегти") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Скасувати") } }
    )
}
