package com.andreyceilings.shared.calculation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CeilingPolygonGeometryTest {
    private fun measure(points: List<CeilingPointMm>, shape: String = "polygon") =
        CeilingPolygonCalculator.calculate(CeilingPolygon(shape, points), 0.01).geometry

    @Test fun rectangleAndOrthogonalLUseExactlyOnePolygonEngine() {
        val rectangular = CeilingShapeTemplates.fromMeasurements(
            "rectangle", mapOf("length" to 5_000L, "width" to 4_000L)
        )
        assertEquals(4, rectangular.vertices.size)
        val rectangle = CeilingPolygonCalculator.calculate(rectangular, 0.01).geometry
        assertEquals(20.0, rectangle.area, 0.000001)
        assertEquals(18.0, rectangle.perimeter, 0.000001)

        val l = CeilingShapeTemplates.fromMeasurements(
            "l_shaped", mapOf(
                "A" to 5_000L, "B" to 4_000L,
                "C" to 2_000L, "D" to 2_000L,
                "E" to 3_000L, "F" to 2_000L
            )
        )
        assertEquals(6, l.vertices.size)
        val result = CeilingPolygonCalculator.calculate(l, 0.01).geometry
        assertEquals(14.0, result.area, 0.000001)
        assertEquals(18.0, result.perimeter, 0.000001)
        assertEquals(6, result.cornersCount)
    }

    @Test fun anySimpleConcavePolygonAndReversedOrderAreSupported() {
        val polygon = CeilingShapeTemplates.fromVertices(listOf(
            CeilingPointMm(0, 0), CeilingPointMm(6000, 0),
            CeilingPointMm(6000, 2000), CeilingPointMm(3000, 2000),
            CeilingPointMm(3000, 5000), CeilingPointMm(0, 5000)
        ))
        val clockwise = CeilingPolygonCalculator.calculate(polygon, 0.01)
        val reversed = CeilingPolygonCalculator.calculate(
            polygon.copy(vertices = polygon.vertices.reversed()), 0.01
        )
        assertEquals(21.0, clockwise.geometry.area, 0.000001)
        assertEquals(22.0, clockwise.geometry.perimeter, 0.000001)
        assertEquals(clockwise.geometry, reversed.geometry)
    }

    @Test fun overlappingCrossingAndTouchingContoursAreRejected() {
        val invalid = listOf(
            listOf(
                CeilingPointMm(0, 0), CeilingPointMm(4000, 4000),
                CeilingPointMm(0, 4000), CeilingPointMm(4000, 0)
            ),
            listOf(
                CeilingPointMm(0, 0), CeilingPointMm(5000, 0),
                CeilingPointMm(3000, 0), CeilingPointMm(3000, 3000),
                CeilingPointMm(0, 3000)
            ),
            listOf(
                CeilingPointMm(0, 0), CeilingPointMm(4000, 0),
                CeilingPointMm(4000, 4000), CeilingPointMm(1000, 0),
                CeilingPointMm(0, 4000)
            ),
            listOf(
                CeilingPointMm(0, 0), CeilingPointMm(4000, 0),
                CeilingPointMm(4000, 4000), CeilingPointMm(0, 0)
            ),
            listOf(
                CeilingPointMm(0, 0), CeilingPointMm(1000, 1000),
                CeilingPointMm(2000, 2000)
            )
        )
        invalid.forEach { vertices ->
            assertFailsWith<IllegalArgumentException> { measure(vertices) }
        }
    }

    @Test fun templateMustHaveAllMeasuredInputsAndCorrectOrthogonalClosure() {
        val correct = mapOf(
            "A" to 5000L, "B" to 4000L,
            "C" to 2000L, "D" to 2000L,
            "E" to 3000L, "F" to 2000L
        )
        assertFailsWith<IllegalArgumentException> {
            CeilingShapeTemplates.fromMeasurements("l_shaped", correct - "E")
        }
        assertFailsWith<IllegalArgumentException> {
            CeilingShapeTemplates.fromMeasurements(
                "l_shaped", correct + ("E" to 3500L)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CeilingShapeTemplates.fromMeasurements(
                "l_shaped", correct + ("G" to 1000L)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CeilingShapeTemplates.fromMeasurements(
                "trapezoid", correct
            )
        }
    }

    @Test fun geometryBoundsAndMeasurementResolutionAreFailClosed() {
        val rightTriangle = listOf(
            CeilingPointMm(0, 0), CeilingPointMm(1001, 0),
            CeilingPointMm(1001, 2000)
        )
        assertFailsWith<IllegalArgumentException> {
            measure(rightTriangle)
        }
        val precise = CeilingPolygonCalculator.calculate(
            CeilingPolygon("polygon", rightTriangle), 0.001
        ).geometry
        assertEquals(1.001, precise.area, 0.000001)
        assertFailsWith<IllegalArgumentException> {
            measure(listOf(
                CeilingPointMm(Long.MIN_VALUE, 0),
                CeilingPointMm(1000, 0), CeilingPointMm(1000, 1000)
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            measure(listOf(
                CeilingPointMm(0, 0), CeilingPointMm(2_000_000, 0),
                CeilingPointMm(2_000_000, 1000)
            ))
        }
    }
}
