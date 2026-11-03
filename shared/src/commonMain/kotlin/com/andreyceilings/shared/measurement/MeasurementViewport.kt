package com.andreyceilings.shared.measurement

import kotlin.math.hypot

/** View transforms and hit testing never enter persisted world geometry. */
data class MeasurementViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun transform(point: DrawingPoint, width: Float, height: Float) = DrawingPoint(
        (point.x - width / 2) * zoom + width / 2 + panX,
        (point.y - height / 2) * zoom + height / 2 + panY)
    fun move(dx: Float, dy: Float, factor: Float = 1f): MeasurementViewport =
        if (!dx.isFinite() || !dy.isFinite() || !factor.isFinite() || factor <= 0) this
        else copy(zoom = (zoom * factor).coerceIn(0.25f, 12f),
            panX = (panX + dx).coerceIn(-100_000f, 100_000f), panY = (panY + dy).coerceIn(-100_000f, 100_000f))
}

fun measurementWallAt(points: List<DrawingPoint>, x: Float, y: Float, tolerance: Float): Int? {
    if (points.size < 3) return null
    fun distance(i: Int): Double {
        val a = points[i]; val b = points[(i + 1) % points.size]
        val dx = b.x - a.x; val dy = b.y - a.y
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else (((x - a.x) * dx + (y - a.y) * dy) / length2).coerceIn(0.0, 1.0)
        return hypot(x - a.x - t * dx, y - a.y - t * dy)
    }
    return points.indices.minByOrNull(::distance)?.takeIf { distance(it) <= tolerance }
}

/** Direct parameter or derived relation. L CD/DE/FA must not acquire independent inputs. */
fun measurementWallParameter(shape: String, side: Int): String? = when (shape) {
    "rectangle" -> if (side % 2 == 0) "length" else "width"
    "l_shaped" -> when (side) { 0 -> "A"; 1 -> "D"; 4 -> "C"; else -> null }
    else -> null
}
fun measurementParameterWall(shape: String, key: String): Int = when (shape to key) {
    "rectangle" to "width" -> 1
    "l_shaped" to "B" -> 5
    "l_shaped" to "C" -> 4
    "l_shaped" to "D" -> 1
    else -> 0
}
fun measurementWallRelation(side: Int): String = when (side) {
    2 -> "CD = A − C. Змініть загальну довжину A або сторону EF (C)."
    3 -> "DE = B − D. Змініть загальну ширину B або сторону BC (D)."
    5 -> "FA = B. Змініть загальну ширину B (BC + DE)."
    else -> ""
}

fun measurementWallParameters(shape: String, side: Int): List<String> =
    measurementWallParameter(shape, side)?.let { listOf(it) } ?: if (shape == "l_shaped") when (side) {
        2 -> listOf("A", "C")
        3 -> listOf("B", "D")
        5 -> listOf("B")
        else -> emptyList()
    } else emptyList()
