package com.andreyceilings.shared.measurement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.IntSize
import kotlin.math.pow
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.ceil
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** Typed Canvas state for interaction verification; never persisted or spoken as labels. */
object MeasurementDrawingSemantics {
    val Points=SemanticsPropertyKey<List<PointPlacement>>("Measurement points")
    val Lines=SemanticsPropertyKey<List<LinePlacement>>("Measurement lines")
    val Areas=SemanticsPropertyKey<List<AreaFeature>>("Measurement areas")
    val SelectedPoint=SemanticsPropertyKey<String>("Selected measurement point")
    val SelectedLine=SemanticsPropertyKey<String>("Selected measurement line")
    val SelectedArea=SemanticsPropertyKey<String>("Selected measurement area")
    val PointDrag=SemanticsPropertyKey<PointDragPreview>("Measurement point drag")
    val Viewport = SemanticsPropertyKey<MeasurementViewport>("Measurement viewport")
    val Projection = SemanticsPropertyKey<MeasurementDrawingProjection>("Measurement world-to-screen projection")
    val SelectedWall = SemanticsPropertyKey<Int>("Selected measurement wall")
    val EditingEnabled=SemanticsPropertyKey<Boolean>("Measurement overlay editing enabled")
}

/** The fitted world center and physical scale are independent of the live Canvas bounds. */
private data class MeasurementDrawingCamera(val center: DrawingPoint, val pixelsPerMm: Double) {
    fun projection(width: Float, height: Float) = MeasurementDrawingProjection(
        pixelsPerMm, width / 2.0 - center.x * pixelsPerMm, height / 2.0 - center.y * pixelsPerMm
    )
}

