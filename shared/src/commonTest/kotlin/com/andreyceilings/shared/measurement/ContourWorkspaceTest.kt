package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.test.*

class ContourWorkspaceTest {
    private val empty = MeasurementCreateDraft(7, MeasurementPrecision.ONE_MILLIMETER, "Кухня")

    @Test fun eachShapeHasAnImmediateUnmeasuredContourAndUndoRestoresIt() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(0, 0, empty))
        for (shape in CeilingShapeTemplates.templates.map { it.shapeType } + "polygon") {
            model.accept(MeasurementCreateIntent.Shape(shape))
            val draft = model.state.value.draft
            val preview = contourWorkspacePreview(draft)
            assertEquals(if (shape == "l_shaped") 6 else 4, preview.points.size)
            assertTrue(preview.sideLabels.isEmpty())
            assertNull(validateMeasurementCreate(draft).geometry)
            assertFalse(draft.outline.hasEnteredDimensions())
        }
        model.undo()
        assertEquals("l_shaped", model.state.value.draft.shape)
        model.redo()
        assertEquals("polygon", model.state.value.draft.shape)
    }

    @Test fun lDiagnosticNamesBothConflictsThenAcceptsExactMillimetres() {
        val draft = empty.copy(outline = MeasurementOutlineDraft.Template("l_shaped",
            mapOf("A" to "5,125", "B" to "4.5", "C" to "6", "D" to "4.5")))
        val invalid = validateMeasurementCreate(draft)
        assertNull(invalid.geometry)
        assertFalse(invalid.canSave)
        assertEquals(
            setOf(MeasurementContourIssueCode.CUTOUT_LENGTH, MeasurementContourIssueCode.CUTOUT_WIDTH),
            invalid.contourIssues.map { it.code }.toSet()
        )
        assertEquals(setOf("A", "B", "C", "D"), invalid.contourIssues.flatMap { it.fields }.toSet())
        assertEquals((0..5).toSet(), draft.problemWalls(invalid.contourIssues))
        assertEquals(6, contourWorkspacePreview(draft).vertexNames.size)

        val corrected = draft.reduce(MeasurementCreateIntent.Dimension("C", ".5"))
            .reduce(MeasurementCreateIntent.Dimension("D", "2.375"))
        val valid = validateMeasurementCreate(corrected)
        val geometry = assertNotNull(valid.geometry)
        assertTrue(valid.canSave)
        assertTrue(valid.contourIssues.isEmpty())
        assertEquals(500L, geometry.measurementsMm["C"])
        assertEquals(2375L, geometry.measurementsMm["D"])
        assertEquals(13.234375, geometry.areaSquareMeters)
        assertEquals(19.25, geometry.perimeterMeters)
    }

    @Test fun unclosedMeasuredContourRemainsDrawnAndCannotBeSaved() {
        val draft = empty.copy(outline = MeasurementOutlineDraft.Orthogonal(
            listOf("5", "4", "4", "4").map { MeasurementSideInput(it) }
        ))
        val invalid = validateMeasurementCreate(draft)
        assertEquals(MeasurementContourIssueCode.OPEN, invalid.contourIssues.single().code)
        assertEquals((0..3).toSet(), draft.problemWalls(invalid.contourIssues))
        assertEquals(4, contourWorkspacePreview(draft).points.size)
        assertFalse(invalid.canSave)
        assertTrue(validateMeasurementCreate(draft.reduce(MeasurementCreateIntent.SideLength(2, "5"))).canSave)
    }

    @Test fun rotationUsesTheSameWorldAxesForEveryWallAndEveryVertex() {
        for (source in AndroidMeasurementFixtures.all) {
            val geometry = MeasurementGeometryCodec.read(source)
            val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))
            for (turn in 0..3) {
                val projection = assertNotNull(measurementDrawingProjection(contour, 223f, 391f,
                    rotation = canonicalDrawingRotation(contour.points, turn)))
                val screen = contour.points.map(projection::project)
                val layout = layoutMeasurementDrawingForProjectedPoints(
                    screen, 223f, 391f, emptyList(),
                    vertexSizes = List(screen.size) { DrawingLabelSize(10f, 14f) }, placeCrowdedVertices = true
                )
                assertEquals(contour.points.indices.toSet(), layout.vertexLabels.map { it.sideIndex }.toSet())
                if (turn == 0) {
                    assertEquals(screen[0].x, screen[1].x, 0.00001)
                    assertTrue(screen[0].y > screen[1].y)
                }
                for (i in screen.indices) {
                    val world = projection.inverse(screen[i])
                    assertEquals(contour.points[i].x, world.x, 0.00001)
                    assertEquals(contour.points[i].y, world.y, 0.00001)
                    val end = screen[(i + 1) % screen.size]
                    assertEquals(i, measurementWallAt(screen,
                        ((screen[i].x + end.x) / 2).toFloat(), ((screen[i].y + end.y) / 2).toFloat(), 2f))
                }
            }
            assertEquals(geometry, MeasurementGeometryCodec.read(source))
        }
    }

    @Test fun concaveLabelsAreOutsideAndPdfChoosesTheLargestScaleForEitherPageAspect() {
        val draft = empty.copy(outline = MeasurementOutlineDraft.Template("l_shaped",
            mapOf("A" to "12", "B" to "4", "C" to "3", "D" to "2")))
        val geometry = assertNotNull(validateMeasurementCreate(draft).geometry)
        val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))
        for ((width, height) in listOf(515f to 195f, 195f to 515f)) {
            val labelSize = DrawingLabelSize(18f, 15f)
            val optimal = assertNotNull(optimalMeasurementDrawingProjection(contour, width, height, labelSize))
            for (turn in 0..3) {
                val candidate = assertNotNull(measurementDrawingProjection(contour, width, height,
                    rotation = canonicalDrawingRotation(contour.points, turn), labelMargin = labelSize))
                assertTrue(optimal.scale >= candidate.scale)
            }
            val layout = layoutMeasurementDrawingForProjectedPoints(contour.points.map(optimal::project), width, height,
                sizes = emptyList(), vertexSizes = List(6) { labelSize }, placeCrowdedVertices = true)
            assertEquals(6, layout.vertexLabels.size)
            for (label in layout.vertexLabels) {
                assertTrue(label.left >= 0 && label.top >= 0 && label.right <= width && label.bottom <= height)
                for (x in label.left.toInt()..label.right.toInt()) for (y in label.top.toInt()..label.bottom.toInt()) {
                    val point = optimal.inverse(DrawingPoint(x.toDouble(), y.toDouble()))
                    assertFalse(point.x in 0.0..3000.0 && point.y in 0.0..4000.0 ||
                        point.x in 3000.0..12000.0 && point.y in 0.0..2000.0)
                }
            }
        }
    }
}
