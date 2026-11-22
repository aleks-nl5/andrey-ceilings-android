package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

internal data class LineCreationPreview(
    val start: PointPositionMm,
    val end: PointPositionMm,
    val parallelWall: Int? = null
)

internal fun previewLineCreation(
    geometry: FrozenPolygonGeometry,
    start: PointPositionMm,
    raw: PointPositionMm,
    previousWall: Int?
): LineCreationPreview {
    val dx = (raw.xMm - start.xMm).toDouble()
    val dy = (raw.yMm - start.yMm).toDouble()
    val length = hypot(dx, dy)
    val free = LineCreationPreview(start, raw)
    if (length < 10.0) return free

    fun candidate(index: Int, held: Boolean): Pair<LineCreationPreview, Double>? {
        val a = geometry.vertices[index]
        val b = geometry.vertices[(index + 1) % geometry.vertices.size]
        val wx = (b.xMm - a.xMm).toDouble()
        val wy = (b.yMm - a.yMm).toDouble()
        val wallLength = hypot(wx, wy)
        if (wallLength == 0.0) return null
        val ux = wx / wallLength
        val uy = wy / wallLength
        val deviation = abs(dx * uy - dy * ux) / length
        if (deviation > (if (held) 0.276 else 0.174)) return null
        val along = dx * ux + dy * uy
        val end = PointPositionMm(
            start.xMm + (along * ux).roundToLong(),
            start.yMm + (along * uy).roundToLong()
        )
        if (!lineStrictlyInside(geometry, start, end)) return null
        return LineCreationPreview(start, end, index) to deviation
    }

    return previousWall?.takeIf { it in geometry.vertices.indices }?.let { candidate(it, held = true)?.first }
        ?: geometry.vertices.indices.mapNotNull { candidate(it, held = false) }.minByOrNull { it.second }?.first
        ?: free
}
