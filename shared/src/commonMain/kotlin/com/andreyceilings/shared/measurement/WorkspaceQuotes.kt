package com.andreyceilings.shared.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.input.CompactOutlinedStringField
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.ContextFeedbackIcon
import com.andreyceilings.shared.ui.ContextFeedbackKind
import com.andreyceilings.shared.ui.QuoteDiscountModeButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal class WorkspaceQuoteSession {
    var opened by mutableStateOf<SavedIndividualQuote?>(null)
    var presenter by mutableStateOf<TransientQuotePresenter?>(null)
    var measurementVersion by mutableStateOf<Long?>(null)
    var expanded by mutableStateOf(false)
    var discardPending by mutableStateOf(false)
    var refresh by mutableIntStateOf(0)

    fun closeView() {
        opened = null
        expanded = false
    }

    fun closeDraft(store: MeasurementElementsStore?) {
        presenter?.let { store?.releaseQuote(it) }
        presenter = null
        measurementVersion = null
        discardPending = false
        expanded = false
    }
}

internal fun quoteSaveFailureResource(reason: IndividualEstimateSaveFailure.Reason?) = when (reason) {
    IndividualEstimateSaveFailure.Reason.DUPLICATE_CONTENT -> Res.string.p5d_duplicate_quote
    IndividualEstimateSaveFailure.Reason.SERIES_EXISTS -> Res.string.p5d_series_blocked
    else -> null
}

