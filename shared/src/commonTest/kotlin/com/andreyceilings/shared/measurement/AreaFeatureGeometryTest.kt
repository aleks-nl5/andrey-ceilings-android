package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.test.*

class AreaFeatureGeometryTest {
    private fun geometry(points: List<PointPositionMm>)=FrozenPolygonGeometryContract.capture("polygon",points.map {CeilingPointMm(it.xMm,it.yMm)},emptyMap(),0.001)
    private fun rect(a:Long,b:Long,c:Long,d:Long)=AreaShape.rectangle(PointPositionMm(a,b),PointPositionMm(c,d))
    private val room=geometry(rect(0,0,4000,3000).vertices)
    private fun feature(shape:AreaShape)=AreaFeature("a","m",shape,1,1,1)

    @Test fun canonicalAreasBoundsAndAggregateRounding() {
        assertEquals(12_000_000,canonicalTwiceAreaMm2(rect(0,0,3000,2000)))
        assertEquals(6_283_186,canonicalTwiceAreaMm2(AreaShape.Circle(PointPositionMm(0,0),1000)))
        assertEquals(6_283_186_000_000,canonicalTwiceAreaMm2(AreaShape.Circle(PointPositionMm(0,0),1_000_000)))
        val triangle=AreaShape.Polygon(listOf(PointPositionMm(0,0),PointPositionMm(1,0),PointPositionMm(0,1)))
        assertEquals(1,canonicalTwiceAreaMm2(triangle))
        val tiny=feature(rect(10,10,30,30))
        assertEquals(0,areaQuantityMilli(listOf(tiny),"м²"))
        assertEquals(1,areaQuantityMilli(listOf(tiny,tiny.copy(uuid="b")),"м²"))
        assertEquals(2000,areaQuantityMilli(listOf(tiny,tiny.copy(uuid="b")),"шт."))
        assertEquals(0,areaQuantityMilli(listOf(tiny.copy(unresolved=true)),"шт."))
        assertEquals(1000,areaQuantityMilli(listOf(tiny.copy(unresolved=true)),"шт.",true))
        for(bad in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-1_000_001L,1_000_001L))assertFails {canonicalTwiceAreaMm2(rect(bad,0,5,5))}
        for(bad in listOf(0L,-1L,1_000_001L,Long.MAX_VALUE))assertFails {canonicalTwiceAreaMm2(AreaShape.Circle(PointPositionMm(0,0),bad))}
        assertEquals(2500,parsePointMetres("2,5"));assertEquals(2500,parsePointMetres("2.5"))
        assertEquals(1250,AreaShape.circleFromDiameter(PointPositionMm(0,0),2500).radiusMm)
        assertFails {AreaShape.circleFromDiameter(PointPositionMm(0,0),3)}
    }
    @Test fun simpleConcaveReversedDegenerateAndMaximumPolygonVertices() {
        val p=listOf(PointPositionMm(100,100),PointPositionMm(300,100),PointPositionMm(300,200),PointPositionMm(200,150),PointPositionMm(100,200))
        assertEquals(canonicalTwiceAreaMm2(AreaShape.Polygon(p)),canonicalTwiceAreaMm2(AreaShape.Polygon(p.reversed())))
        assertTrue(areaStrictlyInside(room,AreaShape.Polygon(p)))
        val many=listOf(PointPositionMm(100,100),PointPositionMm(200,100),PointPositionMm(300,100),PointPositionMm(400,100),
            PointPositionMm(500,100),PointPositionMm(500,200),PointPositionMm(500,300),PointPositionMm(500,400),
            PointPositionMm(500,500),PointPositionMm(400,500),PointPositionMm(300,500),PointPositionMm(200,500),
            PointPositionMm(100,500),PointPositionMm(100,400),PointPositionMm(100,300),PointPositionMm(100,200))
        assertTrue(areaStrictlyInside(room,AreaShape.Polygon(many)))
        for(bad in listOf(p.take(2),many+PointPositionMm(100,150),listOf(p[0],p[1],p[0]),
            listOf(PointPositionMm(100,100),PointPositionMm(300,300),PointPositionMm(100,300),PointPositionMm(300,100))))
            assertFails {canonicalTwiceAreaMm2(AreaShape.Polygon(bad))}
    }
    @Test fun entireFootprintRejectsConcaveCutoutAndTangency() {
        val points=listOf(PointPositionMm(0,0),PointPositionMm(6000,0),PointPositionMm(6000,5000),PointPositionMm(4000,5000),
            PointPositionMm(4000,2000),PointPositionMm(2000,2000),PointPositionMm(2000,5000),PointPositionMm(0,5000))
        for(p in listOf(points,points.reversed())) {
            val g=geometry(p)
            assertFalse(areaStrictlyInside(g,rect(1000,1000,5000,3000)))
            assertFalse(areaStrictlyInside(g,rect(1000,1000,5000,2000)))
            assertTrue(areaStrictlyInside(g,rect(500,500,5500,1500)))
            assertFalse(areaStrictlyInside(g,AreaShape.Circle(PointPositionMm(3000,1000),1000)))
            assertTrue(areaStrictlyInside(g,AreaShape.Circle(PointPositionMm(3000,1000),999)))
            assertFalse(areaStrictlyInside(g,AreaShape.Circle(PointPositionMm(1000,3000),1001)))
        }
        assertFalse(areaStrictlyInside(room,rect(0,100,300,500)))
    }
    @Test fun freePositionsAreApproximateAndUnresolvedRequiresNewMeasurementsAndConfirmation() {
        val walls=room.vertices.indices.map {CeilingWall("w$it",it)}
        val circle=feature(AreaShape.Circle(PointPositionMm(1000,1000),200)).copy(radiusMeasured=true,
            mountings=listOf(PointMounting(anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,"w0","w3",1000,1000))))
        assertEquals(PointPrecision.EXACT,circle.precision)
        assertEquals(PointPrecision.APPROXIMATE,circle.copy(radiusMeasured=false).precision)
        val lost=previewArea(circle,room,walls.map {it.copy(uuid="lost${it.uuid}")},true)
        assertTrue(lost.unresolved);assertTrue(lost.needsReview);assertEquals(circle.shape,lost.shape)
        assertTrue(previewArea(lost,room,walls,true).unresolved)
        assertTrue(previewArea(lost,room,walls,false,AreaFeatureEdit(circle,confirm=true)).unresolved)
        assertFalse(previewArea(lost,room,walls,false,AreaFeatureEdit(circle,confirm=true,remeasured=setOf(0),remeasuredRadius=true)).unresolved)
        val free=circle.copy(mountings=listOf(PointMounting()))
        assertEquals(PointPrecision.APPROXIMATE,free.precision)
        assertTrue(previewArea(lost,room,walls,false,AreaFeatureEdit(free,confirm=true,remeasured=setOf(0),remeasuredRadius=true)).unresolved)
    }
    @Test fun hitTestingIncludesFilledInteriorTinyEdgesAndFarOutside() {
        val screen:(PointPositionMm)->DrawingPoint={DrawingPoint(it.xMm/1000.0,it.yMm/1000.0)}
        assertEquals(0.0,areaHitDistance(rect(100,100,1000,1000),DrawingPoint(0.5,0.5),screen))
        assertTrue(areaHitDistance(AreaShape.Circle(PointPositionMm(500,500),1),DrawingPoint(0.6,0.5),screen)<1)
        assertTrue(areaHitDistance(rect(100,100,1000,1000),DrawingPoint(100.0,100.0),screen)>24)
    }
}
