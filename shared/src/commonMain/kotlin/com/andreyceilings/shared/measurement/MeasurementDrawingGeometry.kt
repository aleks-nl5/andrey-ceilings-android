package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
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
    return runCatching<MeasurementGeometryView> {
        val geometry = MeasurementGeometryCodec.read(drawingJson)
        require(geometry.shapeType == shape) { "Measurement shape differs from drawing" }
        MeasurementGeometryView.Captured(geometry)
    }.getOrDefault(MeasurementGeometryView.Unavailable)
}

data class DrawingPoint(val x: Double, val y: Double)
data class MeasurementDrawingContour(val points: List<DrawingPoint>, val sideLabels: List<String>,
    val historical: Boolean, val measuredSides: Boolean) {
    val vertexNames: List<String> get() = points.indices.map(::measurementVertexLabel)
    val namedSideLabels: List<String> get() {
        val names = measurementSideLabels(points.size)
        return sideLabels.mapIndexed { i, length -> "${names[i]}: $length" }
    }
}

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
            val maxMeters = CeilingPolygonCalculator.MAX_COORDINATE_MM / 1000.0
            require(a.isFinite() && b.isFinite() && a > 0 && b > 0 && a <= maxMeters && b <= maxMeters)
            MeasurementDrawingContour(listOf(DrawingPoint(0.0, 0.0), DrawingPoint(a * 1000, 0.0),
                DrawingPoint(a * 1000, b * 1000), DrawingPoint(0.0, b * 1000)),
                listOf("$a м", "$b м", "$a м", "$b м"), historical = true, measuredSides = false)
        }
        MeasurementGeometryView.Unavailable -> null
    }
}.getOrNull()

data class DrawingLabelSize(val width: Float, val height: Float)
data class DrawingLabelBounds(
    val sideIndex: Int, val left: Float, val top: Float, val width: Float, val height: Float,
    val needsLeader: Boolean = false
) {
    val right get() = left + width
    val bottom get() = top + height
}
data class MeasurementDrawingLayout(val points: List<DrawingPoint>, val labels: List<DrawingLabelBounds>,
    val vertexLabels: List<DrawingLabelBounds> = emptyList())

/** Rotation in screen axes. The inverse maps touch coordinates back to the original world axes. */
data class MeasurementDrawingRotation(val cosine: Double, val sine: Double) {
    fun apply(point: DrawingPoint) =
        DrawingPoint(point.x * cosine - point.y * sine, point.x * sine + point.y * cosine)

    fun inverse(point: DrawingPoint) =
        DrawingPoint(point.x * cosine + point.y * sine, -point.x * sine + point.y * cosine)

    companion object {
        val Identity = MeasurementDrawingRotation(1.0, 0.0)
    }
}

internal fun canonicalDrawingRotation(points: List<DrawingPoint>, quarterTurns: Int): MeasurementDrawingRotation {
    val dx = points[1].x - points[0].x
    val dy = points[1].y - points[0].y
    val length = hypot(dx, dy)
    if (length == 0.0) return MeasurementDrawingRotation.Identity
    val cosine = -dy / length
    val sine = -dx / length
    return when (quarterTurns.mod(4)) {
        0 -> MeasurementDrawingRotation(cosine, sine)
        1 -> MeasurementDrawingRotation(-sine, cosine)
        2 -> MeasurementDrawingRotation(-cosine, -sine)
        else -> MeasurementDrawingRotation(sine, -cosine)
    }
}

/** The single fit projection used by contour, markers and inverse pointer mapping. */
data class MeasurementDrawingProjection(
    val scale: Double, val offsetX: Double, val offsetY: Double,
    val rotation: MeasurementDrawingRotation = MeasurementDrawingRotation.Identity
) {
    fun project(point: DrawingPoint): DrawingPoint {
        val rotated = rotation.apply(point)
        return DrawingPoint(offsetX + rotated.x * scale, offsetY + rotated.y * scale)
    }

    fun inverse(point: DrawingPoint) =
        rotation.inverse(DrawingPoint((point.x - offsetX) / scale, (point.y - offsetY) / scale))
}

