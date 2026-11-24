package com.andreyceilings.shared.measurement

import kotlin.math.hypot
import kotlin.math.max

/** Distance in screen pixels, zero inside fill. A shared accessible halo also hits tiny shapes. */
fun areaHitDistance(shape: AreaShape, point: DrawingPoint, screen: (PointPositionMm)->DrawingPoint): Double {
    if(shape is AreaShape.Circle) {
        val c=screen(shape.center)
        val r=screen(PointPositionMm(shape.center.xMm+shape.radiusMm,shape.center.yMm))
        return max(0.0,hypot(point.x-c.x,point.y-c.y)-hypot(r.x-c.x,r.y-c.y))
    }
    val vertices=shape.positions.map(screen)
    var inside=false
    var distance=Double.POSITIVE_INFINITY
    vertices.indices.forEach {i ->
        val a=vertices[i];val b=vertices[(i+1)%vertices.size]
        distance = minOf(distance, projectedSegmentDistance(point, a, b))
        if((a.y>point.y)!=(b.y>point.y) && point.x<(b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x)inside=!inside
    }
    return if(inside)0.0 else distance
}