private val measurementCameraSaver = listSaver<MeasurementDrawingCamera?, Double>(
    save = { it?.let { camera -> listOf(camera.center.x, camera.center.y, camera.pixelsPerMm) }.orEmpty() },
    restore = { MeasurementDrawingCamera(DrawingPoint(it[0], it[1]), it[2]) }
)

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
    onPreviewLineDrag: (Double,Double)->Unit = {_,_->}, onFinishLineDrag: ()->Unit = {}, onCancelLineDrag: ()->Unit = {},
    areaFeatures: List<AreaFeature> = emptyList(), selectedAreaUuid: String? = null,
    areaPlacementMode: Boolean = false, onAreaTap: (Long,Long)->Unit = {_,_->}, onSelectArea: (String)->Unit = {},
    areaEditingEnabled: Boolean = false, areaDrag: AreaFeature? = null, areaCreationPoints: List<PointPositionMm> = emptyList(),
    onBeginAreaDrag: (String)->Unit = {}, onPreviewAreaDrag: (Double,Double)->Unit = {_,_->},
    onFinishAreaDrag: ()->Unit = {}, onCancelAreaDrag: ()->Unit = {},
    fillAvailableHeight: Boolean = fullscreen,
    showSideLegend: Boolean = !fullscreen,
    retainCameraOnResize: Boolean = false,
    verticalViewControls: Boolean = false,
    cameraFitReady: Boolean = true,
    showWorkspaceGrid: Boolean = false,
    fitTop: Dp = 0.dp,
    fitBottom: Dp = 0.dp
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
    var fittedCamera by rememberSaveable(stateSaver = measurementCameraSaver) {
        mutableStateOf<MeasurementDrawingCamera?>(null)
    }
    val focusRequester = remember { FocusRequester() }
    var markerGesture by remember { mutableStateOf(false) }
    var cameraAdjusted by rememberSaveable { mutableStateOf(false) }
    val currentSelection by rememberUpdatedState(onSelectSide)
    val currentPoints by rememberUpdatedState(pointPlacements)
    val currentPointSelection by rememberUpdatedState(onSelectPoint)
    val currentPointTap by rememberUpdatedState(onPlacePoint)
    val currentMode by rememberUpdatedState(placementMode)
    val fitPoints = if (fitPointPlacements) pointPlacements.map { DrawingPoint(it.xMm.toDouble(), it.yMm.toDouble()) } +
        linePlacements.flatMap {listOf(DrawingPoint(it.start.xMm.toDouble(),it.start.yMm.toDouble()),DrawingPoint(it.end.xMm.toDouble(),it.end.yMm.toDouble()))} else emptyList()
    val currentAreas by rememberUpdatedState(areaFeatures)
    val currentAreaMode by rememberUpdatedState(areaPlacementMode)
    val currentAreaTap by rememberUpdatedState(onAreaTap)
    val currentAreaSelection by rememberUpdatedState(onSelectArea)
    val currentSelectedArea by rememberUpdatedState(selectedAreaUuid)
    val currentBeginAreaDrag by rememberUpdatedState(onBeginAreaDrag)
    val currentPreviewAreaDrag by rememberUpdatedState(onPreviewAreaDrag)
    val currentFinishAreaDrag by rememberUpdatedState(onFinishAreaDrag)
    val currentCancelAreaDrag by rememberUpdatedState(onCancelAreaDrag)
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
    val currentContour by rememberUpdatedState(contour)
    val density = LocalDensity.current.density
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    fun fitProjection(width: Float, height: Float): MeasurementDrawingProjection? {
        val railWidth = if (height >= 144f * density) 48f else 144f
        val rail = if (interactive && verticalViewControls) minOf(railWidth * density, width * 0.30f) else 0f
        val top = minOf(fitTop.value * density, height * 0.28f)
        val bottom = minOf(fitBottom.value * density, height * 0.28f)
        val fitted = measurementDrawingProjection(currentContour, width - rail, height - top - bottom, density, currentFitPoints)
            ?: return null
        return fitted.copy(offsetX = fitted.offsetX + if (isRtl) rail else 0f, offsetY = fitted.offsetY + top)
    }

    fun fitCamera(size: IntSize): MeasurementDrawingCamera? {
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val projection = fitProjection(width, height) ?: return null
        return MeasurementDrawingCamera(projection.inverse(DrawingPoint(width / 2.0, height / 2.0)), projection.scale)
    }

    LaunchedEffect(canvasSize, cameraFitReady, fitTop, fitBottom) {
        if (retainCameraOnResize && cameraFitReady && !cameraAdjusted && fittedCamera == null) {
            // A readiness change can remove the loading row in this same frame.
            // Wait for its layout and let a subsequent size change restart the fit.
            withFrameNanos { }
            withFrameNanos { }
            val camera = fitCamera(canvasSize)
            if (!cameraAdjusted && camera != null) fittedCamera = camera
        }
    }

    fun move(dx: Float, dy: Float, factor: Float = 1f) {
        if (markerGesture) return
        val current = MeasurementViewport(zoom, panX, panY)
        val next = current.move(dx, dy, factor)
        if (next == current) return
        if (retainCameraOnResize) {
            if (fittedCamera == null) fittedCamera = fitCamera(canvasSize)
            cameraAdjusted = true
        }
        zoom = next.zoom
        panX = next.panX
        panY = next.panY
    }

    fun fit() {
        if (retainCameraOnResize) {
            fittedCamera = fitCamera(canvasSize) ?: fittedCamera
            cameraAdjusted = true
        }
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    fun projectDrawing(width: Float, height: Float): MeasurementDrawingProjection? {
        if (width <= 0f || height <= 0f) return null
        val base = fittedCamera?.takeIf { retainCameraOnResize }?.projection(width, height)
            ?: fitProjection(width, height)
            ?: return null
        if (!interactive) return base
        val viewport = MeasurementViewport(zoom, panX, panY)
        val origin = viewport.transform(DrawingPoint(base.offsetX, base.offsetY), width, height)
        return MeasurementDrawingProjection(base.scale * viewport.zoom, origin.x, origin.y)
    }
    val semanticsProjection = projectDrawing(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    val currentSelectedPoint by rememberUpdatedState(selectedPointUuid)
    val currentBeginDrag by rememberUpdatedState(onBeginPointDrag)
    val currentPreviewDrag by rememberUpdatedState(onPreviewPointDrag)
    val currentFinishDrag by rememberUpdatedState(onFinishPointDrag)
    val currentCancelDrag by rememberUpdatedState(onCancelPointDrag)
    val ink = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val gridInk = MaterialTheme.colorScheme.onSurface
    val warningInk = if (showWorkspaceGrid) MaterialTheme.colorScheme.error else Color(0xFFB3261E)
    val areaWarningInk = if (showWorkspaceGrid) warningInk else Color(0xFFD34C44)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    // The contour already has vertex letters A/B/C; draw only dimensions
    // beside segments. AB/BC labels remain in the separate edit/selection list.
    val labelText = remember(contour) { contour.sideLabels }
    val textLayouts = labelText.map { measurer.measure(it, labelStyle) }
    val dimensionLayouts = mountingDimensions.map { measurer.measure(it.label,labelStyle) }
    val vertexLayouts = contour.vertexNames.map { measurer.measure(it, labelStyle) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (interactive && !verticalViewControls) Row {
            TextButton(enabled = !markerGesture, onClick = ::fit) { Text("Вписати креслення") }
            TextButton(onClick = { move(0f, 0f, 1 / 1.25f) }) { Text("−") }
            TextButton(onClick = { move(0f, 0f, 1.25f) }) { Text("+") }
        } else if (!interactive) Text("Креслення заміру", style = MaterialTheme.typography.titleSmall)
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
            .pointerInput(contour, canvasSize, pointEditingEnabled, lineEditingEnabled, areaEditingEnabled, fullscreen, editPointsInFullscreen) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = canvasSize.width.toFloat(); val height = canvasSize.height.toFloat()
                    val projection = projectDrawing(width, height) ?: return@awaitEachGesture
                    fun world(position: Offset) = projection.inverse(DrawingPoint(position.x.toDouble(), position.y.toDouble()))
                    fun hit(position: Offset): PointPlacement? = currentPoints.minByOrNull { marker ->
                        val p = projection.project(DrawingPoint(marker.xMm.toDouble(), marker.yMm.toDouble()))
                        hypot(p.x - position.x, p.y - position.y)
                    }?.takeIf { marker ->
                        val p = projection.project(DrawingPoint(marker.xMm.toDouble(), marker.yMm.toDouble()))
                        hypot(p.x - position.x, p.y - position.y) <= 24.dp.toPx()
                    }
                    fun lineDistance(line:LinePlacement,position:Offset):Double {
                        fun screen(p:PointPositionMm)=projection.project(DrawingPoint(p.xMm.toDouble(),p.yMm.toDouble()))
                        val a=screen(line.start);val b=screen(line.end)
                        val dx=b.x-a.x;val dy=b.y-a.y
                        val square=dx*dx+dy*dy
                        val t=if(square<=1e-12)0.0 else (((position.x-a.x)*dx+(position.y-a.y)*dy)/square).coerceIn(0.0,1.0)
                        return hypot(position.x-a.x-t*dx,position.y-a.y-t*dy)
                    }
                    fun areaDistance(feature:AreaFeature) = areaHitDistance(feature.shape,DrawingPoint(down.position.x.toDouble(),down.position.y.toDouble())) {p ->
                        projection.project(DrawingPoint(p.xMm.toDouble(),p.yMm.toDouble()))
                    }
                    val halo=24.dp.toPx()
                    // A selected target owns its halo before unrelated overlays. Otherwise
                    // POINT > LINE > AREA; equal filled-area hits use the last painted UUID.
                    val selectedArea=currentAreas.find {it.uuid==currentSelectedArea && areaDistance(it)<=halo}
                    val selectedLine=currentLines.find {it.uuid==currentSelectedLine && lineDistance(it,down.position)<=halo}
                    val selectedMarker=currentPoints.find {it.uuid==currentSelectedPoint}?.takeIf {p ->
                        val screen=projection.project(DrawingPoint(p.xMm.toDouble(),p.yMm.toDouble()))
                        hypot(screen.x-down.position.x,screen.y-down.position.y)<=halo
                    }
                    val creating=currentAreaMode || currentLineMode || currentMode
                    val area=selectedArea ?: currentAreas.asReversed().minByOrNull(::areaDistance)?.takeIf {areaDistance(it)<=halo}
                    val line=selectedLine ?: currentLines.minByOrNull {lineDistance(it,down.position)}?.takeIf {lineDistance(it,down.position)<=halo}
                    val marker=if(currentAreaMode || currentLineMode) null else selectedMarker ?: hit(down.position)
                    var ownsMarker=pointEditingEnabled && !currentAreaMode && !currentLineMode && (!fullscreen || editPointsInFullscreen) && selectedMarker!=null
                    var ownsLine=lineEditingEnabled && !creating && !ownsMarker && selectedLine!=null
                    var ownsArea=areaEditingEnabled && !creating && !ownsMarker && !ownsLine && selectedArea!=null
                    val startWorld = world(down.position)
                    var moved = false; var multi = false
                    var last = down.position
                    if(ownsArea){markerGesture=true;currentBeginAreaDrag(checkNotNull(selectedArea).uuid)}
                    if(ownsLine) {markerGesture=true;currentBeginLineDrag(checkNotNull(selectedLine).uuid)}
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
                                if(ownsArea){ownsArea=false;markerGesture=false;currentCancelAreaDrag()}
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
                                if(ownsArea){val target=world(position);currentPreviewAreaDrag(target.x-startWorld.x,target.y-startWorld.y)} else if(ownsLine) {val target=world(position);currentPreviewLineDrag(target.x-startWorld.x,target.y-startWorld.y)} else if (ownsMarker) {
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
                                if(ownsArea){
                                    markerGesture=false
                                    if(moved){val target=world(position);currentPreviewAreaDrag(target.x-startWorld.x,target.y-startWorld.y);currentFinishAreaDrag()} else currentCancelAreaDrag()
                                    ownsArea=false
                                }
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
                                    if(currentAreaMode) {
                                        val target=world(position)
                                        if(target.x.isFinite() && target.y.isFinite() && target.x in -1_000_000.0..1_000_000.0 && target.y in -1_000_000.0..1_000_000.0)currentAreaTap(target.x.roundToLong(),target.y.roundToLong())
                                    } else if(currentLineMode) {
                                        val target=world(position)
                                        if(target.x.isFinite() && target.y.isFinite() && target.x in -1_000_000.0..1_000_000.0 && target.y in -1_000_000.0..1_000_000.0)currentLineTap(target.x.roundToLong(),target.y.roundToLong())
                                    } else if(marker!=null && (!fullscreen || editPointsInFullscreen)) currentPointSelection(marker.uuid)
                                    else if(selectedLine!=null)currentLineSelection(selectedLine.uuid)
                                    else if(selectedArea!=null)currentAreaSelection(selectedArea.uuid)
                                    else if(line!=null)currentLineSelection(line.uuid)
                                    else if(area!=null)currentAreaSelection(area.uuid)
                                    else if (currentMode && !fullscreen) {
                                        val target = world(position)
                                        val bound = com.andreyceilings.shared.calculation.CeilingPolygonCalculator.MAX_COORDINATE_MM.toDouble()
                                        if (target.x.isFinite() && target.y.isFinite() && target.x in -bound..bound && target.y in -bound..bound)
                                            currentPointTap(target.x.roundToLong(), target.y.roundToLong())
                                    } else {
                                        val points = contour.points.map(projection::project)
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
                        if(ownsArea)currentCancelAreaDrag()
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
        BoxWithConstraints(Modifier.fillMaxWidth().then(
            if (fillAvailableHeight) Modifier.weight(1f) else Modifier.height(if (compact) 155.dp else 235.dp)
        )) {
            Canvas(Modifier.fillMaxSize()
                .clipToBounds().onSizeChanged { canvasSize = it }.then(inputModifier)
                .semantics {
                    contentDescription = "Контур стелі, ${contour.points.size} вершин, позначок: ${pointPlacements.size}, ліній: ${linePlacements.size}, площинних об’єктів: ${areaFeatures.size}"
                    this[MeasurementDrawingSemantics.Points]=pointPlacements
                    this[MeasurementDrawingSemantics.Lines]=linePlacements
                    this[MeasurementDrawingSemantics.Areas]=areaFeatures
                    this[MeasurementDrawingSemantics.Viewport] = viewport
                    semanticsProjection?.let {
                        this[MeasurementDrawingSemantics.Projection] = it
                    }
                    selectedSide?.let { this[MeasurementDrawingSemantics.SelectedWall] = it }
                    this[MeasurementDrawingSemantics.EditingEnabled]=pointEditingEnabled || lineEditingEnabled || areaEditingEnabled
                    selectedPointUuid?.let {this[MeasurementDrawingSemantics.SelectedPoint]=it}
                    selectedLineUuid?.let {this[MeasurementDrawingSemantics.SelectedLine]=it}
                    selectedAreaUuid?.let {this[MeasurementDrawingSemantics.SelectedArea]=it}
                    pointDrag?.let {this[MeasurementDrawingSemantics.PointDrag]=it}
                }) {
                val projection = projectDrawing(size.width, size.height) ?: return@Canvas
                if (showWorkspaceGrid) drawMeasurementGrid(projection, gridInk)
                val projectedPoints = contour.points.map(projection::project)
                val points = projectedPoints.map { Offset(it.x.toFloat(), it.y.toFloat()) }
                fun screen(point: DrawingPoint): Offset {
                    val projected = projection.project(point)
                    return Offset(projected.x.toFloat(), projected.y.toFloat())
                }
                fun screenPosition(point: PointPositionMm) =
                    screen(DrawingPoint(point.xMm.toDouble(), point.yMm.toDouble()))
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
                areaFeatures.forEach {saved ->
                    val feature=areaDrag?.takeIf {it.uuid==saved.uuid} ?: saved
                    val color = if (feature.unresolved) areaWarningInk else ink
                    val width=if(feature.uuid==selectedAreaUuid)4.dp.toPx() else 2.dp.toPx()
                    when(val shape=feature.shape) {
                        is AreaShape.Circle -> {
                            val center=screenPosition(shape.center)
                            val radius=(screenPosition(PointPositionMm(shape.center.xMm+shape.radiusMm,shape.center.yMm))-center).getDistance()
                            drawCircle(color.copy(alpha=0.16f),radius,center)
                            drawCircle(color,radius,center,style=Stroke(width))
                        }
                        is AreaShape.Polygon -> {
                            val vertices=shape.vertices.map(::screenPosition)
                            val areaPath=Path().apply {moveTo(vertices.first().x,vertices.first().y);vertices.drop(1).forEach {lineTo(it.x,it.y)};close()}
                            drawPath(areaPath,color.copy(alpha=0.16f));drawPath(areaPath,color,style=Stroke(width))
                        }
                    }
                    if(feature.uuid==selectedAreaUuid)feature.shape.positions.forEach {drawCircle(color,5.dp.toPx(),screenPosition(it))}
                }
                areaCreationPoints.forEach {drawCircle(ink,5.dp.toPx(),screenPosition(it))}
                areaCreationPoints.zipWithNext().forEach {(a,b)->drawLine(ink.copy(alpha=0.6f),screenPosition(a),screenPosition(b),strokeWidth=2.dp.toPx())}
                mountingDimensions.forEachIndexed { index, dimension ->
                    val a=screen(dimension.start);val b=screen(dimension.end)
                    drawLine(ink,a,b,strokeWidth=1.dp.toPx())
                    drawCircle(ink,2.dp.toPx(),b)
                    val label=dimensionLayouts[index]
                    val mid=(a+b)/2f
                    drawRect(surface.copy(alpha=0.96f),mid,Size(label.size.width.toFloat(),label.size.height.toFloat()))
                    drawText(label,topLeft=mid)
                }
                lineStartPoint?.let { start ->
                    drawCircle(ink, 6.dp.toPx(), screenPosition(start), style = Stroke(2.dp.toPx()))
                }
                linePlacements.forEach { line ->
                    val visible = lineDrag?.takeIf { it.uuid == line.uuid } ?: line
                    val a = screenPosition(visible.start)
                    val b = screenPosition(visible.end)
                    val color = if (visible.unresolved) warningInk else ink
                    if(line.uuid==selectedLineUuid)drawLine(color.copy(alpha=0.25f),a,b,strokeWidth=14.dp.toPx())
                    drawLine(color,a,b,strokeWidth=if(visible.precision==PointPrecision.EXACT)3.dp.toPx() else 2.dp.toPx())
                    listOf(a,b).forEach {drawCircle(surface,5.dp.toPx(),it);drawCircle(color,5.dp.toPx(),it,style=Stroke(2.dp.toPx()))}
                    if(visible.needsReview)drawCircle(color,8.dp.toPx(),a,style=Stroke(1.dp.toPx()))
                }
                pointPlacements.forEach { marker ->
                    val preview = pointDrag?.takeIf { it.point.uuid == marker.uuid }
                    val center = screen(DrawingPoint(preview?.xMm ?: marker.xMm.toDouble(), preview?.yMm ?: marker.yMm.toDouble()))
                    drawCircle(surface, 7.dp.toPx(), center)
                    val markerInk = if (marker.mounting.unresolved) warningInk else ink
                    drawCircle(markerInk, 7.dp.toPx(), center, style = Stroke(if (marker.precision == PointPrecision.EXACT) 3.dp.toPx() else 2.dp.toPx()))
                    drawCircle(markerInk, 2.dp.toPx(), center)
                    if(marker.mounting.needsReview) drawCircle(markerInk,10.dp.toPx(),center,style=Stroke(1.dp.toPx()))
                    if (marker.uuid == selectedPointUuid) drawCircle(ink, 12.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                }
                val visibleLabels = layoutMeasurementDrawingForProjectedPoints(
                    projectedPoints, size.width, size.height,
                    textLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) }, density,
                    vertexLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) }
                )
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
            if (interactive && verticalViewControls) {
                val actions: @Composable () -> Unit = {
                    MeasurementIconAction("Вписати креслення", Icons.Default.CenterFocusStrong, !markerGesture, true, ::fit)
                    MeasurementIconAction("Збільшити масштаб", Icons.Default.ZoomIn, !markerGesture, true) {
                        move(0f, 0f, 1.25f)
                    }
                    MeasurementIconAction("Зменшити масштаб", Icons.Default.ZoomOut, !markerGesture, true) {
                        move(0f, 0f, 1 / 1.25f)
                    }
                }
                val rail = Modifier.align(Alignment.CenterEnd).testTag("measurement-edit-zoom-rail")
                if (maxHeight >= 144.dp) Column(rail.width(48.dp)) { actions() }
                else Row(rail) { actions() }
            }
        }
        if (showSideLegend) {
        Text(if (contour.measuredSides) "Виміряні сторони" else "Сторони за збереженим контуром",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Always include every side in normal wrapping text. Narrow/dense
        // drawings omit colliding inline labels, never measured information.
        Text(labelText.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun DrawScope.drawMeasurementGrid(projection: MeasurementDrawingProjection, ink: Color) {
    val minimumSpacing = 16.dp.toPx()
    var majorMm = 500.0
    while (majorMm * projection.scale / 5 < minimumSpacing) majorMm *= 2
    val spacing = majorMm * projection.scale / 5
    fun gridLines(origin: Double, extent: Float, line: (Float, Color) -> Unit) {
        var index = ceil(-origin / spacing).toLong()
        var coordinate = origin + index * spacing
        while (coordinate < extent) {
            line(coordinate.toFloat(), ink.copy(alpha = if (index % 5 == 0L) 0.15f else 0.065f))
            index++
            coordinate = origin + index * spacing
        }
    }
    gridLines(projection.offsetX, size.width) { x, color -> drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx()) }
    gridLines(projection.offsetY, size.height) { y, color -> drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
}
