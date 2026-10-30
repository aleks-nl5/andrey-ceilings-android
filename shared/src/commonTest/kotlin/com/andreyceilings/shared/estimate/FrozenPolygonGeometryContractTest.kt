package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrozenPolygonGeometryContractTest {
    private fun geometry(shape: String, mm: Map<String, Long>): FrozenPolygonGeometry =
        FrozenPolygonGeometryContract.capture(
            shape, CeilingShapeTemplates.fromMeasurements(shape, mm).vertices,
            mm, 0.01
        )

    @Test fun rectangleAndFourInputLShapesUseTheSameFrozenSchema() {
        val rectangle = geometry(
            "rectangle", mapOf("length" to 5000L, "width" to 4000L)
        )
        assertEquals(4, rectangle.vertices.size)
        assertEquals(20.0, rectangle.areaSquareMeters, 0.0)
        assertEquals(18.0, rectangle.perimeterMeters, 0.0)
        assertEquals(rectangle, FrozenPolygonGeometryContract.requireValid(rectangle))

        val l = geometry(
            "l_shaped", mapOf(
                "A" to 5000L, "B" to 4000L,
                "C" to 2000L, "D" to 2000L
            )
        )
        assertEquals(6, l.vertices.size)
        assertEquals(14.0, l.areaSquareMeters, 0.0)
        assertEquals(18.0, l.perimeterMeters, 0.0)
        assertEquals(4, l.measurementsMm.size)
        assertEquals(l, FrozenPolygonGeometryContract.requireValid(l))
    }

    @Test fun concaveFreePolygonHasExactlyOneSharedAreaAndPerimeter() {
        val points = listOf(
            CeilingPointMm(0, 0), CeilingPointMm(6000, 0),
            CeilingPointMm(6000, 2000), CeilingPointMm(3000, 2000),
            CeilingPointMm(3000, 5000), CeilingPointMm(0, 5000)
        )
        val polygon = FrozenPolygonGeometryContract.capture(
            "polygon", points, emptyMap(), 0.01
        )
        assertEquals(21.0, polygon.areaSquareMeters, 0.0)
        assertEquals(22.0, polygon.perimeterMeters, 0.0)
        assertEquals(6, polygon.vertices.size)
    }

    @Test fun invalidVertexOrMeasurementCannotBecomeACommercialSnapshot() {
        val l = geometry(
            "l_shaped", mapOf(
                "A" to 5000L, "B" to 4000L,
                "C" to 2000L, "D" to 2000L
            )
        )
        val invalid = listOf(
            l.copy(vertices = l.vertices.reversed()),
            l.copy(vertices = l.vertices.dropLast(1)),
            l.copy(measurementsMm = l.measurementsMm - "C"),
            l.copy(measurementsMm = l.measurementsMm + ("C" to 3000L)),
            l.copy(measurementsMm = l.measurementsMm + ("E" to 3000L)),
            l.copy(areaSquareMeters = 13.0),
            l.copy(perimeterMeters = 19.0),
            l.copy(shapeType = "unregistered"),
            l.copy(precisionStepMeters = 0.123)
        )
        invalid.forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                FrozenPolygonGeometryContract.requireValid(value)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenPolygonGeometryContract.capture(
                "polygon", l.vertices, mapOf("A" to 5000L), 0.01
            )
        }
    }
}
