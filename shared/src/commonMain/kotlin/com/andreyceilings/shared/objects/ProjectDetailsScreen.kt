package com.andreyceilings.shared.objects

import com.andreyceilings.shared.ui.CeilingsPopupMenu
import com.andreyceilings.shared.calendar.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.ui.CeilingsScreenScaffold
import com.andreyceilings.shared.ui.CeilingsPinnedContent
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.ui.ceilingsFloatingOutlineAlpha
import com.andreyceilings.shared.theme.CeilingsEdge
import com.andreyceilings.shared.theme.CeilingsEdgeGradient
import com.andreyceilings.shared.measurement.formatMeasurementAreaCompact
import com.andreyceilings.shared.measurement.formatMeasurementLengthCompact
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.resources.p5d_retry
import com.andreyceilings.shared.resources.p5d_unknown
import com.andreyceilings.shared.resources.p6a_add_ceiling
import com.andreyceilings.shared.resources.p6a_delete_swipe
import com.andreyceilings.shared.resources.p6a_delete_ceiling
import com.andreyceilings.shared.resources.p6a_delete_ceiling_confirm
import com.andreyceilings.shared.resources.p6a_delete_ceiling_approved
import com.andreyceilings.shared.resources.p6a_delete_ceiling_failed
import com.andreyceilings.shared.resources.p5d_cancel
import com.andreyceilings.shared.measurement.CeilingHasApprovedQuote
import com.andreyceilings.shared.resources.p6a_approved_total
import com.andreyceilings.shared.resources.p6a_call_client
import com.andreyceilings.shared.resources.p6a_call_unavailable
import com.andreyceilings.shared.resources.p6a_ceilings_count
import com.andreyceilings.shared.resources.p6a_client
import com.andreyceilings.shared.resources.p6a_consolidated
import com.andreyceilings.shared.resources.p6a_email_client
import com.andreyceilings.shared.resources.p6a_email_unavailable
import com.andreyceilings.shared.resources.p6a_finance_loading
import com.andreyceilings.shared.resources.p6a_finance_unavailable
import com.andreyceilings.shared.resources.p6a_metrics_unavailable
import com.andreyceilings.shared.resources.p6a_no_approved_quotes
import com.andreyceilings.shared.resources.p6a_no_ceilings
import com.andreyceilings.shared.resources.p6a_no_client
import com.andreyceilings.shared.resources.p6a_open_client
import org.jetbrains.compose.resources.stringResource
import com.andreyceilings.shared.resources.p6c_open_ceilings
import androidx.compose.material.icons.filled.Search
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.estimate.ConsolidatedEstimateScreen
import com.andreyceilings.shared.estimate.ProjectConsolidatedEstimates
import com.andreyceilings.shared.domain.ProjectStatusRules
import com.andreyceilings.shared.domain.ProjectWorkStage
import com.andreyceilings.shared.domain.ProjectPaymentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
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

/**
 * Decide the downward settle from the STATUS CARD's visible position, not
 * firstVisibleItemIndex: that index can still be 1 (or more) even when the
 * status card has almost returned to the expanded heading.
 *
 * The actual at-rest card offset is captured from LazyList layout (it handles
 * one- and two-line addresses). Snap once it is within the last 70% of the
 * heading travel: this corresponds to >=30% of the return already completed.
 */
internal fun projectDetailsShouldSettleFromStatus(
    statusCardTopPx: Int?,
    statusCardAtRestTopPx: Int?,
    headerTravelPx: Int,
    returningDownward: Boolean,
    fingerReleased: Boolean,
    listIsAtTop: Boolean,
    restoredFraction: Float = 0.30f
): Boolean {
    val current = statusCardTopPx ?: return false
    val atRest = statusCardAtRestTopPx ?: return false
    if (!returningDownward || !fingerReleased || listIsAtTop ||
        headerTravelPx <= 0 || current >= atRest) return false
    val remainingThresholdPx = (headerTravelPx *
        (1f - restoredFraction.coerceIn(0f, 1f))).roundToInt()
    return current >= atRest - remainingThresholdPx
}

