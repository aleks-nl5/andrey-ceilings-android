package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.math.abs
import kotlin.test.*

class PointPositionInputTest {
    @Test fun fitKeepsSavedPointsVisibleWhenDraftShrinksAndInverseDragPreservesFingerOffset() {
        val draft = MeasurementCreateFixtures.draft(1, "rectangle", MeasurementPrecision.ONE_MILLIMETER)
            .reduce(MeasurementCreateIntent.Dimension("length", "1")).reduce(MeasurementCreateIntent.Dimension("width", "1"))
        val contour = measurementDrawingContour(MeasurementGeometryView.Captured(validateMeasurementCreate(draft).geometry!!))!!
        val markers = listOf(DrawingPoint(2500.0,1500.0), DrawingPoint(3000.0,1000.0))
        for ((w,h) in listOf(320f to 235f, 800f to 600f, 600f to 800f)) for (density in listOf(1f, 2.5f, 3.5f)) {
            val projection = measurementDrawingProjection(contour,w,h,density,markers)!!
            val layout = layoutMeasurementDrawing(contour,w,h,emptyList(),density,extraWorldPoints = markers)!!
            assertEquals(contour.points.map(projection::project), layout.points)
            markers.forEach { p -> val screen = projection.project(p)
                assertTrue(screen.x in 0.0..w.toDouble() && screen.y in 0.0..h.toDouble()) }
            for (viewport in listOf(MeasurementViewport(), MeasurementViewport(3f,22f,-44f))) {
                val marker = markers.first()
                val initialScreen = viewport.transform(projection.project(marker), w,h)
                val finger = DrawingPoint(initialScreen.x + 12, initialScreen.y - 8)
                val target = DrawingPoint(marker.x + 500, marker.y + 300)
                val targetScreen = viewport.transform(projection.project(target), w,h)
                val endFinger = DrawingPoint(targetScreen.x + 12, targetScreen.y - 8)
                val a = projection.inverse(viewport.inverse(finger,w,h)); val b = projection.inverse(viewport.inverse(endFinger,w,h))
                assertTrue(abs(marker.x + b.x - a.x - target.x) < 1e-6)
                assertTrue(abs(marker.y + b.y - a.y - target.y) < 1e-6)
            }
        }
    }
    @Test fun signedIntegerMillimetresCommaDotIncompleteOverflowRoundtrip() {
        for (mm in listOf(-1_000_000L, -2501L, -1L, 0L, 1L, 2500L, 1_000_000L)) {
            assertEquals(mm, parsePointMetres(formatPointMetres(mm)))
            assertEquals(mm, parsePointMetres(formatPointMetres(mm).replace(',', '.')))
        }
        for (bad in listOf("", "-", "2,", "2.", "1.0001", "--1", "1e3", "NaN", "1000.001", "-1000.001", "9223372036854775807", "9223372036854775808"))
            assertNull(parsePointMetres(bad), bad)
        assertEquals(500L, parsePointMetres(".5"))
        assertEquals(-500L, parsePointMetres("-,5"))
        assertEquals(-7L, parsePointMetres("-.007"))
        assertEquals(7L, parsePointMetres(",007"))
        assertNull(parsePointMetres(".007", 6))
        assertNull(parsePointMetres("9223372036854775.808", Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, parsePointMetres("9223372036854775.807", Long.MAX_VALUE))
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
