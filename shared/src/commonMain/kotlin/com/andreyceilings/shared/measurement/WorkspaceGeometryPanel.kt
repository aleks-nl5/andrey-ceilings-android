package com.andreyceilings.shared.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ChangeHistory
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
import com.andreyceilings.shared.input.CompactOutlinedStringField
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if (state.lineStart == null)
                Res.string.p5b_line_start_short else Res.string.p5b_line_end_short),
                modifier = Modifier.weight(1f), maxLines = 2,
                style = MaterialTheme.typography.labelSmall)
            WorkspaceSnapControl(snap, enabled, onSnapChanged)
            WorkspaceCancelPlacement(model)
        }
    }
    if (state.area.tool != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (tool in AreaTool.entries) {
                val label = stringResource(when(tool) {
                    AreaTool.RECTANGLE -> Res.string.p5b_rectangle
                    AreaTool.CIRCLE -> Res.string.p5b_circle
                    AreaTool.POLYGON -> Res.string.p5b_polygon
                })
                val shape = when(tool) {
                    AreaTool.RECTANGLE -> Icons.Default.CropSquare
                    AreaTool.CIRCLE -> Icons.Default.RadioButtonUnchecked
                    AreaTool.POLYGON -> Icons.Default.ChangeHistory
                }
                val isActive = state.area.tool == tool
                IconButton(onClick = { model.setAreaTool(tool) },
                    enabled = enabled, modifier = Modifier.size(44.dp)
                        .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent, CircleShape)
                        .semantics { this.selected = isActive }) {
                    Icon(shape, label)
                }
            }
            Text(stringResource(when (state.area.role) {
                AreaRole.OBSTACLE -> Res.string.p5b_obstacle
                AreaRole.VOID_MARKER -> Res.string.p5b_void_short
                AreaRole.GENERAL_AREA -> Res.string.p5b_area_type
            }), modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall)
            IconButton(enabled = enabled && model.areaCreationShape() != null,
                onClick = model::acceptAreaCreation) {
                Icon(Icons.Default.Check, stringResource(Res.string.p5b_finish_area))
            }
            WorkspaceCancelPlacement(model)
        }
        if (state.area.tool == AreaTool.CIRCLE) {
            DecimalInputField(state.area.diameter, model::setAreaDiameter, stringResource(Res.string.p5b_numeric_diameter),
                modifier = measurementFieldFocusModifier(), enabled = enabled, compact = true)
        }
    }
}

@Composable
private fun WorkspaceCancelPlacement(model: MeasurementEditModel) {
    IconButton(onClick = model::cancelPlacement) {
        Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
    }
}

