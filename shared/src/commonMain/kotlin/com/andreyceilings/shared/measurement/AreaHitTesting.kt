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
        val dx=b.x-a.x;val dy=b.y-a.y;val square=dx*dx+dy*dy
        val t=if(square<=1e-12)0.0 else (((point.x-a.x)*dx+(point.y-a.y)*dy)/square).coerceIn(0.0,1.0)
        distance=minOf(distance,hypot(point.x-a.x-t*dx,point.y-a.y-t*dy))
        if((a.y>point.y)!=(b.y>point.y) && point.x<(b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x)inside=!inside
    }
    return if(inside)0.0 else distance
}
