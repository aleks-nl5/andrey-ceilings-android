package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.input.DecimalTextPolicy
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.math.hypot
import kotlin.math.roundToLong

/** Metres → signed integer mm. Incomplete text stays in the draft; never rounded or parsed as Double. */
fun parsePointMetres(text: String, boundMm: Long = CeilingPolygonCalculator.MAX_COORDINATE_MM): Long? {
    val input = DecimalTextPolicy(3, signed = true).committed(text.trim()) ?: return null
    val negative = input.startsWith('-')
    val parts = input.removePrefix("-").replace(',', '.').split('.')
    val whole = parts[0].toLongOrNull() ?: return null
    if (boundMm < 0 || whole > boundMm / 1000) return null
    val fraction = parts.getOrNull(1)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
    if (whole == boundMm / 1000 && fraction > boundMm % 1000) return null
    val mm = whole * 1000 + fraction
    if (mm > boundMm) return null
    return if (negative) -mm else mm
}

fun formatPointMetres(mm: Long): String {
    require(mm in -3_000_000L..3_000_000L)
    val magnitude = if (mm < 0) -mm else mm
    val fraction = (magnitude % 1000).toString().padStart(3, '0').trimEnd('0')
    return (if (mm < 0) "-" else "") + (magnitude / 1000) + if (fraction.isEmpty()) "" else ",$fraction"
}

@kotlinx.serialization.Serializable
data class PointPositionMm(val xMm: Long, val yMm: Long)
data class PointWallDistances(val alongMm: Double, val inwardMm: Double)

private data class WallBasis(val x: Double, val y: Double, val dx: Double, val dy: Double,
    val nx: Double, val ny: Double, val length: Double)
private fun wallBasis(geometry: FrozenPolygonGeometry, side: Int): WallBasis {
    FrozenPolygonGeometryContract.requireValid(geometry)
    require(side in geometry.vertices.indices)
    val a = geometry.vertices[side]; val b = geometry.vertices[(side + 1) % geometry.vertices.size]
    val dx = (b.xMm - a.xMm).toDouble(); val dy = (b.yMm - a.yMm).toDouble()
    val length = hypot(dx, dy)
    val area = geometry.vertices.indices.sumOf { i -> val p = geometry.vertices[i]; val q = geometry.vertices[(i + 1) % geometry.vertices.size]
        p.xMm * q.yMm - q.xMm * p.yMm }
    val inward = if (area > 0) 1 else -1
    return WallBasis(a.xMm.toDouble(), a.yMm.toDouble(), dx / length, dy / length,
        -dy / length * inward, dx / length * inward, length)
}

fun pointFromWall(geometry: FrozenPolygonGeometry, side: Int, alongMm: Long, inwardMm: Long): PointPositionMm? = runCatching {
    val wall = wallBasis(geometry, side)
    require(alongMm >= 0 && alongMm.toDouble() <= wall.length && inwardMm in 1..3_000_000)
    val x = wall.x + wall.dx * alongMm + wall.nx * inwardMm
    val y = wall.y + wall.dy * alongMm + wall.ny * inwardMm
    val bound = CeilingPolygonCalculator.MAX_COORDINATE_MM.toDouble()
    require(x.isFinite() && y.isFinite() && x in -bound..bound && y in -bound..bound)
    val point = PointPositionMm(x.roundToLong(), y.roundToLong())
    require(pointStrictlyInside(geometry, point.xMm, point.yMm))
    point
}.getOrNull()

fun pointWallDistances(geometry: FrozenPolygonGeometry, side: Int, point: PointPositionMm): PointWallDistances {
    val wall = wallBasis(geometry, side)
    val dx = point.xMm - wall.x; val dy = point.yMm - wall.y
    return PointWallDistances(dx * wall.dx + dy * wall.dy, dx * wall.nx + dy * wall.ny)
}

enum class PointPositionInputKind { WORLD, WALL }
data class PointPositionDraft(
    val point: PointPlacement, val element: ElementVersion, val contourUpdatedAt: Long,
    val geometry: FrozenPolygonGeometry, val kind: PointPositionInputKind = PointPositionInputKind.WORLD,
    val x: String = formatPointMetres(point.xMm), val y: String = formatPointMetres(point.yMm),
    val side: Int = 0, val along: String = "", val inward: String = ""
) {
    fun position(): PointPositionMm? = when (kind) {
        PointPositionInputKind.WORLD -> {
            val px = parsePointMetres(x); val py = parsePointMetres(y)
            if (px == null || py == null || !pointStrictlyInside(geometry, px, py)) null else PointPositionMm(px, py)
        }
        PointPositionInputKind.WALL -> {
            val a = parsePointMetres(along, 3_000_000); val n = parsePointMetres(inward, 3_000_000)
            if (a == null || n == null) null else pointFromWall(geometry, side, a, n)
        }
    }
}

data class PointPositionEditorState(val draft: PointPositionDraft, val baseline: PointPositionDraft = draft,
    val error: String? = null, val conflict: Boolean = false, val exitRequested: Boolean = false,
    val reloadRequested: Boolean = false) { val dirty get() = draft != baseline }

data class PointDragPreview(val point: PointPlacement, val element: ElementVersion,
    val contourUpdatedAt: Long, val xMm: Double = point.xMm.toDouble(), val yMm: Double = point.yMm.toDouble())
data class PointMoveHistory(val before: PointPlacement, val after: PointPlacement,
    val element: ElementVersion, val contourUpdatedAt: Long)
