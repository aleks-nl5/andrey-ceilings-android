package com.andreyceilings.shared.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WorkspacePlacementTools(
    model: MeasurementEditModel, state: MeasurementEditState, enabled: Boolean,
    snap: Boolean, onSnapChanged: (Boolean) -> Unit
) {
    if (state.pointElementUuid != null) WorkspacePointPlacementHint(model::cancelPlacement)
    if (state.lineElementUuid != null) {
        Text(stringResource(if (state.lineStart == null) Res.string.p5b_line_start else Res.string.p5b_line_end))
        WorkspaceSnapControl(snap, enabled, onSnapChanged)
        WorkspaceCancelPlacement(model)
    }
    if (state.area.tool != null) {
        FlowRow {
            for (tool in AreaTool.entries) FilterChip(selected = state.area.tool == tool,
                onClick = { model.setAreaTool(tool) }, enabled = enabled,
                label = { Text(stringResource(when(tool) {
                    AreaTool.RECTANGLE -> Res.string.p5b_rectangle
                    AreaTool.CIRCLE -> Res.string.p5b_circle
                    AreaTool.POLYGON -> Res.string.p5b_polygon
                })) })
        }
        if (state.area.tool == AreaTool.CIRCLE) {
            DecimalInputField(state.area.diameter, model::setAreaDiameter, stringResource(Res.string.p5b_numeric_diameter),
                modifier = measurementFieldFocusModifier(), enabled = enabled)
        }
        FlowRow {
            TextButton(enabled = enabled && model.areaCreationShape() != null, onClick = model::acceptAreaCreation) {
                Text(stringResource(Res.string.p5b_finish_area))
            }
            WorkspaceCancelPlacement(model)
        }
    }
}

@Composable
private fun WorkspaceCancelPlacement(model: MeasurementEditModel) {
    TextButton(onClick = model::cancelPlacement) { Text(stringResource(Res.string.p5b_cancel_placement)) }
}

@Composable
private fun WorkspaceSnapControl(snap: Boolean, enabled: Boolean, onSnapChanged: (Boolean) -> Unit) {
    FilterChip(selected = snap, onClick = { onSnapChanged(!snap) }, enabled = enabled,
        label = { Text(stringResource(Res.string.p5b_line_snap)) })
}

