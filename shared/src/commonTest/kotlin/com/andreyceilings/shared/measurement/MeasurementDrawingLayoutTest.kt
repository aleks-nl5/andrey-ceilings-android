package com.andreyceilings.shared.measurement

import kotlin.math.abs
import kotlin.test.*

class MeasurementDrawingLayoutTest {
    @Test fun allShapesFitCompactFullAndNarrowViewportsWithoutLabelOverlap() {
        for (source in AndroidMeasurementFixtures.all) for (width in listOf(64f, 120f, 240f, 800f))
            for (height in listOf(155f, 235f)) {
                val geometry = MeasurementGeometryCodec.read(source)
                val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))
                val layout = assertNotNull(layoutMeasurementDrawing(contour, width, height,
                    contour.sideLabels.map { DrawingLabelSize(68f, 16f) }))
                assertEquals(geometry.vertices.size, layout.points.size)
                assertEquals(geometry.vertices.size, contour.sideLabels.size)
                assertTrue(layout.points.all { it.x in 0.0..width.toDouble() && it.y in 0.0..height.toDouble() })
                val worldWidth = contour.points.maxOf { it.x } - contour.points.minOf { it.x }
                val worldHeight = contour.points.maxOf { it.y } - contour.points.minOf { it.y }
                val drawnWidth = layout.points.maxOf { it.x } - layout.points.minOf { it.x }
                val drawnHeight = layout.points.maxOf { it.y } - layout.points.minOf { it.y }
                assertTrue(abs(worldWidth / worldHeight - drawnWidth / drawnHeight) < 0.00001)
                layout.labels.forEachIndexed { i, label ->
                    assertTrue(label.left >= 0 && label.top >= 0 && label.right <= width && label.bottom <= height)
                    layout.labels.drop(i + 1).forEach { other ->
                        assertFalse(label.left < other.right && label.right > other.left &&
                            label.top < other.bottom && label.bottom > other.top)
                    }
                }
                val withVertices = assertNotNull(layoutMeasurementDrawing(contour, width, height,
                    contour.sideLabels.map { DrawingLabelSize(68f, 16f) }, vertexSizes = contour.vertexNames.map { DrawingLabelSize(20f, 16f) }))
                val all = withVertices.labels + withVertices.vertexLabels
                all.forEachIndexed { i, a ->
                    all.drop(i + 1).forEach { b ->
                        assertFalse(a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top)
                    }
                }
            }
    }

    @Test fun denseA4DrawingPlacesEveryVertexLabelWithRoomForLeaders() {
        val points = List(126) { DrawingPoint(245.0 + it * 0.04, 90.0 + it * 0.04) } +
            listOf(DrawingPoint(385.0, 36.0), DrawingPoint(125.0, 159.0))
        val layout = layoutMeasurementDrawingForProjectedPoints(points, 515f, 195f, emptyList(),
            vertexSizes = List(points.size) { DrawingLabelSize(16f, 12f) }, placeCrowdedVertices = true)
        assertEquals(points.indices.toSet(), layout.vertexLabels.map { it.sideIndex }.toSet())
        assertTrue(layout.vertexLabels.any { it.needsLeader })
        layout.vertexLabels.forEachIndexed { i, a ->
            assertTrue(a.left >= 0 && a.top >= 0 && a.right <= 515 && a.bottom <= 195)
            assertTrue(points.none { it.x in a.left.toDouble()..a.right.toDouble() &&
                it.y in a.top.toDouble()..a.bottom.toDouble() })
            layout.vertexLabels.drop(i + 1).forEach { b ->
                assertFalse(a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top)
            }
        }
    }

    @Test fun degenerateInputsAndUnavailableGeometryCannotCrashDrawing() {
        assertNull(measurementDrawingContour(MeasurementGeometryView.Unavailable))
        val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.HistoricalRectangle(5.0, 4.0)))
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(layoutMeasurementDrawing(contour, width, 155f, emptyList()))
        assertNull(layoutMeasurementDrawing(contour.copy(points = emptyList()), 240f, 155f, emptyList()))
    }
}
