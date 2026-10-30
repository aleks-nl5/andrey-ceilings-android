package com.andreyceilings.app.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.MeasuredOrthogonalSide
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.calculation.CeilingPolygon
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import kotlin.math.hypot
import kotlin.math.round

/** Read-only, scale-correct measured contour; geometry is never derived from pixels. */
@Composable
internal fun MeasurementDrawingPreview(
    frozen: FrozenPolygonGeometry,
    modifier: Modifier = Modifier
) {
    val points = frozen.vertices
    val ink = MaterialTheme.colorScheme.primary
    val textInk = MaterialTheme.colorScheme.onSurface
    val step = frozen.precisionStepMeters
    val valid = runCatching {
        CeilingPolygonCalculator.calculate(
            CeilingPolygon(frozen.shapeType, points), step
        )
    }.isSuccess
    if (!valid) return
    Column(modifier) {
        Text("Креслення заміру", style = MaterialTheme.typography.titleSmall)
        Canvas(
            modifier = Modifier.fillMaxWidth().height(235.dp).padding(8.dp)
        ) {
            val left = points.minOf { it.xMm }.toFloat()
            val right = points.maxOf { it.xMm }.toFloat()
            val top = points.minOf { it.yMm }.toFloat()
            val bottom = points.maxOf { it.yMm }.toFloat()
            val dx = (right - left).coerceAtLeast(1f)
            val dy = (bottom - top).coerceAtLeast(1f)
            val padding = 36.dp.toPx()
            val scale = minOf(
                (size.width - 2f * padding) / dx,
                (size.height - 2f * padding) / dy
            ).coerceAtLeast(0.00001f)
            val startX = (size.width - dx * scale) / 2f
            val startY = (size.height - dy * scale) / 2f
            fun xy(point: CeilingPointMm): Offset = Offset(
                startX + (point.xMm - left) * scale,
                startY + (point.yMm - top) * scale
            )
            val fillPoints = points.map(::xy)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(fillPoints[0].x, fillPoints[0].y)
                fillPoints.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, ink.copy(alpha = .08f))
            drawPath(path, ink, style = Stroke(width = 2.5.dp.toPx()))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(
                    (textInk.alpha * 255).toInt(),
                    (textInk.red * 255).toInt(),
                    (textInk.green * 255).toInt(),
                    (textInk.blue * 255).toInt()
                )
                textSize = 11.sp.toPx()
                textAlign = Paint.Align.CENTER
            }
            fillPoints.forEachIndexed { i, p ->
                drawCircle(ink, radius = 3.dp.toPx(), center = p)
                val q = fillPoints[(i + 1) % fillPoints.size]
                val lenMm = if (frozen.sides.isNotEmpty()) {
                    frozen.sides[i].lengthMm.toDouble()
                } else {
                    val a = points[i]
                    val b = points[(i + 1) % points.size]
                    hypot((b.xMm - a.xMm).toDouble(), (b.yMm - a.yMm).toDouble())
                }
                val mid = Offset((p.x + q.x) / 2f, (p.y + q.y) / 2f)
                val delta = Offset(q.x - p.x, q.y - p.y)
                val length = hypot(delta.x.toDouble(), delta.y.toDouble()).toFloat()
                val nx = if (length > 0f) -delta.y / length else 0f
                val ny = if (length > 0f) delta.x / length else 0f
                val displayed = lenMm / 1000.0
                val label = if (displayed == displayed.toLong().toDouble()) {
                    "${displayed.toLong()} м"
                } else "${round(displayed * 1000.0) / 1000.0} м"
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    mid.x + nx * 14.dp.toPx(),
                    mid.y + ny * 14.dp.toPx(),
                    paint
                )
            }
        }
        Text(
            "Площа: ${frozen.areaSquareMeters} м² · Периметр: ${frozen.perimeterMeters} м",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