@Composable
internal fun WorkspaceQuotes(
    store: MeasurementElementsStore?,
    session: WorkspaceQuoteSession,
    edit: MeasurementEditState,
    modifier: Modifier,
    bottomInset: Dp,
    currentSource: Boolean,
    pricingBasis: DraftPricingBasis?,
    onStart: (SavedIndividualQuote?) -> Unit,
    onRefresh: () -> Unit
) {
    val route = remember(edit.original) {
        edit.original.takeUnless { edit.creating }?.let { ElementRoute(it.draft.projectId, it.measurementId, it.draft.recordUuid) }
    }
    var rows by remember(store, route) { mutableStateOf<List<SavedIndividualQuote>?>(null) }
    var error by remember(store, route) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<List<SavedIndividualQuote>?>(null) }
    var removingSeries by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shareResult = store?.quoteShareResult?.collectAsState()?.value
    LaunchedEffect(store, route, session.refresh, shareResult) {
        if (route == null || store == null) {
            rows = emptyList()
            return@LaunchedEffect
        }
        try {
            rows = store.savedQuotes(route.projectId, route.measurementUuid)
            session.opened?.let { opened -> session.opened = rows?.firstOrNull { it.revisionUuid == opened.revisionUuid } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = true
        }
    }
    val changeLinkedStatus = linkedQuoteStatusChange()
    val presenter = session.presenter
    val opened = session.opened
    val approved = rows.orEmpty().filter { it.status == EstimateWorkflowStatus.APPROVED }
    fun changeStatus(target: SavedIndividualQuote, status: EstimateWorkflowStatus, confirmed: List<SavedIndividualQuote>) {
        if (store == null || busy) return
        busy = true
        error = false
        scope.launch {
            try {
                error = !changeLinkedStatus(store, target, status, confirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
                session.refresh++
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(bottom = bottomInset)) {
        if (error) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.p5d_quote_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { error = false; session.refresh++ }) { Text(stringResource(Res.string.p5d_retry)) }
            }
        }
        if (presenter != null && route != null && store != null) {
            val current = currentSource && !edit.dirty && !edit.conflict && !edit.uncertain && !edit.saving &&
                session.measurementVersion == edit.original.updatedAt
            WorkspaceQuoteComposer(store, session, presenter, route, current, onRefresh, observePricing = false)
        } else if (opened != null && store != null) {
            WorkspaceFrozenQuote(store, session, opened, approved, busy, ::changeStatus, onStart,
                onDelete = {
                    removingSeries = false
                    removing = listOf(opened)
                })
        } else {
            WorkspaceFinancialSummary {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.p5d_quotes), Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge)
                if (approved.size > 1) WorkspaceCostHelp(stringResource(Res.string.p5d_approval_conflict), kind = ContextFeedbackKind.ERROR)
                IconButton(enabled = store != null && !busy && !edit.saving && !edit.checking, onClick = { onStart(null) }) {
                    Icon(Icons.Default.Add, stringResource(Res.string.p5d_new_quote))
                }
            }
            }
            WorkspacePanelDivider()
            if (rows == null && !error) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (rows?.isEmpty() == true) item { Text(stringResource(Res.string.p5d_no_quotes), Modifier.padding(8.dp)) }
                rows.orEmpty().groupBy { it.seriesUuid }.forEach { (series, versions) ->
                    item(key = series) {
                        val head = versions.first()
                        WorkspacePanelDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(head.frozen.title, style = MaterialTheme.typography.titleSmall)
                                Text(head.frozen.publicNumber, style = MaterialTheme.typography.labelSmall)
                            }
                            val eligible = versions.all { it.canDeleteQuote }
                            if (!eligible) WorkspaceCostHelp(stringResource(Res.string.p5d_delete_protected))
                            WorkspaceTrash(eligible && !busy, Res.string.p5d_delete_series) {
                                removingSeries = true
                                removing = versions.toList()
                            }
                        }
                    }
                    itemsIndexed(versions, key = { _, it -> it.revisionUuid }) { index, quote ->
                        WorkspaceListRow(index) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            WorkspaceQuoteStatusIcon(quote, approved, !busy, ::changeStatus)
                            Row(Modifier.weight(1f).defaultMinSize(minHeight = 56.dp)
                                .clickable { session.opened = quote }.padding(start = 4.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (quote.status == EstimateWorkflowStatus.APPROVED) {
                                    val savedBasis = (pricingBasis as? DraftPricingBasis.Saved)?.read
                                    val show = savedBasis?.let { quote.frozen.hasPricingDrift(it) }
                                    if (show == true) WorkspaceCostHelp(stringResource(Res.string.p5d_drift), kind = ContextFeedbackKind.WARNING)
                                    else if (pricingBasis is DraftPricingBasis.Unavailable)
                                        WorkspaceCostHelp(stringResource(Res.string.p5d_drift_unavailable), kind = ContextFeedbackKind.WARNING)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(Res.string.p5d_version, quote.versionNumber), style = MaterialTheme.typography.bodyMedium)
                                    if (quote.frozen.title != versions.first().frozen.title)
                                        Text(quote.frozen.title, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(measurementMoney(quote.frozen.amounts.finalCents), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    removing?.let { versions ->
        WorkspaceQuoteDeleteDialog(versions, removingSeries, { removing = null }) {
            removing = null
            val quote = versions.first()
            if (busy || store == null) return@WorkspaceQuoteDeleteDialog
            val series = removingSeries
            busy = true
            error = false
            scope.launch {
                try {
                    val deleted = if (series) store.deleteQuoteSeries(quote.seriesUuid,
                        versions.associate { it.revisionUuid to it.statusVersion })
                    else store.deleteQuoteRevision(quote.seriesUuid, quote.revisionUuid, quote.statusVersion)
                    error = !deleted
                    if (deleted) session.closeView()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = true
                } finally {
                    busy = false
                    session.refresh++
                }
            }
        }
    }
}

@Composable
internal fun ObserveWorkspaceQuotePricing(
    store: MeasurementElementsStore,
    presenter: TransientQuotePresenter,
    route: ElementRoute,
    current: Boolean
) {
    LaunchedEffect(store, presenter, route, current) {
        var read = presenter.beginRead()
        if (current) store.observeQuotePricing(route).collect { result ->
            if (result == null) read = presenter.beginRead() else presenter.acceptRead(read, result)
        }
    }
}

@Composable
internal fun ColumnScope.WorkspaceQuoteComposer(
    store: MeasurementElementsStore,
    session: WorkspaceQuoteSession,
    presenter: TransientQuotePresenter,
    route: ElementRoute,
    current: Boolean,
    onRefresh: () -> Unit,
    observePricing: Boolean = true
) {
    val save by presenter.saveState.collectAsState()
    val state by presenter.state.collectAsState()
    var partialFingerprint by remember(presenter) { mutableStateOf<String?>(null) }
    var showCarry by remember(presenter) { mutableStateOf(false) }
    var carryError by remember(presenter) { mutableStateOf(false) }
    var quantityEditor by remember(presenter) { mutableStateOf<String?>(null) }
    if (observePricing) ObserveWorkspaceQuotePricing(store, presenter, route, current)
    LaunchedEffect(save.resultSeriesUuid, save.resultRevisionUuid) {
        val series = save.resultSeriesUuid ?: return@LaunchedEffect
        val revision = save.resultRevisionUuid ?: return@LaunchedEffect
        session.opened = store.savedQuote(series, revision)
        session.closeDraft(store)
        session.refresh++
    }
    val enabled = current && !save.saving && save.resultSeriesUuid == null
    val title = save.title.orEmpty()
    val canSubmit = enabled && state.amounts != null && validEstimateTitle(title) != null &&
        (state.preview?.canFinalizeFull == true || state.canAcknowledge)
    WorkspaceFinancialSummary {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = !save.saving, onClick = {
            if (presenter.hasUserChanges) session.discardPending = true else session.closeDraft(store)
        }) {
            Icon(Icons.Default.Close, stringResource(Res.string.p5d_discard), tint = MaterialTheme.colorScheme.error)
        }
        CompactOutlinedStringField(title, presenter::editTitle, stringResource(Res.string.p5d_title),
            enabled = !save.saving, modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 4.dp),
            dense = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
        IconButton(enabled = canSubmit, onClick = {
            if (state.canAcknowledge) partialFingerprint = state.fingerprint
            else store.submitQuote(presenter, route.projectId)
        }) { Icon(Icons.Default.Save, stringResource(Res.string.p5d_save_quote)) }
    }
    }
    save.error?.let {
        Text(stringResource(quoteSaveFailureResource(save.failureReason) ?: Res.string.p5d_quote_failed),
            color = MaterialTheme.colorScheme.error)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (save.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!current) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !save.saving, onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.p5d_update_quote))
            }
            WorkspaceExpand(session)
        }
    } else {
        WorkspaceFinancialSummary {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.p5d_to_pay), style = MaterialTheme.typography.labelSmall)
                        Text(state.amounts?.let { measurementMoney(it.finalCents) } ?: stringResource(Res.string.p5d_unknown),
                            style = MaterialTheme.typography.titleLarge)
                    }
                    WorkspaceExpand(session)
                    QuoteDiscountModeButton(state.discountMode, enabled) { mode ->
                        presenter.editDiscount("0", mode, confirmed = true)
                    }
                    Box(Modifier.widthIn(max = 88.dp).padding(top = 8.dp, bottom = 4.dp)) {
                        DecimalInputField(state.discountText, { presenter.editDiscount(it, confirmed = true) },
                            stringResource(Res.string.p5d_discount), enabled = enabled,
                            compact = true, dense = true, selectAllOnFocus = true, zeroWhenBlankOnBlur = true,
                            isError = QuoteDiscount.parse(state.effectiveDiscountText, state.discountMode) == null ||
                                (!state.discountConfirmed && state.discountText.isNotEmpty()))
                    }
                    if (!state.discountConfirmed) IconButton(enabled = enabled, onClick = presenter::confirmDiscount) {
                        Icon(Icons.Default.Check, stringResource(Res.string.p5d_apply_quantity))
                    }
                }
                state.amounts?.takeIf { it.discountCents > 0 }?.let {
                    Text(stringResource(Res.string.p5d_subtotal, measurementMoney(it.subtotalCents)) + "  " +
                        stringResource(Res.string.p5d_discount_value, measurementMoney(it.discountCents)),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val problem = state.unavailable?.let { workspacePricingProblem(it) }
            ?: state.preview?.fatalProblems?.takeIf { it.isNotEmpty() }?.map { workspacePricingProblem(it.code) }?.joinToString("\n")
        if (problem != null) WorkspaceCostHelp(problem, kind = ContextFeedbackKind.ERROR)
        else if (!canSubmit && !state.loading) {
            val help = when {
                state.preview?.canFinalizeFull == true && state.edits.values.none { !it.confirmed } ->
                    stringResource(Res.string.p5d_discount_invalid)
                else -> stringResource(Res.string.p5d_amount_unavailable)
            }
            WorkspaceCostHelp(help, kind = ContextFeedbackKind.WARNING)
        }
        if (presenter.revisionCarrySource != null) Box {
            IconButton(enabled = enabled && state.source != null, onClick = { showCarry = true }) {
                Icon(Icons.Default.ContentCopy, stringResource(Res.string.p5d_carry))
            }
            DropdownMenu(showCarry, onDismissRequest = { showCarry = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.p5d_carry_selection)) }, onClick = {
                    showCarry = false
                    carryError = !presenter.carrySelection()
                })
                DropdownMenuItem(text = { Text(stringResource(Res.string.p5d_carry_discount)) }, onClick = {
                    showCarry = false
                    carryError = !presenter.carryDiscount()
                })
                presenter.revisionCarrySource.frozen.rows.map { it.line }
                    .filter { it.quantityOrigin == QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE }.forEach { line ->
                        DropdownMenuItem(text = { Text(stringResource(Res.string.p5d_line_problem,
                            stringResource(Res.string.p5d_carry_quantity), line.title)) }, onClick = {
                            showCarry = false
                            carryError = line.elementUuid?.let(presenter::carryQuantity) != true
                            quantityEditor = line.elementUuid
                        })
                    }
            }
        }
        if (carryError) WorkspaceCostHelp(stringResource(Res.string.p5d_carry_failed), kind = ContextFeedbackKind.WARNING)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val unavailableEditIds = state.edits.keys.filter { id -> state.lines.none { it.elementUuid == id } }
            items(unavailableEditIds, key = { "override:$it" }) { id ->
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.p5d_carry_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(enabled = enabled, onClick = { presenter.resetQuantity(id) }) {
                        Icon(Icons.Default.Refresh, stringResource(Res.string.p5d_reset_quantity))
                    }
                }
            }
            itemsIndexed(state.lines.sortedWith(compareBy({ it.bucket }, { it.businessRole?.ordinal ?: Int.MAX_VALUE })),
                key = { _, it -> it.sourceKey }) { index, line ->
                WorkspaceListRow(index) {
                val selected = line.sourceKey in state.selected
                val id = line.elementUuid
                val element = state.source?.elements?.firstOrNull { it.uuid == id }
                val eligible = element != null && state.source?.let { BillableOverrideRules.eligible(it, element) } == true
                val quantity = id?.let { state.edits[it] }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val inclusionLabel = stringResource(Res.string.p5d_include, line.title)
                    Checkbox(selected, onCheckedChange = { presenter.select(line.sourceKey, it) },
                        enabled = enabled && line.businessRole == BusinessRole.ADDITIONAL_WORK,
                        modifier = Modifier.semantics { contentDescription = inclusionLabel })
                    Column(Modifier.weight(1f)) { WorkspaceFinancialRow(line) }
                    if (selected && eligible && id != null) IconButton(enabled = enabled, onClick = {
                        quantityEditor = id
                        if (state.edits[id] == null) presenter.editQuantity(id, formatElementMilli(line.effectiveBillableQuantityMilli))
                    }) { Icon(Icons.Default.Edit, stringResource(Res.string.p5d_edit_quantity)) }
                }
                if (quantity != null && quantity.confirmed) {
                    Text(formatElementMilli(line.sourceQuantityMilli), Modifier.padding(start = 52.dp),
                        textDecoration = TextDecoration.LineThrough, style = MaterialTheme.typography.labelSmall)
                }
                if (id != null && quantity != null && quantityEditor == id && selected && eligible) {
                    Row(Modifier.fillMaxWidth().padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            DecimalInputField(quantity.text, { presenter.editQuantity(id, it) },
                                stringResource(Res.string.p5d_edit_quantity), enabled = enabled,
                                isError = TransientQuotePresenter.quantity(quantity.text, line.unit) == null,
                                allowDecimalSeparator = line.unit !in setOf("шт.", "компл."))
                        }
                        IconButton(enabled = enabled && TransientQuotePresenter.quantity(quantity.text, line.unit) != null,
                            onClick = { presenter.confirmQuantity(id); quantityEditor = null }) {
                            Icon(Icons.Default.Check, stringResource(Res.string.p5d_apply_quantity))
                        }
                        IconButton(onClick = { presenter.resetQuantity(id); quantityEditor = null }, enabled = enabled) {
                            Icon(Icons.Default.Refresh, stringResource(Res.string.p5d_reset_quantity))
                        }
                    }
                } else if (id != null && quantity != null && selected) {
                    IconButton(onClick = { presenter.resetQuantity(id) }, enabled = enabled) {
                        Icon(Icons.Default.Refresh, stringResource(Res.string.p5d_reset_quantity))
                    }
                }
                }
            }
            itemsIndexed(state.unresolved, key = { _, it -> it.sourceKey }) { index, row ->
                WorkspaceListRow(state.lines.size + index) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)) {
                        Text(row.label, Modifier.weight(1f, fill = false))
                        WorkspaceCostHelp(row.reasons.map { workspacePricingProblem(it) }.joinToString("\n"), compact = true, kind = ContextFeedbackKind.WARNING)
                    }
                }
            }
            itemsIndexed(state.unavailableSelectedDiscussing, key = { _, it -> "unavailable:${it.sourceKey}" }) { index, row ->
                WorkspaceListRow(state.lines.size + state.unresolved.size + index) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(true, onCheckedChange = { presenter.select(row.sourceKey, it) }, enabled = enabled)
                        Text(row.label, Modifier.weight(1f, fill = false))
                        WorkspaceCostHelp(row.problems.map { workspacePricingProblem(it) }.joinToString("\n"), compact = true)
                    }
                }
            }
        }
    }
    if (session.discardPending) AlertDialog(onDismissRequest = { session.discardPending = false },
        title = { Text(stringResource(Res.string.p5d_discard_title)) },
        dismissButton = { TextButton(onClick = { session.closeDraft(store) }) {
            Text(stringResource(Res.string.p5d_discard), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { session.discardPending = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
    if (partialFingerprint != null && partialFingerprint == state.fingerprint && canSubmit) {
        AlertDialog(onDismissRequest = { partialFingerprint = null },
            title = { Text(stringResource(Res.string.p5d_partial)) },
            text = {
                LazyColumn {
                    items(state.preview?.excluded.orEmpty().filter { it.bucket == PricingBucket.CONFIRMED }) { item ->
                        val label = state.source?.elements?.firstOrNull { elementKey(it.uuid) == item.sourceKey }?.label ?: item.sourceKey
                        val reason = if (item.reason == ExclusionReason.USER_EXCLUDED) stringResource(Res.string.p5d_excluded)
                            else item.problems.map { workspacePricingProblem(it) }.joinToString()
                        Text(stringResource(Res.string.p5d_line_problem, label, reason))
                    }
                }
            },
            dismissButton = { TextButton(onClick = { partialFingerprint = null }) { Text(stringResource(Res.string.p5d_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    if (partialFingerprint == presenter.state.value.fingerprint) {
                        presenter.acknowledge(true)
                        store.submitQuote(presenter, route.projectId)
                    }
                    partialFingerprint = null
                }) { Text(stringResource(Res.string.p5d_partial_accept)) }
            })
    }
}

@Composable
internal fun ColumnScope.WorkspaceFrozenQuote(
    store: MeasurementElementsStore,
    session: WorkspaceQuoteSession,
    quote: SavedIndividualQuote,
    approved: List<SavedIndividualQuote>,
    busy: Boolean,
    onStatus: (SavedIndividualQuote, EstimateWorkflowStatus, List<SavedIndividualQuote>) -> Unit,
    onNewRevision: (SavedIndividualQuote?) -> Unit,
    onDelete: () -> Unit,
    registryPresentation: Boolean = false,
    onBack: () -> Unit = session::closeView
) {
    val frozen = quote.frozen
    var showPdf by remember(quote.revisionUuid) { mutableStateOf(false) }
    var failed by remember(quote.revisionUuid) { mutableStateOf(false) }
    val shareResult by store.quoteShareResult.collectAsState()
    val statusConflict = shareResult?.let { it.revisionUuid == quote.revisionUuid && !it.changed } == true
    var exporting by remember(quote.revisionUuid) { mutableStateOf(false) }
    var drift by remember(quote.revisionUuid, quote.status) { mutableStateOf<Boolean?>(false) }
    val exporter = LocalEstimateDocumentExporter.current
    val textMeasurer = rememberTextMeasurer()
    val fixedDiscountLabel = stringResource(Res.string.p5d_pdf_fixed_discount_label)
    val moneyColumns = documentMoneyColumnLabels()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, quote.revisionUuid, quote.status) {
        if (quote.status != EstimateWorkflowStatus.APPROVED) return@LaunchedEffect
        store.observeQuotePricing(ElementRoute(quote.projectId, 0, frozen.source.measurementUuid)).collect { read ->
            drift = when (read) {
                null -> false
                is PricingReadResult.Unavailable -> null
                is PricingReadResult.Loaded -> frozen.hasPricingDrift(read)
            }
        }
    }
    fun export(action: EstimateDocumentAction) {
        if (exporter == null || exporting) return
        exporting = true
        failed = false
        scope.launch {
            try {
                val document = checkNotNull(store.estimateDocument(quote.seriesUuid, quote.revisionUuid))
                val pages = layoutEstimateDocument(document, DocumentPresentationOptions(EstimateDocumentSection.entries.filter {
                    it != EstimateDocumentSection.ISSUER && it != EstimateDocumentSection.PHOTOS
                }, fixedDiscountLabel = fixedDiscountLabel, moneyColumnLabels = moneyColumns), textMeasurer)
                exporter.exportWithShareConfirmation(pages, action, onComplete = {
                    failed = it != null
                    exporting = false
                }, onShareDispatched = {
                    if (action == EstimateDocumentAction.SHARE) store.markQuoteShareDispatched(quote)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                exporting = false
            }
        }
    }
    WorkspaceFinancialSummary {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = !exporting) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.p5d_back_quotes))
        }
        Column(Modifier.weight(1f)) {
            Text(frozen.title, style = if (registryPresentation) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.p5d_revision_heading, frozen.publicNumber, quote.versionNumber),
                style = if (registryPresentation) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall)
        }
        if (drift != false) WorkspaceCostHelp(stringResource(if (drift == null) Res.string.p5d_drift_unavailable else Res.string.p5d_drift))
        WorkspaceQuoteStatusIcon(quote, approved, !busy && !exporting, onStatus)
        WorkspaceExpand(session)
    }
    }
    WorkspacePanelDivider()
    WorkspaceFinancialSummary {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.p5d_to_pay), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (frozen.amounts.discountCents > 0) {
                val discount = measurementMoney(frozen.amounts.discountCents)
                Text(if (frozen.amounts.discountMode == QuoteDiscountMode.PERCENT)
                    stringResource(Res.string.p5d_discount_percent_value, discount, frozen.discountText)
                else stringResource(Res.string.p5d_discount_value, discount), Modifier.padding(end = 8.dp))
            }
            Text(measurementMoney(frozen.amounts.finalCents), style = MaterialTheme.typography.titleLarge)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (failed || statusConflict) Text(stringResource(if (statusConflict) Res.string.p5d_share_status_conflict else Res.string.p5d_quote_failed),
        color = MaterialTheme.colorScheme.error)
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        itemsIndexed(frozen.rows, key = { _, it -> it.uuid }) { index, row ->
            WorkspaceListRow(index) {
                WorkspaceFinancialRow(row.line)
                if (row.line.quantityOrigin == QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE)
                    Text(formatElementMilli(row.line.sourceQuantityMilli), Modifier.padding(start = 8.dp),
                        textDecoration = TextDecoration.LineThrough, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (frozen.omissions.isNotEmpty()) item {
            WorkspaceGroupHeading(stringResource(Res.string.p5d_partial))
        }
        itemsIndexed(frozen.omissions) { index, item ->
            WorkspaceListRow(frozen.rows.size + index) {
                val label = frozen.source.elements.firstOrNull { elementKey(it.uuid) == item.sourceKey }?.label ?: item.sourceKey
                Text(stringResource(Res.string.p5d_line_problem, label, if (item.reason == ExclusionReason.USER_EXCLUDED)
                    stringResource(Res.string.p5d_excluded) else item.problems.map { workspacePricingProblem(it) }.joinToString()),
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    WorkspaceFinancialSummary {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceTrash(quote.canDeleteQuote && !busy && !exporting, Res.string.p5d_delete_revision, onDelete)
            if (!quote.canDeleteQuote) WorkspaceCostHelp(stringResource(Res.string.p5d_delete_protected))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showPdf = true }, enabled = !exporting) {
                Icon(Icons.Default.PictureAsPdf, stringResource(Res.string.p5d_pdf_preview))
            }
            IconButton(onClick = { export(EstimateDocumentAction.PRINT) }, enabled = !exporting && exporter != null) {
                Icon(Icons.Default.Print, stringResource(Res.string.p5d_print))
            }
            IconButton(onClick = { export(EstimateDocumentAction.SHARE) }, enabled = !exporting && exporter != null) {
                Icon(Icons.Default.Share, stringResource(Res.string.p5d_share))
            }
            IconButton(onClick = { onNewRevision(quote) }, enabled = !exporting && !busy) {
                Icon(Icons.Default.Add, stringResource(Res.string.p5d_new_revision))
            }
        }
    }
    if (showPdf) EstimateDocumentPreview(store, quote.seriesUuid, quote.revisionUuid) {
        showPdf = false
        session.refresh++
    }
}

@Composable
internal fun WorkspaceQuoteStatusIcon(
    quote: SavedIndividualQuote,
    approved: List<SavedIndividualQuote>,
    enabled: Boolean,
    onStatus: (SavedIndividualQuote, EstimateWorkflowStatus, List<SavedIndividualQuote>) -> Unit
) {
    var menu by remember(quote.revisionUuid) { mutableStateOf(false) }
    var confirmation by remember(quote.revisionUuid) { mutableStateOf<List<SavedIndividualQuote>?>(null) }
    Box {
        IconButton(enabled = enabled, onClick = { menu = true }) {
            val (icon, tint) = workspaceStatusAppearance(quote.status)
            Icon(icon, stringResource(Res.string.p5d_status_change, workspaceStatusLabel(quote.status)), tint = tint)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            EstimateWorkflowStatus.entries.forEach { status ->
                val otherApprovals = approved.filter { it.revisionUuid != quote.revisionUuid }
                DropdownMenuItem(text = { Text(workspaceStatusLabel(status)) }, leadingIcon = {
                    val (icon, tint) = workspaceStatusAppearance(status)
                    Icon(icon, contentDescription = null, tint = tint)
                },
                    enabled = status != quote.status || (status == EstimateWorkflowStatus.APPROVED && otherApprovals.isNotEmpty()),
                    onClick = {
                        menu = false
                        if (status == EstimateWorkflowStatus.APPROVED && otherApprovals.isNotEmpty()) confirmation = approved.toList()
                        else onStatus(quote, status, emptyList())
                    })
            }
        }
    }
    confirmation?.let { versions ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(stringResource(Res.string.p5d_replace_approval)) },
            text = {
                LazyColumn {
                    items(versions.filter { it.revisionUuid != quote.revisionUuid }) { WorkspaceQuoteIdentity(it) }
                    item { HorizontalDivider(); WorkspaceQuoteIdentity(quote); Text(stringResource(Res.string.p5d_prior_status)) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(Res.string.p5d_cancel)) } },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                onStatus(quote, EstimateWorkflowStatus.APPROVED, versions)
            }) { Text(stringResource(Res.string.p5d_replace_approval_action)) } })
    }
}