@Composable
internal fun WorkspaceGeometryPanel(
    model: MeasurementEditModel, state: MeasurementEditState, enabled: Boolean,
    snap: Boolean, onSnapChanged: (Boolean) -> Unit
) {
    val point = model.displayPoints().find { it.uuid == state.selectedPointUuid }
    val line = model.displayLines().find { it.uuid == state.selectedLineUuid }
    val area = model.displayAreas().find { it.uuid == state.area.selected }
    val uuid = point?.uuid ?: line?.uuid ?: area?.uuid ?: return
    var edit by rememberSaveable(uuid) { mutableStateOf(false) }
    var deletion by remember(uuid) { mutableStateOf(false) }
    val workId = point?.elementUuid ?: line?.elementUuid ?: area?.elementUuid
    val work = model.workspaceWorks().find { it.uuid == workId }
    val review = point?.mounting?.needsReview == true || line?.needsReview == true || area?.needsReview == true
    val unresolved = point?.mounting?.unresolved == true || line?.unresolved == true || area?.unresolved == true
    val title = work?.label ?: stringResource(when {
        point != null -> Res.string.p5b_point_type
        line != null -> Res.string.p5b_line_type
        area?.role == AreaRole.OBSTACLE -> Res.string.p5b_obstacle
        area?.role == AreaRole.VOID_MARKER -> Res.string.p5b_void
        else -> Res.string.p5b_area_type
    })
    val detail = when {
        line != null -> formatMillimetres(line.lengthMm)
        area?.shape is AreaShape.Circle -> stringResource(Res.string.p5b_diameter_value,
            formatMillimetres((area.shape as AreaShape.Circle).radiusMm * 2))
        area != null -> formatMeasurementArea(area.twiceAreaMm2 / 2_000_000.0, state.draft.precision) + " м²"
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
            if (unresolved || review) Text(stringResource(if (unresolved) Res.string.p5b_unresolved else Res.string.p5b_review),
                style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = {
            if (!edit) {
                edit = true
                when {
                    point != null && state.mountingInput == null -> model.openMounting()
                    line != null && state.lineProperties == null -> model.openLineProperties()
                    area != null && state.area.properties == null -> model.openAreaProperties()
                }
            } else if (state.mountingInput?.dirty != true && state.lineProperties?.dirty != true && state.area.properties?.dirty != true) {
                model.discardMounting()
                model.discardLineProperties()
                model.discardAreaProperties()
                edit = false
            }
        }, enabled = enabled) {
            Icon(Icons.Default.Edit, stringResource(Res.string.p5b_edit_dimensions))
        }
        IconButton(onClick = { deletion = true }, enabled = enabled) {
            Icon(Icons.Default.Delete, stringResource(Res.string.p5b_delete_placement))
        }
        IconButton(onClick = model::clearDrawingSelection) {
            Icon(Icons.Default.Close, stringResource(Res.string.p5b_clear_selection))
        }
    }

    if ((review && !unresolved) || (line != null && work != null && !line.unresolved) ||
        (area != null && work != null && !area.unresolved)) {
        FlowRow {
            if (review && !unresolved) TextButton(onClick = {
                when {
                    point != null -> model.stagePoint(point.uuid, PointMountingEdit(confirm = true))
                    line != null -> model.confirmLine()
                    area != null -> model.confirmArea()
                }
            }) { Text(stringResource(Res.string.p5b_review_confirm)) }
            if (line != null && work != null && !line.unresolved)
                TextButton(onClick = { model.requestDeclaredLength(work.uuid) }) {
                    Text(stringResource(Res.string.p5b_take_length))
                }
            if (area != null && work != null && !area.unresolved)
                TextButton(onClick = { model.requestAreaQuantity(work.uuid) }) {
                    Text(stringResource(Res.string.p5b_take_quantity))
                }
        }
    }

    if (edit) {
        if (point != null && state.mountingInput == null) {
            TextButton(onClick = model::openMounting) { Text(stringResource(Res.string.p5b_wall_distances)) }
        }
        if (line != null) {
            WorkspaceSnapControl(snap, enabled, onSnapChanged)
            if (state.mountingInput == null) FlowRow {
                TextButton(onClick = { model.openLineMounting(true) }) { Text(stringResource(Res.string.p5b_start_distances)) }
                TextButton(onClick = { model.openLineMounting(false) }) { Text(stringResource(Res.string.p5b_end_distances)) }
            }
            if (state.lineProperties == null) TextButton(onClick = model::openLineProperties) {
                Text(stringResource(Res.string.p5b_line_size))
            }
            state.lineProperties?.let { input ->
                val valid = model.linePropertiesCandidate(line, input) != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DecimalInputField(input.length ?: formatPointMetres(line.lengthMm),
                        { model.changeLineProperties(length = it) },
                        stringResource(Res.string.p5b_line_length),
                        modifier = measurementFieldFocusModifier().weight(1f))
                    Spacer(Modifier.width(6.dp))
                    DecimalInputField(input.width, { model.changeLineProperties(width = it) },
                        stringResource(Res.string.p5b_line_size),
                        modifier = measurementFieldFocusModifier().weight(1f),
                        allowDecimalSeparator = false, isError = !input.valid)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input.note, { model.changeLineProperties(note = it) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(Res.string.p5b_line_note)) })
                    IconButton(onClick = model::applyLineProperties, enabled = valid) {
                        Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_draft))
                    }
                    IconButton(onClick = model::discardLineProperties) {
                        Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
                    }
                }
                if (!valid) Text(stringResource(Res.string.p5b_dimensions_invalid),
                    color = MaterialTheme.colorScheme.error)
            }
        }
        if (area != null) {
            if (state.mountingInput == null) FlowRow {
                area.shape.positions.indices.forEach { index ->
                    TextButton(onClick = { model.openAreaMounting(index) }) {
                        Text(if (area.shape is AreaShape.Circle) stringResource(Res.string.p5b_wall_distances)
                            else stringResource(Res.string.p5b_vertex_distances, index + 1))
                    }
                }
            }
            if (state.area.properties == null) TextButton(onClick = model::openAreaProperties) {
                Text(stringResource(Res.string.p5b_area_properties))
            }
            state.area.properties?.let { input ->
                if (input.width != null && input.height != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DecimalInputField(input.width, { value -> model.changeAreaProperties { it.copy(width = value) } },
                            stringResource(Res.string.p5b_rectangle_width),
                            modifier = measurementFieldFocusModifier().weight(1f))
                        Spacer(Modifier.width(6.dp))
                        DecimalInputField(input.height, { value -> model.changeAreaProperties { it.copy(height = value) } },
                            stringResource(Res.string.p5b_rectangle_height),
                            modifier = measurementFieldFocusModifier().weight(1f))
                    }
                }
                if (area.shape is AreaShape.Circle) DecimalInputField(input.diameter,
                    { value -> model.changeAreaProperties { it.copy(diameter = value, diameterMeasured = true) } },
                    stringResource(Res.string.p5b_numeric_diameter), modifier = measurementFieldFocusModifier())
                val valid = model.areaPropertiesCandidate(area, input) != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input.note, { value -> model.changeAreaProperties { it.copy(note = value) } },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(Res.string.p5b_placement_note)) })
                    IconButton(onClick = model::applyAreaProperties, enabled = valid) {
                        Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_draft))
                    }
                    IconButton(onClick = model::discardAreaProperties) {
                        Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
                    }
                }
                if (!valid)
                    Text(stringResource(Res.string.p5b_dimensions_invalid), color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.mountingInput?.pointUuid == uuid) WallAnchorEditor(model, inline = true)
    }

    if (deletion) AlertDialog(onDismissRequest = { deletion = false },
        title = { Text(stringResource(Res.string.p5b_delete_placement)) },
        text = { Text(stringResource(Res.string.p5b_delete_placement_explain)) },
        confirmButton = {
            TextButton(onClick = {
                deletion = false
                when {
                    point != null -> model.deleteWorkspacePoint(point.uuid)
                    line != null -> model.deleteLine()
                    area != null -> model.deleteArea()
                }
            }) { Text(stringResource(Res.string.p5b_delete)) }
        },
        dismissButton = { TextButton(onClick = { deletion = false }) { Text(stringResource(Res.string.p5b_cancel_placement)) } })
    val lengthId = state.lengthConfirm
    val areaId = state.area.quantityConfirm
    if (lengthId != null || areaId != null) {
        val cancel = { model.requestDeclaredLength(null); model.requestAreaQuantity(null) }
        AlertDialog(onDismissRequest = cancel,
            title = { Text(stringResource(if (lengthId != null) Res.string.p5b_take_length else Res.string.p5b_take_quantity)) },
            text = { Text(stringResource(Res.string.p5b_confirm_quantity_change)) },
            confirmButton = { TextButton(onClick = {
                if (lengthId != null) model.takeDeclaredLength() else model.takeAreaQuantity()
                cancel()
            }) { Text(stringResource(Res.string.p5b_apply_dimensions)) } },
            dismissButton = { TextButton(onClick = cancel) { Text(stringResource(Res.string.p5b_cancel_placement)) } })
    }
}

