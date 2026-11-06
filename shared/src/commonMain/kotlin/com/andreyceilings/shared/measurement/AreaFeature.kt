package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

/** An annotation has a measurement owner even when it has no catalog association. */
@Serializable
enum class AreaRole { GENERAL_AREA, OBSTACLE, VOID_MARKER }

@Serializable
sealed class AreaShape {
    @Serializable data class Polygon(val vertices: List<PointPositionMm>) : AreaShape()
    @Serializable data class Circle(val center: PointPositionMm, val radiusMm: Long) : AreaShape()
    val positions: List<PointPositionMm> get() = when (this) {
        is Polygon -> vertices
        is Circle -> listOf(center)
    }
    fun withPositions(points: List<PointPositionMm>): AreaShape = when (this) {
        is Polygon -> Polygon(points)
        is Circle -> { require(points.size == 1); copy(center = points.single()) }
    }
    companion object {
        fun rectangle(a: PointPositionMm, b: PointPositionMm) = Polygon(listOf(a,
            PointPositionMm(b.xMm, a.yMm), b, PointPositionMm(a.xMm, b.yMm)))
        fun circleFromDiameter(center: PointPositionMm, diameterMm: Long): Circle {
            require(diameterMm in 2..2_000_000 && diameterMm % 2 == 0L) {
                "Діаметр має задавати цілий радіус у мм (парна кількість мм)."
            }
            return Circle(center, diameterMm / 2)
        }
    }
}

@Serializable
data class AreaFeature(
    val uuid: String, val measurementUuid: String, val shape: AreaShape,
    val contourUpdatedAt: Long, val createdAt: Long, val updatedAt: Long,
    val elementUuid: String? = null, val role: AreaRole = AreaRole.GENERAL_AREA,
    val note: String = "", val mountings: List<PointMounting> = shape.positions.map { PointMounting() },
    val radiusMeasured: Boolean = false, val needsReview: Boolean = false, val unresolved: Boolean = false,
    val deletedAt: Long? = null, val lastCeilingEditUuid: String? = null
) {
    val twiceAreaMm2: Long get() = canonicalTwiceAreaMm2(shape)
    val precision: PointPrecision get() = if (mountings.size == shape.positions.size &&
        mountings.all { it.anchor != null } && (shape !is AreaShape.Circle || radiusMeasured))
        PointPrecision.EXACT else PointPrecision.APPROXIMATE
    fun point(index: Int): PointPlacement {
        val p = shape.positions[index]
        val m = mountings[index]
        return PointPlacement(uuid, elementUuid.orEmpty(), p.xMm, p.yMm, contourUpdatedAt, createdAt, updatedAt,
            deletedAt = deletedAt, precision = if (m.anchor == null) PointPrecision.APPROXIMATE else PointPrecision.EXACT, mounting = m)
    }
}

@Serializable
data class AreaFeatureEdit(val value: AreaFeature, val confirm: Boolean = false, val defer: Boolean = false,
    val remeasured: Set<Int> = emptySet(), val remeasuredRadius: Boolean = false)

fun MeasurementElement.supportsAreaPlacement(): Boolean {
    val selection = source as? ElementSource.CatalogSelection ?: return false
    return deletedAt == null && selection.placementKind == PlacementKind.AREA &&
        selection.quantityMode == QuantityMode.MANUAL && unit in listOf("м²", "шт.") &&
        quantity !in listOf(ElementQuantity.CeilingArea, ElementQuantity.CeilingPerimeter)
}

/** pi = 3141593/1000000, circle mm² rounded half-up, then expressed in half-mm².
 * Bounds ±1,000,000 mm, radius 1..1,000,000 mm, 3..16 polygon vertices keep all
 * canonical arithmetic in Long. At most 1000 active features per measurement.
 */
fun canonicalTwiceAreaMm2(shape: AreaShape): Long {
    require(shape.positions.all { it.xMm in -1_000_000..1_000_000 && it.yMm in -1_000_000..1_000_000 })
    return when (shape) {
        is AreaShape.Circle -> {
            require(shape.radiusMm in 1..1_000_000)
            2 * ((shape.radiusMm * shape.radiusMm * 3_141_593 + 500_000) / 1_000_000)
        }
        is AreaShape.Polygon -> {
            val p = shape.vertices
            require(p.size in 3..16 && p.distinct().size == p.size)
            p.indices.forEach { i ->
                val a = p[i]; val b = p[(i + 1) % p.size]; val c = p[(i + 2) % p.size]
                require(areaCross(a,b,c) != 0L || (b.xMm-a.xMm)*(c.xMm-b.xMm)+(b.yMm-a.yMm)*(c.yMm-b.yMm)>0)
                for (j in i+1 until p.size) if (j != i+1 && !(i == 0 && j == p.lastIndex))
                    require(!areaSegmentsTouch(a,b,p[j],p[(j+1)%p.size]))
            }
            abs(p.indices.sumOf { i -> val a=p[i]; val b=p[(i+1)%p.size]; a.xMm*b.yMm-b.xMm*a.yMm }).also { require(it>0) }
        }
    }
}

