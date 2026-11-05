package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.math.abs
import kotlin.test.*

class WallAnchorGeometryTest {
    private fun geometry(vararg pairs: Pair<Long,Long>)=FrozenPolygonGeometryContract.capture("polygon",pairs.map { CeilingPointMm(it.first,it.second) },emptyMap(),0.001)
    @Test fun twoPerpendicularWallsBothWindingsNegativeWorldAndMillimetreRoundtrip() {
        for(reverse in listOf(false,true)) {
            val vertices=listOf(-4000L to -3000L,0L to -3000L,0L to 0L,-4000L to 0L).let { if(reverse)it.reversed() else it }
            val g=geometry(*vertices.toTypedArray());val walls=g.vertices.indices.map { CeilingWall("wall-$it",it) }
            val bottom=g.vertices.indices.single { i -> g.vertices[i].yMm == -3000L && g.vertices[(i+1)%4].yMm == -3000L }
            val left=g.vertices.indices.single { i -> g.vertices[i].xMm == -4000L && g.vertices[(i+1)%4].xMm == -4000L }
            val solved=assertNotNull(solveWallAnchor(g,walls,WallAnchor(WallAnchor.Kind.TWO_WALLS,"wall-$bottom","wall-$left",600,1500)))
            assertEquals(PointPositionMm(-2500,-2400),solved.position)
            assertEquals(600.0,solved.firstActualMm,0.0001);assertEquals(1500.0,solved.secondActualMm,0.0001)
        }
    }
    @Test fun inclinedWallsConcavityFiniteFeetParallelInstabilityAndRounding() {
        val slant=geometry(0L to 0L,4000L to 1000L,5000L to 4000L,1000L to 3000L)
        val walls=slant.vertices.indices.map { CeilingWall("w$it",it) }
        val solved=assertNotNull(solveWallAnchor(slant,walls,WallAnchor(WallAnchor.Kind.TWO_WALLS,"w0","w3",701,1234)))
        assertTrue(abs(solved.firstActualMm-701)<=1);assertTrue(abs(solved.secondActualMm-1234)<=1)
        assertTrue(pointStrictlyInside(slant,solved.position.xMm,solved.position.yMm))
        assertNull(solveWallAnchor(slant,walls,WallAnchor(WallAnchor.Kind.TWO_WALLS,"w0","w2",500,500)))
        assertNull(solveWallAnchor(slant,walls,WallAnchor(WallAnchor.Kind.ALONG_INWARD,"w0",null,4_000_000,600)))
        assertNull(solveWallAnchor(slant,walls,WallAnchor(WallAnchor.Kind.TWO_WALLS,"missing","w3",600,600)))
        val l=geometry(0L to 0L,5000L to 0L,5000L to 2000L,2000L to 2000L,2000L to 5000L,0L to 5000L)
        val lw=l.vertices.indices.map { CeilingWall("l$it",it) }
        assertNotNull(solveWallAnchor(l,lw,WallAnchor(WallAnchor.Kind.TWO_WALLS,"l0","l5",600,1500)))
        assertNull(solveWallAnchor(l,lw,WallAnchor(WallAnchor.Kind.TWO_WALLS,"l0","l3",600,600)),"Feet outside finite wall must not be guessed")
        val unstable=geometry(0L to 0L,900000L to 1L,900000L to 1000L,0L to 1000L)
        assertNull(solveWallAnchor(unstable,unstable.vertices.indices.map { CeilingWall("u$it",it) },WallAnchor(WallAnchor.Kind.TWO_WALLS,"u0","u2",100,100)))
        val fallback=assertNotNull(solveWallAnchor(slant,walls,WallAnchor(WallAnchor.Kind.ALONG_INWARD,"w0",null,1500,600)))
        assertTrue(abs(fallback.firstActualMm-1500)<=1 && abs(fallback.secondActualMm-600)<=1)
    }
    @Test fun perpendicularDimensionsMustBeVisibleInsideAConcaveContour() {
        val u=geometry(0L to 0L,6000L to 0L,6000L to 5000L,4000L to 5000L,4000L to 2000L,2000L to 2000L,2000L to 5000L,0L to 5000L)
        val walls=u.vertices.indices.map{CeilingWall("u$it",it)}
        assertNull(solveWallAnchor(u,walls,WallAnchor(WallAnchor.Kind.TWO_WALLS,"u0","u1",3000,5000)))
        assertNull(solveWallAnchor(u,walls,WallAnchor(WallAnchor.Kind.ALONG_INWARD,"u1",null,3000,5000)))
        assertEquals(PointPositionMm(1000,3000),assertNotNull(solveWallAnchor(u,walls,WallAnchor(WallAnchor.Kind.TWO_WALLS,"u0","u7",3000,1000))).position)
    }

}
