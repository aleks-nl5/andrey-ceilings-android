package com.andreyceilings.shared.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
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
                modifier = measurementFieldFocusModifier(), enabled = enabled, compact = true)
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
        leadingIcon = { WorkspaceMagnetIcon() },
        label = { Text(stringResource(Res.string.p5b_line_snap_short), maxLines = 1) })
}

/** Compact, theme-aware horseshoe magnet; no emoji or platform-specific icon. */
@Composable
private fun WorkspaceMagnetIcon() {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val scale = size.minDimension / 24f
        val path = Path().apply {
            moveTo(5f * scale, 5f * scale)
            lineTo(5f * scale, 13f * scale)
            cubicTo(5f * scale, 22f * scale, 19f * scale, 22f * scale, 19f * scale, 13f * scale)
            lineTo(19f * scale, 5f * scale)
        }
        drawPath(path, ink, style = Stroke(width = 2.5f * scale, cap = StrokeCap.Round))
        drawLine(ink, Offset(3f * scale, 5f * scale), Offset(7f * scale, 5f * scale),
            strokeWidth = 4f * scale)
        drawLine(ink, Offset(17f * scale, 5f * scale), Offset(21f * scale, 5f * scale),
            strokeWidth = 4f * scale)
    }
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
    val coincidentPoints = if (point != null) model.overlappingPointsForSelected() else emptyList()
    val coincidentIndex = coincidentPoints.indexOfFirst { it.uuid == uuid }
    // A work-list settings action may open the editor before this panel is composed.
    val parametersOpened = state.mountingInput?.pointUuid == uuid ||
        state.lineProperties?.uuid == uuid || state.area.properties?.uuid == uuid
    var edit by rememberSaveable(uuid) { mutableStateOf(parametersOpened) }
    LaunchedEffect(uuid, parametersOpened) {
        if (parametersOpened) edit = true
    }
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
        area != null -> formatMeasurementAreaCompact(area.twiceAreaMm2 / 2_000_000.0) + " м²"
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
            if (unresolved || review) Text(stringResource(if (unresolved) Res.string.p5b_unresolved else Res.string.p5b_review),
                style = MaterialTheme.typography.bodySmall)
        }
        if (coincidentPoints.size > 1) {
            val positionDescription = stringResource(Res.string.p5b_overlap_position,
                coincidentIndex + 1, coincidentPoints.size)
            Text("${coincidentIndex + 1}/${coincidentPoints.size}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.semantics { contentDescription = positionDescription })
            IconButton(onClick = model::selectNextOverlappingPoint,
                modifier = Modifier.testTag("p5b-overlap-next"),
                enabled = enabled && state.mountingInput == null && state.coordinates == null &&
                    state.pointElementUuid == null) {
                Icon(Icons.Default.KeyboardArrowRight, stringResource(Res.string.p5b_overlap_next))
            }
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

    if ((review && !unresolved) || (edit && (
        line != null && work != null && !line.unresolved ||
            area != null && work != null && !area.unresolved))) {
        FlowRow {
            if (review && !unresolved) TextButton(onClick = {
                when {
                    point != null -> model.stagePoint(point.uuid, PointMountingEdit(confirm = true))
                    line != null -> model.confirmLine()
                    area != null -> model.confirmArea()
                }
            }) { Text(stringResource(Res.string.p5b_review_confirm)) }
            if (edit && line != null && work != null && !line.unresolved)
                OutlinedButton(onClick = { model.requestDeclaredLength(work.uuid) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 36.dp)) {
                    Text(stringResource(Res.string.p5b_take_length),
                        style = MaterialTheme.typography.labelSmall)
                }
            if (edit && area != null && work != null && !area.unresolved)
                OutlinedButton(onClick = { model.requestAreaQuantity(work.uuid) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 36.dp)) {
                    Text(stringResource(Res.string.p5b_take_quantity),
                        style = MaterialTheme.typography.labelSmall)
                }
        }
    }

    if (edit) {
        if (point != null && state.mountingInput == null) {
            WorkspaceShortAction(stringResource(Res.string.p5b_wall_short), Icons.Default.Edit, model::openMounting)
        }
        if (line != null) {
            WorkspaceSnapControl(snap, enabled, onSnapChanged)
            if (state.mountingInput == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkspaceShortAction(stringResource(Res.string.p5b_start_short), Icons.Default.Edit) {
                    model.openLineMounting(true)
                }
                WorkspaceShortAction(stringResource(Res.string.p5b_end_short), Icons.Default.Edit) {
                    model.openLineMounting(false)
                }
            }
            if (state.lineProperties == null)
                WorkspaceShortAction(stringResource(Res.string.p5b_size_short), Icons.Default.Edit,
                    model::openLineProperties)
            state.lineProperties?.let { input ->
                val valid = model.linePropertiesCandidate(line, input) != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DecimalInputField(input.length ?: formatPointMetres(line.lengthMm),
                        { model.changeLineProperties(length = it) },
                        stringResource(Res.string.p5b_line_length),
                        modifier = measurementFieldFocusModifier().weight(1f), compact = true)
                    Spacer(Modifier.width(6.dp))
                    DecimalInputField(input.width, { model.changeLineProperties(width = it) },
                        stringResource(Res.string.p5b_line_size),
                        modifier = measurementFieldFocusModifier().weight(1f),
                        allowDecimalSeparator = false, isError = !input.valid, compact = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input.note, { model.changeLineProperties(note = it) },
                        modifier = Modifier.weight(1f).height(56.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
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
                    WorkspaceShortAction(
                        if (area.shape is AreaShape.Circle) stringResource(Res.string.p5b_wall_short)
                        else stringResource(Res.string.p5b_vertex_short, index + 1),
                        Icons.Default.Edit) { model.openAreaMounting(index) }
                }
            }
            if (state.area.properties == null)
                WorkspaceShortAction(stringResource(Res.string.p5b_size_short), Icons.Default.Edit,
                    model::openAreaProperties)
            state.area.properties?.let { input ->
                if (input.width != null && input.height != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DecimalInputField(input.width, { value -> model.changeAreaProperties { it.copy(width = value) } },
                            stringResource(Res.string.p5b_rectangle_width),
                            modifier = measurementFieldFocusModifier().weight(1f), compact = true)
                        Spacer(Modifier.width(6.dp))
                        DecimalInputField(input.height, { value -> model.changeAreaProperties { it.copy(height = value) } },
                            stringResource(Res.string.p5b_rectangle_height),
                            modifier = measurementFieldFocusModifier().weight(1f), compact = true)
                    }
                }
                if (area.shape is AreaShape.Circle) DecimalInputField(input.diameter,
                    { value -> model.changeAreaProperties { it.copy(diameter = value, diameterMeasured = true) } },
                    stringResource(Res.string.p5b_numeric_diameter), modifier = measurementFieldFocusModifier(), compact = true)
                val valid = model.areaPropertiesCandidate(area, input) != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input.note, { value -> model.changeAreaProperties { it.copy(note = value) } },
                        modifier = Modifier.weight(1f).height(56.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
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
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(Res.string.p5b_delete))
            }
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
private fun WorkspaceShortAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
internal fun WorkspaceWorkList(
    model: MeasurementEditModel, enabled: Boolean, selected: String?,
    highlightedWorkId: String?, onSelect: (String?) -> Unit, onOpenGeometry: () -> Unit
) {
    val works = model.workspaceWorks()
    // One editor for the whole list: switching pencils must open the next one in one click.
    var quantityEditingWork by rememberSaveable { mutableStateOf<String?>(null) }
    for (work in works) key(work.uuid) {
        var deletion by remember { mutableStateOf(false) }
        val isSelected = selected == work.uuid
        val isHighlighted = highlightedWorkId == work.uuid
        val bringIntoView = remember(work.uuid) { BringIntoViewRequester() }
        LaunchedEffect(isHighlighted) { if (isHighlighted) bringIntoView.bringIntoView() }
        val namesakes = works.filter { it.label == work.label }
        val shownTitle = if (namesakes.size > 1)
            "${work.label} #${namesakes.indexOfFirst { it.uuid == work.uuid } + 1}"
        else work.label
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)
            .background(if (isHighlighted || isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(
                alpha = if (isHighlighted) 0.9f else 0.48f) else Color.Transparent,
                RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .semantics { this.selected = isSelected || isHighlighted }.clickable(enabled = enabled) {
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
            val hasPlacement = model.displayPoints().any { it.elementUuid == work.uuid } ||
                model.displayLines().any { it.elementUuid == work.uuid } ||
                model.displayAreas().any { it.elementUuid == work.uuid }
            IconButton(onClick = {
                if (model.selectFirstWorkspacePlacement(work.uuid)) {
                    onOpenGeometry()
                }
            }, enabled = enabled && hasPlacement,
                modifier = Modifier.testTag("p5b-open-geometry-${work.uuid}")) {
                Icon(Icons.Default.Settings, stringResource(Res.string.p5b_open_geometry_parameters))
            }
            val source = work.source as? ElementSource.CatalogSelection
            IconButton(onClick = {
                val closingSameEditor = isSelected && quantityEditingWork == work.uuid
                onSelect(work.uuid)
                quantityEditingWork = if (closingSameEditor) null else work.uuid
            },
                enabled = enabled && source?.quantityMode?.workspaceQuantity() == ElementQuantity.Unknown) {
                Icon(Icons.Default.Edit, stringResource(Res.string.p5b_edit_work))
            }
            IconButton(onClick = { deletion = true }, enabled = enabled) {
                Icon(Icons.Default.Delete, stringResource(Res.string.p5b_delete_work))
            }
        }
        if (isSelected && quantityEditingWork == work.uuid) {
            var quantity by rememberSaveable(work.quantity) {
                mutableStateOf((work.quantity as? ElementQuantity.Exact)?.let { formatElementMilli(it.milli) }.orEmpty())
            }
            var invalid by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DecimalInputField(quantity, { quantity = it; invalid = false },
                    stringResource(Res.string.p5b_declared_quantity, work.unit.orEmpty()),
                    modifier = measurementFieldFocusModifier().weight(1f), compact = true, isError = invalid,
                    allowDecimalSeparator = work.unit != "шт.")
                IconButton(onClick = {
                    val value = if (quantity.isBlank()) ElementQuantity.Unknown
                        else parseElementMilli(quantity, work.unit)?.let { ElementQuantity.Exact(it) }
                    if (value != null && model.editWorkspaceQuantity(work.uuid, value)) quantityEditingWork = null else invalid = true
                }) { Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_quantity)) }
                IconButton(onClick = { quantityEditingWork = null }) {
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
            confirmButton = { TextButton(onClick = { deletion = false }) {
                Text(stringResource(Res.string.p5b_cancel_placement))
            } },
            dismissButton = { TextButton(onClick = {
                deletion = false
                onSelect(null)
                model.deleteWorkspaceWork(work.uuid)
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(Res.string.p5b_delete))
            } })
    }
}