private fun areaCross(a: PointPositionMm,b: PointPositionMm,c: PointPositionMm) =
    (b.xMm-a.xMm)*(c.yMm-a.yMm)-(b.yMm-a.yMm)*(c.xMm-a.xMm)

private fun areaSegmentsTouch(a: PointPositionMm,b: PointPositionMm,c: PointPositionMm,d: PointPositionMm): Boolean {
    val u=areaCross(a,b,c); val v=areaCross(a,b,d); val w=areaCross(c,d,a); val z=areaCross(c,d,b)
    return maxOf(minOf(a.xMm,b.xMm),minOf(c.xMm,d.xMm))<=minOf(maxOf(a.xMm,b.xMm),maxOf(c.xMm,d.xMm)) &&
        maxOf(minOf(a.yMm,b.yMm),minOf(c.yMm,d.yMm))<=minOf(maxOf(a.yMm,b.yMm),maxOf(c.yMm,d.yMm)) &&
        (u==0L || v==0L || (u>0)!=(v>0)) && (w==0L || z==0L || (w>0)!=(z>0))
}

/** Full footprint, including every edge and every wall segment; touching is unresolved. */
fun areaStrictlyInside(g: FrozenPolygonGeometry, shape: AreaShape): Boolean = runCatching {
    FrozenPolygonGeometryContract.requireValid(g)
    canonicalTwiceAreaMm2(shape)
    require(shape.positions.all { pointStrictlyInside(g,it.xMm,it.yMm) })
    when (shape) {
        is AreaShape.Polygon -> require(shape.vertices.indices.all {
            lineStrictlyInside(g,shape.vertices[it],shape.vertices[(it+1)%shape.vertices.size])
        })
        is AreaShape.Circle -> require(g.vertices.indices.all { i ->
            val a=g.vertices[i]; val b=g.vertices[(i+1)%g.vertices.size]
            val dx=(b.xMm-a.xMm).toDouble(); val dy=(b.yMm-a.yMm).toDouble()
            val t=(((shape.center.xMm-a.xMm)*dx+(shape.center.yMm-a.yMm)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
            hypot(shape.center.xMm-a.xMm-t*dx,shape.center.yMm-a.yMm-t*dy)>shape.radiusMm+0.001
        })
    }
    true
}.getOrDefault(false)

/** Round once after the canonical sum; a valid tiny shape can have zero milli-m². */
fun areaQuantityMilli(features: List<AreaFeature>, unit: String, includeUnresolved: Boolean = false): Long {
    require(features.size<=1000 && unit in listOf("м²","шт."))
    val active=features.filter { it.deletedAt==null && (includeUnresolved || !it.unresolved) }
    return if(unit=="шт.") active.size*1000L else (active.sumOf {it.twiceAreaMm2}+1000)/2000
}

fun previewArea(old: AreaFeature, geometry: FrozenPolygonGeometry, walls: List<CeilingWall>,
    geometryChanged: Boolean, edit: AreaFeatureEdit? = null): AreaFeature {
    val value=edit?.value ?: old
    if(value.deletedAt!=null)return value
    require(value.mountings.size==value.shape.positions.size)
    val mapped=value.shape.positions.indices.map { previewMounting(value.point(it),geometry,walls,geometryChanged) }
    val shape=value.shape.withPositions(mapped.map {PointPositionMm(it.xMm,it.yMm)})
    val measured=value.mountings.indices.all { i -> value.mountings[i].anchor?.let {
        solveWallAnchor(geometry,walls,it)?.position==shape.positions[i]
    }==true } && (shape !is AreaShape.Circle || value.radiusMeasured)
    val repaired=edit?.confirm==true && edit.remeasured.containsAll(value.shape.positions.indices.toList()) &&
        (shape !is AreaShape.Circle || edit.remeasuredRadius) && measured
    val unresolved=mapped.any {it.mounting.unresolved} || !areaStrictlyInside(geometry,shape) || old.unresolved && !repaired
    if(unresolved)return old.copy(note=value.note,role=value.role,needsReview=true,unresolved=true)
    return value.copy(shape=shape,mountings=mapped.map {it.mounting},unresolved=false,
        needsReview=if(geometryChanged)true else if(edit?.confirm==true)false else value.needsReview)
}
