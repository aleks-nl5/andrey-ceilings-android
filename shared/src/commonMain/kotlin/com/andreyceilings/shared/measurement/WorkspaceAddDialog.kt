package com.andreyceilings.shared.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.input.CompactOutlinedStringField
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.normalizedNameKey
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.ContextFeedbackIcon
import com.andreyceilings.shared.ui.ContextFeedbackKind
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WorkspaceElementsHeader(
    canvas: List<MeasurementElement>,
    profile: List<MeasurementElement>,
    activeRole: BusinessRole?,
    highlightedUuid: String?,
    enabled: Boolean,
    onMaterial: (BusinessRole) -> Unit,
    onAdd: () -> Unit
) {
    Column {
        WorkspaceFinancialSummary {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (role in listOf(BusinessRole.CANVAS, BusinessRole.PRIMARY_PROFILE)) {
                val current = if (role == BusinessRole.CANVAS) canvas else profile
                val item = current.singleOrNull()
                val state = when {
                    current.size > 1 -> stringResource(Res.string.p5b_material_conflict)
                    item == null -> stringResource(Res.string.p5b_material_missing)
                    (item.source as? ElementSource.CatalogSelection)?.availability == CatalogAvailability.DELETED_OR_MISSING ->
                        stringResource(Res.string.p5b_material_archived, item.label)
                    else -> item.label
                }
                val active = role == activeRole || (item != null && item.uuid == highlightedUuid)
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.weight(1f).padding(horizontal = 2.dp, vertical = 3.dp)
                    .heightIn(min = 48.dp).clip(shape)
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant, shape)
                    .clickable(enabled = enabled && current.size <= 1, role = androidx.compose.ui.semantics.Role.Button) { onMaterial(role) }
                    .testTag("p5b-material-${role.name}").semantics { selected = active }
                    .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(stringResource(materialLabel(role)), style = MaterialTheme.typography.labelMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(state, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = if (enabled && current.size <= 1) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                }
            }
            IconButton(onClick = onAdd, modifier = Modifier.testTag("measurement-workspace-add"), enabled = enabled) {
                Icon(Icons.Default.Add, stringResource(Res.string.p5b_add_title))
            }
        }
        }
        WorkspacePanelDivider()
    }
}

@Composable
internal fun WorkspacePointPlacementHint(onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.p5b_point_tap), modifier = Modifier.weight(1f),
            maxLines = 2, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
        }
    }
}

