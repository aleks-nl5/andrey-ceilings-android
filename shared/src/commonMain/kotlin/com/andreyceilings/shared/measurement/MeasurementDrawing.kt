package com.andreyceilings.shared.measurement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

/** Shared Android/Windows/iOS renderer. All dimensions come from saved world geometry. */
@Composable
fun MeasurementDrawing(
    geometry: MeasurementGeometryView,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val contour = remember(geometry) { measurementDrawingContour(geometry) }
    if (contour == null) {
        Text("Креслення недоступне: неповна або некоректна геометрія.", modifier,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val ink = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val labelText = remember(contour) { contour.sideLabels.mapIndexed { i, label -> "${i + 1}: $label" } }
    val textLayouts = labelText.map { measurer.measure(it, labelStyle) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Креслення заміру", style = MaterialTheme.typography.titleSmall)
        if (contour.historical) Text(
            "Історичний прямокутник зі збережених розмірів. Точність креслення не збережена.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(if (compact) 155.dp else 235.dp)
            .semantics { contentDescription = "Контур стелі, ${contour.points.size} вершин" }) {
            val layout = layoutMeasurementDrawing(contour, size.width, size.height,
                textLayouts.map { DrawingLabelSize(it.size.width.toFloat(), it.size.height.toFloat()) }, density)
                ?: return@Canvas
            val points = layout.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, ink.copy(alpha = 0.08f))
            drawPath(path, ink, style = Stroke(width = 2.5.dp.toPx()))
            points.forEach { drawCircle(ink, 3.dp.toPx(), it) }
            layout.labels.forEach { label ->
                drawRoundRect(surface.copy(alpha = 0.96f),
                    topLeft = Offset(label.left, label.top), size = Size(label.width, label.height),
                    cornerRadius = CornerRadius(3.dp.toPx()))
                drawText(textLayouts[label.sideIndex], topLeft = Offset(label.left, label.top))
            }
        }
        Text(if (contour.measuredSides) "Виміряні сторони" else "Сторони за збереженим контуром",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Always include every side in normal wrapping text. Narrow/dense
        // drawings omit colliding inline labels, never measured information.
        Text(labelText.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}
