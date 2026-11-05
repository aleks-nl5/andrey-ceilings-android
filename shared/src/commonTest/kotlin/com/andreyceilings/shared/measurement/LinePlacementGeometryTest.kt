package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.test.*

class LinePlacementGeometryTest {
    private fun geometry(vararg xy:Pair<Long,Long>)=FrozenPolygonGeometryContract.capture("polygon",xy.map {CeilingPointMm(it.first,it.second)},emptyMap(),0.001)
    @Test fun integerLengthsBoundsResolutionAndRounding() {
        val origin=PointPositionMm(0,0)
        assertEquals(1,lineLengthMm(origin,PointPositionMm(1,0)))
        assertEquals(1,lineLengthMm(origin,PointPositionMm(1,1)))
        assertEquals(3,lineLengthMm(origin,PointPositionMm(2,2)))
        assertEquals(5,lineLengthMm(origin,PointPositionMm(3,4)))
        assertEquals(2500,lineLengthMm(PointPositionMm(-1250,10),PointPositionMm(1250,10)))
        assertEquals(2828427,lineLengthMm(PointPositionMm(-1000000,-1000000),PointPositionMm(1000000,1000000)))
        assertFails {lineLengthMm(origin,origin)}
        for (bad in listOf(Long.MIN_VALUE,Long.MAX_VALUE,1000001L,-1000001L)) assertFails {lineLengthMm(origin,PointPositionMm(bad,1))}
        assertEquals(2500,parsePointMetres("2,500"));assertEquals(2500,parsePointMetres("2.500"))
    }
    @Test fun entireSegmentMustStayInsideConcaveContourBothWindings() {
        val vertices=listOf(0L to 0L,6000L to 0L,6000L to 5000L,4000L to 5000L,4000L to 2000L,2000L to 2000L,2000L to 5000L,0L to 5000L)
        for(reverse in listOf(false,true)) {
            val g=geometry(*(if(reverse)vertices.reversed() else vertices).toTypedArray())
            val a=PointPositionMm(1000,3000);val b=PointPositionMm(5000,3000)
            assertTrue(pointStrictlyInside(g,a.xMm,a.yMm));assertTrue(pointStrictlyInside(g,b.xMm,b.yMm))
            assertFalse(lineStrictlyInside(g,a,b),"Endpoints do not prove concave containment")
            assertTrue(lineStrictlyInside(g,PointPositionMm(1000,1000),PointPositionMm(5000,1000)))
            assertFalse(lineStrictlyInside(g,PointPositionMm(1000,2000),PointPositionMm(5000,2000)),"Boundary overlap is invalid")
            assertFalse(lineStrictlyInside(g,PointPositionMm(0,1000),PointPositionMm(1000,1000)))
            assertFalse(lineStrictlyInside(g,a,a))
        }
    }
    @Test fun remapKeepsWallIdentityAndStickyUnresolvedNeedsBothNewAnchors() {
        val g=geometry(0L to 0L,4000L to 0L,4000L to 3000L,0L to 3000L)
        val walls=g.vertices.indices.map {CeilingWall("w$it",it)}
        fun anchor(x:Long)=WallAnchor(WallAnchor.Kind.TWO_WALLS,"w0","w3",600,x)
        val line=LinePlacement("id","element",PointPositionMm(500,600),PointPositionMm(3000,600),1,1,1,
            startMounting=PointMounting(anchor=anchor(500)),endMounting=PointMounting(anchor=anchor(3000)))
        assertEquals(PointPrecision.EXACT,line.precision)
        val moved=previewLine(line,g,walls,true)
        assertTrue(moved.needsReview);assertFalse(moved.unresolved);assertEquals(2500,moved.lengthMm)
        val lost=previewLine(line,g,walls.map {it.copy(uuid="other${it.uuid}")},true)
        assertTrue(lost.unresolved);assertEquals(line.start,lost.start);assertEquals(line.end,lost.end)
        assertTrue(previewLine(lost,g,walls,true).unresolved)
        assertTrue(previewLine(lost,g,walls,false,LinePlacementEdit(line)).unresolved)
        assertTrue(previewLine(lost,g,walls,false,LinePlacementEdit(line,confirm=true)).unresolved)
        assertFalse(previewLine(lost,g,walls,false,LinePlacementEdit(line,confirm=true,remeasuredStart=true,remeasuredEnd=true)).unresolved)
    }
}
