package com.andreyceilings.shared.measurement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.IntSize
import kotlin.math.pow
import kotlin.math.hypot
import kotlin.math.roundToLong
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * Outline renderer only: all lengths come from world geometry. Optional ceiling features
 * belong in a separate typed overlay, projected through layoutMeasurementDrawing's same
 * transform; painted symbols must never become input to contour/area calculations.
 * compact is presentation only and does not grant editing capability.
 */
@Composable
fun MeasurementDrawing(
    geometry: MeasurementGeometryView,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    interactive: Boolean = false,
    selectedSide: Int? = null,
    onSelectSide: (Int) -> Unit = {},
    fullscreen: Boolean = false,
    pointPlacements: List<PointPlacement> = emptyList(),
    selectedPointUuid: String? = null,
    placementMode: Boolean = false,
    onPlacePoint: (Long, Long) -> Unit = { _, _ -> },
    onSelectPoint: (String) -> Unit = {},
    pointEditingEnabled: Boolean = false, pointDrag: PointDragPreview? = null,
    onBeginPointDrag: (String) -> Unit = {}, onPreviewPointDrag: (Double, Double) -> Unit = { _, _ -> },
    onFinishPointDrag: () -> Unit = {}, onCancelPointDrag: () -> Unit = {},
    fitPointPlacements: Boolean = false,
    editPointsInFullscreen: Boolean = false, mountingDimensions: List<MountingDimension> = emptyList(),
    mountingWallOrdinals: List<Int> = emptyList(),
    linePlacements: List<LinePlacement> = emptyList(), selectedLineUuid: String? = null,
    linePlacementMode: Boolean = false, onLineTap: (Long,Long)->Unit = {_,_->},
    onSelectLine: (String)->Unit = {}, lineEditingEnabled: Boolean = false,
    lineStartPoint:PointPositionMm?=null,lineDrag: LinePlacement? = null, onBeginLineDrag: (String)->Unit = {},
    onPreviewLineDrag: (Double,Double)->Unit = {_,_->}, onFinishLineDrag: ()->Unit = {}, onCancelLineDrag: ()->Unit = {}
) {
    val contour = remember(geometry) { measurementDrawingContour(geometry) }
    if (contour == null) {
        Text("Креслення недоступне: неповна або некоректна геометрія.", modifier,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var panX by rememberSaveable { mutableStateOf(0f) }
    var panY by rememberSaveable { mutableStateOf(0f) }
    val viewport = MeasurementViewport(zoom, panX, panY)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }
    var markerGesture by remember { mutableStateOf(false) }
    fun move(dx: Float, dy: Float, factor: Float = 1f) {
        if (markerGesture) return
        val next = MeasurementViewport(zoom, panX, panY).move(dx, dy, factor)
        zoom = next.zoom; panX = next.panX; panY = next.panY
    }
    fun fit() { zoom = 1f; panX = 0f; panY = 0f }
    val currentViewport by rememberUpdatedState(viewport)
    val currentSelection by rememberUpdatedState(onSelectSide)
    val currentPoints by rememberUpdatedState(pointPlacements)
    val currentPointSelection by rememberUpdatedState(onSelectPoint)
    val currentPointTap by rememberUpdatedState(onPlacePoint)
    val currentMode by rememberUpdatedState(placementMode)
    val fitPoints = if (fitPointPlacements) pointPlacements.map { DrawingPoint(it.xMm.toDouble(), it.yMm.toDouble()) } +
        linePlacements.flatMap {listOf(DrawingPoint(it.start.xMm.toDouble(),it.start.yMm.toDouble()),DrawingPoint(it.end.xMm.toDouble(),it.end.yMm.toDouble()))} else emptyList()
    val currentLines by rememberUpdatedState(linePlacements)
    val currentLineMode by rememberUpdatedState(linePlacementMode)
    val currentLineTap by rememberUpdatedState(onLineTap)
    val currentLineSelection by rememberUpdatedState(onSelectLine)
    val currentSelectedLine by rememberUpdatedState(selectedLineUuid)
    val currentBeginLineDrag by rememberUpdatedState(onBeginLineDrag)
    val currentPreviewLineDrag by rememberUpdatedState(onPreviewLineDrag)
    val currentFinishLineDrag by rememberUpdatedState(onFinishLineDrag)
    val currentCancelLineDrag by rememberUpdatedState(onCancelLineDrag)
    val currentFitPoints by rememberUpdatedState(fitPoints)
    val currentSelectedPoint by rememberUpdatedState(selectedPointUuid)
    val currentBeginDrag by rememberUpdatedState(onBeginPointDrag)
    val currentPreviewDrag by rememberUpdatedState(onPreviewPointDrag)
    val currentFinishDrag by rememberUpdatedState(onFinishPointDrag)
    val currentCancelDrag by rememberUpdatedState(onCancelPointDrag)
    val ink = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    // The contour already has vertex letters A/B/C; draw only dimensions
    // beside segments. AB/BC labels remain in the separate edit/selection list.
    val labelText = remember(contour) { contour.sideLabels }
    val textLayouts = labelText.map { measurer.measure(it, labelStyle) }
    val dimensionLayouts = mountingDimensions.map { measurer.measure(it.label,labelStyle) }
    val vertexLayouts = contour.vertexNames.map { measurer.measure(it, labelStyle) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (interactive) Row {
            TextButton(enabled = !markerGesture, onClick = ::fit) { Text("Вписати креслення") }
            TextButton(onClick = { move(0f, 0f, 1 / 1.25f) }) { Text("−") }
            TextButton(onClick = { move(0f, 0f, 1.25f) }) { Text("+") }
        } else Text("Креслення заміру", style = MaterialTheme.typography.titleSmall)
        if (contour.historical) Text(
            "Історичний прямокутник зі збережених розмірів. Точність креслення не збережена.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val inputModifier = if (!interactive) Modifier else Modifier
            .focusRequester(focusRequester).onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> { move(0f, 0f, 1.25f); true }
                    Key.Minus, Key.NumPadSubtract -> { move(0f, 0f, 1 / 1.25f); true }
                    Key.MoveHome -> { fit(); true }
                    Key.DirectionLeft -> { move(-24f, 0f); true }
                    Key.DirectionRight -> { move(24f, 0f); true }
                    Key.DirectionUp -> { move(0f, -24f); true }
                    Key.DirectionDown -> { move(0f, 24f); true }
                    else -> false
                }
            }.focusable()
            .pointerInput(contour, canvasSize, pointEditingEnabled, lineEditingEnabled, fullscreen, editPointsInFullscreen) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = canvasSize.width.toFloat(); val height = canvasSize.height.toFloat()
                    val projection = measurementDrawingProjection(contour, width, height, density, currentFitPoints) ?: return@awaitEachGesture
                    val initialView = currentViewport
                    fun world(position: Offset) = projection.inverse(initialView.inverse(
                        DrawingPoint(position.x.toDouble(), position.y.toDouble()), width, height))
                    fun hit(position: Offset): PointPlacement? = currentPoints.minByOrNull { marker ->
                        val p = currentViewport.transform(projection.project(DrawingPoint(marker.xMm.toDouble(), marker.yMm.toDouble())), width, height)
                        hypot(p.x - position.x, p.y - position.y)
                    }?.takeIf { marker ->
                        val p = currentViewport.transform(projection.project(DrawingPoint(marker.xMm.toDouble(), marker.yMm.toDouble())), width, height)
                        hypot(p.x - position.x, p.y - position.y) <= 24.dp.toPx()
                    }
                    fun lineDistance(line:LinePlacement,position:Offset):Double {
                        fun screen(p:PointPositionMm)=currentViewport.transform(projection.project(DrawingPoint(p.xMm.toDouble(),p.yMm.toDouble())),width,height)
                        val a=screen(line.start);val b=screen(line.end)
                        val dx=b.x-a.x;val dy=b.y-a.y
                        val t=(((position.x-a.x)*dx+(position.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
                        return hypot(position.x-a.x-t*dx,position.y-a.y-t*dy)
                    }
                    val line=currentLines.minByOrNull {lineDistance(it,down.position)}?.takeIf {lineDistance(it,down.position)<=24.dp.toPx()}
                    val marker = if(currentLineMode || lineEditingEnabled && line!=null && line.uuid==currentSelectedLine) null else hit(down.position)
                    var ownsLine=lineEditingEnabled && !currentLineMode && marker==null && line!=null && line.uuid==currentSelectedLine
                    var ownsMarker = pointEditingEnabled && (!fullscreen || editPointsInFullscreen) && marker != null && marker.uuid == currentSelectedPoint
                    val startWorld = world(down.position)
                    var moved = false; var multi = false
                    var last = down.position
                    if(ownsLine) {markerGesture=true;currentBeginLineDrag(checkNotNull(line).uuid)}
                    if (ownsMarker) { markerGesture = true; currentBeginDrag(checkNotNull(marker).uuid) }
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.count { it.pressed }
                            val change = event.changes.find { it.id == down.id }
                            if (event.changes.any { it.isConsumed }) break
                            if (active > 1) {
                                multi = true
                                if(ownsLine){ownsLine=false;markerGesture=false;currentCancelLineDrag()}
                                if (ownsMarker) { ownsMarker = false; markerGesture = false; currentCancelDrag() }
                            }
                            val position = change?.position ?: last
                            if ((position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (multi) {
                                val pan = event.calculatePan(); val zoomFactor = event.calculateZoom()
                                move(pan.x, pan.y, zoomFactor)
                                event.changes.forEach { it.consume() }
                            } else if (moved && active > 0) {
                                if(ownsLine) {val target=world(position);currentPreviewLineDrag(target.x-startWorld.x,target.y-startWorld.y)} else if (ownsMarker) {
                                    val target = world(position)
                                    currentPreviewDrag(checkNotNull(marker).xMm + target.x - startWorld.x,
                                        marker.yMm + target.y - startWorld.y)
                                } else if (marker == null || fullscreen && !editPointsInFullscreen) {
                                    val delta = position - last
                                    move(delta.x, delta.y)
                                }
                                event.changes.forEach { it.consume() }
                            }
                            last = position
                            if (active == 0) {
                                if(ownsLine) {
                                    markerGesture=false
                                    if(moved){val target=world(position);currentPreviewLineDrag(target.x-startWorld.x,target.y-startWorld.y);currentFinishLineDrag()} else currentCancelLineDrag()
                                    ownsLine=false
                                }
                                if (ownsMarker) {
                                    markerGesture = false
                                    if (moved) {
                                        val target = world(position)
                                        currentPreviewDrag(checkNotNull(marker).xMm + target.x - startWorld.x,
                                            marker.yMm + target.y - startWorld.y)
                                        currentFinishDrag()
                                    } else currentCancelDrag()
                                    ownsMarker = false
                                }
                                if (!moved && !multi) {
                                    if(currentLineMode) {
                                        val target=world(position)
                                        if(target.x.isFinite() && target.y.isFinite() && target.x in -1_000_000.0..1_000_000.0 && target.y in -1_000_000.0..1_000_000.0)currentLineTap(target.x.roundToLong(),target.y.roundToLong())
                                    } else if (marker != null && (!fullscreen || editPointsInFullscreen)) currentPointSelection(marker.uuid)
                                    else if(line!=null)currentLineSelection(line.uuid)
                                    else if (currentMode && !fullscreen) {
                                        val target = world(position)
                                        val bound = com.andreyceilings.shared.calculation.CeilingPolygonCalculator.MAX_COORDINATE_MM.toDouble()
                                        if (target.x.isFinite() && target.y.isFinite() && target.x in -bound..bound && target.y in -bound..bound)
                                            currentPointTap(target.x.roundToLong(), target.y.roundToLong())
                                    } else {
                                        val points = contour.points.map { currentViewport.transform(projection.project(it), width, height) }
                                        measurementWallAt(points, position.x, position.y, 24.dp.toPx())?.let(currentSelection)
                                    }
                                    focusRequester.requestFocus()
                                }
                                break
                            }
                            if (change == null && !multi) break
                        }
                    } finally {
                        markerGesture = false
                        if(ownsLine)currentCancelLineDrag()
                        if (ownsMarker) currentCancelDrag()
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
                            if (event.keyboardModifiers.isCtrlPressed) move(-delta.x * 24, -delta.y * 24)
                            else move(0f, 0f, 1.15f.pow(-delta.y))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        Canvas(Modifier.fillMaxWidth().then(if (fullscreen) Modifier.weight(1f) else Modifier.height(if (compact) 155.dp else 235.dp))
            .clipToBounds().onSizeChanged { canvasSize = it }.then(inputModifier)
            .semantics { contentDescription = "Контур стелі, ${contour.points.size} вершин, позначок: ${pointPlacements.size}, ліній: ${linePlacements.size}" }) {
            val layout = layoutMeasurementDrawing(contour, size.width, size.height,
                textLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) }, density,
                vertexLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) }, extraWorldPoints = fitPoints)
                ?: return@Canvas
            val points = layout.points.map { point ->
                val transformed = if (interactive) viewport.transform(point, size.width, size.height) else point
                Offset(transformed.x.toFloat(), transformed.y.toFloat())
            }
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, ink.copy(alpha = 0.08f))
            drawPath(path, ink, style = Stroke(width = 2.5.dp.toPx()))
            selectedSide?.takeIf { it in points.indices }?.let { i ->
                drawLine(ink, points[i], points[(i + 1) % points.size], strokeWidth = 8.dp.toPx())
            }
            mountingWallOrdinals.filter { it in points.indices }.forEach { i ->
                drawLine(ink.copy(alpha=0.65f),points[i],points[(i+1)%points.size],strokeWidth=6.dp.toPx())
            }
            points.forEach { drawCircle(ink, 3.dp.toPx(), it) }
            val projection = measurementDrawingProjection(contour, size.width, size.height, density, fitPoints)
            if (projection != null) mountingDimensions.forEachIndexed { index, dimension ->
                fun screen(p: DrawingPoint): Offset {
                    val fitted=projection.project(p)
                    val transformed=if(interactive) viewport.transform(fitted,size.width,size.height) else fitted
                    return Offset(transformed.x.toFloat(),transformed.y.toFloat())
                }
                val a=screen(dimension.start);val b=screen(dimension.end)
                drawLine(ink,a,b,strokeWidth=1.dp.toPx())
                drawCircle(ink,2.dp.toPx(),b)
                val label=dimensionLayouts[index]
                val mid=(a+b)/2f
                drawRect(surface.copy(alpha=0.96f),mid,Size(label.size.width.toFloat(),label.size.height.toFloat()))
                drawText(label,topLeft=mid)
            }
            if(projection!=null)lineStartPoint?.let {start ->
                val p=projection.project(DrawingPoint(start.xMm.toDouble(),start.yMm.toDouble()))
                val v=if(interactive)viewport.transform(p,size.width,size.height) else p
                drawCircle(ink,6.dp.toPx(),Offset(v.x.toFloat(),v.y.toFloat()),style=Stroke(2.dp.toPx()))
            }
            if(projection!=null)linePlacements.forEach {line ->
                val visible=lineDrag?.takeIf {it.uuid==line.uuid} ?: line
                fun screen(p:PointPositionMm):Offset {
                    val fitted=projection.project(DrawingPoint(p.xMm.toDouble(),p.yMm.toDouble()))
                    val v=if(interactive)viewport.transform(fitted,size.width,size.height) else fitted
                    return Offset(v.x.toFloat(),v.y.toFloat())
                }
                val a=screen(visible.start);val b=screen(visible.end)
                val color=if(visible.unresolved)androidx.compose.ui.graphics.Color(0xffb3261e) else ink
                if(line.uuid==selectedLineUuid)drawLine(color.copy(alpha=0.25f),a,b,strokeWidth=14.dp.toPx())
                drawLine(color,a,b,strokeWidth=if(visible.precision==PointPrecision.EXACT)3.dp.toPx() else 2.dp.toPx())
                listOf(a,b).forEach {drawCircle(surface,5.dp.toPx(),it);drawCircle(color,5.dp.toPx(),it,style=Stroke(2.dp.toPx()))}
                if(visible.needsReview)drawCircle(color,8.dp.toPx(),a,style=Stroke(1.dp.toPx()))
            }
            if (projection != null) pointPlacements.forEach { marker ->
                val preview = pointDrag?.takeIf { it.point.uuid == marker.uuid }
                val fitted = projection.project(DrawingPoint(preview?.xMm ?: marker.xMm.toDouble(), preview?.yMm ?: marker.yMm.toDouble()))
                val projected = if (interactive) viewport.transform(fitted, size.width, size.height) else fitted
                val center = Offset(projected.x.toFloat(), projected.y.toFloat())
                drawCircle(surface, 7.dp.toPx(), center)
                val markerInk=if(marker.mounting.unresolved) androidx.compose.ui.graphics.Color(0xffb3261e) else ink
                drawCircle(markerInk, 7.dp.toPx(), center, style = Stroke(if (marker.precision == PointPrecision.EXACT) 3.dp.toPx() else 2.dp.toPx()))
                drawCircle(markerInk, 2.dp.toPx(), center)
                if(marker.mounting.needsReview) drawCircle(markerInk,10.dp.toPx(),center,style=Stroke(1.dp.toPx()))
                if (marker.uuid == selectedPointUuid) drawCircle(ink, 12.dp.toPx(), center, style = Stroke(3.dp.toPx()))
            }
            val visibleLabels = if (interactive && (zoom != 1f || panX != 0f || panY != 0f))
                layoutMeasurementDrawingForProjectedPoints(
                    points.map { DrawingPoint(it.x.toDouble(), it.y.toDouble()) },
                    size.width, size.height,
                    textLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) }, density,
                    vertexLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) })
            else layout
            visibleLabels.vertexLabels.forEach { label ->
                drawText(vertexLayouts[label.sideIndex], topLeft = Offset(label.left, label.top))
            }
            visibleLabels.labels.forEach { label ->
                drawRoundRect(surface.copy(alpha = 0.96f),
                    topLeft = Offset(label.left, label.top), size = Size(label.width, label.height),
                    cornerRadius = CornerRadius(3.dp.toPx()))
                drawText(textLayouts[label.sideIndex], topLeft = Offset(label.left, label.top))
            }
        }
        if (!fullscreen) {
        Text(if (contour.measuredSides) "Виміряні сторони" else "Сторони за збереженим контуром",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Always include every side in normal wrapping text. Narrow/dense
        // drawings omit colliding inline labels, never measured information.
        Text(labelText.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}
