package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.math.abs
import kotlin.test.*

class PointPositionInputTest {
    @Test fun signedIntegerMillimetresCommaDotIncompleteOverflowRoundtrip() {
        for (mm in listOf(-1_000_000L, -2501L, -1L, 0L, 1L, 2500L, 1_000_000L)) {
            assertEquals(mm, parsePointMetres(formatPointMetres(mm)))
            assertEquals(mm, parsePointMetres(formatPointMetres(mm).replace(',', '.')))
        }
        for (bad in listOf("", "-", "2,", "2.", "1.0001", "--1", "1e3", "NaN", "1000.001", "-1000.001", "9223372036854775807", "9223372036854775808"))
            assertNull(parsePointMetres(bad), bad)
        assertEquals(2500L, parsePointMetres("2,5")); assertEquals(-1L, parsePointMetres("-0.001"))
    }

    @Test fun wallWindingConcavityAndDiagonalRoundingUseStrictInterior() {
        val rectangle = validateMeasurementCreate(MeasurementCreateFixtures.draft(1, "rectangle", MeasurementPrecision.ONE_MILLIMETER)).geometry!!
        val p = assertNotNull(pointFromWall(rectangle, 0, 2500, 1000))
        assertEquals(PointPositionMm(2500, 1000), p)
        assertEquals(2500.0, pointWallDistances(rectangle, 0, p).alongMm)
        assertEquals(1000.0, pointWallDistances(rectangle, 0, p).inwardMm)
        assertNull(pointFromWall(rectangle, 0, 2500, 0)); assertNull(pointFromWall(rectangle, 0, 999999, 1000))
        val reversed = rectangle.copy(shapeType = "polygon", measurementsMm = emptyMap(), vertices = rectangle.vertices.reversed())
        val reversePoint = assertNotNull(pointFromWall(reversed, 0, 1000, 1000))
        assertTrue(pointStrictlyInside(reversed, reversePoint.xMm, reversePoint.yMm))
        val l = validateMeasurementCreate(MeasurementCreateFixtures.draft(1, "l_shaped", MeasurementPrecision.ONE_MILLIMETER)).geometry!!
        assertNull(pointFromWall(l, 0, 4500, 3500), "Inward normal alone does not guarantee concave polygon interior")
        // A genuine captured slanted polygon, consistent with its validated world vertices/totals.
        val polygon = com.andreyceilings.shared.calculation.CeilingPolygon("polygon", listOf(
            CeilingPointMm(-2000, -2000), CeilingPointMm(1000, -1000), CeilingPointMm(0, 2000), CeilingPointMm(-3000, 1000)))
        val totals = com.andreyceilings.shared.calculation.CeilingPolygonCalculator.calculate(polygon, 0.001)
        val diagonal = rectangle.copy(shapeType = "polygon", vertices = polygon.vertices, measurementsMm = emptyMap(), sides = emptyList(),
            areaSquareMeters = totals.geometry.area, perimeterMeters = totals.geometry.perimeter)
        val d = assertNotNull(pointFromWall(diagonal, 0, 1500, 500))
        val distances = pointWallDistances(diagonal, 0, d)
        assertTrue(abs(distances.alongMm - 1500) < 0.71)
        assertTrue(abs(distances.inwardMm - 500) < 0.71)
        assertTrue(d.xMm < 0 && d.yMm < 0)
    }
}
