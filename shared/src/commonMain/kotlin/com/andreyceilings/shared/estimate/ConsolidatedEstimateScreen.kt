package com.andreyceilings.shared.estimate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.input.CompactOutlinedStringField
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.livepricing.PricingOverflow
import com.andreyceilings.shared.livepricing.QuoteAmounts
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.ContextFeedbackIcon
import com.andreyceilings.shared.ui.ContextFeedbackKind
import com.andreyceilings.shared.ui.CeilingsPopupMenu
import com.andreyceilings.shared.ui.QuoteDiscountModeButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ConsolidatedEstimateScreen(
    store: MeasurementElementsStore,
    session: ConsolidatedComposerSession,
    onClose: () -> Unit,
    onCarry: (String) -> Unit,
    expectedSeriesUuid: String? = null
) {
    val revisionUuid by session.savedRevision.collectAsState()
    val selected by session.selection.collectAsState()
    val title by session.title.collectAsState()
    val discountText by session.discount.collectAsState()
    val discountMode by session.discountMode.collectAsState()
    val basis by session.draftBasis.collectAsState()
    val previewing by session.previewing.collectAsState()
    val saving by session.saving.collectAsState()
    val refresh by store.consolidatedRefresh.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<ConsolidatedRoomCatalog?>(null) }
    var saved by remember(revisionUuid) { mutableStateOf<SavedConsolidatedEstimate?>(null) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var rebaseRequested by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var individual by remember { mutableStateOf<SavedIndividualQuote?>(null) }
    var confirming by remember { mutableStateOf<ConsolidatedStatusConfirmation?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var drift by remember(revisionUuid) { mutableStateOf(emptyMap<String, Boolean?>()) }
    val defaultTitle = stringResource(Res.string.p6a_consolidated)
    val failed = stringResource(Res.string.p6b_failed)
    val projectId = session.projectId

    LaunchedEffect(store, revisionUuid, refresh, reload) {
        try {
            if (revisionUuid != null) {
                saved = checkNotNull(store.savedConsolidated(checkNotNull(revisionUuid),
                    expectedProjectId = projectId.takeIf { expectedSeriesUuid != null }, expectedSeriesUuid = expectedSeriesUuid))
                drift = store.consolidatedDrift(checkNotNull(saved))
            } else {
                val rooms = store.consolidatedRooms(projectId)
                catalog = rooms
                if (session.draftBasis.value == null) {
                    val carried = session.basedOn?.let { checkNotNull(store.savedConsolidated(it, projectId, expectedSeriesUuid)).frozen }
                    val selection = carried?.draft?.selection?.associateBy { it.measurementUuid }
                        ?: rooms.rooms.mapNotNull { room ->
                            room.revisions.singleOrNull { it.status == EstimateWorkflowStatus.APPROVED }?.ref
                        }.associateBy { it.measurementUuid }
                    val discount = carried?.draft?.discount ?: ConsolidatedDiscount(QuoteDiscountMode.PERCENT, 0)
                    val initialTitle = carried?.draft?.title.orEmpty()
                    val head = store.consolidatedHead(projectId)
                    session.selection.value = selection
                    session.title.value = initialTitle
                    session.discount.value = decimalDiscountText(discount.value)
                    session.discountMode.value = discount.mode
                    session.draftBasis.value = ConsolidatedEditBasis(head, selection, initialTitle, discount,
                        carried?.members.orEmpty().associate { it.ref.measurementUuid to it.room })
                } else if (rebaseRequested) {
                    val head = store.consolidatedHead(projectId)
                    session.draftBasis.update { it?.copy(expectedHead = head) }
                    session.operation.value = null
                    rebaseRequested = false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = Res.string.p6b_failed
        }
    }

    val options = remember(catalog) { catalog?.rooms.orEmpty().flatMap { it.revisions }.associateBy { it.ref } }
    val discount = ConsolidatedDiscount.parse(discountText, discountMode)
    val refs = remember(selected, basis, catalog) {
        val carried = if (session.basedOn != null) basis?.initialSelection.orEmpty().keys else emptySet()
        val ordered = carried + catalog?.rooms.orEmpty().map { it.uuid }
        ordered.mapNotNull { selected[it] }
    }
    val cents = refs.mapNotNull { options[it]?.finalCents }
    val amounts = if (discount != null && cents.size == selected.size) {
        try { consolidatedAmounts(cents, discount) } catch (_: PricingOverflow) { null }
    } else null
    val normalizedTitle = validEstimateTitle(title.orEmpty().ifBlank { defaultTitle })
    val ready = basis != null && normalizedTitle != null && discount != null && amounts != null
    val dirty = basis?.let {
        selected != it.initialSelection || title != it.initialTitle || discount != it.initialDiscount
    } == true

    fun exit() {
        if (busy || saving) return
        when {
            individual != null -> individual = null
            revisionUuid == null && dirty -> discard = true
            else -> onClose()
        }
    }

    fun change(operation: suspend () -> Boolean) {
        if (busy || saving) return
        busy = true
        error = null
        scope.launch {
            try { if (!operation()) error = Res.string.p6b_failed }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = Res.string.p6b_failed }
            finally { busy = false }
        }
    }

    fun save() {
        val currentCatalog = catalog ?: return
        if (!ready || busy || saving) return
        val draft = ConsolidatedDraft(checkNotNull(normalizedTitle), checkNotNull(discount), refs)
        val operation = session.operation.value?.takeIf { it.draft == draft }
            ?: ConsolidatedSave(newRecordUuid(), projectId, currentCatalog.projectUuid,
                checkNotNull(basis).expectedHead, session.basedOn, draft)
        session.operation.value = operation
        session.saving.value = true
        error = null
        scope.launch {
            try {
                withContext(NonCancellable) {
                    session.savedRevision.value = store.saveConsolidated(operation).revisionUuid
                }
            } catch (duplicate: DuplicateConsolidatedContent) {
                error = Res.string.p6b_duplicate
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = Res.string.p6b_failed
            } finally {
                session.saving.value = false
            }
        }
    }

    if (previewing && (revisionUuid != null || ready)) {
        val target = revisionUuid
        val labels = ConsolidatedPdfLabels(defaultTitle, stringResource(Res.string.p5d_revision_heading),
            stringResource(Res.string.p6b_project),
            stringResource(Res.string.p6b_address), stringResource(Res.string.p6b_subtotal),
            stringResource(Res.string.p6b_payable), stringResource(Res.string.p6b_discount))
        EstimateDocumentPreviewDialog(
            load = {
                if (target != null) checkNotNull(store.savedConsolidated(target)).document()
                else {
                    val result = store.consolidatedDocument(projectId, checkNotNull(catalog).projectUuid, refs, preserveSelectionOrder = true)
                    val doc = (result as? ConsolidatedDocumentResult.Ready)?.document ?: throw EstimatePreviewLoadFailure(failed)
                    doc.copy(title = normalizedTitle, totalCents = checkNotNull(amounts).finalCents, amounts = amounts)
                }
            },
            layout = { document, presentation, measurer ->
                layoutConsolidatedEstimateDocument(document, presentation, measurer, labels)
            },
            onClose = { session.previewing.value = false },
            editRoomSections = false,
            allowPresentationEditing = false,
            prepareExport = { action ->
                val confirmation = store.verifyConsolidatedExport(checkNotNull(target))
                if (action == EstimateDocumentAction.SHARE && confirmation != null) {
                    { complete -> store.markConsolidatedShareDispatched(confirmation, complete) }
                } else null
            }
        )
        return
    }

    Dialog(onDismissRequest = ::exit, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            // Use the available area while the keyboard is open; keep the footer reachable.
            val dialogHeight = if (keyboardVisible) maxHeight else {
                val fraction = if (individual != null) 0.78f else 0.55f
                (maxHeight * fraction).coerceIn(minOf(300.dp, maxHeight), maxHeight)
            }
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().height(dialogHeight),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxSize()) {
                    val opened = saved
                    val individualQuote = individual
                    val exact = remember(individualQuote) {
                        individualQuote?.let { FrozenEstimateDocument.from(it, null, kotlinx.datetime.TimeZone.UTC) }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = ::exit, enabled = !busy && !saving) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.p5d_back_quotes))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(exact?.title ?: opened?.frozen?.draft?.title
                                    ?: stringResource(if (revisionUuid == null) Res.string.p6b_new else Res.string.p6a_consolidated),
                                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (opened != null || exact != null) {
                                    Text(stringResource(Res.string.p5d_revision_heading,
                                        exact?.publicNumber ?: checkNotNull(opened).frozen.publicNumber,
                                        exact?.version ?: checkNotNull(opened).frozen.version),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (opened != null && exact == null) {
                                if (drift.values.any { it != false }) DriftInfo(drift.values.any { it == null })
                                ConsolidatedStatusControl(opened.status, !busy) { next ->
                                    change {
                                        val proof = store.consolidatedConfirmation(opened.frozen.revisionUuid, next)
                                        if (proof == null || proof.statusVersion != opened.statusVersion) false
                                        else if (next == EstimateWorkflowStatus.APPROVED || opened.status == EstimateWorkflowStatus.APPROVED) {
                                            confirming = proof
                                            true
                                        } else store.setConsolidatedStatus(proof, false)
                                    }
                                }
                            }
                        }
                    }
                    if (busy || saving || (opened == null && revisionUuid != null && error == null)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val errorResource = error
                    if (errorResource != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val description = stringResource(errorResource)
                            ContextFeedbackIcon(description, description, ContextFeedbackKind.ERROR)
                            Text(description, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy && !saving, onClick = {
                                error = null
                                rebaseRequested = revisionUuid == null
                                reload++
                            }) { Text(stringResource(Res.string.p6b_reload)) }
                        }
                    }
                    when {
                        exact != null && individualQuote != null -> ConsolidatedIndividualDetails(exact, individualQuote.frozen.source, Modifier.weight(1f))
                        opened != null -> {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(opened.frozen.members.zip(opened.individuals), key = { it.first.ref.measurementUuid }) { (member, source) ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                        individual = source
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(member.room, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(stringResource(Res.string.p5d_revision_heading, source.frozen.publicNumber, member.individualVersion),
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                        if (member.ref.measurementUuid in drift && drift[member.ref.measurementUuid] != false) {
                                            DriftInfo(drift[member.ref.measurementUuid] == null)
                                        }
                                        Text(measurementMoney(member.finalCents), style = MaterialTheme.typography.labelLarge)
                                    }
                                    HorizontalDivider()
                                }
                            }
                            ConsolidatedFooter(opened.frozen.amounts, discountRow = {
                                val frozenDiscount = opened.frozen.draft.discount
                                Text(stringResource(Res.string.p5d_discount), style = MaterialTheme.typography.labelMedium)
                                Text(decimalDiscountText(frozenDiscount.value), style = MaterialTheme.typography.labelLarge)
                                Text(stringResource(if (frozenDiscount.mode == QuoteDiscountMode.PERCENT)
                                    Res.string.p5d_discount_percent else Res.string.p5d_discount_money),
                                    style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.weight(1f))
                                Text(measurementMoney(opened.frozen.amounts.discountCents), style = MaterialTheme.typography.labelLarge)
                                val help = stringResource(Res.string.p6b_discount_help)
                                ContextFeedbackIcon(help, stringResource(Res.string.p6b_discount), ContextFeedbackKind.HELP)
                            }, leading = {
                                WorkspaceTrash(!busy && opened.status != EstimateWorkflowStatus.APPROVED, Res.string.p5d_delete_revision) {
                                    deleting = true
                                }
                                if (opened.status == EstimateWorkflowStatus.APPROVED) {
                                    val reason = stringResource(Res.string.p5d_delete_protected)
                                    ContextFeedbackIcon(reason, reason)
                                }
                            }) {
                                IconButton(onClick = { session.previewing.value = true }, enabled = !busy, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.PictureAsPdf, stringResource(Res.string.p5d_pdf_preview))
                                }
                                IconButton(onClick = { onCarry(opened.frozen.revisionUuid) }, enabled = !busy, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.ContentCopy, stringResource(Res.string.p5d_new_revision))
                                }
                            }
                        }
                        revisionUuid == null && catalog != null && basis != null -> {
                            val rooms = checkNotNull(catalog).rooms
                            val roomIds = remember(rooms) { rooms.map { it.uuid }.toSet() }
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                item {
                                    CompactOutlinedStringField(title.orEmpty(), { session.title.value = it },
                                        stringResource(Res.string.p5d_title), Modifier.fillMaxWidth(),
                                        enabled = !saving, isError = normalizedTitle == null,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), dense = true)
                                }
                                items(rooms, key = { it.uuid }) { room ->
                                    ConsolidatedRoomRow(room.name, room.revisions, room.uuid in selected, selected[room.uuid],
                                        !saving, onSelect = { ref -> session.selection.update { it + (room.uuid to ref) } },
                                        onRemove = { session.selection.update { it - room.uuid } })
                                }
                                items(selected.keys.filter { it !in roomIds }, key = { it }) { id ->
                                    ConsolidatedRoomRow(basis?.carriedRooms?.get(id).orEmpty(), emptyList(), true,
                                        selected[id], !saving, onSelect = {}, onRemove = { session.selection.update { it - id } })
                                }
                            }
                            ConsolidatedFooter(amounts, discountRow = {
                                DecimalInputField(discountText, { session.discount.value = it },
                                    stringResource(Res.string.p5d_discount), Modifier.width(92.dp),
                                    enabled = !saving, isError = discount == null || (cents.isNotEmpty() &&
                                        cents.size == selected.size && amounts == null), compact = true, dense = true,
                                    selectAllOnFocus = true, zeroWhenBlankOnBlur = true)
                                QuoteDiscountModeButton(discountMode, !saving) { session.discountMode.value = it }
                                Spacer(Modifier.weight(1f))
                                amounts?.let { Text(measurementMoney(it.discountCents), style = MaterialTheme.typography.labelLarge) }
                                val help = stringResource(Res.string.p6b_discount_help)
                                ContextFeedbackIcon(help, stringResource(Res.string.p6b_discount), ContextFeedbackKind.HELP)
                            }, leading = {
                                if (!ready) {
                                    val reason = stringResource(Res.string.p6b_save_blocked)
                                    ContextFeedbackIcon(reason, reason, ContextFeedbackKind.WARNING)
                                }
                            }) {
                                IconButton(enabled = ready && !saving, onClick = { session.previewing.value = true }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.PictureAsPdf, stringResource(Res.string.p5d_pdf_preview))
                                }
                                FilledTonalIconButton(enabled = ready && !saving, onClick = ::save,
                                    modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.Save, stringResource(Res.string.p5d_save_quote))
                                }
                            }
                        }
                        else -> {
                            if (error == null && revisionUuid == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (discard) {
        AlertDialog(onDismissRequest = { discard = false },
            title = { Text(stringResource(Res.string.p5d_discard_title)) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.p5d_discard)) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
    }
    val opened = saved
    if (opened != null && deleting) {
        ConsolidatedDeleteDialog(false, onClose = { deleting = false }) {
            deleting = false
            change {
                val removed = store.deleteConsolidated(projectId, mapOf(opened.frozen.revisionUuid to opened.statusVersion), false)
                if (removed) onClose()
                removed
            }
        }
    }
    confirming?.let { proof ->
        ConsolidatedApprovalDialog(proof, { confirming = null }) { revoke ->
            confirming = null
            change { store.setConsolidatedStatus(proof, revoke) }
        }
    }
}

private fun decimalDiscountText(value: Long): String =
    if (value % 100 == 0L) (value / 100).toString()
    else "${value / 100}.${(value % 100).toString().padStart(2, '0').trimEnd('0')}"

@Composable
private fun ConsolidatedFooter(
    amounts: QuoteAmounts?,
    discountRow: @Composable RowScope.() -> Unit = {},
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp), content = discountRow)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(92.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (amounts != null) Text(measurementMoney(amounts.finalCents),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Row(Modifier.width(92.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    }
}

@Composable
private fun ConsolidatedRoomRow(
    label: String, choices: List<ConsolidatedRevisionOption>, selected: Boolean, ref: ConsolidatedDocumentRef?,
    enabled: Boolean, onSelect: (ConsolidatedDocumentRef) -> Unit, onRemove: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val option = choices.firstOrNull { it.ref == ref }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled && choices.isNotEmpty()) { open = true },
                verticalArrangement = Arrangement.Center) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (choices.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current)
                if (option == null) Text(stringResource(if (choices.isEmpty()) Res.string.p6b_no_individual else Res.string.p6b_select),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(stringResource(Res.string.p5d_revision_heading, option.number, option.version),
                    style = MaterialTheme.typography.labelMedium)
            }
            CeilingsPopupMenu(open, onDismissRequest = { open = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(choice.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(Res.string.p5d_revision_heading, choice.number, choice.version),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }, leadingIcon = {
                        val (icon, tint) = workspaceStatusAppearance(choice.status)
                        Icon(icon, workspaceStatusLabel(choice.status), tint = tint)
                    }, trailingIcon = { Text(measurementMoney(choice.finalCents)) }, onClick = {
                        open = false
                        onSelect(choice.ref)
                    })
                }
            }
        }
        if (selected && option == null) {
            val reason = stringResource(Res.string.p6b_source_missing)
            ContextFeedbackIcon(reason, reason, ContextFeedbackKind.WARNING)
        }
        if (option != null) Text(measurementMoney(option.finalCents), style = MaterialTheme.typography.labelLarge)
        if (selected) IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Default.Delete, stringResource(Res.string.p6b_remove_room))
        } else if (choices.isNotEmpty()) IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.Add, stringResource(Res.string.p6b_select))
        }
    }
    HorizontalDivider()
}