@Composable
private fun WorkspaceSnapControl(snap: Boolean, enabled: Boolean, onSnapChanged: (Boolean) -> Unit) {
    val label = stringResource(Res.string.p5b_line_snap_short)
    IconButton(onClick = { onSnapChanged(!snap) }, enabled = enabled,
        modifier = Modifier.size(44.dp)
            .background(if (snap) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent, CircleShape)
            .semantics { contentDescription = label; selected = snap }) {
        WorkspaceMagnetIcon()
    }
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

/** Horizontal measurement glyph. The action updates the declared LENGTH, not the drawing. */
@Composable
private fun WorkspaceDrawingLengthIcon() {
    val ink = LocalContentColor.current
    val label = stringResource(Res.string.p5b_take_length)
    Box(Modifier.size(24.dp).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val unit = size.minDimension / 24f
            val y = 12f * unit
            drawLine(ink, Offset(3f * unit, y), Offset(21f * unit, y),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(5f * unit, 6f * unit), Offset(5f * unit, 18f * unit),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(19f * unit, 6f * unit), Offset(19f * unit, 18f * unit),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(7f * unit, 9f * unit), Offset(10f * unit, y),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(7f * unit, 15f * unit), Offset(10f * unit, y),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(17f * unit, 9f * unit), Offset(14f * unit, y),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(17f * unit, 15f * unit), Offset(14f * unit, y),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
        }
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
    // The panel's open state derives from the actual editor. Closing/cancelling an
    // editor MUST close its entire controls, not leave an orphaned toolbar behind.
    // Work-row settings opens the editor in the model before this panel composes.
    val edit = state.mountingInput?.pointUuid == uuid ||
        state.lineProperties?.uuid == uuid || state.area.properties?.uuid == uuid
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
                when {
                    point != null -> model.openMounting()
                    line != null -> model.openLineProperties()
                    area != null -> model.openAreaProperties()
                }
            } else {
                when {
                    state.mountingInput?.pointUuid == uuid -> model.dismissMounting()
                    state.lineProperties?.uuid == uuid -> model.dismissLineProperties()
                    state.area.properties?.uuid == uuid -> model.dismissAreaProperties()
                }
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

    if ((review && !unresolved) ||
        (edit && area != null && work != null && !area.unresolved)) {
        FlowRow {
            if (review && !unresolved) TextButton(onClick = {
                when {
                    point != null -> model.stagePoint(point.uuid, PointMountingEdit(confirm = true))
                    line != null -> model.confirmLine()
                    area != null -> model.confirmArea()
                }
            }) { Text(stringResource(Res.string.p5b_review_confirm)) }
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
            val canTakeLength = work != null && !line.unresolved &&
                model.displayLines().any { it.elementUuid == work.uuid && !it.unresolved }
            // Stable one-row toolbar. Selecting an endpoint changes the active field, not
            // the structure or position of the controls; no unsaved input is discarded.
            val endpointOpen = state.mountingInput?.pointUuid == line.uuid
                && state.mountingLineStart != null
            val startActive = endpointOpen && state.mountingLineStart == true
            val endActive = endpointOpen && state.mountingLineStart == false
            val dimensionsActive = state.lineProperties?.uuid == line.uuid
            fun chooseEndpoint(first: Boolean) {
                if (state.lineProperties?.dirty == true) {
                    model.dismissLineProperties()
                    return
                }
                if (state.lineProperties != null) model.discardLineProperties()
                if (state.mountingInput?.dirty == true) {
                    model.dismissMounting()
                    return
                }
                if (endpointOpen && state.mountingLineStart == first) {
                    model.dismissMounting()
                } else {
                    model.discardMounting()
                    model.openLineMounting(first)
                }
            }
            fun chooseDimensions() {
                if (state.mountingInput?.dirty == true) {
                    model.dismissMounting()
                    return
                }
                if (state.mountingInput != null) model.discardMounting()
                if (dimensionsActive) model.dismissLineProperties()
                else model.openLineProperties()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val actions = listOf(
                    Triple(stringResource(Res.string.p5b_start_short), startActive, { chooseEndpoint(true) }),
                    Triple(stringResource(Res.string.p5b_end_short), endActive, { chooseEndpoint(false) }),
                    Triple(stringResource(Res.string.p5b_size_short), dimensionsActive, { chooseDimensions() })
                )
                actions.forEach { (label, active, action) ->
                    OutlinedButton(onClick = action, modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant)) {
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                WorkspaceSnapControl(snap, enabled, onSnapChanged)
                IconButton(onClick = { work?.let { model.requestDeclaredLength(it.uuid) } },
                    enabled = enabled && canTakeLength,
                    modifier = Modifier.size(40.dp).testTag("p5b-line-take-length")) {
                    WorkspaceDrawingLengthIcon()
                }
            }
            state.lineProperties?.let { input ->
                val valid = model.linePropertiesCandidate(line, input) != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DecimalInputField(input.length.orEmpty(),
                        { model.changeLineProperties(length = it) },
                        stringResource(Res.string.p5b_line_length),
                        modifier = measurementFieldFocusModifier().weight(1f).testTag("p5b-line-declared-length"), compact = true)
                    Spacer(Modifier.width(6.dp))
                    DecimalInputField(input.width, { model.changeLineProperties(width = it) },
                        stringResource(Res.string.p5b_line_size),
                        modifier = measurementFieldFocusModifier().weight(1f),
                        allowDecimalSeparator = false, isError = !input.valid, compact = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactOutlinedStringField(input.note, { model.changeLineProperties(note = it) },
                        label = stringResource(Res.string.p5b_line_note),
                        modifier = Modifier.weight(1f).testTag("p5b-line-note"))
                    IconButton(onClick = model::applyLineProperties, enabled = valid) {
                        Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_draft))
                    }
                    IconButton(onClick = model::dismissLineProperties) {
                        Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
                    }
                }
                if (!valid) {
                    Text(stringResource(Res.string.p5b_quantity_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (area != null) {
            // Vertex positions are edited directly on the drawing. Do not render
            // inert or ambiguous per-vertex actions in this compact properties card.
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
                    CompactOutlinedStringField(input.note, { value -> model.changeAreaProperties { it.copy(note = value) } },
                        label = stringResource(Res.string.p5b_placement_note),
                        modifier = Modifier.weight(1f).testTag("p5b-area-note"))
                    IconButton(onClick = model::applyAreaProperties, enabled = valid) {
                        Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_draft))
                    }
                    IconButton(onClick = model::dismissAreaProperties) {
                        Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
                    }
                }
                if (!valid)
                    Text(stringResource(Res.string.p5b_dimensions_invalid), color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.mountingInput?.pointUuid == uuid) {
            if (line != null && state.mountingLineStart != null) {
                Text(stringResource(if (state.mountingLineStart == true)
                    Res.string.p5b_start_distances else Res.string.p5b_end_distances),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 5.dp, top = 2.dp))
            }
            WallAnchorEditor(model, inline = true)
        }
    }

    // The cancel icon must never silently discard an edited line/area. Once the
    // user confirms or continues, the model and the visible panel stay in sync.
    val lineDismiss = state.linePropertiesDismiss && state.lineProperties?.uuid == uuid
    val areaDismiss = state.area.dismissProperties && state.area.properties?.uuid == uuid
    if (lineDismiss || areaDismiss) AlertDialog(
        onDismissRequest = {
            if (lineDismiss) model.continueLineProperties() else model.continueAreaProperties()
        },
        title = { Text(stringResource(Res.string.p5b_discard_parameters_title)) },
        text = { Text(stringResource(Res.string.p5b_discard_parameters_message)) },
        confirmButton = {
            TextButton(onClick = {
                if (lineDismiss) model.continueLineProperties() else model.continueAreaProperties()
            }) { Text(stringResource(Res.string.p5b_discard_parameters_continue)) }
        },
        dismissButton = {
            TextButton(onClick = {
                if (lineDismiss) model.discardLineProperties() else model.discardAreaProperties()
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(Res.string.p5b_discard_parameters_confirm))
            }
        }
    )

    if (deletion) AlertDialog(onDismissRequest = { deletion = false },
        title = { Text(stringResource(Res.string.p5b_delete_placement)) },
        text = { Text(stringResource(Res.string.p5b_delete_placement_explain)) },
        confirmButton = {
            TextButton(onClick = { deletion = false }) { Text(stringResource(Res.string.p5b_cancel_placement)) }
        },
        dismissButton = {
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
        })
    val lengthId = state.lengthConfirm
    val areaId = state.area.quantityConfirm
    if (lengthId != null || areaId != null) {
        val workId = lengthId ?: areaId
        val work = model.workspaceWorks().find { it.uuid == workId }
        val proposed = if (lengthId != null)
            model.displayLines().filter { it.elementUuid == lengthId && !it.unresolved }.sumOf { it.lengthMm }
        else if (work?.unit != null) areaQuantityMilli(
            model.displayAreas().filter { it.elementUuid == areaId },
            work.unit
        ) else 0L
        val currentQuantity = work?.quantity
        val proposedQuantity = ElementQuantity.Exact(proposed)
        // A typed but unconfirmed geometry length is NOT present in displayLines().
        // Never present its old sum as the new value: require the checkmark first.
        val pendingGeometry = state.lineProperties?.dirty == true ||
            state.mountingInput?.dirty == true ||
            state.area.properties?.dirty == true
        val canTransfer = !pendingGeometry && work != null && proposed > 0 &&
            currentQuantity != proposedQuantity &&
            model.canEditWorkspaceQuantity(work.uuid, proposedQuantity)
        val cancel = { model.requestDeclaredLength(null); model.requestAreaQuantity(null) }
        AlertDialog(onDismissRequest = cancel,
            title = { Text(stringResource(Res.string.p5b_confirm_quantity_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.p5b_drawing_quantity_scope),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(Res.string.p5b_quantity_before,
                        work?.let { formatElementQuantity(it.quantity, it.unit) } ?: "—"))
                    Text(stringResource(Res.string.p5b_quantity_after,
                        work?.let { formatElementQuantity(proposedQuantity, it.unit) } ?: "—"),
                        color = MaterialTheme.colorScheme.primary)
                    if (pendingGeometry) Text(
                        stringResource(Res.string.p5b_quantity_apply_line_first),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    else if (proposed <= 0) Text(stringResource(Res.string.p5b_drawing_quantity_empty),
                        color = MaterialTheme.colorScheme.error)
                    else if (currentQuantity == proposedQuantity) Text(
                        stringResource(Res.string.p5b_drawing_quantity_equal),
                        style = MaterialTheme.typography.bodySmall)
                    else if (!canTransfer) Text(
                        stringResource(Res.string.p5b_drawing_quantity_not_allowed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (lengthId != null) model.takeDeclaredLength() else model.takeAreaQuantity()
                    cancel()
                }, enabled = canTransfer) {
                    Text(stringResource(Res.string.p5b_quantity_transfer))
                }
            },
            dismissButton = { TextButton(onClick = cancel) {
                Text(stringResource(Res.string.p5b_cancel_placement))
            } })
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

/**
 * Non-priced areas are geometric notes, not catalog work. Show them as
 * selectable rows so tapping them never jumps straight into parameters.
 */
@Composable
internal fun WorkspaceStandaloneAreaList(
    model: MeasurementEditModel, enabled: Boolean, selected: String?,
    onSelect: (String?) -> Unit, onOpenGeometry: (String) -> Unit
) {
    val annotations = model.displayAreas().filter { it.elementUuid == null }
    if (annotations.isEmpty()) return
    Text(stringResource(Res.string.p5b_annotations_title),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 5.dp, bottom = 2.dp))
    annotations.forEachIndexed { index, feature ->
        key(feature.uuid) {
            val isSelected = feature.uuid == selected
            val requester = remember(feature.uuid) { BringIntoViewRequester() }
            LaunchedEffect(isSelected) { if (isSelected) requester.bringIntoView() }
            val role = stringResource(when (feature.role) {
                AreaRole.OBSTACLE -> Res.string.p5b_obstacle
                AreaRole.VOID_MARKER -> Res.string.p5b_void
                AreaRole.GENERAL_AREA -> Res.string.p5b_area_type
            })
            val shape = stringResource(when {
                feature.shape is AreaShape.Circle -> Res.string.p5b_circle
                feature.shape is AreaShape.Polygon && feature.shape.positions.isAxisAlignedRectangle() ->
                    Res.string.p5b_rectangle
                else -> Res.string.p5b_polygon
            })
            val size = if (feature.shape is AreaShape.Circle)
                stringResource(Res.string.p5b_diameter_value,
                    formatMillimetres(feature.shape.radiusMm * 2))
            else stringResource(Res.string.p5b_annotation_area_value,
                formatMeasurementAreaCompact(feature.twiceAreaMm2 / 2_000_000.0))
            Row(Modifier.fillMaxWidth()
                .bringIntoViewRequester(requester)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent, RoundedCornerShape(10.dp))
                .testTag("p5b-standalone-area-${feature.uuid}"),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .semantics { this.selected = isSelected }
                    .clickable(enabled = enabled) {
                        onSelect(if (isSelected) null else feature.uuid)
                    }, verticalArrangement = Arrangement.Center) {
                    Text(stringResource(Res.string.p5b_annotation_row_title,
                        role, index + 1), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(Res.string.p5b_annotation_row_detail, shape, size),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onOpenGeometry(feature.uuid) }, enabled = enabled,
                    modifier = Modifier.testTag("p5b-open-standalone-area-${feature.uuid}")) {
                    Icon(Icons.Default.Settings,
                        stringResource(Res.string.p5b_open_geometry_parameters))
                }
            }
            HorizontalDivider(Modifier.padding(top = 5.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant)
        }
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
        val hasPlacement = model.displayPoints().any { it.elementUuid == work.uuid } ||
            model.displayLines().any { it.elementUuid == work.uuid } ||
            model.displayAreas().any { it.elementUuid == work.uuid }
        LaunchedEffect(isHighlighted) { if (isHighlighted) bringIntoView.bringIntoView() }
        val namesakes = works.filter { it.label == work.label }
        val shownTitle = if (namesakes.size > 1)
            "${work.label} #${namesakes.indexOfFirst { it.uuid == work.uuid } + 1}"
        else work.label
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)
            .testTag("p5b-work-row-${work.uuid}")
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHighlighted || isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(
                alpha = if (isHighlighted) 0.9f else 0.48f) else Color.Transparent,
                RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(10.dp))
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
                enabled = enabled && workspaceCanPlace(model, work),
                modifier = Modifier.size(44.dp).testTag("p5b-place-work-${work.uuid}")) {
                Icon(Icons.Default.OpenWith, stringResource(Res.string.p5b_place_more))
            }
            IconButton(onClick = {
                if (model.selectFirstWorkspacePlacement(work.uuid)) {
                    onOpenGeometry()
                }
            }, enabled = enabled && hasPlacement,
                modifier = Modifier.size(44.dp).testTag("p5b-open-geometry-${work.uuid}")) {
                Icon(Icons.Default.Settings, stringResource(Res.string.p5b_open_geometry_parameters))
            }
            val source = work.source as? ElementSource.CatalogSelection
            val editableQuantity = source?.quantityMode?.workspaceQuantity() == ElementQuantity.Unknown
            // Keep the pencil present even after the first LINE placement.
            // Placed LINE uses the SAME "Довжина" editor as the geometry panel;
            // the separate work-list quantity field must not be duplicated.
            IconButton(onClick = {
                if (work.supportsLinePlacement() && hasPlacement) {
                    if (model.selectFirstWorkspacePlacement(work.uuid)) {
                        quantityEditingWork = null
                        onOpenGeometry()
                    }
                } else {
                    val closingSameEditor = isSelected && quantityEditingWork == work.uuid
                    onSelect(work.uuid)
                    quantityEditingWork = if (closingSameEditor) null else work.uuid
                }
            }, enabled = enabled && editableQuantity,
                modifier = Modifier.size(44.dp).testTag("p5b-edit-work-${work.uuid}")) {
                Icon(Icons.Default.Edit, stringResource(Res.string.p5b_edit_work))
            }
            IconButton(onClick = { deletion = true }, enabled = enabled,
                modifier = Modifier.size(44.dp).testTag("p5b-delete-work-${work.uuid}")) {
                Icon(Icons.Default.Delete, stringResource(Res.string.p5b_delete_work))
            }
        }
        if (isSelected && quantityEditingWork == work.uuid &&
            (!work.supportsLinePlacement() || !hasPlacement)) {
            var quantity by rememberSaveable(work.quantity) {
                mutableStateOf((work.quantity as? ElementQuantity.Exact)?.let { formatElementMilli(it.milli) }.orEmpty())
            }
            var invalid by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    DecimalInputField(quantity, { quantity = it; invalid = false },
                        stringResource(Res.string.p5b_declared_quantity, work.unit.orEmpty()),
                        modifier = measurementFieldFocusModifier().fillMaxWidth(),
                        compact = true, isError = invalid, allowDecimalSeparator = work.unit != "шт.")
                    WorkspaceQuantityHelp(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp))
                }
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
