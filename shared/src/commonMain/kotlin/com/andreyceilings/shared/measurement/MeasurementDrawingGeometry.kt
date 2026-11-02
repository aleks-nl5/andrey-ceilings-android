package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/** Read-only inputs. Historical dimensions never acquire an invented frozen precision. */
sealed interface MeasurementGeometryView {
    data class Captured(val geometry: FrozenPolygonGeometry) : MeasurementGeometryView
    data class HistoricalRectangle(val lengthMeters: Double, val widthMeters: Double) : MeasurementGeometryView
    data object Unavailable : MeasurementGeometryView
}

fun measurementGeometryForViewing(
    shape: String, drawingJson: String, lengthMeters: Double, widthMeters: Double
): MeasurementGeometryView {
    // Only the actual historical empty-object format is a legacy fallback.
    if (shape == "rectangle" && drawingJson.filterNot { it.isWhitespace() } == "{}") {
        val legacy = MeasurementGeometryView.HistoricalRectangle(lengthMeters, widthMeters)
        return if (measurementDrawingContour(legacy) != null) legacy else MeasurementGeometryView.Unavailable
    }
    return runCatching {
        val geometry = MeasurementGeometryCodec.read(drawingJson)
        require(geometry.shapeType == shape) { "Measurement shape differs from drawing" }
        MeasurementGeometryView.Captured(geometry)
    }.getOrDefault(MeasurementGeometryView.Unavailable)
}

data class DrawingPoint(val x: Double, val y: Double)
data class MeasurementDrawingContour(val points: List<DrawingPoint>, val sideLabels: List<String>,
    val historical: Boolean, val measuredSides: Boolean)

internal fun formatMillimetres(mm: Long): String {
    val fraction = (mm % 1000).toString().padStart(3, '0').trimEnd('0')
    return "${mm / 1000}" + (if (fraction.isEmpty()) "" else ".$fraction") + " м"
}

/** Conversion for drawing only. The saved integer geometry and totals are never modified. */
fun measurementDrawingContour(view: MeasurementGeometryView): MeasurementDrawingContour? = runCatching {
    when (view) {
        is MeasurementGeometryView.Captured -> {
            val g = FrozenPolygonGeometryContract.requireValid(view.geometry)
            MeasurementDrawingContour(g.vertices.map { DrawingPoint(it.xMm.toDouble(), it.yMm.toDouble()) },
                g.vertices.indices.map { i ->
                    if (g.sides.isNotEmpty()) formatMillimetres(g.sides[i].lengthMm)
                    else {
                        val a = g.vertices[i]; val b = g.vertices[(i + 1) % g.vertices.size]
                        formatMillimetres(hypot((b.xMm - a.xMm).toDouble(), (b.yMm - a.yMm).toDouble()).roundToLong())
                    }
                }, historical = false, measuredSides = g.sides.isNotEmpty())
        }
        is MeasurementGeometryView.HistoricalRectangle -> {
            val a = view.lengthMeters; val b = view.widthMeters
            require(a.isFinite() && b.isFinite() && a > 0 && b > 0 && a <= 1000 && b <= 1000)
            MeasurementDrawingContour(listOf(DrawingPoint(0.0, 0.0), DrawingPoint(a * 1000, 0.0),
                DrawingPoint(a * 1000, b * 1000), DrawingPoint(0.0, b * 1000)),
                listOf("$a м", "$b м", "$a м", "$b м"), historical = true, measuredSides = false)
        }
        MeasurementGeometryView.Unavailable -> null
    }
}.getOrNull()

data class DrawingLabelSize(val width: Float, val height: Float)
data class DrawingLabelBounds(val sideIndex: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
}
data class MeasurementDrawingLayout(val points: List<DrawingPoint>, val labels: List<DrawingLabelBounds>)

/** Aspect-preserving projection. Omitted inline labels remain in the wrapping legend. */
fun layoutMeasurementDrawing(
    contour: MeasurementDrawingContour, width: Float, height: Float,
    sizes: List<DrawingLabelSize>, density: Float = 1f
): MeasurementDrawingLayout? {
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 ||
        density <= 0 || !density.isFinite() || contour.points.size < 3 ||
        contour.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val left = contour.points.minOf { it.x }; val top = contour.points.minOf { it.y }
    val dx = contour.points.maxOf { it.x } - left; val dy = contour.points.maxOf { it.y } - top
    if (dx <= 0 || dy <= 0) return null
    val margin = minOf(36f * density, width * 0.22f, height * 0.22f)
    val scale = minOf((width - margin * 2) / dx, (height - margin * 2) / dy)
    val ox = (width - dx * scale) / 2; val oy = (height - dy * scale) / 2
    val points = contour.points.map { DrawingPoint(ox + (it.x - left) * scale, oy + (it.y - top) * scale) }
    val areaSign = if (contour.points.indices.sumOf { i ->
        val a = contour.points[i]; val b = contour.points[(i + 1) % contour.points.size]
        a.x * b.y - b.x * a.y
    } >= 0) 1 else -1
    val labels = mutableListOf<DrawingLabelBounds>()
    sizes.take(points.size).forEachIndexed { i, size ->
        if (size.width <= 0 || size.height <= 0 || size.width > width || size.height > height) return@forEachIndexed
        val a = points[i]; val b = points[(i + 1) % points.size]
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length <= 0) return@forEachIndexed
        for (distance in listOf(14f, 28f, 42f)) {
            val x = (((a.x + b.x) / 2 + (b.y - a.y) / length * areaSign * distance * density)
                .toFloat() - size.width / 2).coerceIn(0f, width - size.width)
            val y = (((a.y + b.y) / 2 - (b.x - a.x) / length * areaSign * distance * density)
                .toFloat() - size.height / 2).coerceIn(0f, height - size.height)
            val candidate = DrawingLabelBounds(i, x, y, size.width, size.height)
            val gap = 3f * density
            val overlapsLabel = labels.any { candidate.left < it.right + gap && candidate.right + gap > it.left &&
                candidate.top < it.bottom + gap && candidate.bottom + gap > it.top }
            val overlapsVertex = points.any { it.x >= x - gap && it.x <= candidate.right + gap &&
                it.y >= y - gap && it.y <= candidate.bottom + gap }
            if (!overlapsLabel && !overlapsVertex) { labels.add(candidate); break }
        }
    }
    return MeasurementDrawingLayout(points, labels)
}
