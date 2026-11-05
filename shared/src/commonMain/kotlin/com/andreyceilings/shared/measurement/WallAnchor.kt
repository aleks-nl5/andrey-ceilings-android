package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.math.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

internal val mountingCodec = Json { encodeDefaults = true }
fun encodeMounting(value: PointMounting): String = if (value == PointMounting()) "{}" else mountingCodec.encodeToString(value)
fun decodeMounting(value: String): PointMounting = mountingCodec.decodeFromString(value)

@Serializable
data class CeilingWall(val uuid: String, val ordinal: Int)

/** Distances are measured integers; the point coordinates are a derived, rounded representation. */
@Serializable
data class WallAnchor(val kind: Kind, val firstWall: String, val secondWall: String? = null,
    val firstMm: Long, val secondMm: Long) {
    @Serializable enum class Kind { TWO_WALLS, ALONG_INWARD }
}

data class AnchorSolution(val position: PointPositionMm, val firstActualMm: Double, val secondActualMm: Double)

/** A 1 mm representation cannot claim better physical accuracy; reject unstable near-parallel bases. */
fun solveWallAnchor(geometry: FrozenPolygonGeometry, walls: List<CeilingWall>, anchor: WallAnchor): AnchorSolution? = runCatching {
    FrozenPolygonGeometryContract.requireValid(geometry)
    require(walls.map { it.uuid }.distinct().size == walls.size && walls.map { it.ordinal }.sorted() == geometry.vertices.indices.toList())
    require(anchor.firstMm in 0..3_000_000 && anchor.secondMm in 1..3_000_000)
    val winding = if (geometry.vertices.indices.sumOf { i ->
        val a = geometry.vertices[i]; val b = geometry.vertices[(i + 1) % geometry.vertices.size]
        a.xMm * b.yMm - b.xMm * a.yMm
    } > 0) 1.0 else -1.0
    data class Basis(val ordinal: Int,val ax: Double, val ay: Double, val tx: Double, val ty: Double,
        val nx: Double, val ny: Double, val length: Double) {
        fun distances(x: Double, y: Double) = PointWallDistances((x-ax)*tx+(y-ay)*ty,(x-ax)*nx+(y-ay)*ny)
    }
    fun basis(id: String): Basis {
        val i = walls.single { it.uuid == id }.ordinal
        val a = geometry.vertices[i]; val b = geometry.vertices[(i+1)%geometry.vertices.size]
        val dx = (b.xMm-a.xMm).toDouble(); val dy = (b.yMm-a.yMm).toDouble(); val len = hypot(dx,dy)
        return Basis(i,a.xMm.toDouble(),a.yMm.toDouble(),dx/len,dy/len,-dy/len*winding,dx/len*winding,len)
    }
    val a = basis(anchor.firstWall)
    val b = if (anchor.kind == WallAnchor.Kind.TWO_WALLS) basis(checkNotNull(anchor.secondWall)) else null
    val x: Double; val y: Double
    if (b == null) {
        require(anchor.secondWall == null && anchor.firstMm <= a.length)
        x = a.ax + a.tx*anchor.firstMm + a.nx*anchor.secondMm
        y = a.ay + a.ty*anchor.firstMm + a.ny*anchor.secondMm
    } else {
        require(anchor.firstMm > 0 && anchor.firstWall != anchor.secondWall)
        val determinant = a.nx*b.ny-a.ny*b.nx
        require(abs(determinant) >= 0.001)
        val c1 = a.nx*a.ax+a.ny*a.ay+anchor.firstMm
        val c2 = b.nx*b.ax+b.ny*b.ay+anchor.secondMm
        x = (c1*b.ny-a.ny*c2)/determinant
        y = (a.nx*c2-c1*b.nx)/determinant
    }
    require(x.isFinite() && y.isFinite() && x in -1_000_000.0..1_000_000.0 && y in -1_000_000.0..1_000_000.0)
    val point = PointPositionMm(x.roundToLong(),y.roundToLong())
    require(pointStrictlyInside(geometry,point.xMm,point.yMm))
    val actualA = a.distances(point.xMm.toDouble(),point.yMm.toDouble())
    require(actualA.alongMm >= -0.001 && actualA.alongMm <= a.length+0.001)
    require(visibleMountingOffset(geometry,point.xMm.toDouble(),point.yMm.toDouble(),a.ax+a.tx*actualA.alongMm,a.ay+a.ty*actualA.alongMm,a.ordinal))
    val actualB = b?.distances(point.xMm.toDouble(),point.yMm.toDouble())
    if (actualB != null) {
        require(actualB.alongMm >= -0.001 && actualB.alongMm <= b!!.length+0.001)
        require(visibleMountingOffset(geometry,point.xMm.toDouble(),point.yMm.toDouble(),b.ax+b.tx*actualB.alongMm,b.ay+b.ty*actualB.alongMm,b.ordinal))
    }
    val first = if (b == null) actualA.alongMm else actualA.inwardMm
    val second = actualB?.inwardMm ?: actualA.inwardMm
    require(abs(first-anchor.firstMm) <= 1.0 && abs(second-anchor.secondMm) <= 1.0)
    AnchorSolution(point,first,second)
}.getOrNull()

