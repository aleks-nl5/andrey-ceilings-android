package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.normalizedNameKey
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WorkspaceAddAction(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.testTag("measurement-workspace-add"), enabled = enabled) {
        Text(stringResource(Res.string.p5b_add))
    }
}

@Composable
internal fun WorkspacePointPlacementHint(onCancel: () -> Unit) {
    Text(stringResource(Res.string.p5b_point_tap))
    TextButton(onCancel) { Text(stringResource(Res.string.p5b_cancel_placement)) }
}

@Composable
internal fun WorkspaceAddDialog(
    model: MeasurementEditModel,
    onDismiss: () -> Unit,
    onPlacedTool: () -> Unit
) {
    var catalog by remember(model) { mutableStateOf<List<ElementCatalogItem>?>(null) }
    var unavailable by remember(model) { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(model) {
        try {
            catalog = model.placementCatalog()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable = true
        }
    }
    val item = catalog?.find { it.uuid == selection }
    val enteredQuantity = if (quantity.isBlank()) ElementQuantity.Unknown
        else parseElementMilli(quantity, item?.unit)?.let { ElementQuantity.Exact(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.p5b_add_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item != null) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(placementLabel(item.placementKind)))
                    DecimalInputField(quantity, { quantity = it }, stringResource(Res.string.p5b_declared_quantity, item.unit),
                        isError = enteredQuantity == null, allowDecimalSeparator = item.unit != "шт.")
                    if (item.placementKind == PlacementKind.POINT && enteredQuantity !is ElementQuantity.Exact) {
                        Text(stringResource(Res.string.p5b_point_requires_quantity))
                    }
                } else {
                    FlowRow {
                        TextButton({
                            model.beginArea(AreaRole.OBSTACLE)
                            onPlacedTool()
                        }) { Text(stringResource(Res.string.p5b_obstacle)) }
                        TextButton({
                            model.beginArea(AreaRole.VOID_MARKER, tool = AreaTool.CIRCLE)
                            onPlacedTool()
                        }) { Text(stringResource(Res.string.p5b_void)) }
                    }
                    val linked = model.pointElements().filter { model.pointCanBePlaced(it.uuid) } +
                        model.lineElements() + model.areaElements()
                    if (linked.isNotEmpty()) Text(stringResource(Res.string.p5b_linked_work),
                        style = MaterialTheme.typography.titleSmall)
                    val term = normalizedNameKey(query)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(Res.string.p5b_catalog_search)) })
                    linked.filter { normalizedNameKey(it.label).contains(term) }.forEach { element ->
                        val source = element.source as ElementSource.CatalogSelection
                        TextButton({
                            model.startWorkspacePlacement(element)
                            onPlacedTool()
                        }, Modifier.testTag("p5b-linked-${element.uuid}")) {
                            Text(stringResource(Res.string.p5b_typed_item, element.label,
                                stringResource(placementLabel(source.placementKind))))
                        }
                    }
                    Text(stringResource(Res.string.p5b_catalog_work), style = MaterialTheme.typography.titleSmall)
                    if (unavailable) Text(stringResource(Res.string.p5b_catalog_unavailable))
                    else if (catalog == null) Text(stringResource(Res.string.p5b_catalog_loading))
                    val matching = catalog.orEmpty().filter {
                        normalizedNameKey(it.title).contains(term) || normalizedNameKey(it.code).contains(term)
                    }
                    if (catalog != null && matching.isEmpty()) Text(stringResource(Res.string.p5b_catalog_empty))
                    matching.forEach { choice ->
                        TextButton({
                            quantity = ""
                            selection = choice.uuid
                        }, Modifier.testTag("p5b-catalog-${choice.uuid}")) {
                            Text(stringResource(Res.string.p5b_typed_item, choice.title,
                                stringResource(placementLabel(choice.placementKind))))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (item != null) FlowRow {
                val canPlace = enteredQuantity != null &&
                    (item.placementKind != PlacementKind.POINT || enteredQuantity is ElementQuantity.Exact)
                TextButton(enabled = canPlace, onClick = {
                    model.addCatalogWork(item, checkNotNull(enteredQuantity), place = true)
                    onPlacedTool()
                }) { Text(stringResource(Res.string.p5b_add_and_place)) }
                TextButton(enabled = enteredQuantity != null, onClick = {
                    model.addCatalogWork(item, checkNotNull(enteredQuantity), place = false)
                    onPlacedTool()
                }) { Text(stringResource(Res.string.p5b_add_unplaced)) }
            }
        },
        dismissButton = {
            TextButton({ if (selection != null) selection = null else onDismiss() }) {
                Text(stringResource(if (selection != null) Res.string.p5b_back_to_catalog else Res.string.p5b_cancel_placement))
            }
        }
    )
}

private fun placementLabel(kind: PlacementKind) = when (kind) {
    PlacementKind.POINT -> Res.string.p5b_point_type
    PlacementKind.LINE -> Res.string.p5b_line_type
    PlacementKind.AREA -> Res.string.p5b_area_type
    PlacementKind.NONE -> Res.string.p5b_add_unplaced
}