fun measurementDrawingProjection(
    contour: MeasurementDrawingContour, width: Float, height: Float,
    density: Float = 1f, extraWorldPoints: List<DrawingPoint> = emptyList(),
    rotation: MeasurementDrawingRotation = MeasurementDrawingRotation.Identity,
    labelMargin: DrawingLabelSize = DrawingLabelSize(0f, 0f)
): MeasurementDrawingProjection? {
    val worldPoints = (contour.points + extraWorldPoints).map(rotation::apply)
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || density <= 0 ||
        !density.isFinite() || contour.points.size < 3 || worldPoints.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val left = worldPoints.minOf { it.x }; val top = worldPoints.minOf { it.y }
    val dx = worldPoints.maxOf { it.x } - left; val dy = worldPoints.maxOf { it.y } - top
    if (!dx.isFinite() || !dy.isFinite() || dx <= 0 || dy <= 0) return null
    val margin = minOf(36f * density, width * 0.22f, height * 0.22f)
    val horizontal = maxOf(margin, labelMargin.width + 6f * density).coerceAtMost(width * 0.45f)
    val vertical = maxOf(margin, labelMargin.height + 6f * density).coerceAtMost(height * 0.45f)
    val scale = minOf((width - horizontal * 2) / dx, (height - vertical * 2) / dy)
    return MeasurementDrawingProjection(scale, (width - dx * scale) / 2 - left * scale,
        (height - dy * scale) / 2 - top * scale, rotation)
}

/** Prefer the largest scale, with canonical AB as the tie-break for symmetric bounds. */
fun optimalMeasurementDrawingProjection(
    contour: MeasurementDrawingContour, width: Float, height: Float,
    labelMargin: DrawingLabelSize = DrawingLabelSize(0f, 0f)
): MeasurementDrawingProjection? {
    if (contour.points.size < 3) return null
    return (0..1).mapNotNull { quarterTurns ->
        measurementDrawingProjection(contour, width, height,
            rotation = canonicalDrawingRotation(contour.points, quarterTurns), labelMargin = labelMargin)
    }.maxByOrNull { it.scale }
}

/** Aspect-preserving projection. Omitted inline labels remain in the wrapping legend. */
fun layoutMeasurementDrawing(
    contour: MeasurementDrawingContour, width: Float, height: Float,
    sizes: List<DrawingLabelSize>, density: Float = 1f,
    vertexSizes: List<DrawingLabelSize> = emptyList(), extraWorldPoints: List<DrawingPoint> = emptyList()
): MeasurementDrawingLayout? {
    val projection = measurementDrawingProjection(contour, width, height, density, extraWorldPoints) ?: return null
    val points = contour.points.map(projection::project)
    return layoutMeasurementDrawingForProjectedPoints(points, width, height, sizes, density, vertexSizes)
}

