package com.andreyceilings.shared.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineCreationSnapTest {
    private val rectangle = MeasurementGeometryCodec.read(AndroidMeasurementFixtures.rectangleV2)

    @Test
    fun snapsNearWallAndKeepsItThroughTheReleaseThreshold() {
        val start = PointPositionMm(500, 200)
        val initial = previewLineCreation(rectangle, start, PointPositionMm(1500, 325), null)
        assertEquals(LineCreationPreview(start, PointPositionMm(1500, 200), 0), initial)

        val betweenThresholds = PointPositionMm(1500, 425)
        assertNull(previewLineCreation(rectangle, start, betweenThresholds, null).parallelWall)
        val held = previewLineCreation(rectangle, start, betweenThresholds, initial.parallelWall)
        assertEquals(initial, held)

        val turnedAway = PointPositionMm(1500, 750)
        assertEquals(LineCreationPreview(start, turnedAway), previewLineCreation(rectangle, start, turnedAway, held.parallelWall))
    }

    @Test
    fun snapsInTheCenterButRejectsAnOutsideEndpoint() {
        val start = PointPositionMm(500, 200)
        val outside = PointPositionMm(5_100, 425)
        assertEquals(LineCreationPreview(start, outside), previewLineCreation(rectangle, start, outside, null))
        val interior = PointPositionMm(1500, 1800)
        val end = PointPositionMm(2600, 1860)
        assertEquals(LineCreationPreview(interior, PointPositionMm(2600, 1800), 0),
            previewLineCreation(rectangle, interior, end, null))
    }

    @Test
    fun coincidentProjectedEndpointsUsePointDistance() {
        val endpoint = DrawingPoint(10.0, 15.0)
        val cursor = DrawingPoint(13.0, 19.0)
        assertEquals(5.0, projectedSegmentDistance(cursor, endpoint, endpoint))
        assertEquals(5.0, projectedSegmentDistance(cursor, endpoint, DrawingPoint(10.0000001, 15.0)))
    }
}