@Composable
internal fun WorkspaceWorkList(
    model: MeasurementEditModel, enabled: Boolean, selected: String?, onSelect: (String?) -> Unit
) {
    val works = model.workspaceWorks()
    for (work in works) key(work.uuid) {
        var edit by rememberSaveable { mutableStateOf(false) }
        var deletion by remember { mutableStateOf(false) }
        val isSelected = selected == work.uuid
        val namesakes = works.filter { it.label == work.label }
        val shownTitle = if (namesakes.size > 1)
            "${work.label} #${namesakes.indexOfFirst { it.uuid == work.uuid } + 1}"
        else work.label
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = isSelected }.clickable(enabled = enabled) {
                onSelect(if (isSelected) null else work.uuid)
            }, verticalArrangement = Arrangement.Center) {
                Text(shownTitle,
                    style = if (isSelected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = workQuantityText(model, work)
                val review = model.displayPoints().any { it.elementUuid == work.uuid && it.mounting.needsReview } ||
                    model.displayLines().any { it.elementUuid == work.uuid && it.needsReview } ||
                    model.displayAreas().any { it.elementUuid == work.uuid && it.needsReview }
                val status = when {
                    review -> stringResource(Res.string.p5b_review)
                    work.quantity == ElementQuantity.Unknown -> stringResource(Res.string.p5b_quantity_unknown)
                    else -> null
                }
                Text(if (status == null) details else stringResource(Res.string.p5b_work_status, details, status),
                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onSelect(null); model.startWorkspacePlacement(work) },
                enabled = enabled && workspaceCanPlace(model, work)) {
                Icon(Icons.Default.Add, stringResource(Res.string.p5b_place_more))
            }
            val source = work.source as? ElementSource.CatalogSelection
            IconButton(onClick = { onSelect(work.uuid); edit = !edit },
                enabled = enabled && source?.quantityMode?.workspaceQuantity() == ElementQuantity.Unknown) {
                Icon(Icons.Default.Edit, stringResource(Res.string.p5b_edit_work))
            }
            IconButton(onClick = { deletion = true }, enabled = enabled) {
                Icon(Icons.Default.Delete, stringResource(Res.string.p5b_delete_work))
            }
        }
        if (isSelected && edit) {
            var quantity by rememberSaveable(work.quantity) {
                mutableStateOf((work.quantity as? ElementQuantity.Exact)?.let { formatElementMilli(it.milli) }.orEmpty())
            }
            var invalid by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DecimalInputField(quantity, { quantity = it; invalid = false },
                    stringResource(Res.string.p5b_declared_quantity, work.unit.orEmpty()),
                    modifier = measurementFieldFocusModifier().weight(1f), isError = invalid,
                    allowDecimalSeparator = work.unit != "шт.")
                IconButton(onClick = {
                    val value = if (quantity.isBlank()) ElementQuantity.Unknown
                        else parseElementMilli(quantity, work.unit)?.let { ElementQuantity.Exact(it) }
                    if (value != null && model.editWorkspaceQuantity(work.uuid, value)) edit = false else invalid = true
                }) { Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_quantity)) }
                IconButton(onClick = { edit = false }) {
                    Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
                }
            }
            if (invalid) Text(stringResource(Res.string.p5b_quantity_invalid), color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider(Modifier.padding(top = 5.dp, bottom = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (deletion) AlertDialog(onDismissRequest = { deletion = false },
            title = { Text(stringResource(Res.string.p5b_delete_work)) },
            text = { Text(stringResource(Res.string.p5b_delete_work_explain, work.label)) },
            confirmButton = { TextButton(onClick = {
                deletion = false
                onSelect(null)
                model.deleteWorkspaceWork(work.uuid)
            }) { Text(stringResource(Res.string.p5b_delete)) } },
            dismissButton = { TextButton(onClick = { deletion = false }) { Text(stringResource(Res.string.p5b_cancel_placement)) } })
    }
}