/** Label positions use projected view pixels; they are recomputed after zoom/pan. */
fun layoutMeasurementDrawingForProjectedPoints(
    points: List<DrawingPoint>, width: Float, height: Float,
    sizes: List<DrawingLabelSize>, density: Float = 1f,
    vertexSizes: List<DrawingLabelSize> = emptyList(),
    placeCrowdedVertices: Boolean = false
): MeasurementDrawingLayout {
    require(width > 0f && height > 0f && width.isFinite() && height.isFinite() &&
        density > 0f && density.isFinite() &&
        points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() })
    val gap = 3f * density
    fun overlaps(a: DrawingLabelBounds, b: DrawingLabelBounds): Boolean =
        a.left < b.right + gap && a.right + gap > b.left && a.top < b.bottom + gap && a.bottom + gap > b.top
    val vertexLabels = mutableListOf<DrawingLabelBounds>()
    fun fitsExterior(candidate: DrawingLabelBounds): Boolean {
        val left = candidate.left - gap
        val top = candidate.top - gap
        val width = (candidate.width + gap * 2).toDouble()
        val height = (candidate.height + gap * 2).toDouble()
        val center = DrawingPoint(candidate.left + candidate.width / 2.0, candidate.top + candidate.height / 2.0)
        var inside = false
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            if (clipMeasurementWallToViewport(
                    DrawingPoint(a.x - left, a.y - top),
                    DrawingPoint(b.x - left, b.y - top), width, height
                ) != null) return false
            if ((a.y > center.y) != (b.y > center.y) &&
                center.x < a.x + (b.x - a.x) * (center.y - a.y) / (b.y - a.y)) inside = !inside
        }
        return !inside
    }

    fun fitsVertexLabel(candidate: DrawingLabelBounds): Boolean =
        vertexLabels.none { overlaps(candidate, it) } && fitsExterior(candidate)

    val measuredVertices = vertexSizes.take(points.size)
    measuredVertices.forEachIndexed { i, size ->
        if (size.width <= 0 || size.height <= 0 || size.width > width || size.height > height) return@forEachIndexed
        val point = points[i]
        // Offscreen vertices should never produce clamped labels along the border.
        if (point.x !in 0.0..width.toDouble() || point.y !in 0.0..height.toDouble()) return@forEachIndexed
        for ((dx, dy) in listOf(1 to -1, -1 to -1, 1 to 1, -1 to 1)) {
            val x = (point.x.toFloat() + if (dx > 0) gap * 2 else -size.width - gap * 2).coerceIn(0f, width - size.width)
            val y = (point.y.toFloat() + if (dy > 0) gap * 2 else -size.height - gap * 2).coerceIn(0f, height - size.height)
            val candidate = DrawingLabelBounds(i, x, y, size.width, size.height)
            if (fitsVertexLabel(candidate)) { vertexLabels.add(candidate); break }
        }
    }
    if (placeCrowdedVertices) {
        val placed = vertexLabels.map { it.sideIndex }.toSet()
        measuredVertices.forEachIndexed { i, size ->
            if (i in placed || size.width <= 0f || size.height <= 0f) return@forEachIndexed
            val point = points[i]
            if (point.x !in 0.0..width.toDouble() || point.y !in 0.0..height.toDouble()) return@forEachIndexed
            var closest: DrawingLabelBounds? = null
            var distanceSquared = Double.POSITIVE_INFINITY
            // Search spare slots only after trying the four adjacent positions for every vertex.
            var y = gap
            while (y + size.height <= height - gap) {
                var x = gap
                while (x + size.width <= width - gap) {
                    val dx = point.x - (x + size.width / 2)
                    val dy = point.y - (y + size.height / 2)
                    val distance = dx * dx + dy * dy
                    if (distance < distanceSquared) {
                        val candidate = DrawingLabelBounds(i, x, y, size.width, size.height, needsLeader = true)
                        if (fitsVertexLabel(candidate)) {
                            closest = candidate
                            distanceSquared = distance
                        }
                    }
                    x += size.width + gap
                }
                y += size.height + gap
            }
            closest?.let(vertexLabels::add)
        }
    }
    val areaSign = if (points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]
        a.x * b.y - b.x * a.y
    } >= 0) 1 else -1
    val labels = mutableListOf<DrawingLabelBounds>()
    sizes.take(points.size).forEachIndexed { i, size ->
        if (size.width <= 0 || size.height <= 0 || size.width > width || size.height > height) return@forEachIndexed
        val a = points[i]; val b = points[(i + 1) % points.size]
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length <= 0) return@forEachIndexed
        // The visible portion of an enlarged wall may have its midpoint elsewhere.
        val clipped = clipMeasurementWallToViewport(a, b, width.toDouble(), height.toDouble())
            ?: return@forEachIndexed
        val midX = (clipped.first.x + clipped.second.x) / 2
        val midY = (clipped.first.y + clipped.second.y) / 2
        for (distance in listOf(14f, 28f, 42f)) {
            val x = ((midX + (b.y - a.y) / length * areaSign * distance * density)
                .toFloat() - size.width / 2).coerceIn(0f, width - size.width)
            val y = ((midY - (b.x - a.x) / length * areaSign * distance * density)
                .toFloat() - size.height / 2).coerceIn(0f, height - size.height)
            val candidate = DrawingLabelBounds(i, x, y, size.width, size.height)
            val overlapsLabel = (labels + vertexLabels).any { overlaps(candidate, it) }
            val overlapsVertex = points.any { it.x >= x - gap && it.x <= candidate.right + gap &&
                it.y >= y - gap && it.y <= candidate.bottom + gap }
            if (!overlapsLabel && !overlapsVertex) { labels.add(candidate); break }
        }
    }
    return MeasurementDrawingLayout(points, labels, vertexLabels)
}

/** Liang–Barsky clip in view pixels for stable zoomed labels, never stored geometry. */
private fun clipMeasurementWallToViewport(
    a: DrawingPoint, b: DrawingPoint, width: Double, height: Double
): Pair<DrawingPoint, DrawingPoint>? {
    val dx = b.x - a.x
    val dy = b.y - a.y
    var start = 0.0
    var end = 1.0
    fun clip(p: Double, q: Double): Boolean {
        if (p == 0.0) return q >= 0.0
        val r = q / p
        if (p < 0.0) {
            if (r > end) return false
            if (r > start) start = r
        } else {
            if (r < start) return false
            if (r < end) end = r
        }
        return true
    }
    if (!clip(-dx, a.x) || !clip(dx, width - a.x) ||
        !clip(-dy, a.y) || !clip(dy, height - a.y) || start > end) return null
    return DrawingPoint(a.x + dx * start, a.y + dy * start) to
        DrawingPoint(a.x + dx * end, a.y + dy * end)
}
