package com.andreyceilings.shared.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.normalizedNameKey
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WorkspaceElementsHeader(
    model: MeasurementEditModel,
    enabled: Boolean,
    onMaterial: (BusinessRole) -> Unit,
    onAdd: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (role in listOf(BusinessRole.CANVAS, BusinessRole.PRIMARY_PROFILE)) {
                val current = model.materials(role)
                val item = current.singleOrNull()
                val state = when {
                    current.size > 1 -> stringResource(Res.string.p5b_material_conflict)
                    item == null -> stringResource(Res.string.p5b_material_missing)
                    (item.source as? ElementSource.CatalogSelection)?.availability == CatalogAvailability.DELETED_OR_MISSING ->
                        stringResource(Res.string.p5b_material_archived, item.label)
                    else -> item.label
                }
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(enabled = enabled && current.size <= 1) { onMaterial(role) }
                    .testTag("p5b-material-${role.name}").padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(stringResource(materialLabel(role)), style = MaterialTheme.typography.labelMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(state, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onAdd, modifier = Modifier.testTag("measurement-workspace-add"), enabled = enabled) {
                Icon(Icons.Default.Add, stringResource(Res.string.p5b_add_title))
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun WorkspacePointPlacementHint(onCancel: () -> Unit) {
    Text(stringResource(Res.string.p5b_point_tap))
    TextButton(onCancel) { Text(stringResource(Res.string.p5b_cancel_placement)) }
}

@Composable
internal fun WorkspaceCatalogSearch(
    model: MeasurementEditModel,
    role: BusinessRole?,
    onDismiss: () -> Unit,
    onPlacedTool: () -> Unit
) {
    var catalog by remember(model) { mutableStateOf<List<ElementCatalogItem>?>(null) }
    var unavailable by remember(model) { mutableStateOf(false) }
    var selectedCatalog by rememberSaveable(role?.name) { mutableStateOf<String?>(null) }
    var selectedWork by rememberSaveable(role?.name) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(role?.name) { mutableStateOf("") }
    var quantity by rememberSaveable(role?.name) { mutableStateOf("") }
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
    if (work != null || item != null) {
        TextButton(onClick = { selectedCatalog = null; selectedWork = null }) {
            Text(stringResource(Res.string.p5b_back_to_catalog))
        }
        if (work != null) {
            Text(work.label, style = MaterialTheme.typography.titleSmall)
            Text(workQuantityText(model, work), style = MaterialTheme.typography.bodySmall)
            val canPlace = workspaceCanPlace(model, work)
            TextButton(onClick = { model.startWorkspacePlacement(work); onPlacedTool() }, enabled = canPlace) {
                Text(stringResource(Res.string.p5b_place_more))
            }
            if (!canPlace) Text(stringResource(
                if (work.supportsLinePlacement() || work.supportsAreaPlacement()) Res.string.p5b_capacity_full
                else Res.string.p5b_placement_unavailable), style = MaterialTheme.typography.bodySmall)
        } else if (item != null) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(placementLabel(item.placementKind)), style = MaterialTheme.typography.bodySmall)
            val default = item.mode.workspaceQuantity()
            val entered = if (default != ElementQuantity.Unknown || quantity.isBlank()) default
                else parseElementMilli(quantity, item.unit)?.let { ElementQuantity.Exact(it) }
            if (default == ElementQuantity.Unknown) {
                DecimalInputField(quantity, { quantity = it }, stringResource(Res.string.p5b_declared_quantity, item.unit),
                    isError = entered == null, allowDecimalSeparator = item.unit != "шт.")
                if (quantity.isBlank()) Text(stringResource(Res.string.p5b_quantity_unknown))
            } else Text(formatElementQuantity(default, item.unit))
            val canPlace = entered != null && item.supportsWorkspacePlacement() &&
                (item.placementKind != PlacementKind.POINT || entered is ElementQuantity.Exact)
            FlowRow {
                if (item.placementKind != PlacementKind.NONE) TextButton(enabled = canPlace, onClick = {
                    model.addCatalogWork(item, checkNotNull(entered), place = true)
                    onPlacedTool()
                }) { Text(stringResource(Res.string.p5b_add_and_place)) }
                TextButton(enabled = entered != null, onClick = {
                    model.addCatalogWork(item, checkNotNull(entered), place = false)
                    onPlacedTool()
                }) { Text(stringResource(Res.string.p5b_add_unplaced)) }
            }
        }
        return
    }

    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("p5b-catalog-search"),
        singleLine = true, label = { Text(stringResource(Res.string.p5b_catalog_search)) })
    FlowRow {
        if (role == null) {
            TextButton({ model.beginArea(AreaRole.OBSTACLE); onPlacedTool() }) {
                Text(stringResource(Res.string.p5b_obstacle))
            }
            TextButton({ model.beginArea(AreaRole.VOID_MARKER, tool = AreaTool.CIRCLE); onPlacedTool() }) {
                Text(stringResource(Res.string.p5b_void))
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.p5b_cancel_placement)) }
    }
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
        val id = if (peers.size > 1) "#${peers.indexOfFirst { it.first.uuid == element.uuid } + 1}" else source.code
        WorkspaceCatalogRow(element.label, stringResource(Res.string.p5b_existing_item, id),
            Modifier.testTag("p5b-linked-${element.uuid}")) { selectedWork = element.uuid }
    }
    val matching = catalog.orEmpty().filter { choice ->
        (if (role != null) choice.materialRole() == role else choice.uuid !in alreadyAdded) &&
            (normalizedNameKey(choice.title).contains(term) || normalizedNameKey(choice.code).contains(term))
    }
    matching.forEach { choice ->
        WorkspaceCatalogRow(choice.title,
            stringResource(Res.string.p5b_catalog_metadata, stringResource(placementLabel(choice.placementKind)), choice.unit, choice.code),
            Modifier.testTag("p5b-catalog-${choice.uuid}")) {
            if (role != null) { model.chooseMaterial(role, choice); onDismiss() }
            else { quantity = ""; selectedCatalog = choice.uuid }
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
    return stringResource(Res.string.p5b_work_quantities, formatElementQuantity(work.quantity, work.unit),
        formatElementMilli(placed), work.unit.orEmpty())
}

private fun materialLabel(role: BusinessRole) =
    if (role == BusinessRole.CANVAS) Res.string.p5b_material_canvas else Res.string.p5b_material_profile

private fun placementLabel(kind: PlacementKind) = when (kind) {
    PlacementKind.POINT -> Res.string.p5b_point_type
    PlacementKind.LINE -> Res.string.p5b_line_type
    PlacementKind.AREA -> Res.string.p5b_area_type
    PlacementKind.NONE -> Res.string.p5b_add_unplaced
}
