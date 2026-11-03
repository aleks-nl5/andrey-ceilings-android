package com.andreyceilings.shared.measurement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
    fullscreen: Boolean = false
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
    fun move(dx: Float, dy: Float, factor: Float = 1f) {
        val next = MeasurementViewport(zoom, panX, panY).move(dx, dy, factor)
        zoom = next.zoom; panX = next.panX; panY = next.panY
    }
    fun fit() { zoom = 1f; panX = 0f; panY = 0f }
    val currentViewport by rememberUpdatedState(viewport)
    val currentSelection by rememberUpdatedState(onSelectSide)
    val ink = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val labelText = remember(contour) { contour.namedSideLabels }
    val textLayouts = labelText.map { measurer.measure(it, labelStyle) }
    val vertexLayouts = contour.vertexNames.map { measurer.measure(it, labelStyle) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (interactive) Row {
            TextButton(onClick = ::fit) { Text("Вписати креслення") }
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
            .pointerInput(contour, canvasSize) {
                detectTapGestures { point ->
                    focusRequester.requestFocus()
                    val layout = layoutMeasurementDrawing(contour, canvasSize.width.toFloat(), canvasSize.height.toFloat(), emptyList(), density)
                    val points = layout?.points?.map { currentViewport.transform(it, canvasSize.width.toFloat(), canvasSize.height.toFloat()) }
                    points?.let { measurementWallAt(it, point.x, point.y, 24.dp.toPx()) }?.let { currentSelection(it) }
                }
            }
            .pointerInput(Unit) { detectTransformGestures { _, pan, scale, _ -> move(pan.x, pan.y, scale) } }
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
            .semantics { contentDescription = "Контур стелі, ${contour.points.size} вершин" }) {
            val layout = layoutMeasurementDrawing(contour, size.width, size.height,
                textLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) }, density,
                vertexLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) })
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
            points.forEach { drawCircle(ink, 3.dp.toPx(), it) }
            if (!interactive || zoom == 1f && panX == 0f && panY == 0f) {
                layout.vertexLabels.forEach { label ->
                    drawText(vertexLayouts[label.sideIndex], topLeft = Offset(label.left, label.top))
                }
                layout.labels.forEach { label ->
                    drawRoundRect(surface.copy(alpha = 0.96f),
                        topLeft = Offset(label.left, label.top), size = Size(label.width, label.height),
                        cornerRadius = CornerRadius(3.dp.toPx()))
                    drawText(textLayouts[label.sideIndex], topLeft = Offset(label.left, label.top))
                }
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