@Composable
internal fun WorkspaceCatalogSearch(
    model: MeasurementEditModel,
    role: BusinessRole?,
    onDismiss: () -> Unit,
    onNestedBackChange: ((() -> Unit)?) -> Unit,
    onExistingWork: (String) -> Unit,
    onPlacedTool: () -> Unit
) {
    var catalog by remember(model) { mutableStateOf<List<ElementCatalogItem>?>(null) }
    var unavailable by remember(model) { mutableStateOf(false) }
    var selectedCatalog by rememberSaveable(role?.name) { mutableStateOf<String?>(null) }
    var selectedWork by rememberSaveable(role?.name) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(role?.name) { mutableStateOf("") }
    var quantity by rememberSaveable(role?.name) { mutableStateOf("") }
    var approvedNewWork by rememberSaveable(selectedCatalog) { mutableStateOf(false) }
    var materialSelectionFailed by remember(role) { mutableStateOf(false) }
    var annotationMenu by remember { mutableStateOf(false) }
    var editExisting by remember(selectedWork) { mutableStateOf(false) }
    var existingQuantity by remember(selectedWork) { mutableStateOf("") }
    var existingQuantityInvalid by remember(selectedWork) { mutableStateOf(false) }
    LaunchedEffect(model) {
        try {
            catalog = model.placementCatalog()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable = true
        }
    }

    val works = model.workspaceWorks()
    val work = works.find { it.uuid == selectedWork }
    val item = catalog?.find { it.uuid == selectedCatalog }
    val backFromDetail = remember(selectedCatalog, selectedWork) {
        { selectedCatalog = null; selectedWork = null }
    }
    SideEffect {
        onNestedBackChange(if (work != null || item != null) backFromDetail else null)
    }
    if (work != null || item != null) {
        if (work != null) {
            val canPlace = workspaceCanPlace(model, work)
            val editableQuantity = (work.source as? ElementSource.CatalogSelection)?.quantityMode
                ?.workspaceQuantity() == ElementQuantity.Unknown
            val goLabel = stringResource(Res.string.p5b_go_to_work)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = { selectedCatalog = null; selectedWork = null },
                    modifier = Modifier.size(40.dp).testTag("p5b-back-to-catalog")) {
                    Icon(Icons.Default.ArrowBack, stringResource(Res.string.p5b_back_to_catalog))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(work.label, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(workQuantityText(model, work), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalIconButton(onClick = { onExistingWork(work.uuid) },
                    modifier = Modifier.size(40.dp).testTag("p5b-go-to-element")
                        .semantics { contentDescription = goLabel }) {
                    WorkspaceBinocularsIcon()
                }
                IconButton(onClick = { model.startWorkspacePlacement(work); onPlacedTool() }, enabled = canPlace,
                    modifier = Modifier.size(40.dp).testTag("p5b-place-existing")) {
                    Icon(Icons.Default.OpenWith, stringResource(Res.string.p5b_place_more))
                }
                if (editableQuantity) IconButton(onClick = {
                    existingQuantity = (work.quantity as? ElementQuantity.Exact)
                        ?.let { formatElementMilli(it.milli) }.orEmpty()
                    editExisting = true
                    existingQuantityInvalid = false
                }, modifier = Modifier.size(40.dp).testTag("p5b-edit-existing-quantity")) {
                    Icon(Icons.Default.Edit, stringResource(Res.string.p5b_edit_work))
                }
            }
            if (editExisting && editableQuantity) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        DecimalInputField(existingQuantity, {
                            existingQuantity = it
                            existingQuantityInvalid = false
                        }, stringResource(Res.string.p5b_declared_quantity, work.unit.orEmpty()),
                            modifier = Modifier.fillMaxWidth(), compact = true,
                            isError = existingQuantityInvalid, allowDecimalSeparator = work.unit != "шт.")
                    }
                    if (existingQuantityInvalid) WorkspaceFieldError(stringResource(Res.string.p5b_quantity_invalid))
                    else WorkspaceQuantityHelp()
                    IconButton(onClick = {
                        val parsed = if (existingQuantity.isBlank()) ElementQuantity.Unknown
                            else parseElementMilli(existingQuantity, work.unit)?.let { ElementQuantity.Exact(it) }
                        if (parsed != null && model.editWorkspaceQuantity(work.uuid, parsed)) editExisting = false
                        else existingQuantityInvalid = true
                    }) { Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_quantity)) }
                    IconButton(onClick = { editExisting = false }) {
                        Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
                    }
                }
            }
            if (!canPlace && !editExisting)
                ContextFeedbackIcon(stringResource(Res.string.p5b_capacity_full),
                    stringResource(Res.string.p5b_capacity_full), ContextFeedbackKind.WARNING)
        } else if (item != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedCatalog = null; selectedWork = null },
                    modifier = Modifier.size(40.dp).testTag("p5b-back-to-catalog")) {
                    Icon(Icons.Default.ArrowBack, stringResource(Res.string.p5b_back_to_catalog))
                }
                Text(item.title, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).testTag("p5b-catalog-detail-title"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.placementKind == PlacementKind.NONE && item.mode == com.andreyceilings.shared.prices.QuantityMode.ENTERED_LENGTH) {
                    ContextFeedbackIcon(stringResource(Res.string.p5b_line_kind_required),
                        stringResource(Res.string.p5b_line_kind_help), ContextFeedbackKind.HELP,
                        modifier = Modifier.testTag("p5b-line-kind-help"))
                }
            }
            Text(stringResource(placementLabel(item.placementKind)), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.p5b_approval_approved),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Checkbox(checked = approvedNewWork, onCheckedChange = { approvedNewWork = it },
                    modifier = Modifier.testTag("p5b-add-approved"))
            }
            val default = item.mode.workspaceQuantity()
            val entered = if (default != ElementQuantity.Unknown || quantity.isBlank()) default
                else parseElementMilli(quantity, item.unit)?.let { ElementQuantity.Exact(it) }
            val canPlace = entered != null && item.supportsWorkspacePlacement() &&
                (item.placementKind != PlacementKind.POINT || entered is ElementQuantity.Exact)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (default == ElementQuantity.Unknown) {
                    Box(Modifier.weight(1f)) {
                        DecimalInputField(quantity, { quantity = it },
                            stringResource(Res.string.p5b_declared_quantity, item.unit),
                            modifier = Modifier.fillMaxWidth(), compact = true, isError = entered == null,
                            allowDecimalSeparator = item.unit != "шт.")
                    }
                    if (entered == null) WorkspaceFieldError(stringResource(Res.string.p5b_quantity_invalid))
                    else WorkspaceQuantityHelp()
                } else Text(formatElementQuantity(default, item.unit), modifier = Modifier.weight(1f))
                if (item.placementKind != PlacementKind.NONE) IconButton(enabled = canPlace, onClick = {
                    model.addCatalogWork(item, checkNotNull(entered), place = true,
                        approval = if (approvedNewWork) ElementApproval.APPROVED else ElementApproval.DISCUSSING)
                    onPlacedTool()
                }) { Icon(Icons.Default.OpenWith, stringResource(Res.string.p5b_add_and_place)) }
                IconButton(enabled = entered != null, onClick = {
                    model.addCatalogWork(item, checkNotNull(entered), place = false,
                        approval = if (approvedNewWork) ElementApproval.APPROVED else ElementApproval.DISCUSSING)
                    onPlacedTool()
                }) { Icon(Icons.Default.Check, stringResource(Res.string.p5b_add_unplaced)) }
            }

        }
        return
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        CompactOutlinedStringField(query, { query = it },
            label = stringResource(Res.string.p5b_search_short),
            modifier = Modifier.weight(1f).testTag("p5b-catalog-search"))
        if (role == null) {
            Box {
                IconButton(onClick = { annotationMenu = true },
                    modifier = Modifier.size(44.dp).testTag("p5b-add-annotation")) {
                    Icon(Icons.Default.Category, stringResource(Res.string.p5b_annotation_add))
                }
                DropdownMenu(expanded = annotationMenu,
                    onDismissRequest = { annotationMenu = false }) {
                    DropdownMenuItem(
                        modifier = Modifier.testTag("p5b-annotation-obstacle"),
                        leadingIcon = { Icon(Icons.Default.CropSquare, null) },
                        text = {
                            Column {
                                Text(stringResource(Res.string.p5b_obstacle))
                                Text(stringResource(Res.string.p5b_obstacle_explain),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            annotationMenu = false
                            model.beginArea(AreaRole.OBSTACLE, tool = AreaTool.RECTANGLE)
                            onPlacedTool()
                        })
                    DropdownMenuItem(
                        modifier = Modifier.testTag("p5b-annotation-hole"),
                        leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, null) },
                        text = {
                            Column {
                                Text(stringResource(Res.string.p5b_void))
                                Text(stringResource(Res.string.p5b_void_explain),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            annotationMenu = false
                            model.beginArea(AreaRole.VOID_MARKER, tool = AreaTool.CIRCLE)
                            onPlacedTool()
                        })
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
        }
    }
    if (materialSelectionFailed) Text(stringResource(Res.string.p5b_material_selection_failed),
        color = MaterialTheme.colorScheme.error)
    if (unavailable) Text(stringResource(Res.string.p5b_catalog_unavailable))
    else if (catalog == null) Text(stringResource(Res.string.p5b_catalog_loading))
    val term = normalizedNameKey(query)
    val linked = if (role == null) works.mapNotNull { element ->
        (element.source as? ElementSource.CatalogSelection)?.let { element to it }
    } else emptyList()
    val alreadyAdded = linked.map { it.second.priceItemUuid }.toSet()
    val listed = linked.filter { (element, source) ->
        normalizedNameKey(element.label).contains(term) || normalizedNameKey(source.code).contains(term)
    }
    listed.forEach { (element, source) ->
        val peers = linked.filter { it.first.label == element.label }
        val suffix = if (peers.size > 1) " #${peers.indexOfFirst { it.first.uuid == element.uuid } + 1}" else ""
        WorkspaceCatalogRow(element.label + suffix, stringResource(Res.string.p5b_in_measurement),
            Modifier.testTag("p5b-linked-${element.uuid}")) { selectedWork = element.uuid }
    }
    val matching = catalog.orEmpty().filter { choice ->
        (if (role != null) choice.materialRole() == role else choice.uuid !in alreadyAdded) &&
            (normalizedNameKey(choice.title).contains(term) || normalizedNameKey(choice.code).contains(term))
    }
    matching.forEach { choice ->
        WorkspaceCatalogRow(choice.title,
            stringResource(Res.string.p5b_catalog_metadata, stringResource(placementLabel(choice.placementKind)), choice.unit),
            Modifier.testTag("p5b-catalog-${choice.uuid}")) {
            if (role != null) {
                materialSelectionFailed = !model.chooseMaterial(role, choice)
                if (!materialSelectionFailed) onDismiss()
            } else { quantity = ""; selectedCatalog = choice.uuid }
        }
    }
    if (catalog != null && listed.isEmpty() && matching.isEmpty()) Text(stringResource(Res.string.p5b_catalog_empty))
}

@Composable
private fun WorkspaceCatalogRow(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun workspaceCanPlace(model: MeasurementEditModel, work: MeasurementElement): Boolean = when {
    work.supportsLinePlacement() -> model.lineHasCapacity(work.uuid)
    work.supportsAreaPlacement() -> model.areaHasCapacity(work.uuid)
    else -> model.pointCanBePlaced(work.uuid)
}

@Composable
internal fun workQuantityText(model: MeasurementEditModel, work: MeasurementElement): String {
    val placed = when {
        work.supportsLinePlacement() -> model.displayLines().filter { it.elementUuid == work.uuid && !it.unresolved }.sumOf { it.lengthMm }
        work.supportsAreaPlacement() -> areaQuantityMilli(model.displayAreas().filter { it.elementUuid == work.uuid }, checkNotNull(work.unit))
        else -> model.displayPoints().count { it.elementUuid == work.uuid && !it.mounting.unresolved } * 1000L
    }
    fun compact(value: Long): String = if (work.unit == "м²")
        formatMeasurementAreaCompact(value / 1000.0) else formatElementMilli(value)
    val declared = when (val quantity = work.quantity) {
        ElementQuantity.Unknown -> "?"
        is ElementQuantity.Exact -> compact(quantity.milli)
        is ElementQuantity.Range -> "${compact(quantity.minMilli)}–${compact(quantity.maxMilli)}"
        else -> formatElementQuantity(quantity, work.unit)
    }
    return "$declared / ${compact(placed)} ${work.unit.orEmpty()}"
}

private fun materialLabel(role: BusinessRole) =
    if (role == BusinessRole.CANVAS) Res.string.p5b_material_canvas else Res.string.p5b_material_profile

private fun placementLabel(kind: PlacementKind) = when (kind) {
    PlacementKind.POINT -> Res.string.p5b_point_type
    PlacementKind.LINE -> Res.string.p5b_line_type
    PlacementKind.AREA -> Res.string.p5b_area_type
    PlacementKind.NONE -> Res.string.p5b_catalog_no_drawing
}

// A clearly recognizable binocular action, independent of platform icon packs.
@Composable
private fun WorkspaceBinocularsIcon() {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        drawCircle(ink, radius = 5f * unit,
            center = Offset(6.7f * unit, 15f * unit),
            style = Stroke(width = 2.3f * unit))
        drawCircle(ink, radius = 5f * unit,
            center = Offset(17.3f * unit, 15f * unit),
            style = Stroke(width = 2.3f * unit))
        drawLine(ink, Offset(10f * unit, 10.5f * unit),
            Offset(14f * unit, 10.5f * unit), strokeWidth = 2.5f * unit,
            cap = StrokeCap.Round)
        drawLine(ink, Offset(6.7f * unit, 6f * unit),
            Offset(6.7f * unit, 9f * unit), strokeWidth = 4f * unit,
            cap = StrokeCap.Round)
        drawLine(ink, Offset(17.3f * unit, 6f * unit),
            Offset(17.3f * unit, 9f * unit), strokeWidth = 4f * unit,
            cap = StrokeCap.Round)
    }
}

@Composable
internal fun WorkspaceQuantityHelp(modifier: Modifier = Modifier) {
    ContextFeedbackIcon(stringResource(Res.string.p5b_quantity_help_description),
        stringResource(Res.string.p5b_quantity_help_short), ContextFeedbackKind.HELP, modifier)
}