/** In the docked state, center the one/two-line address on the FAB's 56dp touch-row center. */
internal fun projectAddressVerticalOffsetPx(
    progress: Float,
    fabTouchHeightPx: Int,
    measuredAddressHeightPx: Int
): Int = ((fabTouchHeightPx - measuredAddressHeightPx) *
    progress.coerceIn(0f, 1f) / 2f).roundToInt()

internal data class ProjectTitleMotion(val sourceTopPx: Int, val progress: Float)

private fun interpolatePx(from: Int, to: Int, progress: Float): Int =
    (from + (to - from) * progress.coerceIn(0f, 1f)).roundToInt()

private sealed interface DetailsState {
    data object Loading : DetailsState
    data object Unavailable : DetailsState
    data class Loaded(val data: ProjectDetailsData) : DetailsState
}

private sealed interface ProjectFinanceState {
    data object Loading : ProjectFinanceState
    data object Unavailable : ProjectFinanceState
    data class Loaded(val summary: ProjectQuoteSummary) : ProjectFinanceState
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
    onDial: (String) -> Unit,
    onOpenClient: (ClientListEntry) -> Unit,
    active: Boolean = true,
    onOpenMeasurement: (Long) -> Unit = {},
    onCreateMeasurement: () -> Unit = {},
    onOpenCeilingRegistry: (() -> Unit)? = null,
    swipeState: CardSwipeState = remember { CardSwipeState() },
    onCalendarEdit: ((CalendarEditorRoute) -> Unit)? = null,
    onProjectCalendar: ((String) -> Unit)? = null,
) {
    val flow = remember(store, projectId) {
        store?.observeProjectDetails(projectId)?.map<ProjectDetailsData, DetailsState> { DetailsState.Loaded(it) }
            ?.catch { emit(DetailsState.Unavailable) }
    }
    val state = key(store, projectId) {
        flow?.collectAsState(initial = store?.cachedProjectDetails(projectId)?.let { DetailsState.Loaded(it) }
            ?: DetailsState.Loading)?.value ?: DetailsState.Unavailable
    }
    val uriHandler = LocalUriHandler.current
    val visibleProject = (state as? DetailsState.Loaded)?.data?.project
    val elements = store?.measurementDetails?.elements
    val composer = elements?.consolidatedSession?.collectAsState()?.value
    LaunchedEffect(active, composer, projectId, visibleProject?.id) {
        if (elements != null && composer != null) {
            val belongsHere = composer.projectId == projectId
            if ((active && !belongsHere) ||
                (belongsHere && (!active || state is DetailsState.Loaded && visibleProject == null))) {
                elements.closeConsolidatedComposer(composer)
            }
        }
    }
    if (active && elements != null && visibleProject != null && composer?.projectId == projectId) {
        key(composer) {
            ConsolidatedEstimateScreen(elements, composer, onClose = {
                val origin = composer.basedOn
                if (origin != null && composer.savedRevision.value == null) {
                    elements.openConsolidatedComposer(projectId, revisionUuid = origin)
                } else elements.closeConsolidatedComposer(composer)
            }, onCarry = { elements.openConsolidatedComposer(projectId, basedOn = it) })
        }
    }
    val detailsListState = rememberSaveable(projectId, saver = LazyListState.Saver) {
        LazyListState()
    }
    var openCeilingSwipe by swipeState.opened
    var deletingCeiling by remember(store, projectId) { mutableStateOf<ProjectMeasurement?>(null) }
    var deletingBusy by remember(store, projectId) { mutableStateOf(false) }
    var deleteError by remember(store, projectId) { mutableStateOf<String?>(null) }
    LaunchedEffect(detailsListState, active, composer) {
        openCeilingSwipe = null
        snapshotFlow { detailsListState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) openCeilingSwipe = null
        }
    }
    var financeRetry by remember(store, projectId) { mutableIntStateOf(0) }
    val finance = if (visibleProject == null) ProjectFinanceState.Loading else key(store, projectId, active, financeRetry) {
        val financeFlow = remember(store, projectId, active, financeRetry) {
            store?.observeProjectQuoteSummary(projectId)
                ?.map<ProjectQuoteSummary?, ProjectFinanceState> {
                    if (it == null) ProjectFinanceState.Unavailable else ProjectFinanceState.Loaded(it)
                }?.retryWhen { cause, attempt ->
                    if (cause is CancellationException) throw cause
                    if (attempt >= 2) false else {
                        emit(ProjectFinanceState.Unavailable)
                        delay(500L * (attempt + 1))
                        true
                    }
                }?.catch { emit(ProjectFinanceState.Unavailable) }
                ?: flowOf(ProjectFinanceState.Unavailable)
        }
        val cached = store?.cachedProjectFinance(projectId)
        financeFlow.collectAsState(initial = if (cached == null) ProjectFinanceState.Loading
            else cached.second?.let { ProjectFinanceState.Loaded(it) } ?: ProjectFinanceState.Unavailable).value
    }
    val visibleCeilings by remember(detailsListState) {
        derivedStateOf {
            val layout = detailsListState.layoutInfo
            layout.visibleItemsInfo.asSequence()
                .filter { it.size > 0 && it.offset < layout.viewportEndOffset &&
                    it.offset + it.size > layout.viewportStartOffset }
                .mapNotNull { it.key as? Long }
                .toSet()
        }
    }
    val measurements = (state as? DetailsState.Loaded)?.data?.measurements.orEmpty()
    val visibleContours = remember(projectId, measurements, visibleCeilings, active, composer) {
        if (!active || composer != null) emptyList() else measurements.asSequence()
            .filter { it.id in visibleCeilings }.map { it.uuid }.take(40).toList()
    }
    val contourFlow = remember(store, projectId, visibleContours) {
        store?.observeVisibleCeilingContours(visibleContours, projectId)?.catch { emit(null) }
    }
    var contours by remember(store, projectId) { mutableStateOf(store?.cachedContours().orEmpty()) }
    LaunchedEffect(contourFlow, visibleContours) {
        if (visibleContours.isNotEmpty()) contourFlow?.collect { next ->
            if (next != null) {
                contours = (contours - visibleContours.toSet() + next).entries.toList().takeLast(160).associate { it.key to it.value }
            } else contours = emptyMap()
        }
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = contentPadding.calculateLeftPadding(layoutDirection)
    val endPadding = contentPadding.calculateRightPadding(layoutDirection)
    val initialTopPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
    val pinnedTopPx = with(density) { systemTopInset.roundToPx() }
    val snapTravelPx = initialTopPx +
        with(density) { 42.dp.roundToPx() } -
        (pinnedTopPx + with(density) { 8.dp.roundToPx() })
    val settleScope = rememberCoroutineScope()
    // A pending settle is cancelled as soon as another finger touches the
    // list. Holding, dragging upward or merely tapping never triggers it.
    var settleJob by remember(projectId) { mutableStateOf<Job?>(null) }
    LaunchedEffect(active) {
        if (!active) { settleJob?.cancel(); settleJob = null }
    }
    // Record the real unscrolled status-card position once its SQLDelight
    // data has loaded. It naturally includes the address wrapping height.
    var statusCardAtRestTopPx by remember(projectId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(projectId, detailsListState, visibleProject?.id) {
        snapshotFlow {
            if (detailsListState.firstVisibleItemIndex != 0 ||
                detailsListState.firstVisibleItemScrollOffset != 0
            ) null else detailsListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == "status" }?.offset
        }.collect { top ->
            if (top != null) statusCardAtRestTopPx = top
        }
    }
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
    LaunchedEffect(projectId, visibleProject?.id, active) {
        if (!active) return@LaunchedEffect
        if (visibleProject == null) {
            progressCallback(0f)
        } else {
            snapshotFlow { titleProgress }.collect { progressCallback(it) }
        }
    }
    DisposableEffect(projectId) {
        onDispose { progressCallback(0f) }
    }
    var statusError by remember(projectId) { mutableStateOf<String?>(null) }
    var updatingStatus by remember(projectId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val approvedDeleteError = stringResource(Res.string.p6a_delete_ceiling_approved)
    val generalDeleteError = stringResource(Res.string.p6a_delete_ceiling_failed)
    deletingCeiling?.let { target ->
        var calendarImpact by remember(target) { mutableStateOf<CalendarDeletionImpact?>(null) }
        AlertDialog(
            onDismissRequest = { if (!deletingBusy) deletingCeiling = null },
            title = { Text(stringResource(Res.string.p6a_delete_ceiling)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.p6a_delete_ceiling_confirm, target.name))
                    if (calendarImpact != null) Text(stringResource(Res.string.work_deletion_ceiling))
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(enabled = !deletingBusy, onClick = {
                    deletingBusy = true
                    deleteError = null
                    scope.launch {
                        try {
                            checkNotNull(store).deleteCeiling(projectId, target.id, target.uuid, calendarImpact)
                            deletingCeiling = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (confirmation: CalendarDeletionConfirmationRequired) { calendarImpact = confirmation.impact }
                        catch (_: CeilingHasApprovedQuote) {
                            deleteError = approvedDeleteError
                        } catch (_: Exception) {
                            deleteError = generalDeleteError
                        } finally {
                            deletingBusy = false
                        }
                    }
                }) { Text(stringResource(Res.string.p6a_delete_ceiling), color = MaterialTheme.colorScheme.error) }
            },
            confirmButton = {
                TextButton(enabled = !deletingBusy, onClick = { deletingCeiling = null }) {
                    Text(stringResource(Res.string.p5d_cancel))
                }
            }
        )
    }

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
    CeilingsScreenScaffold(
        contentPadding = contentPadding,
        windowInsets = WindowInsets(0, 0, 0, 0),
        topGradient = {}, // Moving title owns its fade; host owns the at-rest header.
        bottomGradient = false,
        overlay = {
            if (visibleProject != null) ProjectDetailsMovingTitle(
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
    ) { listPadding ->
        LazyColumn(
            modifier = Modifier.pointerInput(
                projectId, detailsListState, snapTravelPx, statusCardAtRestTopPx
            ) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    settleJob?.cancel()
                    settleJob = null
                    var returningDownward = false
                    var fingerIsDown: Boolean
                    do {
                        // Inspect physical pointer motion even when LazyColumn
                        // consumes the delta. This distinguishes a downward
                        // return from an upward fling or a programmatic scroll.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            val deltaY = change.position.y - change.previousPosition.y
                            if (deltaY > 1f) returningDownward = true
                            else if (deltaY < -1f) returningDownward = false
                        }
                        fingerIsDown = event.changes.any { it.pressed }
                    } while (fingerIsDown)
                    if (returningDownward) {
                        settleJob = settleScope.launch {
                            // Let a real release/fling finish before measuring
                            // the restored heading position.
                            delay(100L)
                            snapshotFlow { detailsListState.isScrollInProgress }
                                .first { !it }
                            val cardTop = detailsListState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == "status" }?.offset
                            if (projectDetailsShouldSettleFromStatus(
                                statusCardTopPx = cardTop,
                                statusCardAtRestTopPx = statusCardAtRestTopPx,
                                headerTravelPx = snapTravelPx,
                                returningDownward = true,
                                fingerReleased = true,
                                listIsAtTop = detailsListState.firstVisibleItemIndex == 0 &&
                                    detailsListState.firstVisibleItemScrollOffset == 0
                            )) {
                                detailsListState.animateScrollToItem(0)
                            }
                        }
                    }
                }
            },
            state = detailsListState,
            contentPadding = listPadding,
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
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // Reserve the exact natural one/two-line text height.
                            // Equal width/typography in the moving overlay prevents
                            // the first status card from touching a wrapped address.
                            Text(
                                project.address.ifBlank {
                                    project.title.ifBlank { "Адреса не вказана" }
                                },
                                modifier = Modifier.weight(1f).alpha(0f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Start,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Width only: a 56dp-height spacer would enlarge
                            // the one-line case and break the equal card gap.
                            Spacer(Modifier.width(56.dp))
                        }
                        if (project.title.isNotBlank() &&
                            project.title != project.address &&
                            project.address.isNotBlank()
                        ) {
                            SelectionContainer { Text(
                                project.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ) }
                        }
                        // Fixed visual gap below either one- or two-line address.
                        // The heading item also has the shared 8dp list gap.
                        Spacer(Modifier.height(12.dp))
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
                        item(key = "project-summary") {
                            ProjectDetailsSummary(state.data.measurements, finance) { financeRetry++ }
                        }
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
                        item(key = "client") {
                            val client = clients.firstOrNull { it.id == project.clientId }
                            ProjectDetailsClient(client, project.clientName, onOpenClient, onDial) {
                                uriHandler.openUri("mailto:${it.trim()}")
                            }
                        }
                        if (project.notes.isNotBlank()) item(key = "notes") {
                            DetailsCard("Примітки") { SelectionContainer { Text(project.notes, style = MaterialTheme.typography.bodyMedium) } }
                        }
                        if (store != null && onCalendarEdit != null && onProjectCalendar != null) item(key = "calendar-works") {
                            val uuid by produceState<String?>(null, store, projectId) {
                                value = store.calendarProjectUuid(projectId)
                            }
                            uuid?.let { projectUuid ->
                                ProjectCalendarWorks(store.calendar, projectUuid,
                                    (finance as? ProjectFinanceState.Loaded)?.summary?.hasApprovedQuote == true,
                                    onCalendarEdit, onProjectCalendar, swipeState)
                            }
                        }
                        item(key = "measurements-heading") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.p6a_ceilings_count, state.data.measurements.size),
                                    modifier = Modifier.weight(1f).padding(top = 4.dp, bottom = 2.dp),
                                    style = MaterialTheme.typography.titleMedium)
                                if (onOpenCeilingRegistry != null) IconButton(onClick = onOpenCeilingRegistry, enabled = active) {
                                    Icon(Icons.Default.Search, stringResource(Res.string.p6c_open_ceilings))
                                }
                                IconButton(onClick = onCreateMeasurement, enabled = active) {
                                    Icon(Icons.Default.Add, stringResource(Res.string.p6a_add_ceiling))
                                }
                            }
                        }
                        if (state.data.measurements.isEmpty()) item {
                            Text(stringResource(Res.string.p6a_no_ceilings),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(state.data.measurements, key = { it.id }) { measurement ->
                            val quotes = (finance as? ProjectFinanceState.Loaded)?.summary
                            val swipeId = "ceiling-${measurement.uuid}"
                            val rowWasOpen = openCeilingSwipe?.id == swipeId
                            val deleteLabel = stringResource(Res.string.p6a_delete_swipe)
                            val requestDelete = {
                                openCeilingSwipe = null
                                deleteError = null
                                deletingCeiling = measurement
                            }
                            SwipeActionsSurface(
                                id = swipeId,
                                actions = emptyList(),
                                openSwipe = openCeilingSwipe,
                                onSwipeOpen = { openCeilingSwipe = it },
                                onSwipeClose = { openCeilingSwipe = null },
                                onDelete = requestDelete,
                                onAction = {},
                                modifier = Modifier.clipToBounds().semantics {
                                    customActions = listOf(CustomAccessibilityAction(deleteLabel) {
                                        requestDelete()
                                        true
                                    })
                                },
                                deleteTrayHeight = 70.dp
                            ) {
                                CompactCeilingCard(
                                    measurement, contours[measurement.uuid], quotes?.ceilings?.get(measurement.id),
                                    quoteUnavailable = finance == ProjectFinanceState.Unavailable ||
                                        measurement.id in quotes?.unavailableCeilings.orEmpty(),
                                    quoteLoading = finance == ProjectFinanceState.Loading
                                ) {
                                    if (rowWasOpen) {
                                        openCeilingSwipe = null
                                    } else {
                                        openCeilingSwipe = null
                                        settleJob?.cancel()
                                        settleJob = null
                                        onOpenMeasurement(measurement.id)
                                    }
                                }
                            }
                        }
                        if (elements != null && store != null) item(key = "consolidated-estimate") {
                            ProjectConsolidatedEstimates(store, elements, projectId)
                        }
                    }
                }
            }
        }
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
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(Res.string.work_copy_info)
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val startPx = with(density) { startPadding.roundToPx() }
    val addressRowPx = with(density) { 42.dp.roundToPx() }
    val dockY = pinnedTopPx + with(density) { 8.dp.roundToPx() }
    val darkTheme = scheme.background.luminance() < 0.4f
    val fabBackground = if (darkTheme) scheme.surfaceContainerHigh else Color.White
    // Same outline role and theme-specific opacity as AppFloatingNavigation.
    val fabOutlineAlpha = ceilingsFloatingOutlineAlpha(darkTheme)
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
        val fabTouchHeightPx = with(density) { 56.dp.roundToPx() }
        var measuredTitleWidth by remember(project.id) { mutableIntStateOf(0) }
        var measuredTitleHeight by remember(project.id) { mutableIntStateOf(0) }

        // Fade is drawing only. Input protection follows the measured union
        // of the address and actual 56dp action targets, never the fade tail.
        if (p > 0.95f) {
            CeilingsEdgeGradient(CeilingsEdge.TOP,
                Modifier.fillMaxWidth().height(with(density) { dockY.toDp() } + 72.dp),
                intensity = p)
            val addressTop = contentY + projectAddressVerticalOffsetPx(
                p, fabTouchHeightPx, measuredTitleHeight)
            val occupiedTop = minOf(backY, addressTop)
            val occupiedBottom = maxOf(backY + fabTouchHeightPx, addressTop + measuredTitleHeight)
            CeilingsPinnedContent(Modifier.fillMaxWidth()
                .offset { IntOffset(0, occupiedTop) }) {
                Spacer(Modifier.height(with(density) { (occupiedBottom - occupiedTop).toDp() }))
            }
        }

        CeilingsFab(
            onClick = onBack,
            actionLabel = "До списку",
            modifier = Modifier.offset { IntOffset(startPx, backY) },
            profile = CeilingsFabProfile.COMPACT,
            progress = p,
            containerColor = fabBackground,
            outlineAlpha = fabOutlineAlpha
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = null,
                tint = actionTint, modifier = Modifier.size(23.dp))
        }

        Box(
            Modifier.offset { IntOffset(addressX, contentY) }
                .width(addressWidth).height(72.dp),
            // Left/top at rest; docked address is centered vertically on
            // the two FABs (56dp touch centers), even with two lines.
            contentAlignment = Alignment.TopStart
        ) {
            Text(
                project.address.ifBlank {
                    project.title.ifBlank { "Адреса не вказана" }
                },
                modifier = Modifier
                    .offset {
                        IntOffset(
                            ((addressWidthPx - measuredTitleWidth).coerceAtLeast(0) * p / 2f)
                                .roundToInt(),
                            projectAddressVerticalOffsetPx(
                                p, fabTouchHeightPx, measuredTitleHeight
                            )
                        )
                    }
                    .combinedClickable(onClick = {}, onLongClickLabel = copyLabel, onLongClick = {
                        clipboard.setText(AnnotatedString(project.address.ifBlank { project.title }))
                    })
                    .onSizeChanged {
                        measuredTitleWidth = it.width
                        measuredTitleHeight = it.height
                    },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = (MaterialTheme.typography.titleLarge.fontSize.value - 3f * p).sp
                ),
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }

        CeilingsFab(
            onClick = onEdit,
            actionLabel = "Редагувати об'єкт",
            modifier = Modifier.offset { IntOffset(editX, backY) },
            profile = CeilingsFabProfile.COMPACT,
            progress = p,
            containerColor = fabBackground,
            outlineAlpha = fabOutlineAlpha
        ) {
            Icon(Icons.Default.Edit, contentDescription = null,
                tint = actionTint, modifier = Modifier.size(23.dp))
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
internal fun WorkStagePicker(
    projectId: Long,
    status: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember(projectId) { mutableStateOf(false) }
    val label = localizedWorkStage(status)
    val description = stringResource(Res.string.oc_change_work, label)
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
                        contentDescription = description
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                WorkStatusMark(status)
                Text(
                    label,
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
        CeilingsPopupMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 204.dp)
        ) {
            ProjectWorkStage.entries.forEach { stage ->
                DropdownMenuItem(
                    text = { Text(localizedWorkStage(stage.wireId), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { WorkStatusMark(stage.wireId) },
                    trailingIcon = {
                        if (stage.wireId == status) {
                            Icon(
                                Icons.Default.Check, contentDescription = stringResource(Res.string.oc_current_work),
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
internal fun PaymentStatusPicker(
    projectId: Long,
    status: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember(projectId) { mutableStateOf(false) }
    val tint = paymentStateTint(status)
    val label = localizedPayment(status, compact = true)
    val description = stringResource(Res.string.oc_change_payment, label)
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
                        contentDescription = description
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    label,
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
        CeilingsPopupMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 204.dp)
        ) {
            ProjectPaymentStatus.entries.forEach { payment ->
                DropdownMenuItem(
                    text = { Text(localizedPayment(payment.wireId), style = MaterialTheme.typography.bodyMedium) },
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
                                Icons.Default.Check, contentDescription = stringResource(Res.string.oc_current_payment),
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
private fun ProjectDetailsSummary(ceilings: List<ProjectMeasurement>, finance: ProjectFinanceState, onRetry: () -> Unit) {
    val metrics = remember(ceilings) {
        if (ceilings.any { !it.area.isFinite() || !it.perimeter.isFinite() || it.area < 0 || it.perimeter < 0 }) null
        else (ceilings.sumOf { it.area } to ceilings.sumOf { it.perimeter })
            .takeIf { it.first.isFinite() && it.second.isFinite() }
    }
    val cents = (finance as? ProjectFinanceState.Loaded)?.summary?.approvedCents
    val amount = if (cents != null) compactQuoteAmount(cents) else stringResource(Res.string.p5d_unknown)
    val label = when (finance) {
        ProjectFinanceState.Loading -> stringResource(Res.string.p6a_finance_loading)
        ProjectFinanceState.Unavailable -> stringResource(Res.string.p6a_finance_unavailable)
        is ProjectFinanceState.Loaded -> when {
            finance.summary.approvedUnavailable -> stringResource(Res.string.p6a_finance_unavailable)
            finance.summary.approvedCents == null -> stringResource(Res.string.p6a_no_approved_quotes)
            else -> stringResource(Res.string.p6a_approved_total, amount)
        }
    }
    val summaryStyle = MaterialTheme.typography.bodyLarge
    val summaryIconSize = 18.dp
    CompactMetricsRow(metrics?.first, metrics?.second, textStyle = summaryStyle, iconSize = summaryIconSize) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactMetric(Icons.Default.Payments, amount, label, summaryStyle, summaryIconSize)
            if (finance == ProjectFinanceState.Unavailable) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, stringResource(Res.string.p5d_retry), Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ProjectDetailsClient(
    client: ClientListEntry?,
    clientName: String,
    onOpenClient: (ClientListEntry) -> Unit,
    onDial: (String) -> Unit,
    onEmail: (String) -> Unit
) {
    var error by remember(client) { mutableStateOf<String?>(null) }
    val openClientLabel = stringResource(Res.string.p6a_open_client)
    val nameModifier = if (client == null) Modifier else Modifier
        .heightIn(min = 48.dp)
        .clickable(role = Role.Button, onClickLabel = openClientLabel) { onOpenClient(client) }
    DetailsCard(stringResource(Res.string.p6a_client)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).then(nameModifier), contentAlignment = Alignment.CenterStart) {
                Text(
                    client?.name ?: clientName.ifBlank { stringResource(Res.string.p6a_no_client) },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            client?.phone?.takeIf { it.isNotBlank() }?.let { number ->
                val failure = stringResource(Res.string.p6a_call_unavailable)
                IconButton(onClick = { error = if (runCatching { onDial(number) }.isFailure) failure else null }) {
                    Icon(Icons.Default.Phone, stringResource(Res.string.p6a_call_client))
                }
            }
            client?.email?.takeIf { it.isNotBlank() }?.let { address ->
                val failure = stringResource(Res.string.p6a_email_unavailable)
                IconButton(onClick = { error = if (runCatching { onEmail(address) }.isFailure) failure else null }) {
                    Icon(Icons.Default.Email, stringResource(Res.string.p6a_email_client))
                }
            }
        }
        client?.notes?.takeIf { it.isNotBlank() }?.let {
            SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
