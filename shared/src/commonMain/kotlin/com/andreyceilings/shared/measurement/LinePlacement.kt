package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.serialization.Serializable

/** A centre axis, not an opening or a width envelope. Coordinates and quantities are integer mm. */
@Serializable
data class LinePlacement(
    val uuid: String, val elementUuid: String,
    val start: PointPositionMm, val end: PointPositionMm,
    val contourUpdatedAt: Long, val createdAt: Long, val updatedAt: Long,
    val deletedAt: Long? = null, val widthMm: Long? = null, val note: String = "",
    val startMounting: PointMounting = PointMounting(), val endMounting: PointMounting = PointMounting(),
    val needsReview: Boolean = false, val unresolved: Boolean = false,
    val lastCeilingEditUuid: String? = null
) {
    val lengthMm: Long get() = lineLengthMm(start, end)
    val precision: PointPrecision get() = if (startMounting.anchor != null && endMounting.anchor != null)
        PointPrecision.EXACT else PointPrecision.APPROXIMATE
    fun endpoint(first: Boolean): PointPlacement {
        val p = if (first) start else end
        return PointPlacement(uuid, elementUuid, p.xMm, p.yMm, contourUpdatedAt, createdAt, updatedAt, deletedAt,
            if ((if (first) startMounting else endMounting).anchor != null) PointPrecision.EXACT else PointPrecision.APPROXIMATE,
            mounting = if (first) startMounting else endMounting)
    }
}

@Serializable
data class LinePropertiesInput(val uuid:String,val width:String,val note:String,val initialWidth:String=width,val initialNote:String=note) {
    val dirty get()=width!=initialWidth || note!=initialNote
    val valid get()=width.isBlank() || width.toLongOrNull()?.let {it in 1..1_000_000}==true
}

@Serializable
data class LinePlacementEdit(val value: LinePlacement, val confirm: Boolean = false, val defer: Boolean = false,
    val remeasuredStart: Boolean = false, val remeasuredEnd: Boolean = false)

sealed class LinePlacementFailure(message: String) : IllegalArgumentException(message) {
    class UnsupportedElement : LinePlacementFailure("Для лінії потрібна каталогова позиція LINE, м.п., ручна або введена довжина.")
    class InvalidSegment : LinePlacementFailure("Ненульова лінія має повністю лежати всередині контуру, без дотику до стін.")
    class CapacityReached : LinePlacementFailure("Довжина ліній разом із зарезервованими перевищує заявлену. Явно виправте кількість.")
    class RequiresMeasuredAnchors : LinePlacementFailure("Для уточнення задайте виміряні опори обох кінців і підтвердьте розміщення.")
}

fun MeasurementElement.supportsLinePlacement(): Boolean {
    val snapshot = source as? ElementSource.CatalogSelection ?: return false
    return deletedAt == null && snapshot.placementKind == PlacementKind.LINE && unit == "м.п." &&
        snapshot.quantityMode in listOf(QuantityMode.MANUAL, QuantityMode.ENTERED_LENGTH) &&
        quantity !in listOf(ElementQuantity.CeilingArea, ElementQuantity.CeilingPerimeter)
}
fun linePlacementExplanation(element: MeasurementElement): String? = if(element.supportsLinePlacement()) null else
    "Для лінії потрібен збережений каталоговий тип LINE, одиниця м.п. та ручна або введена довжина. Периметр не є розміщеною лінією; попередній вибір каталогу не змінюється автоматично."

fun MeasurementElement.lineCapacityMm(): Long? = when (val q = quantity) {
    is ElementQuantity.Exact -> q.milli
    is ElementQuantity.Range -> q.maxMilli
    else -> null
}

/** Integer square root and half-up rounding: independent of floating point, locale and platform. */
fun lineLengthMm(start: PointPositionMm, end: PointPositionMm): Long {
    val bound = CeilingPolygonCalculator.MAX_COORDINATE_MM
    require(listOf(start.xMm, start.yMm, end.xMm, end.yMm).all { it in -bound..bound })
    val dx = end.xMm - start.xMm; val dy = end.yMm - start.yMm
    val square = dx * dx + dy * dy
    require(square > 0)
    var low = 0L; var high = 3_000_000L
    while (low + 1 < high) {
        val mid = (low + high) / 2
        if (mid * mid <= square) low = mid else high = mid
    }
    return if (4 * square >= 4 * low * low + 4 * low + 1) low + 1 else low
}

/** Valid endpoints alone do not prove that a segment stays inside a concave polygon. */
fun lineStrictlyInside(g: FrozenPolygonGeometry, start: PointPositionMm, end: PointPositionMm): Boolean {
    if (runCatching { lineLengthMm(start, end) }.isFailure ||
        !pointStrictlyInside(g, start.xMm, start.yMm) || !pointStrictlyInside(g, end.xMm, end.yMm)) return false
    fun cross(ax: Long, ay: Long, bx: Long, by: Long, cx: Long, cy: Long) = (bx-ax)*(cy-ay)-(by-ay)*(cx-ax)
    return g.vertices.indices.none { i ->
        val a=g.vertices[i]; val b=g.vertices[(i+1)%g.vertices.size]
        val c1=cross(start.xMm,start.yMm,end.xMm,end.yMm,a.xMm,a.yMm)
        val c2=cross(start.xMm,start.yMm,end.xMm,end.yMm,b.xMm,b.yMm)
        val c3=cross(a.xMm,a.yMm,b.xMm,b.yMm,start.xMm,start.yMm)
        val c4=cross(a.xMm,a.yMm,b.xMm,b.yMm,end.xMm,end.yMm)
        maxOf(minOf(start.xMm,end.xMm),minOf(a.xMm,b.xMm)) <= minOf(maxOf(start.xMm,end.xMm),maxOf(a.xMm,b.xMm)) &&
        maxOf(minOf(start.yMm,end.yMm),minOf(a.yMm,b.yMm)) <= minOf(maxOf(start.yMm,end.yMm),maxOf(a.yMm,b.yMm)) &&
        (c1==0L || c2==0L || (c1>0)!=(c2>0)) && (c3==0L || c4==0L || (c3>0)!=(c4>0))
    }
}

/** Lost references preserve the entire last known segment, including mounting context. */
fun previewLine(line: LinePlacement, geometry: FrozenPolygonGeometry, walls: List<CeilingWall>,
    geometryChanged: Boolean, edit: LinePlacementEdit? = null): LinePlacement {
    val value=edit?.value ?: line
    if (value.deletedAt != null) return value
    val a=previewMounting(value.endpoint(true),geometry,walls,geometryChanged)
    val b=previewMounting(value.endpoint(false),geometry,walls,geometryChanged)
    val start=PointPositionMm(a.xMm,a.yMm); val end=PointPositionMm(b.xMm,b.yMm)
    val measured=value.startMounting.anchor!=null && value.endMounting.anchor!=null &&
        solveWallAnchor(geometry,walls,value.startMounting.anchor)?.position==start &&
        solveWallAnchor(geometry,walls,value.endMounting.anchor)?.position==end
    val unresolved=a.mounting.unresolved || b.mounting.unresolved || !lineStrictlyInside(geometry,start,end) ||
        line.unresolved && !(edit?.confirm==true && edit.remeasuredStart && edit.remeasuredEnd && measured)
    if (unresolved) return line.copy(widthMm=value.widthMm,note=value.note,unresolved=true,needsReview=true)
    return value.copy(start=start,end=end,startMounting=a.mounting,endMounting=b.mounting,unresolved=false,
        needsReview=if (geometryChanged) true else if (edit?.confirm==true) false else value.needsReview)
}