@Composable
private fun WorkspaceQuoteIdentity(quote: SavedIndividualQuote) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(quote.frozen.title, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(Res.string.p5d_revision_heading, quote.frozen.publicNumber, quote.versionNumber))
        Text(measurementMoney(quote.frozen.amounts.finalCents))
    }
}

@Composable
internal fun WorkspaceQuoteDeleteDialog(
    versions: List<SavedIndividualQuote>, series: Boolean, onClose: () -> Unit, onDelete: () -> Unit
) = AlertDialog(onDismissRequest = onClose,
    title = { Text(stringResource(if (series) Res.string.p5d_delete_series_title else Res.string.p5d_delete_revision_title)) },
    text = { LazyColumn { items(versions, key = { it.revisionUuid }) { WorkspaceQuoteIdentity(it) } } },
    dismissButton = { TextButton(onClick = onDelete) {
        Text(stringResource(if (series) Res.string.p5d_delete_series else Res.string.p5d_delete_revision),
            color = MaterialTheme.colorScheme.error)
    } },
    confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.p5d_cancel)) } })

internal val SavedIndividualQuote.canDeleteQuote: Boolean
    get() = status != null && status != EstimateWorkflowStatus.APPROVED

@Composable
internal fun WorkspaceTrash(enabled: Boolean, label: org.jetbrains.compose.resources.StringResource, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Delete, stringResource(label), tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@Composable
internal fun workspaceStatusAppearance(status: EstimateWorkflowStatus?) = when (status) {
    EstimateWorkflowStatus.DRAFT -> Icons.Default.EditNote to MaterialTheme.colorScheme.onSurfaceVariant
    EstimateWorkflowStatus.SENT -> Icons.AutoMirrored.Filled.Send to MaterialTheme.colorScheme.primary
    EstimateWorkflowStatus.APPROVED -> Icons.Default.CheckCircle to Color(0xFF32965D)
    EstimateWorkflowStatus.REJECTED -> Icons.Default.Close to MaterialTheme.colorScheme.error
    null -> Icons.Default.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun workspaceStatusLabel(status: EstimateWorkflowStatus?) = stringResource(when (status) {
    EstimateWorkflowStatus.DRAFT -> Res.string.p5d_status_draft
    EstimateWorkflowStatus.SENT -> Res.string.p5d_status_sent
    EstimateWorkflowStatus.APPROVED -> Res.string.p5d_status_approved
    EstimateWorkflowStatus.REJECTED -> Res.string.p5d_status_rejected
    null -> Res.string.p5d_status_unknown
})

@Composable
private fun WorkspaceExpand(session: WorkspaceQuoteSession) {
    IconButton(onClick = { session.expanded = !session.expanded }) {
        Icon(if (session.expanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            stringResource(if (session.expanded) Res.string.p5d_collapse else Res.string.p5d_expand))
    }
}