@Composable
private fun ConsolidatedIndividualDetails(document: FrozenEstimateDocument, source: FrozenIndividualSource, modifier: Modifier) {
    val geometry = remember(source.drawingJson) { MeasurementGeometryCodec.read(source.drawingJson) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (document.room != document.title) {
                        Text(document.room, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    } else Spacer(Modifier.weight(1f))
                    Text(document.createdOn, style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant)) {
                    MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
                        Modifier.fillMaxWidth().height(160.dp).padding(6.dp),
                        pointPlacements = source.points, linePlacements = source.lines, areaFeatures = source.areas,
                        fitPointPlacements = true)
                }
            }
            items(document.rows) { row ->
                Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyMedium)
                            Text("${formatElementMilli(row.quantityMilli)} ${row.unit} · ${measurementMoney(row.unitPriceCents)}",
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                        Text(measurementMoney(row.totalCents), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            items(document.omissions) { omission ->
                Text(omission, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Surface(color = colors.surfaceContainer, tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(measurementMoney(document.amounts.finalCents), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun DriftInfo(unavailable: Boolean) {
    val warning = stringResource(if (unavailable) Res.string.p5d_drift_unavailable else Res.string.p5d_drift)
    ContextFeedbackIcon(warning, warning, ContextFeedbackKind.WARNING)
}

@Composable
private fun ConsolidatedStatusControl(status: EstimateWorkflowStatus, enabled: Boolean, onChange: (EstimateWorkflowStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val (icon, tint) = workspaceStatusAppearance(status)
        IconButton(enabled = enabled, onClick = { expanded = true }) {
            Icon(icon, stringResource(Res.string.p5d_status_change, workspaceStatusLabel(status)), tint = tint)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            EstimateWorkflowStatus.entries.filter { it != EstimateWorkflowStatus.DRAFT }.forEach {
                DropdownMenuItem(text = { Text(workspaceStatusLabel(it)) }, enabled = it != status, onClick = {
                    expanded = false
                    onChange(it)
                })
            }
        }
    }
}

@Composable
private fun ConsolidatedApprovalDialog(
    confirmation: ConsolidatedStatusConfirmation, onClose: () -> Unit, onConfirm: (Boolean) -> Unit
) {
    val approving = confirmation.targetStatus == EstimateWorkflowStatus.APPROVED
    val replacing = approving && confirmation.priorApproval != null
    var revoke by remember(confirmation) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text(stringResource(if (approving) Res.string.p6b_approve else Res.string.p6b_deapprove)) },
        text = {
            Column {
                if (approving) Text(stringResource(Res.string.p6b_approve_help))
                if (replacing) Text(stringResource(Res.string.p6b_replace_help))
                if (!approving || replacing) Row(Modifier.fillMaxWidth().clickable { revoke = !revoke },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(revoke, onCheckedChange = { revoke = it })
                    Text(stringResource(if (replacing) Res.string.p6b_revoke_excluded else Res.string.p6b_revoke_included))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(revoke) }) { Text(stringResource(Res.string.p6b_confirm)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.p5d_cancel)) } })
}

@Composable
internal fun ConsolidatedDeleteDialog(entireSeries: Boolean, onClose: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onClose,
        title = { Text(stringResource(if (entireSeries) Res.string.p5d_delete_series_title else Res.string.p5d_delete_revision_title)) },
        dismissButton = { TextButton(onClick = onDelete) {
            Text(stringResource(if (entireSeries) Res.string.p5d_delete_series else Res.string.p5d_delete_revision),
                color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.p5d_cancel)) } })
}