@Serializable
data class PointMounting(
    val anchor: WallAnchor? = null,
    val needsReview: Boolean = false,
    val unresolved: Boolean = false,
    val modifiedVersion: Long = 0,
    val confirmedAt: Long? = null,
    val deferredAt: Long? = null,
    val anchorGeometryJson: String? = null,
    val lastValidGeometryJson: String? = null
)

/** Explicit edits only; absence means retain/remap the actual persisted record, never infer a wall. */
@Serializable
data class PointMountingEdit(val anchor: WallAnchor? = null, val freeX: Long? = null, val freeY: Long? = null,
    val confirm: Boolean = false, val defer: Boolean = false)

fun previewMounting(point: PointPlacement, geometry: FrozenPolygonGeometry, walls: List<CeilingWall>,
    geometryChanged: Boolean, edit: PointMountingEdit? = null): PointPlacement {
    if (!geometryChanged && edit == null) return point
    val anchor = if (edit != null && (edit.anchor != null || edit.freeX != null)) edit.anchor else point.mounting.anchor
    val solution = anchor?.let { solveWallAnchor(geometry,walls,it) }
    val free = edit?.freeX?.let { PointPositionMm(it,checkNotNull(edit.freeY)) }
    val position = if(point.mounting.unresolved && edit?.anchor==null && free==null) PointPositionMm(point.xMm,point.yMm)
        else solution?.position ?: free ?: PointPositionMm(point.xMm,point.yMm)
    val unresolved = point.mounting.unresolved && edit?.anchor==null || if (anchor != null) solution == null else !pointStrictlyInside(geometry,position.xMm,position.yMm)
    val measured = edit?.anchor != null
    val dragged = free != null
    val review = when {
        geometryChanged -> true
        edit?.confirm == true && !unresolved -> false
        measured && !unresolved -> false
        dragged && point.mounting.anchor != null -> true
        else -> point.mounting.needsReview || unresolved
    }
    return point.copy(xMm=position.xMm,yMm=position.yMm,
        precision=if (measured) PointPrecision.EXACT else if (dragged) PointPrecision.APPROXIMATE else point.precision,
        mounting=point.mounting.copy(anchor=anchor,needsReview=review,unresolved=unresolved,
            confirmedAt=if (review) null else point.mounting.confirmedAt,
            deferredAt=if (review && edit?.defer != true) point.mounting.deferredAt else null))
}


data class MountingDimension(val start: DrawingPoint,val end: DrawingPoint,val label: String)
fun mountingDimensions(point: PointPlacement?,geometry: FrozenPolygonGeometry,walls: List<CeilingWall>): List<MountingDimension> {
    val anchor=point?.mounting?.anchor ?: return emptyList()
    if(point.mounting.unresolved) return emptyList()
    val center=DrawingPoint(point.xMm.toDouble(),point.yMm.toDouble())
    fun dimension(id: String,mm: Long,along: Boolean=false): MountingDimension? {
        val side=walls.find { it.uuid==id }?.ordinal ?: return null
        val a=geometry.vertices[side];val b=geometry.vertices[(side+1)%geometry.vertices.size]
        val dx=(b.xMm-a.xMm).toDouble();val dy=(b.yMm-a.yMm).toDouble()
        val t=((point.xMm-a.xMm)*dx+(point.yMm-a.yMm)*dy)/(dx*dx+dy*dy)
        val foot=DrawingPoint(a.xMm+dx*t,a.yMm+dy*t)
        return MountingDimension(if(along) DrawingPoint(a.xMm.toDouble(),a.yMm.toDouble()) else center,foot,
            "${measurementSideLabels(walls.size)[side]}: ${formatPointMetres(mm)} м")
    }
    return if(anchor.kind==WallAnchor.Kind.TWO_WALLS) listOfNotNull(dimension(anchor.firstWall,anchor.firstMm),anchor.secondWall?.let { dimension(it,anchor.secondMm) })
    else listOfNotNull(dimension(anchor.firstWall,anchor.firstMm,true),dimension(anchor.firstWall,anchor.secondMm))
}


/** A perpendicular dimension cannot pass through a concave cutout or another wall. */
private fun visibleMountingOffset(g:FrozenPolygonGeometry,px:Double,py:Double,fx:Double,fy:Double,wall:Int):Boolean {
    val rx=fx-px;val ry=fy-py;val r2=rx*rx+ry*ry
    return g.vertices.indices.filter {it!=wall}.none {i->
        val a=g.vertices[i];val b=g.vertices[(i+1)%g.vertices.size]
        val sx=(b.xMm-a.xMm).toDouble();val sy=(b.yMm-a.yMm).toDouble()
        val ax=a.xMm-px;val ay=a.yMm-py
        val den=rx*sy-ry*sx;val t=ax*sy-ay*sx;val u=ax*ry-ay*rx
        if(abs(den)>1e-7) t/den>1e-8 && t/den<1-1e-8 && u/den>=-1e-8 && u/den<=1+1e-8
        else abs(u)<=1e-7 && maxOf(0.0,minOf(ax*rx+ay*ry,(b.xMm-px)*rx+(b.yMm-py)*ry))<
            minOf(r2,maxOf(ax*rx+ay*ry,(b.xMm-px)*rx+(b.yMm-py)*ry))-1e-7
    }
}
