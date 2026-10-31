package com.andreyceilings.shared.calculation

import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrthogonalSideContourTest {
    private val r = OrthogonalTurn.RIGHT
    private val l = OrthogonalTurn.LEFT
    private fun sides(vararg input: Pair<Double, OrthogonalTurn>) =
        OrthogonalSideContour.fromMeters(input.toList(), 0.01)

    @Test fun measuredRectangleClosesWithoutSynthesizingTheLastWall() {
        val walls = sides(5.0 to r, 4.0 to r, 5.0 to r, 4.0 to r)
        val frozen = FrozenPolygonGeometryContract.captureSides(walls, 0.01)
        assertEquals(4, frozen.vertices.size)
        assertEquals(20.0, frozen.areaSquareMeters, 0.0)
        assertEquals(18.0, frozen.perimeterMeters, 0.0)
        assertEquals(walls, frozen.sides)
        assertEquals(frozen, FrozenPolygonGeometryContract.requireValid(frozen))
    }

    @Test fun concaveLWallSequenceReconstructsExactVertices() {
        val walls = sides(
            5.0 to r, 2.0 to r, 3.0 to l,
            2.0 to r, 2.0 to r, 4.0 to r
        )
        val result = FrozenPolygonGeometryContract.captureSides(walls, 0.01)
        assertEquals(14.0, result.areaSquareMeters, 0.0)
        assertEquals(18.0, result.perimeterMeters, 0.0)
        assertEquals(6, result.vertices.size)
        assertFailsWith<IllegalArgumentException> {
            FrozenPolygonGeometryContract.requireValid(
                result.copy(sides = result.sides.dropLast(1))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenPolygonGeometryContract.requireValid(
                result.copy(sides = result.sides.mapIndexed { index, side ->
                    if (index == 0) side.copy(lengthMm = 5100) else side
                })
            )
        }
    }

    @Test fun invalidClosureDirectionSelfIntersectionAndPrecisionFailClosed() {
        val rectangle = sides(5.0 to r, 4.0 to r, 5.0 to r, 4.0 to r)
        assertFailsWith<IllegalArgumentException> {
            OrthogonalSideContour.build(
                rectangle.dropLast(1) + rectangle.last().copy(lengthMm = 3000), 0.01
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OrthogonalSideContour.build(
                rectangle.dropLast(1) + rectangle.last().copy(turnAfter = l), 0.01
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OrthogonalSideContour.fromMeters(
                listOf(5.0 to r, 4.0 to r, 5.005 to r, 4.0 to r), 0.01
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OrthogonalSideContour.build(
                listOf(
                    MeasuredOrthogonalSide(5000, r),
                    MeasuredOrthogonalSide(4000, r),
                    MeasuredOrthogonalSide(5000, r),
                    MeasuredOrthogonalSide(4000, r)
                ).reversed(), 0.123
            )
        }
    }

    @Test fun legacyVertexPolygonRemainsValidWithoutInventedSideMeasurements() {
        val polygon = FrozenPolygonGeometryContract.capture(
            "polygon", listOf(
                CeilingPointMm(0, 0), CeilingPointMm(5000, 0),
                CeilingPointMm(5000, 4000), CeilingPointMm(0, 4000)
            ), emptyMap(), 0.01
        )
        assertEquals(emptyList(), polygon.sides)
        assertEquals(20.0,
            FrozenPolygonGeometryContract.requireValid(polygon).areaSquareMeters, 0.0)
    }
}
