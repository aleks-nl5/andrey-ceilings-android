package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.*

class PointPlacementGeometryTest {
    @Test fun projectionInverseAcrossShapesDensityOrientationFitZoomAndPan() {
        for (shape in listOf("rectangle", "l_shaped", "polygon")) {
            val geometry = validateMeasurementCreate(MeasurementCreateFixtures.draft(1, shape,
                MeasurementPrecision.ONE_MILLIMETER)).geometry!!
            val contour = measurementDrawingContour(MeasurementGeometryView.Captured(geometry))!!
            for ((width,height) in listOf(320f to 235f, 1080f to 640f, 640f to 1080f, 240f to 155f))
                for (density in listOf(0.75f, 1f, 2f, 3.5f))
                    for (view in listOf(MeasurementViewport(), MeasurementViewport(0.25f, -321f, 102f), MeasurementViewport(12f, 500f, -200f))) {
                        val projection = measurementDrawingProjection(contour, width, height, density)!!
                        val layout = layoutMeasurementDrawing(contour, width, height, emptyList(), density)!!
                        assertEquals(layout.points, contour.points.map(projection::project))
                        for (point in contour.points + listOf(DrawingPoint(1234.0, 2345.0), DrawingPoint(-999999.0, 1000000.0))) {
                            val screen = view.transform(projection.project(point), width, height)
                            val inverse = projection.inverse(view.inverse(screen, width, height))
                            assertTrue(abs(inverse.x - point.x) < 1e-6)
                            assertTrue(abs(inverse.y - point.y) < 1e-6)
                            assertEquals(point.x.roundToLong(), inverse.x.roundToLong())
                            assertEquals(point.y.roundToLong(), inverse.y.roundToLong())
                        }
                    }
        }
    }

    @Test fun strictInteriorRejectsEdgesVerticesConcavityAndOverflowWithoutPrecisionChange() {
        val rectangle = validateMeasurementCreate(MeasurementCreateFixtures.draft(1, "rectangle", MeasurementPrecision.ONE_MILLIMETER)).geometry!!
        assertTrue(pointStrictlyInside(rectangle, 1234, 2345))
        assertFalse(pointStrictlyInside(rectangle, 0, 1234))
        assertFalse(pointStrictlyInside(rectangle, 0, 0))
        assertFalse(pointStrictlyInside(rectangle, Long.MIN_VALUE, Long.MAX_VALUE))
        val l = validateMeasurementCreate(MeasurementCreateFixtures.draft(1, "l_shaped", MeasurementPrecision.ONE_MILLIMETER)).geometry!!
        assertTrue(pointStrictlyInside(l, 1000, 1000))
        val maxX = l.vertices.maxOf { it.xMm }; val maxY = l.vertices.maxOf { it.yMm }
        assertFalse(pointStrictlyInside(l, maxX - 1, maxY - 1), "Notch belongs outside even inside bounding rectangle")
        l.vertices.forEach { assertFalse(pointStrictlyInside(l, it.xMm, it.yMm)) }
        val original = MeasurementGeometryCodec.write(l)
        repeat(100) { pointStrictlyInside(l, 1001L + it, 1003) }
        assertEquals(original, MeasurementGeometryCodec.write(l))
        val contour = measurementDrawingContour(MeasurementGeometryView.Captured(l))!!
        for ((w,h,d) in listOf(Triple(Float.NaN, 1f, 1f), Triple(1f, 0f, 1f), Triple(1f, 1f, 0f), Triple(Float.POSITIVE_INFINITY, 1f, 1f)))
            assertNull(measurementDrawingProjection(contour, w, h, d))
    }
}
